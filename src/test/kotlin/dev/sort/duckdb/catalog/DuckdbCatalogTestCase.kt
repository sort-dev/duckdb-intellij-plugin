package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.datagrid.DataProducer
import com.intellij.database.datagrid.GridDataRequest
import com.intellij.database.remote.jdbc.helpers.GenericJdbcHelper
import com.intellij.database.remote.jdbc.impl.RemoteConnectionImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.runBlocking

abstract class DuckdbCatalogTestCase : BasePlatformTestCase() {
    protected lateinit var errors: BackgroundErrors
    protected lateinit var catalog: DuckdbLiveCatalogService
    protected lateinit var directory: Path
    private val sources = ArrayList<LocalDataSource>()
    private val heldConnections = ArrayList<DatabaseConnection>()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        directory = Files.createTempDirectory(Files.createDirectories(Path.of("build", "catalog-tests")), "fixture-")
        replaceCatalog()
    }

    protected fun replaceCatalog() {
        catalog = DuckdbLiveCatalogService(errors.newScope(testRootDisposable))
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(DuckdbLiveCatalogService::class.java, catalog, testRootDisposable)
    }

    protected fun source(): LocalDataSource = LocalDataSource().apply {
        name = "catalog-${UUID.randomUUID()}"
        databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native"))
        url = "jdbc:duckdb:"
        isAutoSynchronize = false
    }.also {
        LocalDataSourceManager.getInstance(project).addDataSource(it)
        sources += it
    }

    protected fun connected(jdbc: Connection, source: LocalDataSource, requestor: ConnectionRequestor = Engine()) {
        val remote = RemoteConnectionImpl(jdbc, GenericJdbcHelper())
        val connection = Proxy.newProxyInstance(DatabaseConnection::class.java.classLoader, arrayOf(DatabaseConnection::class.java)) { proxy, method, args ->
            when (method.name) {
                "getRemoteConnection" -> remote
                "getConnectionPoint" -> source
                "getDbms" -> source.dbms
                "getRequestor" -> requestor
                "getAutoCommit" -> jdbc.autoCommit
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "embedded DuckDB connection"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as DatabaseConnection
        heldConnections += connection
        val proto = Proxy.newProxyInstance(DatabaseConnectionInterceptor.ProtoConnection::class.java.classLoader,
            arrayOf(DatabaseConnectionInterceptor.ProtoConnection::class.java)) { _, method, _ -> throw UnsupportedOperationException(method.name) }
            as DatabaseConnectionInterceptor.ProtoConnection
        runBlocking { DuckdbHarvestConnectionInterceptor().handleConnected(connection, proto) }
    }

    protected fun legacy(source: LocalDataSource, functions: List<Pair<String, String>>, keywords: List<String> = listOf("SELECT")) {
        val file = DuckdbLiveCatalogProfile.legacyFiles.file(source.uniqueId)
        Files.createDirectories(file.parent)
        Files.writeString(file, buildString {
            appendLine("# duckdb-brikk live catalog v2 (see DuckdbLiveCatalog for the format)")
            appendLine("meta\tv1.5.5\t\t1")
            functions.forEach { (name, kind) -> appendLine("fn\t$name\t$kind") }
            keywords.forEach { appendLine("kw\t$it") }
        })
    }

    override fun tearDown() = RunAll(
        { errors.awaitScopes() },
        { sources.forEach { catalog.forget(it.uniqueId); LocalDataSourceManager.getInstance(project).removeDataSource(it) } },
        { errors.assertNone() },
        { directory.toFile().deleteRecursively() },
        { super.tearDown() },
    ).run()

    protected class Engine : DataProducer, ConnectionRequestor by ConnectionRequestor.Anonymous() {
        override fun processRequest(request: GridDataRequest): Unit = throw UnsupportedOperationException("not executed")
    }
}
