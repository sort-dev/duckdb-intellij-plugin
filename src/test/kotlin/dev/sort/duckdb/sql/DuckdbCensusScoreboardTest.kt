package dev.sort.duckdb.sql

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The CENSUS scoreboard: parses the upstream-harvested corpus (corpus/census/, produced by
 * `./gradlew harvestCensus` from DuckDB's own test/sql suite) and reports per-family green rates.
 * Informational — no green-lock yet (that arrives as Stage 2 raises the floor); fails only if the
 * committed census is missing. The curated corpus (corpus/duckdb/) keeps the named, locked
 * scoreboard; this one measures the whole language.
 */
class DuckdbCensusScoreboardTest : BasePlatformTestCase() {

    private var counter = 0

    private data class Verdict(val errors: Int, val first: String?, val firstText: String?)

    private fun verdict(sql: String): Verdict {
        val psi = myFixture.configureByText("c${counter++}.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DuckdbSqlDialect.INSTANCE)
        val file = com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile)!!
        val errs = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        val first = errs.firstOrNull()
        val around = first?.let {
            val t = file.text
            val s = (it.textRange.startOffset - 20).coerceAtLeast(0)
            t.substring(s, (it.textRange.startOffset + 20).coerceAtMost(t.length)).replace("\n", "⏎")
        }
        return Verdict(errs.size, first?.errorDescription?.take(60), around)
    }

    fun testCensus() {
        val dir = File(System.getProperty("corpus.dir"), "census")
        val files = dir.listFiles { f -> f.extension == "sql" }?.sortedBy { it.name }.orEmpty()
        assertTrue("census corpus missing — run ./gradlew harvestCensus and commit", files.isNotEmpty())
        var green = 0
        val reds = ArrayList<Pair<String, Verdict>>()
        for (f in files) {
            val v = verdict(f.readText())
            if (v.errors == 0) green++ else reds.add(f.name to v)
        }
        val board = StringBuilder("\n=== DuckDB census (upstream test/sql sample) ===\n")
        board.append("families green: $green/${files.size}\n")
        reds.sortedByDescending { it.second.errors }.forEach { (name, v) ->
            board.append("  red  $name  (${v.errors})  [${v.first}]  «${v.firstText}»\n")
        }
        board.append("===============================================")
        println(board)
    }
}
