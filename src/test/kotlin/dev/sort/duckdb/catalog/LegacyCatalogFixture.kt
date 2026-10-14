package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.remote.jdbc.helpers.GenericJdbcHelper
import com.intellij.database.remote.jdbc.impl.RemoteConnectionImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.catalog.*
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/** Preserve the baseline JUnit4 test names while letting the IDE fixture own EDT/setup/cleanup. */
internal abstract class LegacyCatalogFixture : DuckdbCatalogTestCase() {
    private lateinit var body: (LegacyCatalogFixture) -> Unit
    fun testBaselineCatalogParity() = body(this)
    fun execute(action: (LegacyCatalogFixture) -> Unit) {
        body = action
        name = "testBaselineCatalogParity"
        runBare()
    }
    val scratch get() = directory
    val testProject get() = project
    val disposable get() = testRootDisposable
    fun dataSource() = source()
    fun sqlFile() = myFixture.configureByText("legacy-parity-${java.util.UUID.randomUUID()}.sql", "SELECT 1")

    fun service(profile: LiveCatalogProfile = DuckdbLiveCatalogProfile): ParityCatalogService {
        val service = ParityCatalogService(errors.newScope(testRootDisposable), profile, directory.resolve("parity-store"))
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(ParityCatalogService::class.java, service, testRootDisposable)
        return service
    }

    fun connect(jdbc: Connection, source: LocalDataSource) {
        val remote = RemoteConnectionImpl(jdbc, GenericJdbcHelper())
        val requestor = Engine()
        val connection = Proxy.newProxyInstance(DatabaseConnection::class.java.classLoader, arrayOf(DatabaseConnection::class.java)) { proxy, method, args ->
            when (method.name) {
                "getRemoteConnection" -> remote
                "getConnectionPoint" -> source
                "getDbms" -> source.dbms
                "getRequestor" -> requestor
                "getAutoCommit" -> jdbc.autoCommit
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "baseline parity JDBC connection"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as DatabaseConnection
        val proto = Proxy.newProxyInstance(DatabaseConnectionInterceptor.ProtoConnection::class.java.classLoader,
            arrayOf(DatabaseConnectionInterceptor.ProtoConnection::class.java)) { _, method, _ -> throw UnsupportedOperationException(method.name) }
            as DatabaseConnectionInterceptor.ProtoConnection
        runBlocking { object : HarvestConnectionInterceptor(ParityCatalogService::class.java) {}.handleConnected(connection, proto) }
    }
}

internal fun legacyCatalog(action: (LegacyCatalogFixture) -> Unit) = object : LegacyCatalogFixture() {}.execute(action)

internal class ParityCatalogService(scope: CoroutineScope, override val profile: LiveCatalogProfile, private val folder: Path) : LiveCatalogService(scope) {
    val events = mutableListOf<Pair<String, RefreshReason>>()
    override fun persistenceDirectory() = folder
    override fun onRefreshed(project: com.intellij.openapi.project.Project, source: LocalDataSource, outcome: RefreshOutcome, reason: RefreshReason) {
        events += source.uniqueId to reason
        super.onRefreshed(project, source, outcome, reason)
    }
    fun notifyOutcome(project: com.intellij.openapi.project.Project, source: LocalDataSource, outcome: RefreshOutcome) =
        onRefreshed(project, source, outcome, RefreshReason.Manual)
}
