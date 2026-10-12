package dev.sort.duckdb.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.sql.psi.SqlTokens
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.lexAll
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

class DuckdbEditorSupportTest : BasePlatformTestCase() {
    private fun configure(sql: String) {
        val file = myFixture.configureByText("editor.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, DuckdbSqlDialect)
    }

    private fun color(type: IElementType): TextAttributesKey =
        SyntaxHighlighterFactory.getSyntaxHighlighter(DuckdbSqlDialect, project, myFixture.file.virtualFile)
            .getTokenHighlights(type).lastOrNull() ?: HighlighterColors.TEXT

    private fun recolors(all: List<HighlightInfo>, sql: String, text: String): List<TextAttributesKey> {
        val start = sql.indexOf(text)
        assertTrue(text, start >= 0)
        return all.filter { it.startOffset == start && it.endOffset == start + text.length }
            .mapNotNull { it.forcedTextAttributesKey }
    }

    fun testMaskedQuotedIdentifiersAndStrings() {
        val sql = "SELECT {'key': 'value', \"x\": 1}, TRY_CAST(1 AS \"INTEGER\");"
        configure(sql)
        val all = myFixture.doHighlighting()
        for (word in listOf("\"x\"", "\"INTEGER\"")) {
            assertTrue(word, recolors(all, sql, word).contains(color(SqlTokens.SQL_IDENT_DELIMITED)))
            assertFalse(word, recolors(all, sql, word).contains(color(SqlTokens.SQL_STRING_TOKEN)))
        }
        for (word in listOf("'key'", "'value'")) {
            assertTrue(word, recolors(all, sql, word).contains(color(SqlTokens.SQL_STRING_TOKEN)))
        }
    }

    fun testCommentsInsideMasksAndGenuineComments() {
        val sql = "SELECT * EXCLUDE(a /* inside */) FROM t; /* genuine */ -- unchanged"
        configure(sql)
        val all = myFixture.doHighlighting()
        assertTrue(recolors(all, sql, "/* inside */").contains(color(SqlTokens.SQL_BLOCK_COMMENT)))
        assertTrue(recolors(all, sql, "EXCLUDE").contains(color(SqlTokens.SQL_KEYWORD_TOKEN)))
        assertTrue(all.none { it.forcedTextAttributesKey != null && it.startOffset >= sql.indexOf("/* genuine */") })
    }

    fun testCollapsedTypesUseIdentifierColors() {
        val sql = "CREATE TABLE t (a STRUCT(field_name INTEGER));"
        configure(sql)
        val all = myFixture.doHighlighting()
        assertFalse(color(SqlTokens.SQL_KEYWORD_TOKEN) == color(SqlTokens.SQL_IDENT))
        assertTrue(recolors(all, sql, "field_name").contains(color(SqlTokens.SQL_IDENT)))
        assertTrue(recolors(all, sql, "STRUCT").contains(color(SqlTokens.SQL_KEYWORD_TOKEN)))
    }

    fun testKeywordOverlayUsesCapturedKeywords() {
        configure("SELECT 1;")
        val first = DuckdbSyntaxHighlighterFactory().getSyntaxHighlighter(project, myFixture.file.virtualFile)
        val second = DuckdbSyntaxHighlighterFactory().getSyntaxHighlighter(project, myFixture.file.virtualFile)
        val sql = "UNPIVOT ordinary_name"
        assertTrue("UNPIVOT" in DUCKDB_OVERLAY_KEYWORDS)
        assertSame(SqlTokens.SQL_IDENT, PgLexer().lexAll(sql).first().type)
        val tokens = first.highlightingLexer.lexAll(sql)
        val keyword = tokens.first()
        assertNotSame(SqlTokens.SQL_IDENT, keyword.type)
        assertSame(keyword.type, second.highlightingLexer.lexAll(sql).first().type)
        assertEquals(color(SqlTokens.SQL_KEYWORD_TOKEN), first.getTokenHighlights(keyword.type).last())
        assertSame(SqlTokens.SQL_IDENT, tokens.last().type)
    }

    fun testUnreservedNamesKeepIdentifierColoring() {
        val sql = "SELECT sample, macro, install, source, label FROM t"
        configure(sql)
        val highlighter = DuckdbSyntaxHighlighterFactory().getSyntaxHighlighter(project, myFixture.file.virtualFile)
        val tokens = highlighter.highlightingLexer.lexAll(sql)
        for (word in listOf("sample", "macro", "install")) {
            assertTrue(word.uppercase() in DUCKDB_KEYWORDS)
            assertFalse(word.uppercase() in DUCKDB_OVERLAY_KEYWORDS)
            val token = tokens.single { sql.substring(it.start, it.end) == word }
            assertSame(SqlTokens.SQL_IDENT, token.type)
            val identifierColor = highlighter.getTokenHighlights(token.type).lastOrNull() ?: HighlighterColors.TEXT
            assertEquals(color(SqlTokens.SQL_IDENT), identifierColor)
            assertFalse(color(SqlTokens.SQL_KEYWORD_TOKEN) == identifierColor)
        }
        val plain = object : SqlSyntaxHighlighterFactory.Base(DuckdbSqlDialect) {}
            .getSyntaxHighlighter(project, myFixture.file.virtualFile)
        val plainTokens = plain.highlightingLexer.lexAll(sql)
        for (word in listOf("source", "label")) {
            val expected = plainTokens.single { sql.substring(it.start, it.end) == word }
            val actual = tokens.single { sql.substring(it.start, it.end) == word }
            assertSame(expected.type, actual.type)
            assertTrue(plain.getTokenHighlights(expected.type).contentEquals(highlighter.getTokenHighlights(actual.type)))
        }
    }

    fun testEnterSplitsQuotedStrings() {
        configure("SELECT 'ab<caret>cd';")
        myFixture.type('\n')
        myFixture.checkResult("SELECT 'ab' ||\n       '<caret>cd';")
    }

    fun testEnterDoesNotSplitCollapsedSpans() {
        configure("SELECT {'x': <caret>1};")
        myFixture.type('\n')
        assertFalse(myFixture.editor.document.text.contains("||"))
        assertTrue(myFixture.editor.document.text.contains("{'x':"))
        assertTrue(myFixture.editor.document.text.endsWith("1};"))
    }

    fun testPlatformCommenterRoundTrip() {
        configure("<caret>SELECT 1;")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("-- SELECT 1;", myFixture.editor.document.text)
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("SELECT 1;", myFixture.editor.document.text)
    }

    fun testSubstrateErrorsOnlyHiddenForDuckdb() {
        val factory = PsiFileFactory.getInstance(project)
        val duckdb = factory.createFileFromText("duck.sql", DuckdbSqlDialect, "SELECT +;")
        val postgres = factory.createFileFromText("pg.sql", PgDialect.INSTANCE, "SELECT +;")
        val duckError = PsiTreeUtil.findChildrenOfType(duckdb, PsiErrorElement::class.java).first()
        val pgError = PsiTreeUtil.findChildrenOfType(postgres, PsiErrorElement::class.java).first()
        val filter = DuckdbHighlightErrorFilter()
        assertFalse(filter.shouldHighlightErrorElement(duckError))
        assertTrue(filter.shouldHighlightErrorElement(pgError))
    }
}
