package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.console.session.DatabaseSession
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

internal data class GrammarTarget(val identity: GrammarIdentity, val source: WeakReference<LocalDataSource>,
    val session: WeakReference<DatabaseSession>? = null)

/** No PSI or discovery is used by the connection supplier, which runs on the verifier's worker. */
@Service(Service.Level.PROJECT)
internal class DuckdbGrammarVerification(private val owner: Project) {
    private val validators = object : LinkedHashMap<GrammarIdentity, NativeSqlValidator>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GrammarIdentity, NativeSqlValidator>?) = size > 32
    }

    fun identity(file: PsiFile): GrammarIdentity? = target(file)?.identity

    fun target(file: PsiFile): GrammarTarget? {
        if (file.project !== owner) return null
        val resolver = DuckdbCatalogProfile.dataSources
        val source = resolver.resolve(file).dataSource ?: return null
        if (!DuckdbGrammarSources.accepts(source)) return null
        val session = resolver.consoleSession(file)
        val runtime = owner.service<DuckdbGrammarRuntime>()
        val follow = runtime.follows(session)
        // Managed profile selection is independent of console routing. A bound
        // console must never borrow another console's more recent runtime.
        val state = runtime.runtime(source, session)
        val configured = owner.service<DuckdbGrammarSettings>().profile(source)
        val profile = if (follow) DuckdbGrammarProfile(state?.snapshot?.effective.orEmpty()) else configured
        val reason = unavailable(source,state,follow)
        val identity = GrammarIdentity(source.uniqueId, source.url, source.username, profile, follow,
            runtime.sessionIdentity(session), state, runtime.stamp, reason)
        return GrammarTarget(identity, WeakReference(source), session?.let(::WeakReference))
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

    fun isCurrent(target: GrammarTarget): Boolean {
        if (owner.isDisposed) return false
        val source = target.source.get() ?: return false
        val session = target.session?.get()
        val identity = target.identity
        if (identity.session != null && session == null) return false
        if (source.uniqueId != identity.sourceId || source.url != identity.url || source.username != identity.user) return false
        val runtime = owner.service<DuckdbGrammarRuntime>()
        if (runtime.stamp != identity.revision || runtime.follows(session) != identity.follow ||
            runtime.sessionIdentity(session) !== identity.session) return false
        val state = runtime.runtime(source, session)
        if (state == null || state !== identity.runtime) return false
        val profile = if (identity.follow) DuckdbGrammarProfile(state.snapshot.effective)
            else owner.service<DuckdbGrammarSettings>().profile(source)
        return profile == identity.profile && unavailable(source,state,identity.follow) == identity.unavailable
    }

    private fun unavailable(source: LocalDataSource, state: DuckdbGrammarRuntime.Runtime?, follow: Boolean): String? = when {
        state == null -> "Connect the data source and refresh its grammar state"
        !state.snapshot.supported -> state.snapshot.unavailable
        state.routed -> "Validation unavailable after session routing changes: no matching isolated helper is established"
        follow -> "Follow-console validation unavailable: the IDE cannot duplicate the source runtime using the currently supported APIs"
        !isFileBacked(source.url) -> "Validation unavailable: a matching in-memory or remote helper cannot be established"
        else -> null
    }

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
