package dev.sort.duckdb.pipes

import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.intellij.pipe.containsPipe
import dev.brikk.house.sql.intellij.pipe.lower
import dev.brikk.house.sql.intellij.pipe.statementAt
import org.junit.Test
import org.junit.Assert.*
import java.sql.DriverManager

class DuckdbPipesEngineTest {
    @Test fun nativeValidatorPreservesDuckdbVerdictsAndSubmissionTiming() {
        val validator = DuckdbPipeDialect.validator
        assertEquals(ValidationTiming.Submission, validator.timing)
        assertSame(NativeVerdict.Accepted, validator.validate("SELECT 1", null))
        val rejected = validator.validate("SELECT FROM WHERE", null) as NativeVerdict.Rejected
        assertTrue(rejected.message, rejected.message.contains("Parser Error"))
        assertSame(NativeVerdict.Accepted, validator.validate("SELECT '" + "x".repeat(100_001) + "'", null))
        assertSame(NativeVerdict.Unavailable, validator.validate("SELECT 'before\u0000after'", null))
        assertSame(NativeVerdict.Unavailable, validator.validate("CREATE TABLE never_created AS SELECT 1", null))
        assertNull(supported("FROM range(3) |> LIMIT 1").verdict)
    }
    private fun supported(sql: String): PipeTranslation.Lowered {
        val result = DuckdbPipeDialect.lower(sql)
        assertTrue("$sql: $result", result is PipeTranslation.Lowered)
        return (result as PipeTranslation.Lowered).also {
            assertNull(it.blocker)
            assertSame(it.sql, it.result.sourceMap!!.output)
            assertFalse(DuckdbPipeDialect.containsPipe(it.sql))
        }
    }
    @Test fun executesGeneratedSqlAgainstDuckdb() {
        val result = supported("FROM range(5) AS t(i) |> WHERE i > 1 |> SELECT i * 2 AS doubled |> ORDER BY doubled")
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s ->
                s.executeQuery(result.sql).use { rows ->
                    val values = mutableListOf<Long>()
                    while (rows.next()) values.add(rows.getLong(1))
                    assertEquals(listOf(4L, 6L, 8L), values)
                }
            }
        }
    }
    @Test fun nativeFromFirstAndQuotedMarkersAreNotClaimed() {
        for (sql in listOf("FROM range(3)", "FROM range(3) SELECT *", "SELECT '|>'", "SELECT \"|>\"", "SELECT $$|>$$",
            "SELECT /* |> */ FROM", "SELECT 1 | > 2")) {
            assertFalse(sql, DuckdbPipeDialect.containsPipe(sql))
            assertSame(sql, PipeTranslation.NotPipe, DuckdbPipeDialect.lower(sql))
        }
        val unterminated = "SELECT 'unclosed |> fake"
        assertFalse(DuckdbPipeDialect.containsPipe(unterminated))
        assertTrue(DuckdbPipeDialect.lower(unterminated) is PipeTranslation.Refused)
    }
    @Test fun lexicalBoundariesPreserveQuotedAndCommentedSemicolons() {
        val sql = "SELECT 'a; |> b', \$\$c; |> d\$\$, \"semi;col\"; /* nested /* ; |> */ ; */ FROM t |> LIMIT 1; -- ; |>\n"
        val chunks = DuckdbPipeDialect.scriptRanges(sql)
        assertEquals(3, chunks.size)
        assertEquals(listOf(false, true, false), chunks.map { it.hasPipe })
        assertEquals(listOf(true, true, false), chunks.map { it.hasSql })
        assertEquals(sql, chunks.joinToString("") { sql.substring(it.start, it.end) })
        assertEquals(chunks[1], DuckdbPipeDialect.statementAt(sql, chunks[1].start))
    }
    @Test fun escapedAndDollarQuotedLiteralsDoNotExposeOperators() {
        for (sql in listOf("SELECT E'a\\'; |> b'; SELECT 2;", "SELECT \$tag\$a; |> b\$tag\$;",
            "SELECT \$🦆\$a; FROM t |> LIMIT 1;\$🦆\$;", "SELECT 'a''; |> b';")) {
            assertFalse(sql, DuckdbPipeDialect.containsPipe(sql))
            assertTrue(DuckdbPipeDialect.scriptRanges(sql).all { it.boundaryError == null })
        }
    }
    @Test fun malformedBoundariesAndMixedFragmentsRefuse() {
        for (sql in listOf("FROM t |> SELECT '", "FROM t |> LIMIT 1 /* open", "FROM t |> SELECT \$\$open; SELECT 1")) {
            val chunks = DuckdbPipeDialect.scriptRanges(sql)
            assertEquals(1, chunks.size)
            assertNotNull(chunks.single().boundaryError)
            assertTrue(DuckdbPipeDialect.lower(sql) is PipeTranslation.Refused)
        }
        assertTrue(DuckdbPipeDialect.lower("SELECT 1; FROM t |> LIMIT 1;") is PipeTranslation.Refused)
        assertTrue(DuckdbPipeDialect.lower("FROM t |> BOGUS 1") is PipeTranslation.Refused)
        assertTrue(DuckdbPipeDialect.lower("FROM range(3) |> LIMIT -1") is PipeTranslation.Refused)
        assertTrue(DuckdbPipeDialect.lower("FROM range(3) |> OFFSET -1") is PipeTranslation.Refused)
    }
    @Test fun nestedPipesAreLoweredEvenWhenRootIsNotPipe() {
        val sql = "WITH x AS (FROM range(4) |> LIMIT 2) SELECT * FROM x"
        assertFalse(SqlFragment(sql, "duckdb").isPipe)
        supported(sql)
    }
    @Test fun parametersExcludeDuckdbColons() {
        val text = "FROM t |> WHERE id = :id |> SELECT :value AS v, {'k':x}, label:amount, x::INT"
        assertEquals(listOf(":id", ":value"), DuckdbPipeDialect.parameterSpans(text).map { text.substring(it.start, it.end) })
    }

    @Test fun coreParameterDiscoveryKeepsExactRepeatedOccurrencesAndIgnoresLiterals() {
        val text = "  FROM t |> WHERE id = :id OR other = :id |> SELECT ':literal', \$\$ :dollar \$\$, :value AS v, {key: x}, alias: amount, x::INT -- :comment\n;"
        val parameters = DuckdbPipeDialect.parameterSpans(text)
        assertEquals(listOf("id", "id", "value"), parameters.map { it.name })
        assertEquals(listOf(":id", ":id", ":value"), parameters.map { text.substring(it.start, it.end) })
    }

    @Test fun incompleteDelimitersNeverExposeAnExecutableSuffix() {
        val text = "FROM t |> SELECT (id; SELECT 2;"
        val chunks = DuckdbPipeDialect.scriptRanges(text)
        assertEquals(1, chunks.size)
        assertNotNull(chunks.single().boundaryError)
        assertTrue(DuckdbPipeDialect.lower(text) is PipeTranslation.Refused)
    }

}
