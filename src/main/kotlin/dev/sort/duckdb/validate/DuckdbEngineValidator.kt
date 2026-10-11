package dev.sort.duckdb.validate

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

/** Only the native parser runs; unsupported serialization never falls back to binding. */
object DuckdbEngineValidator {

    sealed interface Verdict {
        data object Clean : Verdict
        data object Unavailable : Verdict
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

    val available: Boolean get() = withDrivers { it.defaultDriver != null }

    private inline fun <T> withDrivers(operation: (DuckdbEngineDrivers) -> T): T {
        val application = ApplicationManager.getApplication()
        return if (application == null) DuckdbEngineDrivers().use(operation)
        else operation(application.service<DuckdbEngineDriverService>().drivers)
    }

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

    fun verdicts(statements: List<String>, jarPath: String? = null): List<Verdict> = withDrivers { drivers ->
        verdictsWithDriver(statements, if (jarPath == null) drivers.defaultDriver else drivers.driverForJar(jarPath))
    }

    internal fun verdictsWithDriver(statements: List<String>, engine: Driver?): List<Verdict> {
        ProgressManager.checkCanceled()
        if (engine == null) return statements.map { Verdict.Unavailable }
        val connection = try {
            connect(engine)
        } catch (_: SQLException) {
            null
        } catch (_: LinkageError) {
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

        return Verdict.Unavailable
    }

    private val NEAR_TOKEN = Regex("""at or near "([^"]+)"""")
    private const val MAX_STATEMENT = 100_000
}
