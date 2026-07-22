package dev.sort.duckdb.tools

import java.io.File
import java.sql.DriverManager

/**
 * Bundled-catalog harvester (PLAN.md Stage 4, "not connected" source): asks an in-process DuckDB
 * for its own inventories and writes them as plugin resources —
 *  - functions.tsv: name<TAB>kind (kind = scalar|aggregate|table|macro|table_macro|pragma),
 *    deduped, name-sorted (DuckDB self-describes; zero hand maintenance)
 *  - keywords.txt: duckdb_keywords() names, sorted
 * Deterministic output; re-run on every duckdb_jdbc bump and commit the diff.
 */
fun main(args: Array<String>) {
    val outDir = File(args[0]).apply { mkdirs() }
    DriverManager.getConnection("jdbc:duckdb:").use { c ->
        val fns = sortedMapOf<String, String>()
        c.createStatement().executeQuery(
            // NB: internal=true MEANS built-in in duckdb_functions() — no filter, we want them all.
            "SELECT DISTINCT function_name, function_type FROM duckdb_functions() ORDER BY 1, 2",
        ).use { rs ->
            while (rs.next()) fns.putIfAbsent(rs.getString(1), rs.getString(2))
        }
        File(outDir, "functions.tsv").writeText(
            fns.entries.joinToString("\n") { (n, k) -> "$n\t$k" } + "\n",
        )
        val kws = ArrayList<String>()
        c.createStatement().executeQuery("SELECT keyword_name FROM duckdb_keywords() ORDER BY 1").use { rs ->
            while (rs.next()) kws.add(rs.getString(1))
        }
        File(outDir, "keywords.txt").writeText(kws.joinToString("\n") + "\n")
        println("catalog: ${fns.size} functions, ${kws.size} keywords -> $outDir")
    }
}
