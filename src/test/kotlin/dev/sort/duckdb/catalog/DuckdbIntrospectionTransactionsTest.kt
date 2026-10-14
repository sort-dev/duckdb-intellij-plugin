package dev.sort.duckdb.catalog

import com.intellij.database.console.client.SessionClient
import com.intellij.database.dataSource.connection.DGDepartment
import com.intellij.database.datagrid.DataRequest
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import java.lang.reflect.Proxy

/** The plugin guard deliberately protects more than the kit's current-console transaction rule. */
class DuckdbIntrospectionTransactionsTest : DuckdbCatalogTestCase() {
    private fun withConsoles(action: (RecordingConsole, RecordingConsole, DuckdbIntrospectionTransactions) -> Unit) {
        val source = source()
        com.intellij.database.psi.DbPsiFacade.getInstance(project).clearCaches()
        val a = myFixture.addFileToProject("guard-a.sql", "SELECT 1").virtualFile
        val b = myFixture.addFileToProject("guard-b.sql", "SELECT 1").virtualFile
        RecordingConsole(project, a, source).use { first ->
            RecordingConsole(project, b, source).use { second ->
                action(first, second, project.getService(DuckdbIntrospectionTransactions::class.java))
            }
        }
    }

    private fun request(recording: RecordingConsole, sql: String, owner: DataRequest.Owner = recording.console): DataRequest.Context {
        val query = object : DataRequest.QueryRequest(owner, sql, DataRequest.newConstraints(), null) {}
        return DataRequest.Context(recording.console.session.messageBus.dataProducer, DataRequest.newOutputHandler(), query, DataRequest.AUTO_COMMIT)
    }

    fun testActiveRequestsInAnotherSessionOfTheSameSourceBlockIntrospection() = withConsoles { a, b, guard ->
        val file = requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(a.console.virtualFile))
        assertFalse(guard.blocked(file))
        val context = request(b, "SELECT 1")
        guard.started(context)
        assertTrue("Other-session active requests still guard this file", guard.blocked(file))
        guard.finished(context)
        assertFalse(guard.blocked(file))
    }

    fun testExplicitBeginInAnotherSessionBlocksUntilCommit() = withConsoles { a, b, guard ->
        val file = requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(a.console.virtualFile))
        val begin = request(b, "BEGIN TRANSACTION")
        guard.started(begin)
        guard.finished(begin)
        assertTrue("Explicit BEGIN must survive the end of its request", guard.blocked(file))
        val commit = request(b, "COMMIT")
        guard.started(commit)
        guard.finished(commit)
        assertFalse(guard.blocked(file))
    }

    fun testIntrospectionRequestsDoNotCountAsBusyOrChangeTransactionState() = withConsoles { a, b, guard ->
        val file = requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(a.console.virtualFile))
        val owner = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(DataRequest.Owner::class.java, SessionClient::class.java, DataRequest.DepartmentHolder::class.java)) { proxy, method, args ->
            when (method.name) {
                "getSession" -> b.console.session
                "getDepartment" -> DGDepartment.INTROSPECTION
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "getTitle" -> "introspection test owner"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as DataRequest.Owner
        val context = request(b, "BEGIN TRANSACTION", owner)
        guard.started(context)
        assertFalse("Metadata must not block its own next step", guard.blocked(file))
        guard.finished(context)
        assertFalse("Metadata must not leave an explicit transaction marker", guard.blocked(file))
    }
}
