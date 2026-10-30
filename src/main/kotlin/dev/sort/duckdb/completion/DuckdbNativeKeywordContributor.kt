package dev.sort.duckdb.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDocumentManager
import dev.sort.duckdb.grammar.DuckdbGrammarVerification
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** Additive native keywords. Existing symbol and function contributors always run. */
class DuckdbNativeKeywordContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (parameters.completionType != CompletionType.BASIC || !file.language.isKindOf(DuckdbSqlDialect) ||
            InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        val text = document.text
        val stamp = document.modificationStamp
        val prefix = NativeKeywordPrefix.capture(text, parameters.offset, ProgressManager::checkCanceled) ?: return
        val factory = file.project.service<DuckdbGrammarVerification>()
        val target = factory.target(file) ?: return
        if (parameters.invocationCount == 0) repeat(6) {
            ProgressManager.checkCanceled()
            com.intellij.util.TimeoutUtil.sleep(10)
        }
        val service = file.project.service<DuckdbNativeKeywordService>()
        val response = service.complete(target, prefix)
        if (document.modificationStamp != stamp || parameters.editor.caretModel.offset != prefix.caret || factory.identity(file) != target.identity) return
        response.status?.let {
            result.addLookupAdvertisement(it)
            if (parameters.invocationCount > 0) service.announceUnavailable(target, it)
        }
        if (response.items.isEmpty()) return
        result.restartCompletionOnAnyPrefixChange()
        val names = response.items.mapTo(HashSet()) { it.word.uppercase() }
        result.runRemainingContributors(parameters) { completion ->
            val item = completion.lookupElement
            val plain = item.`object` as? String
            val duplicateKeyword = plain?.equals(item.lookupString, ignoreCase = true) == true &&
                item.lookupString.uppercase() in names && LookupElementPresentation.renderElement(item).isItemTextBold
            if (!duplicateKeyword) result.passResult(completion)
        }
        val sink = result.withPrefixMatcher(prefix.typed).caseInsensitive()
        for (candidate in response.items) {
            val word = candidate.word.uppercase()
            val insertion = NativeKeywordInsertion(text, prefix, word, candidate.extra)
            sink.addElement(LookupElementBuilder.create(candidate, word).withBoldness(true).withTypeText("native keyword", true)
                .withCaseSensitivity(true).withInsertHandler { context, _ ->
                    context.setAddCompletionChar(false)
                    val current = context.document.text
                    val replacement = insertion.apply(current, factory.isCurrent(target)) ?: return@withInsertHandler
                    var first = 0
                    while (first < current.length && first < replacement.first.length && current[first] == replacement.first[first]) first++
                    var suffix = 0
                    while (suffix < current.length - first && suffix < replacement.first.length - first &&
                        current[current.lastIndex - suffix] == replacement.first[replacement.first.lastIndex - suffix]) suffix++
                    if (current != replacement.first) context.document.replaceString(first, current.length - suffix,
                        replacement.first.substring(first, replacement.first.length - suffix))
                    context.setTailOffset(replacement.second)
                    context.editor.caretModel.moveToOffset(replacement.second)
                })
        }
    }
}

/** Accept only the standard replacement of the captured trailing word; never overwrite a changed document. */
internal class NativeKeywordInsertion(private val original: String, private val prefix: NativeKeywordPrefix, private val word: String, private val extra: Char?) {
    fun apply(inserted: String, current: Boolean): Pair<String, Int>? {
        val before = original.substring(0, prefix.wordStart)
        val after = original.substring(prefix.caret)
        val normal = before + word + after
        val tail = after.takeWhile { it.isLetterOrDigit() || it == '_' }
        val tab = before + word + after.drop(tail.length)
        if (inserted != normal && inserted != tab) return null
        if (!current) return original to prefix.caret
        // Completing WH<caret>ERE replaces that existing keyword, not WHERE + ERE.
        // An unrelated suffix remains intact, including when Tab removed it first.
        val retained = if (tail.isNotEmpty() && word.startsWith(prefix.typed + tail, ignoreCase = true)) after.drop(tail.length) else after
        val completed = before + word + retained
        val offset = before.length + word.length
        if (extra == null) return completed to offset
        if (retained.firstOrNull() == extra || extra == ' ' && retained.firstOrNull()?.isWhitespace() == true) {
            return completed to (offset + if (retained.firstOrNull() == extra && extra != '\n' && extra != '\r') 1 else 0)
        }
        return (before + word + extra + retained) to (offset + 1)
    }
}
