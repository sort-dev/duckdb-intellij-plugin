package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.catalog.helperRequestor
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.verify.ConnectionDuckdbVerifier
import dev.sort.duckdb.catalog.DuckdbCatalogProfile
import java.lang.ref.WeakReference
import java.sql.SQLException

internal data class GrammarIdentity(
    val sourceId: String,
    val url: String?,
    val user: String?,
    val profile: DuckdbGrammarProfile,
    val follow: Boolean,
    val session: Any?,
    val runtime: Any?,
    val revision: Long,
    val unavailable: String?,
)

internal data class GrammarTarget(val identity: GrammarIdentity, val source: WeakReference<LocalDataSource>)

/** No PSI or discovery is used by the connection supplier, which runs on the verifier's worker. */
@Service(Service.Level.PROJECT)
internal class DuckdbGrammarVerification(private val owner: Project) {
    private val validators = object : LinkedHashMap<GrammarIdentity, NativeSqlValidator>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GrammarIdentity, NativeSqlValidator>?) = size > 32
    }

    fun identity(file: PsiFile): GrammarIdentity? {
        if (file.project !== owner) return null
        val resolver = DuckdbCatalogProfile.dataSources
        val source = resolver.resolve(file).dataSource ?: return null
        if (!DuckdbGrammarSources.accepts(source)) return null
        val session = resolver.consoleSession(file)
        val runtime = owner.service<DuckdbGrammarRuntime>()
        val follow = runtime.follows(session)
        val state = runtime.runtime(source, session.takeIf { follow })
        val configured = owner.service<DuckdbGrammarSettings>().profile(source)
        val profile = if (follow) DuckdbGrammarProfile(state?.snapshot?.effective.orEmpty()) else configured
        val reason = when {
            state == null -> "Connect the data source and refresh its grammar state"
            !state.snapshot.supported -> state.snapshot.unavailable
            state.routed -> "Validation unavailable after session routing changes; reconnect to restore the managed connection"
            follow -> "Follow-console validation unavailable: the IDE cannot duplicate the source runtime using the currently supported APIs"
            !isFileBacked(source.url) -> "Validation unavailable: a matching in-memory or remote helper cannot be established"
            else -> null
        }
        return GrammarIdentity(source.uniqueId, source.url, source.username, profile, follow,
            runtime.sessionIdentity(session.takeIf { follow }), state, runtime.stamp, reason)
    }

    fun validator(file: PsiFile): NativeSqlValidator? {
        val target = target(file) ?: return null
        val identity = target.identity
        return synchronized(validators) {
            validators.getOrPut(identity) {
                NativeSqlValidator.of(ConnectionDuckdbVerifier(connect = {
                    identity.unavailable?.let { throw SQLException(it) }
                    openHelper(target)
                }, checkCanceled = ProgressManager::checkCanceled))
            }
        }
    }

    fun target(file: PsiFile): GrammarTarget? {
        val identity = identity(file) ?: return null
        val source = DuckdbCatalogProfile.dataSources.resolve(file).dataSource ?: return null
        return GrammarTarget(identity, WeakReference(source))
    }

    fun isCurrent(target: GrammarTarget): Boolean = !owner.isDisposed && target.source.get()?.let { isCurrent(it, target.identity) } == true

    fun openHelper(target: GrammarTarget): DuckdbGrammarHelper? =
        recoverEditorOperation {
            target.identity.unavailable?.let { throw SQLException(it) }
            val source = target.source.get() ?: return@recoverEditorOperation null
            if (!isCurrent(target)) return@recoverEditorOperation null
            val ref = runBlockingMaybeCancellable {
                DatabaseConnectionManager.getInstance().build(owner, HelperConnectionPoint(source))
                    .setRequestor(helperRequestor()).setAskPassword(false).create()
            } ?: return@recoverEditorOperation null
            val helper = DuckdbGrammarHelper(ref.get(), { ref.close() }, target.identity.profile, { isCurrent(target) })
            try {
                // The file-backed driver cache normally shares definitions. An equivalent helper is
                // accepted only after discovering every required name and reading back exact activation.
                DuckdbGrammarDiscovery.apply(helper, target.identity.profile)
                helper.checkCurrent()
                helper
            } catch (error: Throwable) {
                helper.close()
                throw error
            }
        }.getOrElse { throw SQLException(it.message ?: "Grammar helper initialization failed", it) }

    private fun isCurrent(source: LocalDataSource, identity: GrammarIdentity): Boolean =
        source.url == identity.url && source.username == identity.user &&
            owner.service<DuckdbGrammarSettings>().profile(source) == identity.profile &&
            owner.service<DuckdbGrammarRuntime>().let { it.stamp == identity.revision && it.runtime(source) != null }

    companion object {
        fun isFileBacked(url: String?): Boolean {
            val location = url?.takeIf { it.startsWith("jdbc:duckdb:") }?.removePrefix("jdbc:duckdb:")
                ?.substringBefore(';')?.substringBefore('?')?.trim() ?: return false
            if (location.isEmpty() || location.startsWith(":memory:", ignoreCase = true)) return false
            // URI locations can route to remote storage or another server. A drive letter is a local path.
            val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(location)?.value
            return scheme == null || (scheme.length == 2 && location.getOrNull(2) in listOf('/', '\\'))
        }
    }
}
