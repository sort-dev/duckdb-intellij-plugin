package dev.sort.duckdb.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import dev.sort.duckdb.brikk.BrikkDuckdbProfile

internal inline fun <T> runPipeCatching(action: () -> T): Result<T> = recoverEditorOperation(action)

object DuckdbPipes {
    const val PROPERTY = "duckdb.pipes"
    const val MARKER = "|>"

    fun isEnabled(project: Project?): Boolean = project != null && !project.isDisposed && !project.isDefault &&
        !"false".equals(System.getProperty(PROPERTY), true) && project.service<DuckdbPipesSettings>().enabled

    /** Automatic Brikk enablement is scoped to one injected file, never the project service. */
    fun isEnabled(file: PsiFile): Boolean = !"false".equals(System.getProperty(PROPERTY), true) &&
        (BrikkDuckdbProfile.forFile(file) != null || isEnabled(file.project))

    data class Chunk(val text: String, val startOffset: Int, val endOffset: Int,
                     val hasPipeOperator: Boolean, val hasSql: Boolean, val boundaryError: String? = null)

    /** Core owns DuckDB lexical boundaries. All offsets are half-open UTF-16 ranges. */
    fun chunks(text: String): List<Chunk> {
        val snapshot = SqlScriptSnapshot.parse(text, "duckdb")
        val comment = if ("/*" in text) unterminatedComment(text) else null
        return snapshot.statements.map {
            val error = it.boundaryError ?: if (comment != null && comment in it.start until it.end)
                "Unterminated SQL comment; the complete statement is unknown." else null
            Chunk(text.substring(it.start, it.end), it.start, it.end, it.hasPipe, it.hasSql, error)
        }
    }

    /** 0.19.0 accepts an unfinished block comment. Inspect only tokenizer gaps, never
     * literal/identifier tokens, until core reports that lexical failure itself. */
    private fun unterminatedComment(text: String): Int? {
        val lexed = Tokenizer(TokenizerConfigs.forName("duckdb")).tokenizeForEditor(text)
        if (lexed.error != null) return null // Core already refuses the opaque remainder.
        var cursor = 0
        for ((index, end) in (lexed.tokens.map { it.start } + text.length).withIndex()) {
            var i = cursor
            while (i < end) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                when {
                    text.startsWith("--", i) -> i = text.indexOf('\n', i + 2).takeIf { it in i until end } ?: end
                    text.startsWith("/*", i) -> {
                        val start = i
                        var depth = 1
                        i += 2
                        while (i < end && depth > 0) {
                            com.intellij.openapi.progress.ProgressManager.checkCanceled()
                            when {
                                text.startsWith("/*", i) -> { depth++; i += 2 }
                                text.startsWith("*/", i) -> { depth--; i += 2 }
                                else -> i++
                            }
                        }
                        if (depth > 0) return start
                    }
                    else -> i++
                }
            }
            cursor = lexed.tokens.getOrNull(index)?.let { it.end + 1 } ?: text.length
        }
        return null
    }

    fun containsPipeOperator(text: String): Boolean = MARKER in text && chunks(text).any { it.hasPipeOperator }
    fun chunkAt(text: String, offset: Int): Chunk? = chunks(text).let { chunks ->
        chunks.firstOrNull { offset >= it.startOffset && offset < it.endOffset }
            ?: chunks.lastOrNull()?.takeIf { offset == it.endOffset }
    }
}
