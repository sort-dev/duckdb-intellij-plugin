package dev.sort.duckdb.grammar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import dev.sort.duckdb.catalog.DuckdbCatalogProfile

abstract class DuckdbGrammarConsoleAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    protected fun file(event: AnActionEvent) = event.getData(CommonDataKeys.PSI_FILE)
    protected fun session(event: AnActionEvent) = file(event)?.let { DuckdbCatalogProfile.dataSources.consoleSession(it) }

    override fun update(e: AnActionEvent) {
        val session = session(e)
        e.presentation.isEnabledAndVisible = session != null && DuckdbGrammarSources.accepts(session.connectionPoint.dataSource)
    }
}

class DuckdbRefreshGrammarAction : DuckdbGrammarConsoleAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = session(e) ?: return
        project.service<DuckdbGrammarRuntime>().refresh(project, session)
    }
}

class DuckdbFollowConsoleGrammarAction : DuckdbGrammarConsoleAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = if (e.project?.service<DuckdbGrammarRuntime>()?.follows(session(e)) == true) "Use Managed DuckDB Grammar Profile"
            else "Follow Console DuckDB Grammars (Validation Unavailable)"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val session = session(e) ?: return
        val runtime = (e.project ?: return).service<DuckdbGrammarRuntime>()
        runtime.follow(session, !runtime.follows(session))
        e.project?.let { runtime.refresh(it, session) }
        if (runtime.follows(session)) {
            Messages.showInfoMessage(e.project,
                "This mode snapshots the console's activation without changing the managed profile.\n" +
                    "Native validation is unavailable: a same-instance isolated helper cannot be created with the currently supported IDE APIs.\n" +
                    "The console's SQL, transaction and settings are not replayed or changed.", "DuckDB follow-console limitation")
        }
    }
}

class DuckdbGrammarStatusAction : DuckdbGrammarConsoleAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val file = file(e) ?: return
        val session = session(e) ?: return
        val source = session.connectionPoint.dataSource
        val runtime = file.project.service<DuckdbGrammarRuntime>()
        val snapshot = runtime.runtime(source, session)?.snapshot
        val configured = file.project.service<DuckdbGrammarSettings>().profile(source.uniqueId)
        val identity = file.project.service<DuckdbGrammarVerification>().identity(file)
        Messages.showInfoMessage(e.project, buildString {
            appendLine("Configured order: ${configured.enabled.joinToString().ifEmpty { "base grammar" }}")
            appendLine("Available in this console: ${snapshot?.available?.joinToString() ?: "not discovered"}")
            appendLine("Effective order: ${snapshot?.effective?.joinToString() ?: "not discovered"}")
            appendLine("Mode: ${if (runtime.follows(session)) "follow console" else "managed data-source profile"}")
            appendLine(snapshot?.unavailable ?: "")
            appendLine(runtime.runtime(source, session)?.initializationError ?: "")
            appendLine(file.project.service<dev.sort.duckdb.completion.DuckdbNativeKeywordService>().describe(source))
            append(identity?.unavailable ?: "Managed file validation checks availability and exact activation on each helper.")
        }, "DuckDB grammar state")
    }
}

class DuckdbCheckNativeCompletionAction : DuckdbGrammarConsoleAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val file = file(e) ?: return
        val project = file.project
        val target = project.service<DuckdbGrammarVerification>().target(file) ?: return
        val service = project.service<dev.sort.duckdb.completion.DuckdbNativeKeywordService>()
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val prefix = requireNotNull(dev.sort.duckdb.completion.NativeKeywordPrefix.capture("SELECT ", 7))
            val result = service.complete(target, prefix)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) Messages.showInfoMessage(project, result.status ?: "Native keyword completion is available on a correctly configured isolated helper.", "Native keyword completion")
            }
        }
    }
}

class DuckdbAdoptConsoleGrammarAction : DuckdbGrammarConsoleAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val session = session(e) ?: return
        e.presentation.isEnabled = e.project?.service<DuckdbGrammarRuntime>()?.runtime(session.connectionPoint.dataSource, session)?.snapshot?.supported == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val session = session(e) ?: return
        e.project?.service<DuckdbGrammarRuntime>()?.adopt(session.connectionPoint.dataSource, session)
    }
}
