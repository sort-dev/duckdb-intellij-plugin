package dev.sort.duckdb.sql

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.sort.duckdb.catalog.DuckdbCatalogResolver
import dev.sort.duckdb.registeredSqlInspection
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path

class DuckdbBuiltinRecognitionTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private var autocomplete = false

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        myFixture.enableInspections(
            registeredSqlInspection("SqlResolveInspection"),
            registeredSqlInspection("SqlTypeInspection"),
            registeredSqlInspection("SqlInsertValuesInspection"),
        )
    }

    override fun tearDown() {
        val recordedErrors = errors
        RunAll(
            { CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete },
            { super.tearDown() },
            { recordedErrors.assertNone() },
        ).run()
    }

    private fun open(sql: String) {
        val file = myFixture.configureByText("builtin-recognition.sql", sql).virtualFile
        setSqlDialectMapping(project, file, DuckdbSqlDialect, testRootDisposable)
        myFixture.configureFromExistingVirtualFile(file)
        assertSame(DuckdbSqlDialect, myFixture.file.language)
    }

    private fun reports(): List<HighlightInfo> = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }

    private fun HighlightInfo.evidence(): String =
        "${myFixture.file.text.substring(startOffset, endOffset)}: $description [inspection=$inspectionToolId, type=$type]"

    fun testBuiltinTableFunctionsResolveInSelectAndCtas() {
        val failures = mutableListOf<String>()
        for ((name, arguments) in listOf(
            "read_parquet" to "'synthetic-input.parquet'",
            "read_csv" to "'synthetic-input.csv'",
            "range" to "3",
            "generate_series" to "1, 3",
            "duckdb_tables" to "",
        )) {
            for (head in listOf("SELECT * FROM", "CREATE OR REPLACE TABLE builtin_result AS SELECT * FROM")) {
                val sql = "$head $name($arguments)"
                open(sql)
                val active = DuckdbCatalogResolver.activeCatalogFor(myFixture.file)
                val metadata = active.functions.filter { it.name == name }
                val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single()
                println("$sql: language=${myFixture.file.language.id}, dbms=${DuckdbSqlDialect.dbms}, live=${active.live}, metadata=$metadata, nativeBuiltin=${DuckdbSqlDialect.supportedFunctions.get(name)}, resolve=${call.nameElement?.resolveSymbol()}")
                failures += reports().map { "$sql: ${it.evidence()}" }
                assertNotNull("$sql must resolve its builtin table function", call.nameElement?.resolveSymbol())
            }
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }

    fun testUnsignedTypesAndAliasesResolveInDeclarationsAndCasts() {
        val failures = mutableListOf<String>()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for (name in listOf("UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "UINT8", "UINT32", "INT8", "STRING", "INTEGER")) {
                for (sql in listOf("CREATE OR REPLACE TABLE builtin_type_probe (sample_value $name)", "SELECT CAST(0 AS $name)")) {
                    connection.createStatement().use { it.execute(sql) }
                    open(sql)
                    println("$name: language=${myFixture.file.language.id}, typeSystem=${DuckdbSqlDialect.typeSystem.javaClass.name}, nativeType=${DuckdbSqlDialect.builtInTypes[name.lowercase()] ?: DuckdbSqlDialect.builtInTypes[name]}")
                    failures += reports().map { "$sql: ${it.evidence()}" }
                }
            }
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }

    fun testUnknownFunctionAndTypeRemainErrors() {
        for ((sql, name) in listOf(
            "SELECT * FROM not_a_builtin_reader('synthetic-input')" to "not_a_builtin_reader",
            "CREATE OR REPLACE TABLE builtin_type_probe (sample_value not_a_builtin_type)" to "not_a_builtin_type",
        )) {
            open(sql)
            val found = reports()
            assertTrue(found.map { it.evidence() }.toString(), found.any { myFixture.file.text.substring(it.startOffset, it.endOffset) == name })
        }
    }

    fun testInsertSelectWithUnknownReaderOutputDoesNotInventALayoutMismatch() {
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build", "r5-r6-builtins")), "parquet-")
        try {
            val path = directory.resolve("synthetic.parquet").toAbsolutePath().toString().replace("'", "''")
            val declaration = "CREATE OR REPLACE TABLE insert_probe (source_label VARCHAR, record_key UINTEGER, payload_text VARCHAR, status_text VARCHAR)"
            val insertion = "INSERT INTO insert_probe SELECT 'local' AS source_label, record_key::UINTEGER, * EXCLUDE(record_key) FROM read_parquet('$path')"
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("COPY (SELECT 7 AS record_key, 'x' AS payload_text, 'fresh' AS status_text) TO '$path' (FORMAT PARQUET)")
                    statement.execute(declaration)
                    statement.execute(insertion)
                }
            }
            open("$declaration;\n$insertion")
            val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single { it.nameElement?.name == "read_parquet" }
            val fromType = (call.parent as? com.intellij.sql.psi.SqlDasTypeAwareElement)?.dasType
            val table = fromType as? com.intellij.sql.psi.SqlTableType
            println("INSERT reader PSI=${call.javaClass.name}, type=${call.dasType}, FROM type=$fromType, columns=${table?.let { (0 until it.columnCount).map(it::getColumnName) }}")
            val found = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WARNING }
            assertEmpty(found.joinToString("\n") { it.evidence() }, found)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    fun testKnownInsertLayoutMismatchStillReports() {
        open("CREATE TABLE layout_probe (first_value INTEGER, second_value INTEGER); INSERT INTO layout_probe SELECT 1")
        val found = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WARNING }
        assertTrue(found.map { it.evidence() }.toString(), found.any { it.inspectionToolId == "SqlInsertValuesInspection" })
    }

    fun testTableAndScalarCandidatesInsertCallsWithTheirSemanticKinds() {
        for ((name, head, kind) in listOf(
            Triple("read_parquet", "SELECT * FROM", FunctionKind.TABLE_VALUED),
            Triple("read_csv", "CREATE OR REPLACE TABLE builtin_result AS SELECT * FROM", FunctionKind.TABLE_VALUED),
            Triple("range", "SELECT * FROM", FunctionKind.TABLE_VALUED),
            Triple("abs", "SELECT", FunctionKind.SCALAR),
        )) {
            open("$head ${name.dropLast(1)}<caret>")
            val item = myFixture.completeBasic().orEmpty().single { it.lookupString == name && it.`object` is FunctionPresentation }
            assertEquals(kind, (item.`object` as FunctionPresentation).kind)
            myFixture.lookup.currentItem = item
            myFixture.finishLookup('\n')
            myFixture.checkResult("$head $name(<caret>)")
        }
    }
}
