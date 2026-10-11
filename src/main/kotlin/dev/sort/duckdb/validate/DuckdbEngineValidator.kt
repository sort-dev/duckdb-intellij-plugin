package dev.sort.duckdb.validate

import com.intellij.openapi.progress.ProgressManager
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

/** Parse-only validation first, then restricted EXPLAIN binding of structurally safe statements. */
object DuckdbEngineValidator {

    sealed interface Verdict {
        data object Clean : Verdict
        data object HeadRejected : Verdict
        data object Unavailable : Verdict
        data object Unverified : Verdict
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

    internal fun connect(driver: Driver): Connection? = driver.connect("jdbc:duckdb:", Properties().apply {
        setProperty("enable_external_access", "false")
        setProperty("autoinstall_known_extensions", "false")
        setProperty("autoload_known_extensions", "false")
        setProperty("threads", "1")
        setProperty("memory_limit", "256MB")
    })

    /** Returns only engine-confirmed parser errors, aligned with the input statements. */
    fun validate(statements: List<String>, jarPath: String? = null): Map<Int, EngineError> =
        verdicts(statements, jarPath).withIndex()
            .mapNotNull { (i, v) -> (v as? Verdict.Flagged)?.let { i to it.error } }
            .toMap()

    fun verdicts(statements: List<String>, jarPath: String? = null): List<Verdict> =
        verdictsWithDriver(statements, if (jarPath == null) driver else driverForJar(jarPath))

    internal fun verdictsWithDriver(statements: List<String>, engine: Driver?): List<Verdict> {
        ProgressManager.checkCanceled()
        if (engine == null) return statements.map { Verdict.Unavailable }
        val connection = try {
            connect(engine)
        } catch (_: SQLException) {
            null
        } ?: return statements.map { Verdict.Unavailable }
        return connection.use { statements.map { sql -> verdict(sql, it) } }
    }

    internal fun verdict(sql: String, connection: Connection): Verdict {
        ProgressManager.checkCanceled()
        if (sql.isBlank()) return Verdict.Clean
        if (sql.length > MAX_STATEMENT) return Verdict.Unavailable
        val parsed = try {
            val literal = "'" + sql.replace("'", "''") + "'"
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT json_serialize_sql($literal)").use { result ->
                    if (!result.next()) return Verdict.Unavailable
                    Json.parseToJsonElement(result.getString(1)).jsonObject
                }
            }
        } catch (_: SQLException) {
            return Verdict.Unavailable
        }
        ProgressManager.checkCanceled()
        if (parsed["error"]?.jsonPrimitive?.booleanOrNull == false) return Verdict.Clean
        if (parsed["error_type"]?.jsonPrimitive?.contentOrNull == "parser") {
            val message = parsed["error_message"]?.jsonPrimitive?.contentOrNull ?: return Verdict.Unavailable
            val position = parsed["position"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val bytes = sql.toByteArray(Charsets.UTF_8)
            val line = bytes.take(position.coerceIn(0, bytes.size)).count { it == '\n'.code.toByte() } + 1
            return Verdict.Flagged(EngineError(line, NEAR_TOKEN.find(message)?.groupValues?.get(1), "Parser Error: $message"))
        }

        val tokenized = Tokenizer(TokenizerConfigs.forName("duckdb")).tokenizeForEditor(sql)
        if (tokenized.error != null) return Verdict.Unavailable
        val tokens = tokenized.tokens.dropLastWhile { it.tokenType == TokenType.SEMICOLON }
        if (tokens.any { it.tokenType == TokenType.SEMICOLON }) return Verdict.HeadRejected
        val payload = if (tokens.firstOrNull()?.text.equals("EXPLAIN", true)) tokens.drop(1) else tokens
        val head = payload.firstOrNull() ?: return Verdict.HeadRejected
        // Only plain EXPLAIN is accepted. ANALYZE and all option-list spellings are refused.
        if (head.text.uppercase() !in BINDABLE_HEADS)
            return Verdict.HeadRejected
        val text = sql.substring(head.start, payload.last().end + 1)
        return try {
            connection.createStatement().use { it.execute("EXPLAIN $text") }
            ProgressManager.checkCanceled()
            Verdict.Clean
        } catch (e: SQLException) {
            classify(e, EXPLAIN_PREFIX_LEN, payload.take(3).map { it.text.uppercase() })
        }
    }

    /**
     * Classify an engine exception. Binder/catalog errors are unverified. A
     * parser error whose caret points at the PAYLOAD'S FIRST CHARACTER (right after the
     * "EXPLAIN " prefix) is EXPLAIN's own statement-type rejection — some VALID heads (COMMENT,
     * PREPARE, ...) aren't explainable — so the engine's real opinion is unknowable:
     * [Verdict.HeadRejected], never a false red. Carets deeper in the text are real parser
     * errors, even when the near-token happens to equal the first word (e.g. a nested SELECT).
     */
    private fun classify(e: SQLException, prefixLen: Int, payloadLeadWords: List<String>): Verdict {
        val msg = e.message ?: return Verdict.Unverified
        val idx = msg.indexOf(PARSER_MARKER)
        if (idx < 0) return Verdict.Unverified
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
    private val BINDABLE_HEADS = EXPLAINABLE_HEADS + setOf("CREATE", "ALTER", "DROP", "COPY")
    private val KNOWN_STATEMENT_HEADS = EXPLAINABLE_HEADS + setOf(
        "COMMENT", "PREPARE", "EXECUTE", "DEALLOCATE", "EXPORT", "IMPORT", "ANALYZE", "VACUUM",
        "CHECKPOINT", "CALL", "INSTALL", "LOAD", "FORCE", "ATTACH", "DETACH", "USE", "BEGIN",
        "COMMIT", "ROLLBACK", "ABORT", "SET", "RESET", "PRAGMA", "CREATE", "ALTER", "DROP",
        "COPY", "TRUNCATE", "SUMMARIZE", "DESCRIBE", "SHOW", "PIVOT", "UNPIVOT", "EXPLAIN",
    )
    private const val MAX_STATEMENT = 100_000
}
