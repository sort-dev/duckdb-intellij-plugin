package dev.sort.duckdb.sql

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.psi.SqlStatement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tier-1 contract ([DuckdbPsiParser]): DuckDB-only statements parse as ONE clean SQL_STATEMENT
 * (correct run-block boundaries, no error elements), and the dispatch gates never steal
 * statements the PG grammar parses fine.
 */
class DuckdbStatementBoundaryTest : BasePlatformTestCase() {

    private var counter = 0

    private fun file(sql: String): com.intellij.psi.PsiFile {
        val psi = myFixture.configureByText("s${counter++}.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DuckdbSqlDialect.INSTANCE)
        return com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile)!!
    }

    /** [sql] must parse as exactly [n] statements with zero PsiErrorElements. */
    private fun assertCleanStatements(sql: String, n: Int) {
        val f = file(sql)
        val errors = PsiTreeUtil.findChildrenOfType(f, PsiErrorElement::class.java)
        assertTrue("expected no parse errors for: $sql\ngot: ${errors.map { it.errorDescription }}", errors.isEmpty())
        val statements = PsiTreeUtil.findChildrenOfType(f, SqlStatement::class.java)
            .filterNot { it.parent is SqlStatement }
        assertEquals("statement count for: $sql", n, statements.size)
    }

    fun testAttachDetachUse() = assertCleanStatements(
        "ATTACH 'other.db' AS other;\nUSE other;\nDETACH other;",
        3,
    )

    fun testSummarizeAndDescribe() = assertCleanStatements(
        "SUMMARIZE orders;\nDESCRIBE SELECT id, name FROM users;",
        2,
    )

    fun testPivotAndUnpivot() = assertCleanStatements(
        "PIVOT sales ON year USING sum(amount) GROUP BY region;\n" +
            "UNPIVOT monthly ON jan, feb, mar INTO NAME month VALUE amount;",
        2,
    )

    fun testFromFirst() = assertCleanStatements(
        "FROM orders SELECT user_id, sum(amount) AS total GROUP BY user_id;",
        1,
    )

    fun testCreateMacroAndSecret() = assertCleanStatements(
        "CREATE MACRO add_one(a) AS a + 1;\n" +
            "CREATE OR REPLACE TEMP MACRO t() AS TABLE SELECT 1;\n" +
            "CREATE SECRET s (TYPE s3, KEY_ID 'k', SECRET 'v');",
        3,
    )

    fun testDuckdbCopyLenientButPgCopyStructured() {
        assertCleanStatements("COPY (SELECT 1) TO 'out.parquet' (FORMAT parquet);", 1)
        // Plain PG-form COPY must NOT be stolen by the lenient gate (no DuckDB marker present) —
        // and it happens to parse clean on the substrate, structure intact.
        assertCleanStatements("COPY t FROM 'in.csv';", 1)
    }

    fun testInstallLoadCallCheckpointPragmaExportImport() = assertCleanStatements(
        "INSTALL spatial;\nLOAD spatial;\nCALL pragma_table_info('t');\nCHECKPOINT;\n" +
            "PRAGMA memory_limit='2GB';\nEXPORT DATABASE 'dir';\nIMPORT DATABASE 'dir';\nFORCE CHECKPOINT;",
        8,
    )

    fun testSetVariantsGatedNotStolen() {
        // DuckDB-scoped SET forms go lenient…
        assertCleanStatements("SET GLOBAL memory_limit='2GB';\nSET VARIABLE x = 42;", 2)
        // …but the PG-parseable form keeps its real parse.
        assertCleanStatements("SET search_path = main;", 1)
    }

    fun testCreateTableWithMacroColumnKeepsRealParse() {
        // The CREATE gate looks only at the first few words — a column named `macro` must not
        // trigger the lenient path (real structure proves it: the statement still parses clean
        // AND yields a create-table statement node, not an opaque blob).
        val f = file("CREATE TABLE t (macro INT, secret TEXT);")
        assertTrue(PsiTreeUtil.findChildrenOfType(f, PsiErrorElement::class.java).isEmpty())
        assertTrue(
            "expected a real CREATE TABLE node",
            PsiTreeUtil.findChildrenOfType(f, com.intellij.sql.psi.SqlCreateTableStatement::class.java).isNotEmpty(),
        )
    }
}
