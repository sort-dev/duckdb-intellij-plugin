package dev.sort.duckdb.validate

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStatement
import dev.sort.duckdb.sql.DuckdbSqlDialect
import dev.sort.duckdb.pipes.DuckdbPipes
import dev.sort.duckdb.pipes.DuckdbPipesEngine
import dev.sort.duckdb.pipes.runPipeCatching
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.shape.ShapeError

/**
 * Editor surface for [DuckdbEngineValidator]: statement text goes to a real in-memory DuckDB,
 * engine-confirmed PARSER errors come back as squiggles carrying the engine's own message —
 * version-exact, zero grammar drift (the doris fe-sql-parser annotator role). Files stay silent
 * when no engine is reachable; binder/catalog opinions are never surfaced (the das layer owns
 * resolution).
 */
class DuckdbErrorAnnotator : ExternalAnnotator<DuckdbErrorAnnotator.Info, DuckdbErrorAnnotator.Result>() {

    data class Info(val statements: List<Pair<TextRange, String>>, val engineJar: String?,
                    val pipesEnabled: Boolean = false, val pipes: List<DuckdbPipes.Chunk> = emptyList(),
                    val brikk: BrikkDuckdbProfile? = null, val brikkText: String? = null,
                    val brikkSlots: Set<String> = emptySet())
    data class Result(val errors: List<Pair<TextRange, DuckdbEngineValidator.EngineError>>,
                      val pipesEnabled: Boolean = false,
                      val pipeErrors: List<Pair<TextRange, DuckdbPipesEngine.Transpile.Err>> = emptyList())

    // The default 3-arg variant bails when the file has PSI errors — but broken-looking PSI is
    // exactly when the engine's opinion matters most (the substrate can't parse everything).
    override fun collectInformation(file: PsiFile, editor: com.intellij.openapi.editor.Editor, hasErrors: Boolean): Info? =
        collectInformation(file)

    override fun collectInformation(file: PsiFile): Info? {
        if (!file.language.isKindOf(DuckdbSqlDialect.INSTANCE)) return null
        val profile = BrikkDuckdbProfile.forFile(file)
        if (profile != null) return Info(emptyList(), null, DuckdbPipes.isEnabled(file),
            brikk = profile, brikkText = com.intellij.lang.injection.InjectedLanguageManager.getInstance(file.project).getUnescapedText(file),
            brikkSlots = dev.sort.duckdb.brikk.BrikkSqlEditorSupport.contracts(file).keys)
        // engine sources: driver-classpath / downloaded drivers (locator) or sysprop/test classpath
        val engineJar = DuckdbEngineLocator.engineJarFor(file)?.toString()
        val enabled = DuckdbPipes.isEnabled(file)
        val pipes = if (enabled) DuckdbPipes.chunks(file.text).filter { it.hasPipeOperator } else emptyList()
        if (engineJar == null && !DuckdbEngineValidator.available && pipes.isEmpty()) return null
        val statements = PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java)
            .filterNot { it.parent is SqlStatement }
            .map { it.textRange to it.text }
            .filterNot { (range, _) -> pipes.any { range.startOffset >= it.startOffset && range.startOffset < it.endOffset } }
        return if (statements.isEmpty() && pipes.isEmpty()) null else Info(statements, engineJar, enabled, pipes)
    }

    override fun doAnnotate(collectedInfo: Info?): Result? {
        val info = collectedInfo ?: return null
        if (info.brikk != null) {
            val text = info.brikkText.orEmpty()
            val errors = mutableListOf<Pair<TextRange, DuckdbPipesEngine.Transpile.Err>>()
            val range = TextRange(0, text.length)
            // Interpolation refusals belong to authored Kotlin ranges, handled by the optional
            // host annotator. Never underline a guessed synthetic bind as if it were SQL text.
            if (info.brikk.issues.isEmpty()) {
                try { dev.sort.duckdb.brikk.BrikkSqlScopes.parse(text, info.brikkSlots) } catch (e: ParseError) {
                    val first = e.errors.firstOrNull()
                    errors.add(range to DuckdbPipesEngine.Transpile.Err(first?.line, first?.col, first?.description ?: "Invalid Brikk SQL"))
                } catch (e: TokenError) {
                    errors.add(range to DuckdbPipesEngine.Transpile.Err(null, null, e.message ?: "Invalid SQL token"))
                } catch (e: ShapeError) {
                    errors.add(range to DuckdbPipesEngine.Transpile.Err(null, null, e.message ?: "Brikk SQL requires exactly one statement"))
                }
            }
            return Result(emptyList(), info.pipesEnabled, errors)
        }
        val flagged = DuckdbEngineValidator.validate(info.statements.map { it.second }, info.engineJar)
        val pipeErrors = info.pipes.mapNotNull { chunk ->
            val result = runPipeCatching { DuckdbPipesEngine.transpile(chunk.text) }.getOrElse {
                DuckdbPipesEngine.Transpile.Err(null, null, "PIPE translation failed: ${it.message}")
            }
            val error = when (result) {
                is DuckdbPipesEngine.Transpile.Err -> result
                is DuckdbPipesEngine.Transpile.Ok -> result.executionError
                else -> null
            } ?: return@mapNotNull null
            TextRange(chunk.startOffset, chunk.endOffset) to error
        }
        return Result(flagged.map { (i, err) -> info.statements[i].first to err }, info.pipesEnabled, pipeErrors)
    }

    override fun apply(file: PsiFile, annotationResult: Result?, holder: AnnotationHolder) {
        val result = annotationResult ?: return
        if (result.pipesEnabled != DuckdbPipes.isEnabled(file)) return
        val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for ((stmtRange, err) in result.errors) {
            val range = errorRange(doc, stmtRange, err)
            holder.newAnnotation(HighlightSeverity.ERROR, "DuckDB: ${err.message}")
                .range(range)
                .create()
        }
        for ((stmtRange, err) in result.pipeErrors) {
            val brikk = BrikkDuckdbProfile.forFile(file) != null
            val text = if (brikk) com.intellij.lang.injection.InjectedLanguageManager.getInstance(file.project).getUnescapedText(file)
                else doc.charsSequence.subSequence(stmtRange.startOffset, stmtRange.endOffset).toString()
            var offset = text.length - text.trimStart().length
            repeat(((err.line ?: 1) - 1).coerceAtLeast(0)) {
                offset = (text.indexOf('\n', offset).takeIf { it >= 0 }?.plus(1) ?: offset)
            }
            offset = (offset + ((err.col ?: 1) - 1).coerceAtLeast(0)).coerceIn(0, (text.length - 1).coerceAtLeast(0))
            val start = stmtRange.startOffset + offset
            if (brikk) {
                val manager = com.intellij.lang.injection.InjectedLanguageManager.getInstance(file.project)
                val mapped = manager.mapUnescapedOffsetToInjected(file, start)
                val candidate = TextRange(mapped, (mapped + 1).coerceAtMost(file.textLength))
                val range = manager.intersectWithAllEditableFragments(file, candidate).firstOrNull()
                    ?: if (err.line == null) manager.intersectWithAllEditableFragments(file, TextRange(0, file.textLength))
                        .firstOrNull()?.let { TextRange(it.startOffset, (it.startOffset + 1).coerceAtMost(it.endOffset)) }
                    else null
                if (range == null) continue
                holder.newAnnotation(HighlightSeverity.ERROR, "DuckDB Brikk: ${err.message}")
                    .range(range).create()
                continue
            }
            holder.newAnnotation(HighlightSeverity.ERROR, "DuckDB PIPE: ${err.message}")
                .range(TextRange(start, (start + 2).coerceAtMost(stmtRange.endOffset))).create()
        }
    }

    /** Map the engine's statement-relative line + near-token onto a document range. */
    private fun errorRange(doc: Document, stmt: TextRange, err: DuckdbEngineValidator.EngineError): TextRange {
        val stmtStartLine = doc.getLineNumber(stmt.startOffset)
        val targetLine = (stmtStartLine + err.line - 1).coerceAtMost(doc.lineCount - 1)
        val lineStart = doc.getLineStartOffset(targetLine).coerceAtLeast(stmt.startOffset)
        val lineEnd = doc.getLineEndOffset(targetLine).coerceAtMost(stmt.endOffset)
        if (lineEnd <= lineStart) return stmt
        val near = err.nearToken
        if (!near.isNullOrBlank()) {
            val lineText = doc.charsSequence.subSequence(lineStart, lineEnd).toString()
            val at = lineText.indexOf(near)
            if (at >= 0) return TextRange(lineStart + at, lineStart + at + near.length)
        }
        return TextRange(lineStart, lineEnd)
    }
}
