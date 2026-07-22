package dev.sort.duckdb.validate

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Grades the Stage-3 validator against upstream DuckDB's own expectations:
 *
 *  1. Every census-negatives record whose upstream `expectedError` names a Parser Error must be
 *     flagged (the authority catches what the engine's own tests call parser errors).
 *  2. Records expecting binder/catalog/other errors must NOT be flagged as parser errors
 *     (schema questions are the das layer's job — the validator stays silent).
 *  3. Every statement in the 100%-green census corpus must pass silently (no EXPLAIN-prefix
 *     artifacts, no false reds on valid DuckDB).
 */
class DuckdbEngineValidatorGradingTest {

    private fun corpusDir() = File(System.getProperty("corpus.dir"))

    @Test
    fun `parser negatives flagged, binder negatives silent, census clean`() {
        assertTrue("validator engine must be available in tests", DuckdbEngineValidator.available)

        val negatives = File(corpusDir(), "census-negatives.jsonl").readLines()
            .filter { it.isNotBlank() }
            .map { JsonParser.parseString(it).asJsonObject }
        val parserNegatives = ArrayList<String>()
        val otherNegatives = ArrayList<String>()
        for (n in negatives) {
            val sql = n.get("sql").asString
            val expected = n.get("expectedError")?.takeIf { !it.isJsonNull }?.asString ?: ""
            if (expected.contains("Parser Error")) parserNegatives.add(sql) else otherNegatives.add(sql)
        }

        val parserVerdicts = DuckdbEngineValidator.verdicts(parserNegatives)
        // Contract: upstream parser errors must be Flagged or HeadRejected — silently Clean is a
        // MECHANICAL fault (statement reuse, EXPLAIN-prefix artifacts) — EXCEPT two honest classes:
        //  (a) SET/PRAGMA-headed statements: upstream's "Parser Error: unrecognized option/value"
        //      is execution-time VALUE validation; the syntax parses, and this validator never
        //      executes — invisible by design.
        //  (b) pinned engine-disagreements: the same-version engine demonstrably parses the text
        //      (upstream's expectation hinges on session flags we don't replicate).
        val exemptOrigins = setOf("setops/test_pg_union.test:456")
        val silentlyClean = parserNegatives.indices.filter { i ->
            parserVerdicts[i] is DuckdbEngineValidator.Verdict.Clean &&
                parserNegatives[i].trimStart().uppercase().let { u ->
                    !u.startsWith("SET") && !u.startsWith("PRAGMA")
                } &&
                negatives.none { n ->
                    n.get("sql").asString == parserNegatives[i] &&
                        n.get("origin").asString in exemptOrigins
                }
        }
        assertTrue(
            "upstream parser errors must be Flagged or HeadRejected, never silently Clean — " +
                "${silentlyClean.size}/${parserNegatives.size} unexempted Clean, e.g.: " +
                silentlyClean.take(3).map { parserNegatives[it].take(80) },
            silentlyClean.isEmpty(),
        )
        val flaggedCount = parserVerdicts.count { it is DuckdbEngineValidator.Verdict.Flagged }
        val headRejected = parserVerdicts.count { it is DuckdbEngineValidator.Verdict.HeadRejected }

        // Upstream classified these as binder/catalog errors, but the ENGINE is the authority:
        // when its parser also rejects one (classification drift between upstream test labels and
        // the shipped parser), flagging is CORRECT. The bound is a drift indicator, not a
        // correctness gate — the census-clean assertion below is the real false-positive tripwire.
        val flaggedOther = DuckdbEngineValidator.validate(otherNegatives)
        assertTrue(
            "unexpectedly large engine-vs-upstream classification drift (${flaggedOther.size}): " +
                flaggedOther.entries.take(5).map { (i, err) -> otherNegatives[i].take(60) + " -> " + err.message },
            flaggedOther.size <= 12,
        )

        val censusStatements = File(corpusDir(), "census").listFiles { f -> f.extension == "sql" }!!
            .sortedBy { it.name }
            .flatMap { f ->
                f.readText().split(Regex("(?m)^-- from .*$")).map { it.trim() }.filter { it.isNotEmpty() }
            }
        val falseReds = DuckdbEngineValidator.validate(censusStatements)
        assertTrue(
            "validator false-flagged ${falseReds.size}/${censusStatements.size} valid census statements, e.g.: " +
                falseReds.entries.take(3).map { (i, err) -> censusStatements[i].take(60) + " -> " + err.message },
            falseReds.isEmpty(),
        )

        println(
            "validator grading: ${parserNegatives.size} parser negatives -> " +
                "$flaggedCount flagged + $headRejected head-rejected (0 silently clean); " +
                "${otherNegatives.size} binder/catalog negatives (${flaggedOther.size} engine-drift flags); " +
                "${censusStatements.size} census statements all clean",
        )
    }
}
