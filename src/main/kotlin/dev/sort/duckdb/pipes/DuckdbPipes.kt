package dev.sort.duckdb.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

/** Recover optional editor operations, never cancellation or JVM errors. */
internal inline fun <T> runPipeCatching(action: () -> T): Result<T> = try {
    Result.success(action())
} catch (e: ProcessCanceledException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

object DuckdbPipes {
    const val PROPERTY = "duckdb.pipes"
    const val MARKER = "|>"

    fun isEnabled(project: Project?): Boolean = project != null && !project.isDisposed && !project.isDefault &&
        !"false".equals(System.getProperty(PROPERTY), true) && project.service<DuckdbPipesSettings>().enabled

    data class Chunk(val text: String, val startOffset: Int, val endOffset: Int,
                     val hasPipeOperator: Boolean, val hasSql: Boolean, val boundaryError: String? = null)

    /** DuckDB lexical boundaries, independent of PSI masks and project enablement. UTF-16 offsets. */
    fun chunks(text: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        var start = 0
        var i = 0
        var pipe = false
        var sql = false
        var error: String? = null
        fun flush(end: Int) {
            if (text.substring(start, end).isNotBlank())
                out.add(Chunk(text.substring(start, end), start, end, pipe, sql, error))
            start = end
            pipe = false
            sql = false
        }
        while (i < text.length) {
            ProgressManager.checkCanceled()
            when {
                text.startsWith("--", i) -> {
                    i = text.indexOf('\n', i + 2).takeIf { it >= 0 } ?: text.length
                }
                text.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < text.length && depth > 0) {
                        when {
                            text.startsWith("/*", i) -> { depth++; i += 2 }
                            text.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                    if (depth > 0) error = "Unterminated SQL comment; the complete statement is unknown."
                }
                text[i] == '\'' || text[i] == '"' -> {
                    sql = true
                    val quote = text[i++]
                    // Backslash escaping is specific to E'...' strings; regular DuckDB strings
                    // use doubled quotes. Never interpret a backslash as an escape in identifiers.
                    val escaped = quote == '\'' && i >= 2 && text[i - 2] in "eE" &&
                        (i < 3 || !text[i - 3].isLetterOrDigit() && text[i - 3] != '_')
                    var closed = false
                    while (i < text.length) {
                        if (escaped && text[i] == '\\') { i = (i + 2).coerceAtMost(text.length); continue }
                        if (text[i++] == quote) {
                            if (i < text.length && text[i] == quote) i++ else { closed = true; break }
                        }
                    }
                    if (!closed) error = "Unterminated SQL string or quoted identifier; the complete statement is unknown."
                }
                text[i] == '$' && (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] !in "_$" && text[i - 1].code < 128) &&
                    dollarTag.find(text, i)?.range?.first == i -> {
                    sql = true
                    val tag = dollarTag.find(text, i)!!.value
                    val end = text.indexOf(tag, i + tag.length)
                    if (end < 0) {
                        error = "Unterminated dollar-quoted string; the complete statement is unknown."
                        i = text.length
                    } else i = end + tag.length
                }
                text[i] == ';' -> { i++; flush(i) }
                else -> {
                    if (!text[i].isWhitespace()) sql = true
                    if (text.startsWith(MARKER, i)) pipe = true
                    i++
                }
            }
        }
        flush(text.length)
        return out
    }

    // PostgreSQL-derived dollar tags accept UTF-8 high bytes too, not just ASCII identifiers.
    private val dollarTag = Regex("\\$(?:[A-Za-z_\\x{80}-\\x{10FFFF}][A-Za-z_0-9\\x{80}-\\x{10FFFF}]*)?\\$")
    fun containsPipeOperator(text: String): Boolean = MARKER in text && chunks(text).any { it.hasPipeOperator }
    fun chunkAt(text: String, offset: Int): Chunk? = chunks(text).let { chunks ->
        chunks.firstOrNull { offset >= it.startOffset && offset < it.endOffset }
            ?: chunks.lastOrNull()?.takeIf { offset == it.endOffset }
    }
}
