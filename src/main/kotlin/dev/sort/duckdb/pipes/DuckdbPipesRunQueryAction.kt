package dev.sort.duckdb.pipes

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.script.ScriptModel
import com.intellij.database.script.translator.TranslateException
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import dev.brikk.house.sql.intellij.*
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.parseParameterOccurrences
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.validate.DuckdbEngineLocator
import dev.sort.duckdb.validate.DuckdbEngineValidator

internal object PipesExecuteInterceptor {
    fun handle(e: AnActionEvent, option: DatabaseSettings.ExecOption): ExecutionClaim {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        if (file != null && dev.sort.duckdb.brikk.isBrikkInjectedSql(file)) {
            NotificationGroupManager.getInstance().getNotificationGroup("DuckDB PIPE")
                .createNotification("Brikk SQL is an inspection view",
                    "Run the compiled/composed relation with its binding map. SQL inspection placeholders are not runtime values.",
                    NotificationType.WARNING).notify(e.project)
            return ExecutionClaim.HANDLED
        }
        val console = JdbcConsole.findConsole(e) ?: return ExecutionClaim.DECLINED
        if (console.session.connectionPoint.dbms !== DuckdbDbms.DUCKDB_BRIKK || !DuckdbPipes.isEnabled(console.project))
            return ExecutionClaim.DECLINED
        val editor = e.getData(CommonDataKeys.EDITOR)
            ?: (e.getData(PlatformCoreDataKeys.FILE_EDITOR) as? TextEditor)?.editor ?: return ExecutionClaim.DECLINED
        val text = editor.document.text
        val current = DuckdbPipes.chunkAt(text, editor.caretModel.offset)
        val tokens = current?.takeIf { it.boundaryError == null }?.let {
            Tokenizer(TokenizerConfigs.forName("duckdb")).tokenizeForEditor(it.text).tokens
                .filterNot { token -> token.tokenType == TokenType.SEMICOLON }
        }
        val outside = current == null || !current.hasSql || tokens != null &&
            (tokens.isEmpty() || editor.caretModel.offset < current.startOffset + tokens.first().start ||
                editor.caretModel.offset > current.startOffset + tokens.last().end + 1)
        val selection = editor.selectionModel.selectedText
        val candidate = if (selection != null) {
            if (option.execSelection == DatabaseSettings.EXECUTE_SELECTION_SMART_EXPAND) {
                val start = editor.selectionModel.selectionStart
                val end = editor.selectionModel.selectionEnd
                val selected = DuckdbPipes.chunks(text).filter { it.startOffset < end && it.endOffset > start }
                if (selected.isEmpty()) selection else text.substring(selected.first().startOffset, selected.last().endOffset)
            } else selection
        } else if (outside) when (option.execOutside) {
            DatabaseSettings.EXECUTE_OUTSIDE_WHOLE_SCRIPT -> text
            DatabaseSettings.EXECUTE_OUTSIDE_SCRIPT_TAIL -> {
                val start = if (current != null && tokens?.firstOrNull()?.let {
                    current.startOffset + it.start >= editor.caretModel.offset
                } == true) current.startOffset else DuckdbPipes.chunks(text)
                    .firstOrNull { it.hasSql && it.startOffset >= editor.caretModel.offset }?.startOffset ?: text.length
                text.substring(start)
            }
            else -> return ExecutionClaim.DECLINED
        } else when (option.execInside) {
            DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT,
            DatabaseSettings.EXECUTE_INSIDE_SHOW_CHOOSER,
            DatabaseSettings.EXECUTE_INSIDE_BATCH -> text
            DatabaseSettings.EXECUTE_INSIDE_SCRIPT_TAIL -> text.substring(current.startOffset)
            else -> current.text
        }
        if (!DuckdbPipes.containsPipeOperator(candidate)) return ExecutionClaim.DECLINED
        DuckdbPipes.chunks(candidate).firstOrNull { it.boundaryError != null }?.let {
            notifyPipeError(console, it.boundaryError!!)
            return ExecutionClaim.HANDLED
        }
        // Claim before preparation. Cancellation or a missing Info must not execute raw PIPE.
        val snapshot = EditorSnapshot.capture(editor.document)
        val owner = console
        try {
            object : RunQueryAction(1) {
                override fun actionPerformed(e: AnActionEvent) = super.actionPerformed(e)
                override fun getExecOption() = option
                override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
                    if (console == null) return
                    if (console !== owner) throw SqlExecutionRefusal("The destination console changed during preparation")
                    snapshot.requireCurrent()
                    JdbcConsoleProvider.chooseStatements(info, "Nothing to run", true) { selected ->
                        snapshot.requireCurrent()
                        executePipeModel(console, editor, selected, info.execOption)
                    }
                }
            }.actionPerformed(e)
        } catch (failure: SqlExecutionRefusal) {
            notifyPipeError(console, failure.message ?: "PIPE execution refused")
        }
        return ExecutionClaim.HANDLED
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

internal class DuckdbExecutionAdapter(private val firstStage: Boolean = false) : SqlExecutionAdapter {
    override fun claims(text: String) = firstStage || DuckdbPipes.containsPipeOperator(text)
    override fun parameters(text: String): List<SqlParameterSpan> = try {
        parseParameterOccurrences(text, "duckdb").filter { it.style == ParamStyle.NAMED_COLON && it.name != null }
            .map { SqlParameterSpan(it.name!!, it.range.start, it.range.end) }
    } catch (e: ParseError) {
        throw SqlExecutionRefusal(e.message ?: "Invalid PIPE SQL")
    } catch (e: TokenError) {
        throw SqlExecutionRefusal(e.message ?: "Invalid PIPE token")
    } catch (e: ShapeError) {
        throw SqlExecutionRefusal(e.message ?: "Invalid PIPE statement")
    }

    override fun translate(text: String, beforeSubstitution: Boolean): RenderedSql {
        val result = requirePipeTranslation(text, firstStage)
        val leading = text.length - text.trimStart().length
        return RenderedSql(result.sql, text) map@ { line, column ->
            if (line < 1 || column < 1) return@map null
            val outputLine = result.sql.lineSequence().elementAtOrNull(line - 1) ?: return@map null
            if (column > outputLine.length + 1) return@map null
            val position = result.result.mapErrorToSource(line, column) ?: return@map null
            if (position.start < 0 || position.end < position.start || position.end + leading >= text.length) return@map null
            TextRange(position.start + leading, position.end + leading + 1)
        }
    }
}

internal fun <E> executePipeModel(console: JdbcConsole, editor: Editor, selected: ScriptModel<E>,
                                 option: DatabaseSettings.ExecOption, allowFirstFromStage: Boolean = false) {
    try {
        val session = console.session
        val target = console.dataSource
        val url = target.url
        val snapshot = EditorSnapshot.capture(editor.document)
        val model = TranslatingScriptModel(selected, editor, DuckdbExecutionAdapter(allowFirstFromStage))
        if (!console.beforeExecuteQueries(model)) return
        snapshot.requireCurrent()
        model.finalizeTranslations(console.pStorage)
        val file = com.intellij.psi.PsiManager.getInstance(console.project).findFile(selected.virtualFile)
        val jar = file?.let { DuckdbEngineLocator.engineJarFor(it)?.toString() }
        // Optional local syntax preflight is product policy, not a dependency of the helper.
        val generated = model.statements().filter { DuckdbPipes.containsPipeOperator(it.text()) || allowFirstFromStage }
            .map { it.query() }.toList()
        val errors = DuckdbEngineValidator.validate(generated, jar)
        if (errors.isNotEmpty()) throw PipeTranslationFailure(DuckdbPipesEngine.Transpile.Err(null, null,
            "Generated DuckDB SQL failed syntax validation: ${errors.values.first().message}"))
        if (!console.isValid || console.session !== session || console.dataSource !== target || target.url != url ||
            session.connectionPoint.dbms !== DuckdbDbms.DUCKDB_BRIKK)
            throw SqlExecutionRefusal("The destination changed during parameter preparation. Run the query again.")
        if (!DuckdbPipes.isEnabled(console.project)) throw SqlExecutionRefusal("PIPE was disabled during preparation")
        snapshot.requireCurrent()
        if (!model.isActual) throw SqlExecutionRefusal("The statement changed during preparation. Run it again.")
        if (console.session.clientsWithFile.isEmpty()) throw SqlExecutionRefusal("No attached console client")
        console.executeQueries(editor, model, option)
    } catch (e: PipeTranslationFailure) {
        notifyPipeError(console, e.error.message)
    } catch (e: SqlExecutionRefusal) {
        notifyPipeError(console, e.message ?: "PIPE execution refused")
    } catch (e: TranslateException) {
        notifyPipeError(console, e.message ?: "Parameter substitution failed")
    }
}
