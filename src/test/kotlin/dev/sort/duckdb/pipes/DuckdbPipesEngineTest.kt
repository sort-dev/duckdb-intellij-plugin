package dev.sort.duckdb.pipes

import dev.brikk.house.sql.shape.SqlFragment
import org.junit.Test
import org.junit.Assert.*
import java.sql.DriverManager

class DuckdbPipesEngineTest {
    private fun supported(sql: String): DuckdbPipesEngine.Transpile.Ok {
        val result = DuckdbPipesEngine.transpile(sql)
        assertTrue("$sql: $result", result is DuckdbPipesEngine.Transpile.Ok)
        return (result as DuckdbPipesEngine.Transpile.Ok).also {
            assertNull(it.executionError)
            assertSame(it.sql, it.result.sourceMap!!.output)
            assertFalse(DuckdbPipes.containsPipeOperator(it.sql))
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
            "SELECT /* |> */ FROM", "SELECT 'unclosed |> fake", "SELECT 1 | > 2")) {
            assertFalse(sql, DuckdbPipes.containsPipeOperator(sql))
            assertSame(sql, DuckdbPipesEngine.Transpile.NotPipe, DuckdbPipesEngine.transpile(sql))
        }
    }
    @Test fun lexicalBoundariesPreserveQuotedAndCommentedSemicolons() {
        val sql = "SELECT 'a; |> b', \$\$c; |> d\$\$, \"semi;col\"; /* nested /* ; |> */ ; */ FROM t |> LIMIT 1; -- ; |>\n"
        val chunks = DuckdbPipes.chunks(sql)
        assertEquals(3, chunks.size)
        assertEquals(listOf(false, true, false), chunks.map { it.hasPipeOperator })
        assertEquals(listOf(true, true, false), chunks.map { it.hasSql })
        assertEquals(sql, chunks.joinToString("") { it.text })
        assertEquals(chunks[1], DuckdbPipes.chunkAt(sql, chunks[1].startOffset))
    }
    @Test fun escapedAndDollarQuotedLiteralsDoNotExposeOperators() {
        for (sql in listOf("SELECT E'a\\'; |> b'; SELECT 2;", "SELECT \$tag\$a; |> b\$tag\$;",
            "SELECT \$🦆\$a; FROM t |> LIMIT 1;\$🦆\$;", "SELECT 'a''; |> b';")) {
            assertFalse(sql, DuckdbPipes.containsPipeOperator(sql))
            assertTrue(DuckdbPipes.chunks(sql).all { it.boundaryError == null })
        }
    }
    @Test fun malformedBoundariesAndMixedFragmentsRefuse() {
        for (sql in listOf("FROM t |> SELECT '", "FROM t |> LIMIT 1 /* open", "FROM t |> SELECT \$\$open; SELECT 1")) {
            val chunks = DuckdbPipes.chunks(sql)
            assertEquals(1, chunks.size)
            assertNotNull(chunks.single().boundaryError)
            assertTrue(DuckdbPipesEngine.transpile(sql) is DuckdbPipesEngine.Transpile.Err)
        }
        assertTrue(DuckdbPipesEngine.transpile("SELECT 1; FROM t |> LIMIT 1;") is DuckdbPipesEngine.Transpile.Err)
        assertTrue(DuckdbPipesEngine.transpile("FROM t |> BOGUS 1") is DuckdbPipesEngine.Transpile.Err)
        assertTrue(DuckdbPipesEngine.transpile("FROM range(3) |> LIMIT -1") is DuckdbPipesEngine.Transpile.Err)
        assertTrue(DuckdbPipesEngine.transpile("FROM range(3) |> OFFSET -1") is DuckdbPipesEngine.Transpile.Err)
    }
    @Test fun nestedPipesAreLoweredEvenWhenRootIsNotPipe() {
        val sql = "WITH x AS (FROM range(4) |> LIMIT 2) SELECT * FROM x"
        assertFalse(SqlFragment(sql, "duckdb").isPipe)
        supported(sql)
    }
    @Test fun stagePrefixesKeepUtf16OffsetsAndInitialFrom() {
        val text = "FROM t |> SELECT '😀' AS face |> LIMIT 2"
        val prefix = DuckdbPipesEngine.stagePrefixAt(text, text.indexOf("face"))!!
        assertEquals(2, prefix.stage)
        assertEquals(3, prefix.total)
        assertEquals("FROM t |> SELECT '😀' AS face", prefix.text)
        assertEquals("FROM t", DuckdbPipesEngine.stagePrefixAt(text, 2)!!.text)
        assertTrue(DuckdbPipesEngine.transpile("FROM t", allowFirstFromStage = true) is DuckdbPipesEngine.Transpile.Ok)
        assertTrue(DuckdbPipesEngine.transpile("SELECT id FROM t", allowFirstFromStage = true) is DuckdbPipesEngine.Transpile.Ok)
    }
    @Test fun warningsCannotBeSubmitted() {
        val safe = supported("FROM range(3) |> LIMIT 1")
        val warned = safe.copy(result = safe.result.copy(unsupportedMessages = listOf("lossy", "")))
        assertNotNull(warned.executionError)
        assertEquals(listOf("lossy", ""), warned.result.unsupportedMessages)
    }
    @Test fun parametersExcludeDuckdbColons() {
        val text = "FROM t |> WHERE id = :id |> SELECT :value AS v, {'k':x}, label:amount, x::INT"
        assertEquals(listOf(":id", ":value"), namedParameterRanges(text).map { text.substring(it) })
    }
    @Test fun optionalRecoveryDoesNotSwallowCancellationOrErrors() {
        for (failure in listOf(com.intellij.openapi.progress.ProcessCanceledException(),
            java.util.concurrent.CancellationException(), AssertionError("fatal"), LinkageError("linkage"))) {
            try {
                runPipeCatching<Unit> { throw failure }
                fail("Expected propagation")
            } catch (actual: Throwable) { assertSame(failure, actual) }
        }
    }
}
