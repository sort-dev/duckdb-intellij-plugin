package dev.sort.duckdb.catalog

import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.console.client.SessionClient
import com.intellij.openapi.components.Service
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.catalog.StatementEffect
import dev.brikk.house.sql.intellij.catalog.StatementHeadDetector
import dev.brikk.house.sql.intellij.catalog.StatementRules
import java.util.WeakHashMap

/** Explicit BEGIN is not reflected by DatabaseSession.currentTx on every driver. */
@Service(Service.Level.PROJECT)
class DuckdbIntrospectionTransactions {
    private data class State(var explicit: Boolean = false, var busy: Int = 0)
    private val sessions = WeakHashMap<DatabaseSession, State>()
    private val detector = StatementHeadDetector(StatementRules.TRANSACTIONS, "duckdb")

    fun started(context: DataRequest.Context) = synchronized(sessions) {
        val session = session(context) ?: return@synchronized
        sessions.getOrPut(session, ::State).busy++
    }

    fun finished(context: DataRequest.Context) = synchronized(sessions) {
        val session = session(context) ?: return@synchronized
        val state = sessions.getOrPut(session, ::State)
        state.busy = (state.busy - 1).coerceAtLeast(0)
        val text = (context.request as? DataRequest.QueryRequest)?.query ?: context.query
        val effects = detector.detect(text).mapNotNull { (it.effect as? StatementEffect.Transaction)?.begins }
        // A failed script may have begun a transaction, but may not have reached its COMMIT.
        if (context.failed()) {
            if (true in effects) state.explicit = true
        } else if (context.request is DataRequest.TxRequest) state.explicit = false
        else effects.lastOrNull()?.let { state.explicit = it }
    }

    fun blocked(file: PsiFile): Boolean = synchronized(sessions) {
        val resolver = DuckdbCatalogProfile.dataSources
        val source = resolver.resolve(file).dataSource ?: return@synchronized true
        val current = resolver.consoleSession(file)
        if (current != null && current.currentTx != DataRequest.AUTO_COMMIT) return@synchronized true
        sessions.any { (session, state) ->
            !com.intellij.openapi.util.Disposer.isDisposed(session) && session.connectionPoint.dataSource.uniqueId == source.uniqueId &&
                (state.explicit || state.busy > 0 || session.currentTx != DataRequest.AUTO_COMMIT)
        }
    }

    private fun session(context: DataRequest.Context): DatabaseSession? =
        ((context.request.owner as? SessionClient<*>)?.session as? DatabaseSession)
            ?.takeIf { DuckdbCatalogProfile.dataSources.accepts(it.connectionPoint.dataSource.dbms) }

    private fun DataRequest.Context.failed() = getUserData(FAILED) == true

    companion object {
        internal val FAILED = com.intellij.openapi.util.Key.create<Boolean>("duckdb.introspection.transaction.failed")
    }
}
