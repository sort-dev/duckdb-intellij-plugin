package dev.sort.duckdb.sql

import com.intellij.sql.psi.SqlTokens
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.lexAll

class DuckdbLexerTest : BasePlatformTestCase() {
    fun testCastTargets() {
        for (target in listOf("INTEGER", "\"INTEGER\"", "_int", "/* type */ INTEGER", "STRUCT(a INTEGER)")) {
            val sql = "SELECT TRY_CAST(1 AS $target);"
            val mask = DuckdbLexer().lexAll(sql).single { it.type == SqlTokens.SQL_BLOCK_COMMENT }
            assertEquals("AS $target", sql.substring(mask.start, mask.end))
            assertRestartsMatchFullLex(::DuckdbLexer, sql)
        }
    }

    fun testMissingCastTargetsKeepAsAndDelimiters() {
        for (sql in listOf("TRY_CAST(1 AS )", "TRY_CAST(1 AS")) {
            val tokens = DuckdbLexer().lexAll(sql)
            assertFalse(tokens.any { it.type == SqlTokens.SQL_BLOCK_COMMENT })
            assertSame(SqlTokens.SQL_AS, tokens.single { sql.substring(it.start, it.end) == "AS" }.type)
            if (sql.endsWith(")")) assertSame(SqlTokens.SQL_RIGHT_PAREN, tokens.last().type)
            else assertEquals("AS", sql.substring(tokens.last().start, tokens.last().end))
            assertRestartsMatchFullLex(::DuckdbLexer, sql)
        }
    }

    fun testCreateOrReplaceTableTracksColumnTypes() {
        val sql = "CREATE OR REPLACE TABLE t (a DOUBLE);"
        val tokens = DuckdbLexer().lexAll(sql)
        assertEquals("OR REPLACE", tokens.single { it.type == SqlTokens.SQL_BLOCK_COMMENT }.let { sql.substring(it.start, it.end) })
        assertSame(SqlTokens.SQL_IDENT, tokens.single { sql.substring(it.start, it.end) == "DOUBLE" }.type)
        assertRestartsMatchFullLex(::DuckdbLexer, sql)
    }

    fun testGroupByAllAndQualifyAreRetyped() {
        val sql = "SELECT a FROM t GROUP BY ALL QUALIFY a > 0;"
        val tokens = DuckdbLexer().lexAll(sql)
        assertSame(SqlTokens.SQL_IDENT, tokens.single { sql.substring(it.start, it.end) == "ALL" }.type)
        assertSame(SqlTokens.SQL_HAVING, tokens.single { sql.substring(it.start, it.end) == "QUALIFY" }.type)
        assertRestartsMatchFullLex(::DuckdbLexer, sql)
    }

    fun testCommentsDoNotReplacePreviousToken() {
        val sql = "SELECT * /* keep */ EXCLUDE(a) FROM t;"
        val comments = DuckdbLexer().lexAll(sql).filter { it.type == SqlTokens.SQL_BLOCK_COMMENT }
        assertEquals(listOf("/* keep */", "EXCLUDE(a)"), comments.map { sql.substring(it.start, it.end) })
    }

    fun testCastContextEndsAtSemicolon() {
        val sql = "SELECT TRY_CAST(1; SELECT 2 AS value;"
        assertFalse(DuckdbLexer().lexAll(sql).any { it.type == SqlTokens.SQL_BLOCK_COMMENT })
        assertRestartsMatchFullLex(::DuckdbLexer, sql)
    }

    fun testMultipleEmissionsPreserveContext() {
        val sql = "ALTER TABLE t ALTER COLUMN a.b.c TYPE STRUCT(x INTEGER); CREATE TABLE 't' (a DOUBLE); SELECT * FROM 't' JOIN 'u' USING (a);"
        val idents = DuckdbLexer().lexAll(sql).filter { it.type == SqlTokens.SQL_IDENT }.map { sql.substring(it.start, it.end) }
        assertTrue(idents.containsAll(listOf(" a.b.c", "STRUCT(x INTEGER)", "'t'", "DOUBLE", "'u'")))
        assertRestartsMatchFullLex(::DuckdbLexer, sql)
    }

    fun testRestoreInsideStatement() {
        val sql = "CREATE TABLE t (a STRUCT(x INTEGER), b DOUBLE); SELECT TRY_CAST(1 AS INTEGER);"
        val lexer = DuckdbLexer()
        lexer.start(sql)
        while (lexer.tokenText != "STRUCT(x INTEGER)") {
            assertNotNull(lexer.tokenType)
            lexer.advance()
        }
        val position = lexer.currentPosition
        val expected = buildList {
            while (lexer.tokenType != null) {
                add(Triple(lexer.tokenStart, lexer.tokenEnd, lexer.tokenType))
                lexer.advance()
            }
        }
        lexer.restore(position)
        val restored = buildList {
            while (lexer.tokenType != null) {
                add(Triple(lexer.tokenStart, lexer.tokenEnd, lexer.tokenType))
                lexer.advance()
            }
        }
        assertEquals(expected, restored)
    }
}
