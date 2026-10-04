package dev.sort.duckdb.pipes

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.settings.DatabaseSettings
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

internal object DuckdbPipesUi {
    fun chunk(file: PsiFile, editor: Editor): DuckdbPipes.Chunk? {
        if (!file.language.isKindOf(DuckdbSqlDialect.INSTANCE) || !DuckdbPipes.isEnabled(file.project)) return null
        return DuckdbPipes.chunkAt(editor.document.text, editor.caretModel.offset)?.takeIf { it.hasPipeOperator }
    }
    fun preview(project: Project, file: PsiFile, editor: Editor) {
        val text = chunk(file, editor)?.text ?: return
        val result = DuckdbPipesEngine.transpile(text)
        val sql = if (result is DuckdbPipesEngine.Transpile.Ok) result.sql else ""
        val error = when (result) {
            is DuckdbPipesEngine.Transpile.Ok -> result.executionError?.message
            is DuckdbPipesEngine.Transpile.Err -> result.message
            else -> "Not a PIPE statement"
        }
        val area = JTextArea(sql).apply {
            isEditable = false
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13)
        }
        val panel = JPanel(BorderLayout()).apply {
            add(JScrollPane(area).apply { preferredSize = Dimension(640, 360) })
            error?.let {
                add(JScrollPane(JTextArea(it).apply {
                    isEditable = false; lineWrap = true; wrapStyleWord = true
                }).apply { preferredSize = Dimension(640, 100) }, BorderLayout.NORTH)
            }
        }
        JBPopupFactory.getInstance().createComponentPopupBuilder(panel, area)
            .setTitle(if (error == null) "Generated DuckDB SQL" else "DuckDB PIPE (execution blocked)")
            .setResizable(true).setMovable(true).setRequestFocus(true).createPopup().showInBestPositionFor(editor)
    }
    fun runToStage(project: Project, file: PsiFile, editor: Editor) {
        val chunk = chunk(file, editor) ?: return
        val console = JdbcConsoleProvider.getValidConsole(project, file.viewProvider.virtualFile) ?: return
        if (console.session.connectionPoint.dbms !== DuckdbDbms.DUCKDB_BRIKK) return
        chunk.boundaryError?.let { notifyPipeError(console, it); return }
        val prefix = DuckdbPipesEngine.stagePrefixAt(chunk.text, editor.caretModel.offset - chunk.startOffset)
            ?: return notifyPipeError(console, "Could not determine top-level PIPE stage boundaries.")
        val range = TextRange(chunk.startOffset + chunk.text.length - chunk.text.trimStart().length,
            chunk.startOffset + prefix.text.length)
        executePipeModel(console, editor, console.scriptModel.subModel(range),
            DatabaseSettings.ExecOption().apply { execSelection = 1 }, allowFirstFromStage = prefix.stage == 1)
    }
}

class PreviewPipeSqlIntention : IntentionAction {
    override fun getText() = "DuckDB PIPE: preview generated SQL"
    override fun getFamilyName() = "DuckDB PIPE"
    override fun startInWriteAction() = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) =
        editor != null && file != null && DuckdbPipesUi.chunk(file, editor) != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null && file != null) DuckdbPipesUi.preview(project, file, editor)
    }
}
class RunPipesToStageIntention : IntentionAction {
    override fun getText() = "DuckDB PIPE: run stages up to caret"
    override fun getFamilyName() = "DuckDB PIPE"
    override fun startInWriteAction() = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) =
        editor != null && file != null && DuckdbPipesUi.chunk(file, editor) != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null && file != null) DuckdbPipesUi.runToStage(project, file, editor)
    }
}
abstract class DuckdbPipesMenuAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = file != null && editor != null && DuckdbPipesUi.chunk(file, editor) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        perform(e.project ?: return, e.getData(CommonDataKeys.PSI_FILE) ?: return, e.getData(CommonDataKeys.EDITOR) ?: return)
    }
    abstract fun perform(project: Project, file: PsiFile, editor: Editor)
}
class PreviewPipeSqlAction : DuckdbPipesMenuAction() {
    override fun perform(project: Project, file: PsiFile, editor: Editor) = DuckdbPipesUi.preview(project, file, editor)
}
class RunPipesToStageAction : DuckdbPipesMenuAction() {
    override fun perform(project: Project, file: PsiFile, editor: Editor) = DuckdbPipesUi.runToStage(project, file, editor)
}
