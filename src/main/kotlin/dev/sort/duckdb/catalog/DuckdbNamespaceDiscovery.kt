package dev.sort.duckdb.catalog

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.LoaderContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotifications
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine
import dev.brikk.house.sql.intellij.catalog.NamespaceTarget
import dev.brikk.house.sql.intellij.catalog.pathResolver
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Adds missing root/ancestor discovery; listed targets still use the shared one-element queue. */
internal class DuckdbNamespaceDiscovery(
    private val project: Project,
    private val scope: CoroutineScope,
    private val requests: DuckdbAutoIntrospectService,
) {
    private class Inventory(@Volatile var model: Any, @Volatile var listed: Boolean, @Volatile var hadRoots: Boolean) {
        val touched = HashSet<List<String>>()
        val retryNow = HashSet<List<String>>()
        @Volatile var retryRootAt = 0L
    }
    private data class Request(val file: VirtualFile, val source: LocalDataSource, val path: List<String>, val inventory: Inventory, val stamp: Long?)
    private val inventories = HashMap<String, Inventory>()
    private val pending = LinkedHashSet<Request>()
    private val workers = ConcurrentHashMap<String, Channel<Request>>()

    fun pending(file: VirtualFile): String? = synchronized(pending) {
        pending.lastOrNull { it.file == file }?.path?.joinToString(".")
    }

    fun dismiss(file: VirtualFile) {
        synchronized(pending) { pending.removeAll { it.file == file } }
        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    fun request(file: PsiFile, path: List<String>): Boolean {
        if (!requests.profile.autoIntrospect || path.isEmpty() || path.size > requests.profile.levels.size || blocked(file)) return false
        val source = requests.profile.dataSources.resolve(file).dataSource ?: return false
        val model = DbPsiFacade.getInstance(project).findDataSource(source.uniqueId)?.model ?: return false
        val roots = model.modelRoots.toList()
        val inventory = synchronized(inventories) {
            val previous = inventories[source.uniqueId]
            // A real sync can replace EmptyModel with BasicModel while the user is still typing.
            // That is not a user clear and must not abandon the in-flight discovery chain.
            if (previous != null && roots.isNotEmpty() && DataSourceSyncManager.getInstance().isActive(source)) previous.model = model
            if (previous == null || previous.model !== model || previous.hadRoots && roots.isEmpty()) {
                Inventory(model, roots.isNotEmpty(), roots.isNotEmpty()).also { fresh ->
                    previous?.touched?.forEach { touched ->
                        requests.engine.finish(AutoIntrospectEngine.Key(source.uniqueId, touched), false)
                        fresh.retryNow += touched
                    }
                    inventories[source.uniqueId] = fresh
                }
            } else previous
        }
        val target = resolve(file, source, path)
        if (target != null) {
            if (!requests.profile.pathResolver.isChildless(target.node)) return false
            val queued = submit(file, source, target, inventory)
            val virtualFile = file.originalFile.viewProvider.virtualFile
            val stamp = FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.modificationStamp
            if (queued) scope.launch(Dispatchers.IO) {
                if (await(source, target.path)) refreshEditor(virtualFile, stamp)
            }
            return queued
        }
        // A missing name under an already loaded parent is a typo, not a reason to refresh it.
        if (!(roots.isEmpty() && !inventory.listed && System.nanoTime() >= inventory.retryRootAt) && ancestor(file, source, path) == null) return false
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val request = Request(virtualFile, source, path, inventory, FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.modificationStamp)
        synchronized(pending) {
            if (pending.any { it.source.uniqueId == source.uniqueId && it.path == path }) return false
            pending += request
        }
        val worker = workers.computeIfAbsent(source.uniqueId) {
            Channel<Request>(32).also { queue -> scope.launch(Dispatchers.IO) { for (next in queue) discover(next) } }
        }
        if (worker.trySend(request).isFailure) {
            synchronized(pending) { pending -= request }
            return false
        }
        EditorNotifications.getInstance(project).updateNotifications(request.file)
        return true
    }

    private suspend fun discover(request: Request) {
        var changed = false
        try {
            // At most one list plus one task per namespace level. Never a full-scope sync.
            repeat(requests.profile.levels.size + 1) {
                val step = readAction {
                    val file = psi(request.file) ?: return@readAction null
                    if (blocked(file) || synchronized(inventories) { inventories[request.source.uniqueId] !== request.inventory }) return@readAction null
                    val roots = request.source.model.modelRoots.toList()
                    if (roots.isEmpty() && !request.inventory.listed) return@readAction Step.Root
                    val target = resolve(file, request.source, request.path)
                    if (target != null) Step.Target(target, last = true)
                    else ancestor(file, request.source, request.path)?.let { Step.Target(it, last = false) }
                } ?: return
                when (step) {
                    Step.Root -> {
                        val context = LoaderContext.selectListNamespacesTask(project, request.source)
                        val result = DataSourceSyncManager.getInstance().tryPerformSync(context, stopRunning = false, merge = false)
                        if (result == null || context.errorHandler?.hasErrors() == true) {
                            request.inventory.retryRootAt = System.nanoTime() + 60_000_000_000L
                            return
                        }
                        request.inventory.listed = true
                        readAction {
                            request.inventory.model = request.source.model
                            request.inventory.hadRoots = request.source.model.modelRoots.any()
                        }
                        changed = true
                    }
                    is Step.Target -> {
                        val queued = readAction {
                            val file = psi(request.file) ?: return@readAction false
                            !blocked(file) && requests.profile.pathResolver.isChildless(step.target.node) &&
                                submit(file, request.source, step.target, request.inventory)
                        }
                        val key = AutoIntrospectEngine.Key(request.source.uniqueId, step.target.path)
                        if (!queued && requests.engine.state(key) != AutoIntrospectEngine.State.InFlight) return
                        if (!await(request.source, step.target.path)) return
                        changed = true
                        if (step.last) return
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            request.inventory.retryRootAt = System.nanoTime() + 60_000_000_000L
            logger<DuckdbNamespaceDiscovery>().warn("Discovering ${request.path} failed", e)
        } finally {
            synchronized(pending) { pending -= request }
            if (!project.isDisposed) {
                EditorNotifications.getInstance(project).updateNotifications(request.file)
                if (changed) refreshEditor(request.file, request.stamp)
            }
        }
    }

    private sealed interface Step {
        data object Root : Step
        data class Target(val target: NamespaceTarget, val last: Boolean) : Step
    }

    private fun resolve(file: PsiFile, source: LocalDataSource, path: List<String>): NamespaceTarget? =
        requests.profile.pathResolver.resolve(source.model.modelRoots.toList(), path, current(file, source))

    private fun current(file: PsiFile, source: LocalDataSource): List<String> =
        requests.profile.dataSources.consoleNamespace(file).ifEmpty {
            // A mapped SQL file need not have an executed console. Use the model's engine-reported
            // current namespace, never the first catalog or a scan of every attached catalog.
            generateSequence(source.model.currentRootNamespace as com.intellij.database.model.DasObject?) { it.dasParent }
                .filter { it.kind in requests.profile.levels }.map { it.name }.toList().asReversed()
        }

    private fun ancestor(file: PsiFile, source: LocalDataSource, path: List<String>): NamespaceTarget? {
        for (length in path.size - 1 downTo 1) {
            val target = resolve(file, source, path.take(length)) ?: continue
            if (requests.profile.pathResolver.isChildless(target.node)) return target
        }
        // `sales.` may be relative to a console catalog whose schemas have not been listed yet.
        if (path.size != 1) return null
        val catalog = current(file, source).take(1)
        return catalog.takeIf { it.isNotEmpty() }?.let { resolve(file, source, it) }
            ?.takeIf { requests.profile.pathResolver.isChildless(it.node) }
    }

    private fun submit(file: PsiFile, source: LocalDataSource, target: NamespaceTarget, inventory: Inventory): Boolean {
        synchronized(inventories) { inventory.touched += target.path }
        return if (synchronized(inventories) { inventory.retryNow.remove(target.path) }) requests.introspect(file, source, target)
        else requests.request(file, source, target)
    }

    private suspend fun await(source: LocalDataSource, path: List<String>): Boolean = withTimeoutOrNull(60_000) {
        val key = AutoIntrospectEngine.Key(source.uniqueId, path)
        while (requests.engine.state(key) == AutoIntrospectEngine.State.InFlight) delay(20)
        requests.engine.state(key) == AutoIntrospectEngine.State.Done
    } ?: false

    private fun blocked(file: PsiFile) = project.getService(DuckdbIntrospectionTransactions::class.java).blocked(file)
    private fun psi(file: VirtualFile) = file.takeIf { it.isValid && !project.isDisposed }?.let(PsiManager.getInstance(project)::findFile)

    private suspend fun refreshEditor(file: VirtualFile, stamp: Long?) {
        readAction { psi(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, "DuckDB namespaces discovered") } }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed || !file.isValid) return@withContext
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext
            if (editor.document.modificationStamp == stamp && FileDocumentManager.getInstance().getFile(editor.document) == file) {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }
        }
    }
}
