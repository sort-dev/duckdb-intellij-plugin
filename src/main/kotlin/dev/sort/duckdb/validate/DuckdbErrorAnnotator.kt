package dev.sort.duckdb.validate

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStatement
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.isEditingEnabled
import dev.brikk.house.sql.intellij.pipe.lower
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.verify.VerifyError
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import dev.sort.duckdb.pipes.DuckdbPipeDialect
import dev.sort.duckdb.sql.DuckdbSqlDialect

/**
 * Editor surface for [DuckdbEngineValidator]: statement text goes to a real in-memory DuckDB,
 * engine-confirmed PARSER errors come back as squiggles carrying the engine's own message —
 * version-exact, zero grammar drift (the doris fe-sql-parser annotator role). Files stay silent
 * when no engine is reachable; binder/catalog opinions are never surfaced (the das layer owns
 * resolution).
 */
class DuckdbErrorAnnotator : ExternalAnnotator<DuckdbErrorAnnotator.Info, DuckdbErrorAnnotator.Result>() {

    data class Info(val statements: List<Pair<TextRange, String>>, val engineJar: String?,
                    val pipesEnabled: Boolean = false, val pipes: List<Pair<SqlStatementRange, String>> = emptyList(),
                    val brikk: BrikkDuckdbProfile? = null, val brikkText: String? = null,
                    val brikkSlots: Set<String> = emptySet())
    data class Result(val errors: List<Pair<TextRange, VerifyError>>,
                      val pipesEnabled: Boolean = false,
                      val pipeErrors: List<Pair<TextRange, PipeTranslation.Refused>> = emptyList())

    // The default 3-arg variant bails when the file has PSI errors — but broken-looking PSI is
    // exactly when the engine's opinion matters most (the substrate can't parse everything).
    override fun collectInformation(file: PsiFile, editor: com.intellij.openapi.editor.Editor, hasErrors: Boolean): Info? =
        collectInformation(file)

    override fun collectInformation(file: PsiFile): Info? {
        if (!file.language.isKindOf(DuckdbSqlDialect)) return null
        val profile = BrikkDuckdbProfile.forFile(file)
        if (profile != null) return Info(emptyList(), null, DuckdbPipeDialect.isEditingEnabled(file),
            brikk = profile, brikkText = com.intellij.lang.injection.InjectedLanguageManager.getInstance(file.project).getUnescapedText(file),
            brikkSlots = dev.sort.duckdb.brikk.BrikkSqlEditorSupport.contracts(file).keys)
        // engine sources: driver-classpath / downloaded drivers (locator) or sysprop/test classpath
        val engineJar = DuckdbEngineLocator.engineJarFor(file)?.toString()
        val enabled = DuckdbPipeDialect.isEditingEnabled(file)
        val source = PipeText.of(file)
        val text = source.text
        val pipes = if (enabled) DuckdbPipeDialect.scriptRanges(text).filter { it.hasPipe }
            .map { it to text.substring(it.start, it.end) } else emptyList()
        if (engineJar == null && !DuckdbEngineValidator.available && pipes.isEmpty()) return null
        val statements = PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java)
            .filterNot { it.parent is SqlStatement }
            .map { it.textRange to it.text }
            .filterNot { (range, _) -> pipes.any { (statement, _) -> source.textOffset(range.startOffset) in statement.start until statement.end } }
        return if (statements.isEmpty() && pipes.isEmpty()) null else Info(statements, engineJar, enabled, pipes)
    }

    override fun doAnnotate(collectedInfo: Info?): Result? {
        val info = collectedInfo ?: return null
        if (info.brikk != null) {
            val text = info.brikkText.orEmpty()
            val errors = mutableListOf<Pair<TextRange, PipeTranslation.Refused>>()
            val range = TextRange(0, text.length)
            // Interpolation refusals belong to authored Kotlin ranges, handled by the optional
            // host annotator. Never underline a guessed synthetic bind as if it were SQL text.
            if (info.brikk.issues.isEmpty()) {
                try { dev.sort.duckdb.brikk.BrikkSqlScopes.parse(text, info.brikkSlots) } catch (e: ParseError) {
                    val first = e.errors.firstOrNull()
                    val position = first?.start?.let { SyntacticRange(it, first.end ?: it + 1) }
                    errors.add(range to PipeTranslation.Refused(position,
                        first?.description ?: "Invalid Brikk SQL"))
                } catch (e: TokenError) {
                    errors.add(range to PipeTranslation.Refused(null, e.message ?: "Invalid SQL token"))
                } catch (e: ShapeError) {
                    errors.add(range to PipeTranslation.Refused(null, e.message ?: "Brikk SQL requires exactly one statement"))
                }
            }
            return Result(emptyList(), info.pipesEnabled, errors)
        }
        val flagged = DuckdbEngineValidator.validate(info.statements.map { it.second }, info.engineJar)
        val pipeErrors = info.pipes.mapNotNull { (statement, text) ->
            val result = recoverEditorOperation { DuckdbPipeDialect.lower(text) }.getOrElse {
                PipeTranslation.Refused(null, "PIPE translation failed: ${it.message}")
            }
            val error = when (result) {
                is PipeTranslation.Refused -> result
                is PipeTranslation.Lowered -> result.blocker?.let { PipeTranslation.Refused(null, it) }
                else -> null
            } ?: return@mapNotNull null
            TextRange(statement.start, statement.end) to error
        }
        return Result(flagged.map { (i, err) -> info.statements[i].first to err }, info.pipesEnabled, pipeErrors)
    }

    override fun apply(file: PsiFile, annotationResult: Result?, holder: AnnotationHolder) {
        val result = annotationResult ?: return
        if (result.pipesEnabled != DuckdbPipeDialect.isEditingEnabled(file)) return
        val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for ((stmtRange, err) in result.errors) {
            val range = errorRange(doc, stmtRange, err)
            holder.newAnnotation(HighlightSeverity.ERROR, "DuckDB: ${err.message}")
                .range(range)
                .create()
        }
        val source = PipeText.of(file)
        val brikk = BrikkDuckdbProfile.forFile(file) != null
        for ((stmtRange, err) in result.pipeErrors) {
            val text = source.text.substring(stmtRange.startOffset, stmtRange.endOffset)
            val range = source.errorRange(stmtRange.startOffset, text, err.range)
                ?: if (brikk && err.range == null) source.fileRange(stmtRange.startOffset, stmtRange.endOffset)
                    ?.let { TextRange(it.startOffset, it.startOffset + 1) } else null
            if (range == null) continue
            val label = if (brikk) "DuckDB Brikk" else DuckdbPipeDialect.label
            holder.newAnnotation(HighlightSeverity.ERROR, "$label: ${err.message}").range(range).create()
        }
    }

    private fun errorRange(doc: Document, stmt: TextRange, err: VerifyError): TextRange {
        err.offset?.let { offset ->
            val start = (stmt.startOffset + offset).coerceIn(stmt.startOffset, (stmt.endOffset - 1).coerceAtLeast(stmt.startOffset))
            val sql = doc.charsSequence.subSequence(stmt.startOffset, stmt.endOffset).toString()
            val end = err.endOffset ?: Tokenizer(TokenizerConfigs.forName("duckdb")).tokenizeForEditor(sql, ProgressManager::checkCanceled).tokens
                .firstOrNull { offset in it.start..it.end }?.let { it.end + 1 } ?: offset + 1
            return TextRange(start, (stmt.startOffset + end).coerceIn(start + 1, stmt.endOffset))
        }
        val stmtStartLine = doc.getLineNumber(stmt.startOffset)
        val targetLine = (stmtStartLine + (err.line ?: 1) - 1).coerceAtMost(doc.lineCount - 1)
        val lineStart = doc.getLineStartOffset(targetLine).coerceAtLeast(stmt.startOffset)
        val lineEnd = doc.getLineEndOffset(targetLine).coerceAtMost(stmt.endOffset)
        if (lineEnd <= lineStart) return stmt
        return TextRange(lineStart, lineEnd)
    }
}
