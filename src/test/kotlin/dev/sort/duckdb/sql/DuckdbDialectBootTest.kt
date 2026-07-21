package dev.sort.duckdb.sql

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.psi.SqlStatement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.DuckdbDbms

/** The seed contract: the dialect registers, maps, and parses friendly SQL with zero errors. */
class DuckdbDialectBootTest : BasePlatformTestCase() {

    private var counter = 0

    private fun parse(sql: String): com.intellij.psi.PsiFile {
        val psi = myFixture.configureByText("b${counter++}.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DuckdbSqlDialect.INSTANCE)
        return com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile)!!
    }

    fun testDialectRegistersWithOwnDbms() {
        assertEquals("DUCKDB_BRIKK", DuckdbDbms.DUCKDB_BRIKK.name)
        assertEquals(DuckdbDbms.DUCKDB_BRIKK, DuckdbSqlDialect.INSTANCE.dbms)
        assertEquals("DuckDBBrikk", DuckdbSqlDialect.INSTANCE.id)
    }

    fun testFriendlySqlParsesClean() {
        val file = parse(
            """
            SELECT id, name, count(*) AS n
            FROM main.users
            WHERE created_at >= '2026-01-01'::date
            GROUP BY id, name
            HAVING count(*) > 1
            ORDER BY n DESC
            LIMIT 10;
            """.trimIndent(),
        )
        assertTrue(file.language.isKindOf(DuckdbSqlDialect.INSTANCE))
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue("friendly SQL must parse clean, got: ${errors.map { it.errorDescription }}", errors.isEmpty())
        assertEquals(1, PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java).map { it.parent }.toSet().size)
    }

    fun testWindowFunctionsAndCtesParse() {
        val file = parse(
            """
            WITH ranked AS (
                SELECT user_id, amount,
                       row_number() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn
                FROM orders
            )
            SELECT * FROM ranked WHERE rn = 1;
            """.trimIndent(),
        )
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue("window+CTE must parse clean on the PG base, got: ${errors.map { it.errorDescription }}", errors.isEmpty())
    }

    fun testPgFlavorSyntaxParses() {
        // The substrate bet: DuckDB inherits PG-isms — :: casts and $$-quoting must just work.
        val file = parse("SELECT 1::bigint, ${'$'}${'$'}raw text${'$'}${'$'} AS s;")
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue("PG-isms must parse clean, got: ${errors.map { it.errorDescription }}", errors.isEmpty())
    }
}
