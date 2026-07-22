package dev.sort.duckdb.tools

import dev.brikk.ducklake.slt.RecordKind
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltExpander
import dev.brikk.ducklake.slt.SltRequire
import java.io.File

/**
 * Census harvester: samples upstream DuckDB's own sqllogictest suite (every .test file under
 * test/sql/) into a committed syntax-census corpus.
 *
 * - `statement ok` + `query` records (valid-by-upstream-CI DuckDB SQL) → per-family census files
 *   (family = directory under test/sql), [PER_FAMILY] statements each, deduped, provenance
 *   comments included.
 * - `statement error` records → a negatives bank (JSONL: sql + expectedError + origin) reserved
 *   for the Stage-3 PREPARE-validator tests.
 * - Files gated by `require <extension>` are skipped whole (core-syntax census only, for now);
 *   `__TEST_DIR__` is substituted; records the expander marks unsupported are skipped by
 *   construction.
 *
 * Deterministic: same input tree → same output (stable sort, first-N sampling) so re-harvests
 * diff cleanly. Usage: SltCensusHarvest <duckdbCheckout> <censusOutDir> <negativesOutFile>
 */
fun main(args: Array<String>) {
    val src = File(args[0], "test/sql")
    val outDir = File(args[1])
    val negativesOut = File(args[2])
    require(src.isDirectory) { "not a duckdb checkout (no test/sql): ${args[0]}" }

    val perFamily = LinkedHashMap<String, MutableList<Pair<String, String>>>() // family -> (sql, origin)
    val negatives = ArrayList<Triple<String, String?, String>>() // sql, expectedError, origin
    var files = 0
    var skippedRequire = 0
    var parseProblems = 0

    src.walkTopDown().filter { it.isFile && it.extension == "test" }.sortedBy { it.path }.forEach { f ->
        files++
        val rel = f.relativeTo(src).path
        val parsed = runCatching { SltParser.parse(rel, f.readText()) }.getOrElse { parseProblems++; return@forEach }
        if (parsed.records.any { it is SltRequire }) { skippedRequire++; return@forEach }
        val records = runCatching {
            SltExpander.expand(parsed, "duckdb", mapOf("__TEST_DIR__" to "/tmp/duckdb_test"))
        }.getOrElse { parseProblems++; return@forEach }
        val family = rel.substringBeforeLast('/', "misc").replace('/', '-')
        for (r in records) {
            val sql = r.sql.trim().removeSuffix(";").plus(";")
            if (sql.length < 8 || sql.length > 2000) continue
            // upstream template/truncation artifacts: unexpanded <type> placeholders, unexpanded
            // ${var} refs (concurrentloop vars the expander can't know), dangling commas
            if (Regex("""<\w+>""").containsMatchIn(sql)) continue
            if (sql.contains("\${")) continue
            if (sql.trimEnd(';').trimEnd().endsWith(",")) continue
            when (r.kind) {
                RecordKind.OK, RecordKind.QUERY -> {
                    val bucket = perFamily.getOrPut(family) { ArrayList() }
                    if (bucket.size < PER_FAMILY * OVERSAMPLE && bucket.none { it.first == sql }) {
                        bucket.add(sql to "${r.file}:${r.line}")
                    }
                }
                RecordKind.ERROR -> {
                    val isParser = r.expectedError?.contains("Parser Error") == true
                    val quotaLeft =
                        if (isParser) negatives.count { it.second?.contains("Parser Error") == true } < PARSER_NEGATIVES
                        else negatives.count { it.second?.contains("Parser Error") != true } < OTHER_NEGATIVES
                    if (quotaLeft) negatives.add(Triple(sql, r.expectedError, "${r.file}:${r.line}"))
                }
            }
        }
    }

    outDir.deleteRecursively()
    outDir.mkdirs()
    var statements = 0
    for ((family, bucket) in perFamily) {
        val sample = bucket.take(PER_FAMILY)
        if (sample.isEmpty()) continue
        statements += sample.size
        File(outDir, "$family.sql").writeText(
            sample.joinToString("\n\n") { (sql, origin) -> "-- from $origin\n$sql" } + "\n",
        )
    }
    negativesOut.parentFile.mkdirs()
    negativesOut.writeText(
        negatives.joinToString("\n") { (sql, err, origin) ->
            """{"sql": ${sql.json()}, "expectedError": ${err?.json() ?: "null"}, "origin": ${origin.json()}}"""
        } + "\n",
    )

    println(
        "census: $files files scanned, $skippedRequire require-gated skipped, $parseProblems parse problems, " +
            "${perFamily.size} families, $statements statements, ${negatives.size} negatives",
    )
}

private fun String.json(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""

private const val PER_FAMILY = 4
private const val OVERSAMPLE = 3 // collect a few extra pre-dedupe, sample deterministically from the front
private const val PARSER_NEGATIVES = 100 // stratified: upstream parser-error expectations
private const val OTHER_NEGATIVES = 150 // binder/catalog/etc — the validator must stay SILENT on these
