package dev.sort.duckdb.pipes

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.script.ScriptModel
import com.intellij.database.script.ScriptModelUtilCore
import com.intellij.database.script.translator.TranslateException
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Conditions
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.containers.JBIterable
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.TokenType
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.validate.DuckdbEngineLocator
import dev.sort.duckdb.validate.DuckdbEngineValidator

internal open class DuckdbPipesRunQueryAction(
    index: Int,
    private val previous: AnAction,
    private val intercept: (AnActionEvent, DatabaseSettings.ExecOption) -> Boolean = PipesExecuteInterceptor::handle,
) : RunQueryAction(index), ActionWithDelegate<AnAction>, PerformWithDocumentsCommitted {
    init { copyFrom(previous) }
    @Volatile internal var isDetached = false
        private set
    internal fun detach() { isDetached = true }
    override fun getDelegate(): AnAction = previous
    override fun update(e: AnActionEvent) = previous.update(e)
    override fun getActionUpdateThread(): ActionUpdateThread = previous.actionUpdateThread
    override fun isDumbAware() = previous.isDumbAware
    override fun isInInjectedContext() = previous.isInInjectedContext
    override fun isPerformWithDocumentsCommitted() = PerformWithDocumentsCommitted.isPerformWithDocumentsCommitted(previous)
    override fun actionPerformed(e: AnActionEvent) {
        if (isDetached || !intercept(e, getExecOption())) ActionWrapperUtil.actionPerformed(e, this, previous)
    }
}

internal class DuckdbPipesRunSelectionAction(
    previous: AnAction,
    intercept: (AnActionEvent, DatabaseSettings.ExecOption) -> Boolean = PipesExecuteInterceptor::handle,
) : DuckdbPipesRunQueryAction(-1, previous, intercept) {
    private val selectionOption = DatabaseSettings.ExecOption().apply { execSelection = 1 }
    override fun getExecOption() = selectionOption
}

internal object PipesExecuteInterceptor {
    fun handle(e: AnActionEvent, option: DatabaseSettings.ExecOption): Boolean {
        val console = JdbcConsole.findConsole(e) ?: return false
        if (console.session.connectionPoint.dbms !== DuckdbDbms.DUCKDB_BRIKK || !DuckdbPipes.isEnabled(console.project)) return false
        val editor = e.getData(CommonDataKeys.EDITOR)
            ?: (e.getData(PlatformCoreDataKeys.FILE_EDITOR) as? TextEditor)?.editor ?: return false
        val text = editor.document.text
        val current = DuckdbPipes.chunkAt(text, editor.caretModel.offset)
        val candidate = editor.selectionModel.selectedText ?: when (option.execInside) {
            DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT -> text
            DatabaseSettings.EXECUTE_INSIDE_SCRIPT_TAIL -> text.substring(current?.startOffset ?: editor.caretModel.offset)
            else -> current?.text ?: return false
        }
        if (!DuckdbPipes.containsPipeOperator(candidate)) return false
        DuckdbPipes.chunks(candidate).firstOrNull { it.boundaryError != null }?.let {
            notifyPipeError(console, it.boundaryError!!)
            return true
        }
        // Invocation-local stock preparation keeps selection, variant, document commit and console.
        var handled = false
        object : RunQueryAction(1) {
            override fun actionPerformed(e: AnActionEvent) = super.actionPerformed(e)
            override fun getExecOption() = option
            override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
                if (console == null) return
                handled = true // A claimed failure must never run raw PIPE through a predecessor.
                JdbcConsoleProvider.chooseStatements(info, "Nothing to run", true) { selected ->
                    if (selected.statements().none { DuckdbPipes.containsPipeOperator(it.query()) }) {
                        if (console.beforeExecuteQueries(selected)) console.executeQueries(editor, selected, info.execOption)
                    } else executePipeModel(console, editor, selected, info.execOption)
                }
            }
        }.actionPerformed(e)
        return handled
    }
}

internal fun notifyPipeError(console: JdbcConsole, message: String) {
    NotificationGroupManager.getInstance().getNotificationGroup("DuckDB PIPE")
        .createNotification("PIPE translation failed",
            StringUtil.escapeXmlEntities(message).replace("\n", "<br>"), NotificationType.ERROR).notify(console.project)
}

internal class PipeTranslationFailure(val error: DuckdbPipesEngine.Transpile.Err) : RuntimeException(error.message)

internal fun requirePipeTranslation(text: String, allowFirstFromStage: Boolean = false): DuckdbPipesEngine.Transpile.Ok =
    when (val result = DuckdbPipesEngine.transpile(text, allowFirstFromStage)) {
        is DuckdbPipesEngine.Transpile.Ok -> {
            result.executionError?.let { throw PipeTranslationFailure(it) }
            result
        }
        is DuckdbPipesEngine.Transpile.Err -> throw PipeTranslationFailure(result)
        DuckdbPipesEngine.Transpile.NotPipe -> throw PipeTranslationFailure(
            DuckdbPipesEngine.Transpile.Err(null, null, "PIPE statement was not translated."))
    }

internal fun <E> executePipeModel(console: JdbcConsole, editor: Editor, selected: ScriptModel<E>,
                                 option: DatabaseSettings.ExecOption, allowFirstFromStage: Boolean = false) {
    try {
        val model = PipeScriptModel(selected, editor, allowFirstFromStage)
        if (!console.beforeExecuteQueries(model)) return
        // Prompt/substitute and check every PIPE before the first ordinary or PIPE request runs.
        model.finalizeTranslations(console.pStorage)
        val file = com.intellij.psi.PsiManager.getInstance(console.project).findFile(selected.virtualFile)
        val jar = file?.let { DuckdbEngineLocator.engineJarFor(it)?.toString() }
        // Optional native syntax preflight uses the user's local driver only. Remote consoles
        // without that driver still work; catalog/binder failures are not syntax failures.
        val generated = model.statements().filter { DuckdbPipes.containsPipeOperator(it.text()) || allowFirstFromStage }
            .map { it.query() }.toList()
        val errors = DuckdbEngineValidator.validate(generated, jar)
        if (errors.isNotEmpty()) throw PipeTranslationFailure(DuckdbPipesEngine.Transpile.Err(null, null,
            "Generated DuckDB SQL failed syntax validation: ${errors.values.first().message}"))
        console.executeQueries(editor, model, option)
    } catch (e: PipeTranslationFailure) {
        notifyPipeError(console, e.error.message)
    } catch (e: TranslateException) {
        notifyPipeError(console, e.message ?: "Parameter substitution failed")
    }
}

/** Adapted from Doris's cooperative ScriptModel. Only claimed statements change query text. */
internal class PipeScriptModel<E>(
    private val delegate: ScriptModel<E>,
    private val editor: Editor,
    private val allowFirstFromStage: Boolean = false,
) : ScriptModel<E>() {
    private data class StatementKey(val range: TextRange, val offset: Long)
    private fun key(it: ScriptModel.StatementIt<E>) = StatementKey(it.range(), it.rangeOffset())
    private inner class Plan(val source: ScriptModel.StatementIt<E>, val sourceText: String,
                             var translation: DuckdbPipesEngine.Transpile.Ok) {
        val parameters = (snapshotParameters(source.parameters()) + pipeParameters(source, sourceText))
            .distinctBy { it.name() to it.range() }
        var finalized = false
    }
    private val documentText = editor.document.text
    private val parameters = snapshotParameters(delegate.parameters())
    private val plans = delegate.statements().mapNotNull { statement ->
        val offset = statement.rangeOffset().coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val range = statement.range().shiftRight(offset)
        val cached = statement.query()
        if (range.startOffset < 0 || range.endOffset > documentText.length || range.isEmpty) {
            if (DuckdbPipes.containsPipeOperator(cached)) throw staleStatement()
            return@mapNotNull null
        }
        val text = documentText.substring(range.startOffset, range.endOffset)
        val claimed = DuckdbPipes.containsPipeOperator(text) || allowFirstFromStage
        if (!claimed) {
            if (DuckdbPipes.containsPipeOperator(cached)) throw staleStatement()
            return@mapNotNull null
        }
        key(statement) to Plan(statement, text, requirePipeTranslation(text, allowFirstFromStage))
    }.toList().toMap()

    fun finalizeTranslations(storage: ScriptModel.PStorage) {
        if (editor.document.text != documentText) throw staleStatement()
        for (plan in plans.values) {
            PipeStatement(plan).consoleQuery(storage, Conditions.alwaysFalse())
            plan.finalized = true
        }
    }
    override fun isActual() = delegate.isActual
    override fun subModel(range: TextRange?): ScriptModel<E> = PipeScriptModel(delegate.subModel(range), editor, allowFirstFromStage)
    override fun everything(): JBIterable<E> = delegate.everything()
    override fun statements(): JBIterable<out ScriptModel.StatementIt<E>> = delegate.statements().transform { statement ->
        plans[key(statement)]?.let { PipeStatement(it) } ?: statement
    }
    override fun parameters(): JBIterable<out ScriptModel.ParamIt<E>> =
        JBIterable.from((parameters + plans.values.flatMap { it.parameters }).distinctBy { it.name() to it.range() })
    override fun externals(): JBIterable<out ScriptModel.ExternalIt<E>> = delegate.externals()
    override fun getVirtualFile() = delegate.virtualFile
    override fun getTextRange() = delegate.textRange
    override fun getLanguage() = delegate.language
    override fun <EE : Any?> rawTransform(
        transform: com.intellij.util.Function<in com.intellij.psi.SyntaxTraverser<E>, out com.intellij.psi.SyntaxTraverser<EE>>,
    ): ScriptModel<EE> = PipeScriptModel(delegate.rawTransform(transform), editor, allowFirstFromStage)

    private inner class PipeStatement(private val plan: Plan) : ScriptModel.StatementIt<E> by plan.source {
        override fun query() = plan.translation.sql
        override fun text() = plan.sourceText
        override fun parameters(): JBIterable<out ScriptModel.ParamIt<E>> = JBIterable.from(plan.parameters)
        override fun consoleQuery(storage: ScriptModel.PStorage, condition: Condition<in ScriptModel.ParamIt<E>>): String {
            if (!plan.finalized) {
                plan.translation = requirePipeTranslation(ScriptModelUtilCore.statementText(this, storage, condition), allowFirstFromStage)
            }
            return plan.translation.sql
        }
    }
}

private fun staleStatement() = PipeTranslationFailure(DuckdbPipesEngine.Transpile.Err(null, null,
    "The statement changed during preparation. Run it again; cached SQL was not executed."))

private fun <E> snapshotParameters(parameters: Iterable<ScriptModel.ParamIt<E>>): List<ScriptModel.ParamIt<E>> =
    parameters.map { parameter ->
        val name = parameter.name()
        val description = parameter.description().toList()
        val text = parameter.text()
        val range = parameter.range()
        val type = parameter.type()
        val api = parameter.api()
        val offset = parameter.rangeOffset()
        val value = parameter.`object`()
        object : ScriptModel.ParamIt<E> {
            override fun name() = name
            override fun description(): Iterable<String> = description
            override fun text() = text
            override fun range() = range
            override fun type() = type
            override fun api() = api
            override fun rangeOffset() = offset
            override fun `object`() = value
        }
    }

internal fun namedParameterRanges(text: String): List<IntRange> {
    val tokens = Dialects.DUCKDB.tokenize(text)
    return tokens.mapIndexedNotNull { index, colon ->
        if (colon.tokenType != TokenType.COLON) return@mapIndexedNotNull null
        val name = tokens.getOrNull(index + 1) ?: return@mapIndexedNotNull null
        if (name.start != colon.end + 1 || !name.text.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) return@mapIndexedNotNull null
        // DuckDB's prefix aliases and struct/map keys also use colons, never parameters.
        val previous = tokens.getOrNull(index - 1)
        if (previous?.tokenType in setOf(TokenType.VAR, TokenType.IDENTIFIER, TokenType.STRING)) return@mapIndexedNotNull null
        colon.start..name.end
    }
}

private fun <E> pipeParameters(statement: ScriptModel.StatementIt<E>, text: String): List<ScriptModel.ParamIt<E>> =
    namedParameterRanges(text).map { range ->
        val name = text.substring(range.first + 1, range.last + 1)
        object : ScriptModel.ParamIt<E> {
            override fun name() = name
            override fun description(): Iterable<String> = emptyList()
            override fun text() = text.substring(range.first, range.last + 1)
            override fun range() = TextRange(statement.range().startOffset + range.first, statement.range().startOffset + range.last + 1)
            override fun type() = statement.type()
            override fun api() = statement.api()
            override fun rangeOffset() = statement.rangeOffset()
            override fun `object`() = statement.`object`()
        }
    }
