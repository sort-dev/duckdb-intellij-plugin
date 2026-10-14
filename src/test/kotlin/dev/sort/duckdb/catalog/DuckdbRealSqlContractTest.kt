package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.testing.*
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.util.UUID

/** The shared everyday-SQL and partial-model packs run through the product registrations. */
class DuckdbRealSqlContractTest : BasePlatformTestCase() {
    private val manager get() = LocalDataSourceManager.getInstance(project)
    private var source: LocalDataSource? = null
    private var console: RecordingConsole? = null

    override fun tearDown() {
        try {
            console?.close()
            source?.let(manager::removeDataSource)
            DbPsiFacade.getInstance(project).clearCaches()
        } finally { super.tearDown() }
    }

    private fun install(content: RealSqlModel) {
        console?.close(); console = null
        source?.let(manager::removeDataSource)
        val model = ModelFactory.BLACK_HOLE.createModel(DuckdbDbms.DUCKDB_BRIKK)
        val root = model.root as PgRoot
        for ((path, columns) in content.tables) {
            val names = path.split('.')
            val table = root.databases.createOrGet(names[0]).schemas.createOrGet(names[1]).tables.createOrGet(names[2])
            columns.forEach { table.columns.createOrGet(it) }
        }
        for (path in content.bare) {
            val names = path.split('.')
            root.databases.createOrGet(names[0]).schemas.createOrGet(names[1]).tables.createOrGet(names[2])
        }
        for (path in content.namespaces) {
            val names = path.split('.')
            val database = root.databases.createOrGet(names[0])
            names.getOrNull(1)?.let { database.schemas.createOrGet(it) }
        }
        source = LocalDataSource().apply {
            name = "SQL contract ${UUID.randomUUID()}"
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native"))
            url = "jdbc:offline:sql-contract"
            isAutoSynchronize = false
            this.model = model
            val namespaces = ((content.tables.keys + content.bare).map { it.split('.').dropLast(1) } + content.namespaces.map { it.split('.') }).distinct()
            setIntrospectionScope(namespaces.map { CatalogScopes.select(listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA), it) }.reduce(CatalogScopes::widen))
        }.also(manager::addDataSource)
        // These are intentionally installed model snapshots, not live empty schemas waiting
        // for JDBC. Mark the selected namespaces complete in the auto service as a real sync would.
        val requests = project.getService(DuckdbAutoIntrospectService::class.java)
        val paths = ((content.tables.keys + content.bare).map { it.split('.').dropLast(1) } + content.namespaces.map { it.split('.') }).distinct()
        paths.forEach { requests.engine.finish(dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine.Key(requireNotNull(source).uniqueId, it), true) }
        DbPsiFacade.getInstance(project).clearCaches()
    }

    private fun target(): RealSqlTarget = object : RealSqlTarget {
        override val dialect = "duckdb"
        override val features = RealSqlFeature.entries.toSet() - setOf(
            RealSqlFeature.BACKTICK_IDENTIFIERS, RealSqlFeature.DORIS,
            RealSqlFeature.CLICKHOUSE_TABLE_FUNCTIONS, RealSqlFeature.DORIS_TABLE_FUNCTIONS,
            RealSqlFeature.TRINO_TABLE_FUNCTIONS, RealSqlFeature.DUCKDB_TABLE_FUNCTIONS,
        )
        override val fixture get() = myFixture
        override fun install(model: RealSqlModel) = this@DuckdbRealSqlContractTest.install(model)
        override fun open(sql: String) {
            console?.close()
            val file = myFixture.configureByText("everyday-${UUID.randomUUID()}.sql", sql).virtualFile
            setSqlDialectMapping(project, file, DuckdbSqlDialect, testRootDisposable)
            myFixture.configureFromExistingVirtualFile(file)
            console = RecordingConsole(project, file, requireNotNull(source)).also {
                it.console.setSearchPath(SearchPath.of(ObjectPath.create("shop", ObjectKind.DATABASE).append("sales", ObjectKind.SCHEMA)))
            }
        }
    }

    fun testEverydaySqlUsesTheRealProductFeatures() {
        install(RealSqlModel.STANDARD)
        myFixture.enableInspections(dev.sort.duckdb.registeredSqlInspection("SqlResolveInspection"))
        RealSqlContract(target()).check()
        println("DuckDB everyday SQL contract: ${RealSqlCases.ALL.size} shared cases, native checking enabled")
    }

    fun testUnknownAndUnintrospectedModelStatesDoNotInventColumnErrors() {
        install(RealSqlModel.STANDARD)
        myFixture.enableInspections(dev.sort.duckdb.registeredSqlInspection("SqlResolveInspection"))
        RealSqlContract(target()).checkModelStates()
        println("DuckDB model-state contract: ${RealSqlCases.EXTERNAL.size} cases across ${RealSqlModel.STATES.size} model states")
    }

    fun testApplicableSharedTableFunctionCases() {
        install(RealSqlModel.STANDARD)
        myFixture.enableInspections(dev.sort.duckdb.registeredSqlInspection("SqlResolveInspection"))
        RealSqlContract(target(), RealSqlCases.TABLE_FUNCTIONS).check()
        println("DuckDB table-function contract: ${RealSqlCases.TABLE_FUNCTIONS.size} shared cases, filtered to applicable features")
    }
}
