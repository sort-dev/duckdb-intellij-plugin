package dev.sort.duckdb.catalog

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.brikk.house.sql.intellij.catalog.IntrospectBannerProvider
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import dev.brikk.house.sql.shape.SqlNameContext
import java.util.function.Function
import javax.swing.JComponent

/** Uses the shared dialect tokenizer for qualified paths, including escaped identifiers. */
class DuckdbQualifiedPathIntrospectContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val before = parameters.editor.document.charsSequence.subSequence(0, parameters.offset).toString()
        val tokenizer = Tokenizer(TokenizerConfigs.forName("duckdb"))
        val tokens = tokenizer.tokenizeForEditor(before)
        if (tokens.failureOffset != null) return
        val last = tokens.tokens.lastOrNull()
        val start = if (last != null && last.tokenType != TokenType.DOT && last.end + 1 == before.length) last.start else before.length
        val context = SqlNameContext.of(before.substring(0, start), "duckdb")?.takeIf { it.inCode } ?: return
        val qualifier = context.qualifier ?: return
        val path = tokenizer.tokenize(qualifier).filter { it.tokenType != TokenType.DOT }.map { it.text }
        parameters.originalFile.project.getService(DuckdbAutoIntrospectService::class.java).discovery.request(parameters.originalFile, path)
    }
}

class DuckdbIntrospectBannerProvider : EditorNotificationProvider {
    private val shared = object : IntrospectBannerProvider(DuckdbAutoIntrospectService::class.java) {}

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val requests = project.getService(DuckdbAutoIntrospectService::class.java)
        val namespace = requests.discovery.pending(file) ?: return shared.collectNotificationData(project, file)
        return Function {
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text = "DuckDB: discovering '$namespace'… completion will refresh when it finishes"
                createActionLabel("Dismiss") { requests.discovery.dismiss(file); requests.dismiss(file) }
            }
        }
    }
}
