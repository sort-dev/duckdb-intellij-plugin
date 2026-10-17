package dev.sort.duckdb.validate

import com.google.gson.JsonParser
import org.duckdb.DuckDBDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.util.Properties
import dev.brikk.house.sql.verify.VerifyResult

class DuckdbEngineValidatorGradingTest {
    private enum class Expected { CLEAN, FLAGGED, UNAVAILABLE }
    private data class Case(val origin: String, val sql: String, val parserFailure: Boolean)

    private val corpusDir get() = File(System.getProperty("corpus.dir"))

    // These errors require binding the embedded query, not parsing the outer SELECT.
    private val bindingOnlyErrors = setOf(
        "catalog/function/query_function.test:80",
        "catalog/function/query_function.test:85",
        "catalog/function/query_function.test:90",
        "catalog/function/query_function.test:95",
        "catalog/function/query_function.test:101",
        "catalog/function/query_function.test:112",
        "catalog/function/query_function.test:124",
        "catalog/function/query_function.test:191",
    )

    // Duplicate PKs, temp-schema table creation and PRAGMA value checks are not serializable SELECTs.
    private val unavailableErrors = setOf(
        "catalog/test_incorrect_table_creation.test:16",
        "catalog/test_incorrect_table_creation.test:21",
        "catalog/test_temporary.test:34",
        "order/test_nulls_first.test:124",
    )

    @Test
    fun `every negative has an explicit permitted verdict`() {
        val cases = File(corpusDir, "census-negatives.jsonl").readLines().filter { it.isNotBlank() }.map {
            val record = JsonParser.parseString(it).asJsonObject
            Case(record.get("origin").asString, record.get("sql").asString,
                record.get("expectedError")?.takeUnless { it.isJsonNull }?.asString?.contains("Parser Error") == true)
        }
        val parserOrigins = cases.filter { it.parserFailure }.map { it.origin }.toSet()
        val exceptions = bindingOnlyErrors + unavailableErrors
        assertEquals("stale parser exceptions", exceptions, parserOrigins.intersect(exceptions))
        val verdicts = DuckdbEngineValidator.verdicts(cases.map { it.sql })
        requireNotNull(DuckDBDriver().connect("jdbc:duckdb:", Properties())).use { connection ->
            cases.zip(verdicts).forEach { (case, verdict) ->
                val expected = when {
                    !case.parserFailure -> nativeExpectation(case.sql, connection)
                    case.origin in bindingOnlyErrors -> Expected.CLEAN
                    case.origin in unavailableErrors -> Expected.UNAVAILABLE
                    else -> Expected.FLAGGED
                }
                assertVerdict(case.origin, expected, verdict)
            }
        }
    }

    @Test
    fun `valid census matches native parse-only coverage`() {
        val statements = requireNotNull(File(corpusDir, "census").listFiles { file -> file.extension == "sql" })
            .sortedBy { it.name }.flatMap { file ->
                file.readText().split(Regex("(?m)^-- from .*$")).map { it.trim() }.filter { it.isNotEmpty() }
            }
        val verdicts = DuckdbEngineValidator.verdicts(statements)
        requireNotNull(DuckDBDriver().connect("jdbc:duckdb:", Properties())).use { connection ->
            statements.zip(verdicts).forEach { (sql, verdict) ->
                val expected = nativeExpectation(sql, connection)
                assertTrue("valid corpus rejected by the native parser: $sql", expected != Expected.FLAGGED)
                assertVerdict(sql, expected, verdict)
            }
        }
    }

    private fun nativeExpectation(sql: String, connection: Connection): Expected =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT json_serialize_sql('${sql.replace("'", "''")}')").use { result ->
                assertTrue(result.next())
                val document = JsonParser.parseString(result.getString(1)).asJsonObject
                when {
                    !document.get("error").asBoolean -> Expected.CLEAN
                    document.get("error_type").asString == "parser" -> Expected.FLAGGED
                    else -> Expected.UNAVAILABLE
                }
            }
        }

    private fun assertVerdict(source: String, expected: Expected, actual: VerifyResult) {
        when (expected) {
            Expected.CLEAN -> assertEquals(source, VerifyResult.Accepted, actual)
            Expected.FLAGGED -> assertTrue("$source must report a parser failure, got $actual",
                actual is VerifyResult.Rejected)
            Expected.UNAVAILABLE -> assertTrue("$source must remain unverified, got $actual", actual is VerifyResult.Unverified)
        }
    }
}
