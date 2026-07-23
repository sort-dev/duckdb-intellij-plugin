package dev.sort.duckdb.validate

import java.sql.Connection
import java.sql.SQLException

/**
 * Stage 3 — the engine as the syntax authority (PLAN.md).
 *
 * Validates DuckDB SQL by asking a real DuckDB: each statement is wrapped in `EXPLAIN` and run
 * against a throwaway in-memory instance — EXPLAIN parses and binds but never executes, so there
 * are no side effects (no files written, nothing attached, nothing mutated). Only errors the
 * engine labels **`Parser Error`** are surfaced: `Binder Error` / `Catalog Error` need schema
 * knowledge the validator deliberately doesn't have (the editor's das layer owns resolution), and
 * statement forms EXPLAIN itself rejects simply go unvalidated. This is the fe-sql-parser role
 * from doris-intellij — except the "grammar" is the user's actual engine version, forever exact.
 *
 * Engine acquisition (in order):
 *  1. `-Dduckdb.validator.jar=<path>` — explicit jar (isolated classloader).
 *  2. `org.duckdb.DuckDBDriver` on the current classpath (tests; environments that ship it).
 *  3. (Stage-3 follow-up) the file's data-source driver classpath.
 * No engine → [available] is false and callers stay silent; the plugin never bundles DuckDB.
 */
object DuckdbEngineValidator {

    sealed interface Verdict {
        /** Engine parsed it fine (or only binder/catalog complaints — not ours to judge). */
        object Clean : Verdict
        /** EXPLAIN can't see this statement type; the engine's real opinion is unknowable here. */
        object HeadRejected : Verdict
        data class Flagged(val error: EngineError) : Verdict
    }

    data class EngineError(
        /** 1-based line within the VALIDATED statement text. */
        val line: Int,
        /** The token the engine pointed at ("syntax error at or near \"X\""), if any. */
        val nearToken: String?,
        /** Full first line of the engine message, "Parser Error: ..." included. */
        val message: String,
    )

    private val driver: java.sql.Driver? by lazy {
        System.getProperty("duckdb.validator.jar")?.let { jarPath ->
            runCatching {
                val loader = java.net.URLClassLoader(
                    arrayOf(java.io.File(jarPath).toURI().toURL()),
                    ClassLoader.getPlatformClassLoader(),
                )
                loader.loadClass("org.duckdb.DuckDBDriver").getDeclaredConstructor().newInstance() as java.sql.Driver
            }.getOrNull()
        } ?: runCatching {
            Class.forName("org.duckdb.DuckDBDriver").getDeclaredConstructor().newInstance() as java.sql.Driver
        }.getOrNull()
    }

    val available: Boolean get() = driver != null

    /** Per-jar driver cache (isolated URLClassLoaders; duckdb extracts its native lib per loader). */
    private val jarDrivers = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.sql.Driver>>()

    /** Load (and cache) the DuckDB driver from an explicit jar — the driver-classpath path. */
    fun driverForJar(jarPath: String): java.sql.Driver? =
        jarDrivers.computeIfAbsent(jarPath) {
            java.util.Optional.ofNullable(
                runCatching {
                    val loader = java.net.URLClassLoader(
                        arrayOf(java.io.File(jarPath).toURI().toURL()),
                        ClassLoader.getPlatformClassLoader(),
                    )
                    loader.loadClass("org.duckdb.DuckDBDriver").getDeclaredConstructor().newInstance() as java.sql.Driver
                }.getOrNull(),
            )
        }.orElse(null)

    /** A fresh in-memory engine per pass: statements can't pollute each other across validations. */
    private fun connect(d: java.sql.Driver?): Connection? =
        runCatching { d?.connect("jdbc:duckdb:", java.util.Properties()) }.getOrNull()

    /**
     * Validate [statements] (raw text, in order). Returns engine-confirmed PARSER errors per
     * statement index. One connection per call; DDL executed by EXPLAIN-binding stays inside the
     * throwaway instance.
     */
    fun validate(statements: List<String>, jarPath: String? = null): Map<Int, EngineError> =
        verdicts(statements, jarPath).withIndex()
            .mapNotNull { (i, v) -> (v as? Verdict.Flagged)?.let { i to it.error } }
            .toMap()

    /** Full three-way verdicts, aligned with [statements] by index. */
    fun verdicts(statements: List<String>, jarPath: String? = null): List<Verdict> {
        val engine = jarPath?.let { driverForJar(it) } ?: driver
        val conn = connect(engine) ?: return statements.map { Verdict.Clean }
        val out = ArrayList<Verdict>(statements.size)
        conn.use { c ->
            for (raw in statements) {
                val sql = raw.trim().removeSuffix(";")
                if (sql.isBlank() || sql.length > MAX_STATEMENT) {
                    out.add(Verdict.Clean)
                    continue
                }
                val words = sql.trimStart().split(Regex("\\s+"), limit = 5)
                val head = words.getOrNull(0)?.uppercase()
                val second = words.getOrNull(1)?.uppercase()
                if (head == "EXPLAIN" && second == "ANALYZE") {
                    // EXPLAIN ANALYZE EXECUTES the statement — never run it from a validator.
                    out.add(Verdict.HeadRejected)
                    continue
                }
                val wrapped = head != "EXPLAIN"
                val toRun = if (wrapped) "EXPLAIN $sql" else sql
                out.add(
                    try {
                        // fresh Statement per SQL: duckdb's JDBC invalidates a Statement after an
                        // error, and later failures surface as non-parser "closed" messages.
                        c.createStatement().use { st -> st.execute(toRun) }
                        Verdict.Clean
                    } catch (e: SQLException) {
                        classify(
                            e,
                            if (wrapped) EXPLAIN_PREFIX_LEN else 0,
                            // payload leading words (statement-form region): EXPLAIN's partial
                            // grammars can reject at any of them (CREATE [PERSISTENT] SECRET ...)
                            (if (wrapped) words else words.drop(1)).take(3).map { it.uppercase() },
                        )
                    },
                )
            }
        }
        return out
    }

    /**
     * Classify an engine exception. Binder/catalog → [Verdict.Clean] (not ours to judge). A
     * parser error whose caret points at the PAYLOAD'S FIRST CHARACTER (right after the
     * "EXPLAIN " prefix) is EXPLAIN's own statement-type rejection — some VALID heads (COMMENT,
     * PREPARE, ...) aren't explainable — so the engine's real opinion is unknowable:
     * [Verdict.HeadRejected], never a false red. Carets deeper in the text are real parser
     * errors, even when the near-token happens to equal the first word (e.g. a nested SELECT).
     */
    private fun classify(e: SQLException, prefixLen: Int, payloadLeadWords: List<String>): Verdict {
        val msg = e.message ?: return Verdict.Clean
        val idx = msg.indexOf(PARSER_MARKER)
        if (idx < 0) return Verdict.Clean
        val fromMarker = msg.substring(idx)
        val firstLine = fromMarker.lineSequence().first().trim()
        val near = NEAR_TOKEN.find(fromMarker)?.groupValues?.get(1)
        val line = LINE_MARKER.find(fromMarker)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        if (prefixLen > 0) {
            // Preferred discriminator: the caret column relative to the shown text (which starts
            // with the "EXPLAIN " prefix) — a caret at the payload's first character means
            // EXPLAIN itself rejected the statement type. Some message shapes carry no caret
            // block; those fall back to "the near-token is the payload's first word".
            val lines = fromMarker.lines()
            val lineIdx = lines.indexOfFirst { LINE_MARKER.containsMatchIn(it) }
            val shownLine = if (lineIdx >= 0) lines[lineIdx] else null
            val caret = if (lineIdx >= 0 && lineIdx + 1 < lines.size) lines[lineIdx + 1].indexOf('^') else -1
            if (caret >= 0 && shownLine != null) {
                val contentStart = shownLine.indexOf(':') + 2 // after "LINE n: "
                if (line == 1 && (caret - contentStart) in 0..prefixLen &&
                    payloadLeadWords.firstOrNull() in KNOWN_STATEMENT_HEADS &&
                    payloadLeadWords.firstOrNull() !in EXPLAINABLE_HEADS
                ) {
                    // caret at the payload's first char = EXPLAIN's statement-type rejection —
                    // but only for KNOWN-VALID non-query heads; a typo'd head carets there too
                    // and must FLAG.
                    return Verdict.HeadRejected
                }
            }
            // Statement-form rejection: a head EXPLAIN only partially supports (CREATE
            // [PERSISTENT] SECRET, ANALYZE t(c), EXPORT ...) errors "near" one of the payload's
            // LEAD words — with or without a caret block (1.2 messages had none; 1.5 carets the
            // offending word). Query-family heads are ALWAYS fully explainable, so for them any
            // parser error is real (SELECT FROM WHERE must flag even though WHERE is word 3).
            if (near != null &&
                payloadLeadWords.firstOrNull() in KNOWN_STATEMENT_HEADS &&
                payloadLeadWords.firstOrNull() !in EXPLAINABLE_HEADS &&
                payloadLeadWords.any { w ->
                    near.equals(w.takeWhile { it.isLetterOrDigit() || it == '_' }, ignoreCase = true)
                }
            ) {
                // ...but only for KNOWN-VALID heads: a typo'd head (SELEC 1) also errors near
                // itself, and that must FLAG — head-rejection is for real statement forms
                // EXPLAIN merely can't take, never for garbage.
                return Verdict.HeadRejected
            }
        }
        return Verdict.Flagged(EngineError(line, near, firstLine))
    }

    private val NEAR_TOKEN = Regex("""at or near "([^"]+)"""")
    private val LINE_MARKER = Regex("""LINE (\d+):""")
    private const val PARSER_MARKER = "Parser Error"
    private const val EXPLAIN_PREFIX_LEN = "EXPLAIN ".length
    private val EXPLAINABLE_HEADS = setOf("SELECT", "WITH", "VALUES", "FROM", "INSERT", "UPDATE", "DELETE")
    private val KNOWN_STATEMENT_HEADS = EXPLAINABLE_HEADS + setOf(
        "COMMENT", "PREPARE", "EXECUTE", "DEALLOCATE", "EXPORT", "IMPORT", "ANALYZE", "VACUUM",
        "CHECKPOINT", "CALL", "INSTALL", "LOAD", "FORCE", "ATTACH", "DETACH", "USE", "BEGIN",
        "COMMIT", "ROLLBACK", "ABORT", "SET", "RESET", "PRAGMA", "CREATE", "ALTER", "DROP",
        "COPY", "TRUNCATE", "SUMMARIZE", "DESCRIBE", "SHOW", "PIVOT", "UNPIVOT", "EXPLAIN",
    )
    private const val MAX_STATEMENT = 100_000
}
