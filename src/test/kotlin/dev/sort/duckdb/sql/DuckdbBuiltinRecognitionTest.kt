package dev.sort.duckdb.sql

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.dialects.BuiltinFunction
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

    fun testReaderNamedArgumentLabelsResolveForEngineSupportedOperators() {
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build", "r7-named-arguments")), "reader-")
        val failures = mutableListOf<String>()
        try {
            val parquet = directory.resolve("synthetic.parquet").toAbsolutePath().toString().replace("'", "''")
            val csv = directory.resolve("synthetic.csv").toAbsolutePath()
            Files.writeString(csv, "sample_value,payload_text\n7,x\n")
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { it.execute("COPY (SELECT 7 AS sample_value, 'x' AS payload_text) TO '$parquet' (FORMAT PARQUET)") }
                for ((name, path, keys) in listOf(
                    Triple("read_parquet", parquet, listOf("filename", "file_row_number")),
                    Triple("read_csv", csv.toString().replace("'", "''"), listOf("header", "filename")),
                )) {
                    for (operator in listOf("=", ":=")) {
                        val arguments = keys.joinToString(", ") { "$it $operator true" }
                        val sql = "SELECT * FROM $name('$path', $arguments)"
                        connection.createStatement().use { statement ->
                            statement.executeQuery(sql).use { rows -> assertTrue("Local DuckDB must accept $name $operator", rows.next()) }
                        }
                        open(sql)
                        val active = DuckdbCatalogResolver.activeCatalogFor(myFixture.file)
                        val declared = active.snapshot.functions[name]?.overloads.orEmpty().flatMap { it.argNames.orEmpty() }.toSet()
                        assertTrue("$name argument names already exist in the metadata: $declared", declared.containsAll(keys))
                        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single()
                        val binding = call.nameElement?.resolveSymbol()
                        println("$name $operator: binding=${binding?.javaClass?.name}, nativePrototypes=${(binding as? BuiltinFunction)?.prototypes?.size}, live=${active.live}")
                        for (key in keys) {
                            val leaf = checkNotNull(myFixture.file.findElementAt(sql.indexOf("$key $operator")))
                            val reference = PsiTreeUtil.getParentOfType(leaf, SqlReferenceExpression::class.java, false)
                            println("$name $operator $key: PSI=${generateSequence(leaf) { it.parent }.take(5).map { it.javaClass.simpleName }.toList()}, kind=${reference?.referenceElementType?.targetKind}, resolve=${reference?.resolveSymbol()}")
                        }
                        failures += reports().map { "$name $operator: ${it.evidence()}" }
                    }
                }
            }
            assertEmpty(failures.joinToString("\n"), failures)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    fun testArgumentValuesAndScalarEqualitiesKeepColumnResolution() {
        val declaration = "CREATE TABLE parameter_inputs (flag_value BOOLEAN, number_value INTEGER);\n"
        for (operator in listOf("=", ":=")) {
            open(declaration + "SELECT * FROM parameter_inputs p CROSS JOIN read_parquet('synthetic-input.parquet', filename $operator p.flag_value)")
            val value = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlReferenceExpression::class.java).single { it.text == "p.flag_value" }
            assertNotNull("A named-argument value must retain column binding", value.resolveSymbol())
            open("SELECT * FROM read_parquet('synthetic-input.parquet', filename $operator missing_flag)")
            val found = reports()
            assertTrue(found.map { it.evidence() }.toString(), found.any { myFixture.file.text.substring(it.startOffset, it.endOffset) == "missing_flag" })
        }
        open(declaration + "SELECT abs(number_value), abs(CAST(number_value = number_value AS INTEGER)) FROM parameter_inputs")
        assertEmpty(reports().map { it.evidence() })
        for (sql in listOf(
            "SELECT abs(missing_number) FROM parameter_inputs",
            "SELECT abs(CAST(missing_number = number_value AS INTEGER)) FROM parameter_inputs",
        )) {
            open(declaration + sql)
            val found = reports()
            assertTrue(found.map { it.evidence() }.toString(), found.any { myFixture.file.text.substring(it.startOffset, it.endOffset) == "missing_number" })
        }
    }
}
