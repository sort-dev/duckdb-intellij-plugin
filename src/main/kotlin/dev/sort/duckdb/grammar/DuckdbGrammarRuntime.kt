package dev.sort.duckdb.grammar

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.console.client.SessionClient
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.DataRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.brikk.house.sql.intellij.catalog.StatementEffect
import dev.brikk.house.sql.intellij.catalog.StatementHeadDetector
import dev.brikk.house.sql.intellij.catalog.StatementRule
import dev.brikk.house.sql.intellij.catalog.executeStatement
import dev.brikk.house.sql.intellij.catalog.isHelperRequestor
import dev.brikk.house.sql.intellij.catalog.queryRows
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.diagnostics.VerificationConnectionListener
import java.util.concurrent.CompletableFuture
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.sort.duckdb.catalog.DuckdbCatalogProfile
import dev.sort.duckdb.catalog.DuckdbIntrospectionTransactions
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

internal class IdeGrammarConnection(private val connection: DatabaseConnectionCore) : GrammarConnection {
    override fun rows(sql: String) = connection.queryRows(sql, 4096, 5)
    override fun execute(sql: String) = connection.executeStatement(sql, 5)
}

/** Runtime facts never persist, and inventories from different consoles are never unioned. */
@Service(Service.Level.PROJECT)
internal class DuckdbGrammarRuntime(private val project: Project) {
    internal class Runtime(connection: DatabaseConnectionCore, val sourceId: String, val login: Pair<String?, String?>) {
        val connection = WeakReference(connection)
        @Volatile var snapshot = DuckdbGrammarSnapshot(unavailable = "Grammar state has not been read")
        @Volatile var initializationError: String? = null
        @Volatile var routed = false
        @Volatile var observedAt = System.nanoTime()
        // API support survives a temporarily stale snapshot, such as a SET observed inside a transaction.
        @Volatile var canActivate = false
        var pending: DuckdbGrammarProfile? = null
        var application: String? = null
        var queued = false
    }

    private val connections = WeakHashMap<Any, Runtime>()
    private val sessions = WeakHashMap<DatabaseSession, WeakReference<Any>>()
    private val following = WeakHashMap<DatabaseSession, Boolean>()
    private val sessionIds = WeakHashMap<DatabaseSession, Any>()
    private val revision = AtomicLong()
    val stamp get() = revision.get()

    fun connected(connection: DatabaseConnectionCore, knownHelper: Boolean = false) {
        val source = connection.connectionPoint.dataSource
        val grammar = IdeGrammarConnection(connection)
        if (!DuckdbGrammarSources.accepts(source)) return
        val settings = project.service<DuckdbGrammarSettings>()
        val requestor = (connection as? DatabaseConnection)?.requestor
        val helper = knownHelper || isHelperRequestor(requestor)
        var defaultsChanged = false
        var initializationError: String? = null
        var snapshot = recoverEditorOperation { DuckdbGrammarDiscovery.read(grammar) }.getOrElse {
            DuckdbGrammarSnapshot(unavailable = it.message ?: "Grammar discovery is unsupported")
        }
        if (snapshot.supported) {
            if (!helper) defaultsChanged = settings.discovered(source, snapshot.available)
            val profile = settings.profile(source)
            snapshot = recoverEditorOperation { DuckdbGrammarDiscovery.apply(grammar, profile) }.getOrElse {
                initializationError = it.message ?: "Managed grammar activation failed"
                snapshot
            }
        }
        // Helpers receive the managed profile too, but never replace a user's runtime inventory.
        if (helper) return
        synchronized(this) {
            connections[requestor ?: connection] = Runtime(connection, source.uniqueId, source.url to source.username).also {
                it.snapshot = snapshot
                it.canActivate = snapshot.supported
                it.initializationError = initializationError
                if (initializationError != null) {
                    it.pending = settings.profile(source)
                    it.application = "Pending: $initializationError"
                }
            }
        }
        changed()
        if (defaultsChanged) schedule(source, settings.profile(source))
    }

    fun disconnected(connection: DatabaseConnection) {
        val removed = synchronized(this) {
            connections.entries.removeIf {
                val observed = it.value.connection.get()
                observed === connection || observed?.remoteConnection === connection.remoteConnection
            }
        }
        if (removed) changed()
    }

    @Synchronized
    fun runtime(source: LocalDataSource, session: DatabaseSession? = null): Runtime? {
        if (session != null) return sessions[session]?.get()?.let(connections::get)
            ?.takeIf { current(it, source) }
        return connections.values.filter { current(it, source) }.maxByOrNull { it.observedAt }
    }

    private fun current(runtime: Runtime, source: LocalDataSource) = runtime.sourceId == source.uniqueId &&
        runtime.login == (source.url to source.username) && runtime.connection.get() != null

    @Synchronized
    fun sessionIdentity(session: DatabaseSession?) = session?.let { sessionIds.getOrPut(it) { Any() } }

    @Synchronized
    fun follows(session: DatabaseSession?) = session != null && following[session] == true

    fun follow(session: DatabaseSession, enabled: Boolean) {
        synchronized(this) { following[session] = enabled }
        changed()
    }

    fun adopt(source: LocalDataSource, session: DatabaseSession): Boolean {
        val snapshot = runtime(source, session)?.snapshot?.takeIf { it.supported } ?: return false
        val previous = project.service<DuckdbGrammarSettings>().profile(source)
        val disabled = (previous.disabled + snapshot.available.filterNot { it in snapshot.effective }).distinct()
        return applyProfile(source, DuckdbGrammarProfile(snapshot.effective, disabled.filterNot { it in snapshot.effective })) == null
    }

    /** Only this explicit action commits editor choices. Native connection options never call it. */
    fun applyProfile(source: LocalDataSource, profile: DuckdbGrammarProfile): String? {
        val actual = LocalDataSourceManager.getInstance(project).dataSources.firstOrNull { it.uniqueId == source.uniqueId }
            ?: return "Save the data source first, then apply its grammar changes."
        if (!actual.equalConfiguration(source)) {
            return "Save the edited connection details first. No grammar changes were applied to the old connection."
        }
        if (project.service<DuckdbGrammarSettings>().setProfile(actual.uniqueId, profile)) {
            changed()
            schedule(actual, profile)
        }
        return null
    }

    @Synchronized fun applicationStatus(source: LocalDataSource): String? = connections.values
        .filter { it.sourceId == source.uniqueId && it.connection.get() != null }.mapNotNull { it.application }.distinct()
        .takeIf { it.isNotEmpty() }?.joinToString("\n")

    private fun schedule(source: LocalDataSource, profile: DuckdbGrammarProfile) {
        val targets = synchronized(this) {
            connections.values.filter { it.sourceId == source.uniqueId && it.connection.get() != null }.forEach { state ->
                if (state.snapshot.supported && state.snapshot.effective == profile.enabled) {
                    state.pending = null; state.application = null
                } else {
                    state.pending = profile; state.application = "Pending: waiting for an idle auto-commit session."
                }
            }
            sessions.keys.filter { it.connectionPoint.dataSource.uniqueId == source.uniqueId }.toList()
        }
        targets.forEach(::queueApply)
        changed()
    }

    private fun queueApply(session: DatabaseSession) {
        val source = session.connectionPoint.dataSource
        val state = synchronized(this) { sessions[session]?.get()?.let(connections::get) } ?: return
        synchronized(this) {
            if (state.pending == null || state.queued) return
            state.application = when {
                !current(state, source) -> "Pending: connection details changed."
                state.routed -> "Pending: this session is routed; its grammar settings were left untouched."
                follows(session) -> "Pending: this console follows its own grammar settings."
                !state.canActivate -> "Pending: refresh this session when grammar discovery is supported."
                !session.isConnected || project.service<DuckdbIntrospectionTransactions>().blocked(session) -> "Pending: session is busy or in a transaction."
                else -> null
            }
            if (state.application != null) return
            state.queued = true
            state.application = "Pending: applying grammar changes without reconnecting…"
        }
        val identity = Triple(source.uniqueId, source.url, source.username)
        val request = object : DataRequest.RawRequest(session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                var applied = false
                try {
                    val desired = synchronized(this@DuckdbGrammarRuntime) { state.pending } ?: return
                    if (project.isDisposed || identity != Triple(source.uniqueId, source.url, source.username) || !current(state, source) ||
                        connection.remoteConnection !== state.connection.get()?.remoteConnection || state.routed || follows(session)) {
                        state.application = "Pending: source/session changed; no settings were applied."
                        return
                    }
                    if (project.service<DuckdbIntrospectionTransactions>().blocked(session) || !connection.autoCommit) {
                        state.application = "Pending: session is busy or in a transaction."
                        return
                    }
                    val snapshot = recoverEditorOperation { DuckdbGrammarDiscovery.apply(IdeGrammarConnection(connection), desired) }.getOrElse {
                        state.application = "Pending: ${it.message ?: "grammar activation failed"}"
                        return
                    }
                    synchronized(this@DuckdbGrammarRuntime) {
                        state.snapshot = snapshot
                        if (state.pending == desired) { state.pending = null; state.application = null }
                    }
                    applied = true
                } finally {
                    synchronized(this@DuckdbGrammarRuntime) { state.queued = false }
                    changed()
                    if (applied && state.pending != null) queueApply(session)
                }
            }
        }
        request.promise.onError {
            synchronized(this) { state.queued = false; state.application = "Pending: ${it.message ?: "session request failed"}" }
            changed()
        }
        session.messageBus.dataProducer.processRequest(request)
    }

    /** Runs on the source engine thread after execution. Never queues arbitrary console SQL. */
    fun finished(project: Project, context: DataRequest.Context) {
        if (project !== this.project) return
        val introspection = (context.request.owner as? DataRequest.DepartmentHolder)?.department ==
            com.intellij.database.dataSource.connection.DGDepartment.INTROSPECTION
        val session = ((context.request.owner as? SessionClient<*>)?.session as? DatabaseSession)
        if (session == null) {
            if (introspection) {
                val known = synchronized(this) { connections[context.producer] }
                if (known != null) refresh(known)
                else changed() // No source accessor is guessed, and no console is queried concurrently.
            }
            return
        }
        if (!DuckdbGrammarSources.accepts(session.connectionPoint.dataSource)) return
        val requestor = context.producer ?: return
        val runtime = synchronized(this) {
            sessions[session] = WeakReference(requestor)
            connections[requestor]
        }
        val text = (context.request as? DataRequest.QueryRequest)?.query ?: context.query
        val changes = changes.detect(text)
        val failed = context.getUserData(DuckdbIntrospectionTransactions.FAILED) == true
        val routed = changes.any { it.rule.pattern == "CONNECT|DISCONNECT" }
        if (runtime == null) {
            // The interceptor may see only DatabaseConnectionCore, without a requestor. A queued,
            // read-only request learns this console's actual connection instead of guessing by source UUID.
            if (!failed) refresh(project, session, routed)
            return
        }
        if (!failed) queueApply(session)
        if (changes.isEmpty() && !introspection) return
        if (routed) runtime.routed = true
        // A failed multi-statement request may have changed state before failing. Discard facts, don't infer a profile.
        if (failed || project.service<DuckdbIntrospectionTransactions>().blocked(session)) {
            runtime.snapshot = runtime.snapshot.copy(unavailable = if (failed) "Refresh after the failed request" else "Refresh after the console transaction ends")
            changed()
            return
        }
        refresh(runtime)
    }

    /** Called only on the source's engine thread or before its connection is handed to the IDE. */
    fun refresh(runtime: Runtime) {
        val connection = runtime.connection.get() ?: return
        if (!recoverEditorOperation { connection.autoCommit }.getOrDefault(false)) {
            runtime.snapshot = runtime.snapshot.copy(unavailable = "Refresh after the console transaction ends")
            changed()
            return
        }
        val snapshot = recoverEditorOperation { DuckdbGrammarDiscovery.read(IdeGrammarConnection(connection)) }.getOrElse {
            runtime.snapshot.copy(unavailable = it.message ?: "Grammar discovery is unsupported")
        }
        synchronized(this) {
            runtime.snapshot = snapshot
            if (snapshot.supported) runtime.canActivate = true
            runtime.observedAt = System.nanoTime()
        }
        val source = connection.connectionPoint.dataSource
        if (snapshot.supported && current(runtime, source) && !runtime.routed &&
            project.service<DuckdbGrammarSettings>().discovered(source, snapshot.available)) {
            schedule(source, project.service<DuckdbGrammarSettings>().profile(source))
        }
        changed()
    }

    fun refresh(project: Project, session: DatabaseSession, routed: Boolean = false): CompletableFuture<DuckdbGrammarSnapshot> {
        val result = CompletableFuture<DuckdbGrammarSnapshot>()
        if (project !== this.project) return result.completed("The source belongs to a different project.")
        val source = session.connectionPoint.dataSource
        val identity = Triple(source.uniqueId, source.url, source.username)
        if (!session.isConnected) return result.completed("Disconnected. Connect this data source in a console first.")
        if (project.service<DuckdbIntrospectionTransactions>().blocked(session)) return result.completed("Busy or in a transaction. Refresh after the console is idle.")
        val request = object : DataRequest.RawRequest(session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                if (result.isDone || project.isDisposed) return
                if (identity != Triple(source.uniqueId, source.url, source.username)) {
                    result.completed("Data source changed while discovery was queued. Reconnect and Discover again.")
                    return
                }
                if (project.service<DuckdbIntrospectionTransactions>().blocked(session)) {
                    result.completed("Busy or in a transaction. Refresh after the console is idle.")
                    return
                }
                val requestor = context.producer ?: run { result.completed("The source connection could not be identified."); return }
                val recorded = synchronized(this@DuckdbGrammarRuntime) {
                    connections[requestor] ?: connections.values.firstOrNull { it.connection.get()?.remoteConnection === connection.remoteConnection }
                }
                if (recorded != null && !current(recorded, source)) {
                    result.completed("Connection configuration changed. Reconnect before discovering grammars.")
                    return
                }
                val runtime = synchronized(this@DuckdbGrammarRuntime) {
                    sessions[session] = WeakReference(requestor)
                    connections.getOrPut(requestor) {
                        connections.values.firstOrNull { it.connection.get()?.remoteConnection === connection.remoteConnection }
                            ?: Runtime(connection, source.uniqueId, source.url to source.username)
                    }.also { if (routed) it.routed = true }
                }
                refresh(runtime)
                queueApply(session)
                if (identity == Triple(source.uniqueId, source.url, source.username)) result.complete(runtime.snapshot)
                else result.completed("Data source changed during discovery. Reconnect and Discover again.")
            }
        }
        request.promise.onError { result.completed("Discovery failed: ${it.message ?: "connection error"}") }
        request.promise.onSuccess { if (!result.isDone) result.completed("Discovery did not return grammar state.") }
        session.messageBus.dataProducer.processRequest(request)
        return result
    }

    /** Selects only an existing console of this exact source in this project. It never opens a connection. */
    fun discover(source: LocalDataSource): CompletableFuture<DuckdbGrammarSnapshot> {
        val identity = Triple(source.uniqueId, source.url, source.username)
        val session = discoverySession(source)
        return if (session != null) refresh(project, session).thenApply { snapshot ->
            if (identity == Triple(source.uniqueId, source.url, source.username)) snapshot
            else DuckdbGrammarSnapshot(unavailable = "Data source changed during discovery. Discover again for the edited connection.")
        }
        else CompletableFuture.completedFuture(DuckdbGrammarSnapshot(unavailable = "Disconnected or connection changed. Connect this data source and run a query, then Discover."))
    }

    fun canDiscover(source: LocalDataSource) = discoverySession(source) != null

    private fun discoverySession(source: LocalDataSource): DatabaseSession? = synchronized(this) {
            sessions.keys.filter { it.isConnected && it.connectionPoint.dataSource.uniqueId == source.uniqueId &&
                it.connectionPoint.dataSource.url == source.url && it.connectionPoint.dataSource.username == source.username &&
                runtime(source, it) != null }
                .maxByOrNull { runtime(source, it)?.observedAt ?: Long.MIN_VALUE }
    }

    private fun CompletableFuture<DuckdbGrammarSnapshot>.completed(reason: String) = apply {
        complete(DuckdbGrammarSnapshot(unavailable = reason))
    }

    fun changed() {
        revision.incrementAndGet()
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("DuckDB grammar profile changed")
        }
    }

    companion object {
        val changes = StatementHeadDetector(listOf("LOAD", "SET", "RESET", "CONNECT|DISCONNECT").map {
            StatementRule(it, StatementEffect.FunctionInventory(sessionFacts = true))
        }, "duckdb")
    }
}

/** Uses only the interceptor methods already used by the live catalog. */
@JvmDefaultWithoutCompatibility
internal class DuckdbGrammarConnectionInterceptor : DatabaseConnectionInterceptor {
    override suspend fun interceptConnection(proto: DatabaseConnectionInterceptor.ProtoConnection, silent: Boolean) =
        DuckdbCatalogProfile.dataSources.accepts(proto.connectionPoint.dbms)

    override suspend fun handleConnected(connection: DatabaseConnectionCore, proto: DatabaseConnectionInterceptor.ProtoConnection) {
        if (DuckdbGrammarSources.accepts(connection.connectionPoint.dataSource)) proto.project.service<DuckdbGrammarRuntime>()
            .connected(connection, proto.connectionPoint is HelperConnectionPoint)
    }
}

internal class DuckdbGrammarConnectionListener : VerificationConnectionListener() {
    override fun connectionChanged(connection: DatabaseConnection, added: Boolean) {
        if (!added && DuckdbCatalogProfile.dataSources.accepts(connection.connectionPoint.dataSource.dbms) && !isHelperRequestor(connection.requestor)) {
            ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach {
                it.service<DuckdbGrammarRuntime>().disconnected(connection)
            }
        }
        super.connectionChanged(connection, added)
    }
}
