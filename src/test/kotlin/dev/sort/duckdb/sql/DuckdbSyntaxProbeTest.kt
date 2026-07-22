package dev.sort.duckdb.sql

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The substrate SCOREBOARD: parse every corpus file with the PG-based dialect and report, per
 * file, whether it parses clean. This test never fails on red rows — it fails only if the
 * BASELINE (files known green) regresses, so the corpus can honestly contain everything DuckDB
 * accepts while the dialect grows into it (the doris-intellij golden-corpus philosophy, seed
 * edition). The printed scoreboard is the evidence base for lenient-parse/masking priorities.
 */
class DuckdbSyntaxProbeTest : BasePlatformTestCase() {

    private var counter = 0

    private fun errorsIn(sql: String): List<String> {
        val psi = myFixture.configureByText("p${counter++}.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DuckdbSqlDialect.INSTANCE)
        val file = com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile)!!
        return PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.errorDescription }
    }

    fun testCorpusScoreboard() {
        val dir = File(System.getProperty("corpus.dir"), "duckdb")
        val files = dir.listFiles { f -> f.extension == "sql" }?.sortedBy { it.name }.orEmpty()
        assertTrue("corpus dir must contain probe files: $dir", files.isNotEmpty())
        val red = ArrayList<String>()
        val board = StringBuilder("\n=== DuckDB syntax scoreboard (PG substrate) ===\n")
        for (f in files) {
            val errs = errorsIn(f.readText())
            if (errs.isEmpty()) {
                board.append("  GREEN  ${f.name}\n")
            } else {
                red.add(f.name)
                board.append("  red    ${f.name}  (${errs.size} errors; first: ${errs.first().take(80)})\n")
            }
        }
        board.append("=== ${files.size - red.size}/${files.size} green ===")
        println(board)

        // Baseline contract: known-green files (PG-shared syntax + Tier-1 lenient-boundary wins)
        // must stay green. Extend this list every time a family flips — never shrink it.
        val greenLocked = setOf(
            "00-", "01-", // PG-shared baselines
            "16-", "19-", "21-", "23-", "26-", // PG-shared: read_*, json ops, slicing, VALUES/RETURNING, recursive CTE
            "13-", "14-", "15-", "17-", "24-", // Tier-1 lenient boundaries
            "10-", "11-", "12-", "20-", "22-", "27-", "28-", "29-", // Stage-2 lexer bridge (EXCLUDE, QUALIFY, BY ALL, struct/MAP, lambdas, SAMPLE, TRY_CAST)
            // still red by design: 18- (suffix-form UNPIVOT), 25- (QUALIFY after a WINDOW clause)
        )
        val mustBeGreen = files.map { it.name }.filter { f -> greenLocked.any { f.startsWith(it) } }
        val regressed = mustBeGreen.filter { it in red }
        assertTrue("green-locked corpus files regressed: $regressed", regressed.isEmpty())
    }
}
