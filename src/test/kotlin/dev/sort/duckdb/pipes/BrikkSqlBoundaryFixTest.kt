package dev.sort.duckdb.pipes

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.shape.parseParameterOccurrences
import org.junit.Assert.*
import org.junit.Test

/** Calls core directly, so the temporary DuckDB guard cannot hide a missing fix. */
class BrikkSqlBoundaryFixTest {
    @Test fun unterminatedOrdinaryAndNestedCommentsAreRefused() {
        for (sql in listOf("FROM t |> LIMIT 1 /* open", "FROM t |> LIMIT 1 /*",
            "FROM t |> LIMIT 1 /* outer /* inner */", "FROM t |> LIMIT 1 /* outer /* inner")) {
            val statement = SqlScriptSnapshot.parse(sql, "duckdb").statements.single()
            assertNotNull(sql, statement.boundaryError)
            assertTrue(sql, statement.hasPipe)
            assertEquals(sql.length, statement.end)
            assertThrows(sql, TokenError::class.java) { Dialects.DUCKDB.tokenize(sql) }
        }
    }

    @Test fun knownEarlierStatementSurvivesAndUnfinishedTailStaysOpaque() {
        val sql = "SELECT 1; FROM t |> LIMIT 1 /* open; SELECT 99; FROM later |> LIMIT 2;"
        val statements = SqlScriptSnapshot.parse(sql, "duckdb").statements
        assertEquals(2, statements.size)
        assertNull(statements.first().boundaryError)
        assertEquals("SELECT 1;", sql.substring(statements.first().start, statements.first().end))
        assertNotNull(statements.last().boundaryError)
        assertEquals(sql.length, statements.last().end)
        assertEquals(statements.first().end, statements.last().start)
    }

    @Test fun quotedMarkersAndLineCommentsDoNotProduceFalseFailures() {
        val sql = "SELECT '/*', \"/*\", \$\$/*\$\$; -- /*\r\nSELECT 2; /* outer /* inner */ closed */"
        val statements = SqlScriptSnapshot.parse(sql, "duckdb").statements
        assertTrue(statements.toString(), statements.all { it.boundaryError == null })
        assertEquals(2, statements.count { it.hasSql })
        assertEquals(sql, statements.joinToString("") { sql.substring(it.start, it.end) })
    }

    @Test fun commentOnlyRemainderCannotBecomeAnExecutableSuffix() {
        val sql = "SELECT 1; /* open; FROM t |> LIMIT 1;"
        val statements = SqlScriptSnapshot.parse(sql, "duckdb").statements
        assertEquals(2, statements.size)
        assertNull(statements.first().boundaryError)
        assertNotNull(statements.last().boundaryError)
        assertFalse(statements.last().hasPipe)
    }

    @Test fun parameterDiscoveryDoesNotSilentlyAcceptUnterminatedComments() {
        assertThrows(TokenError::class.java) {
            parseParameterOccurrences("FROM t |> WHERE id = :id /* open", "duckdb")
        }
    }
}
