package dev.sort.duckdb.brikk.kotlin

import com.intellij.psi.util.PsiTreeUtil
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*

/** Resolved symbols are authoritative. No short-name annotation/call matching, no cached guesses. */
@OptIn(KaAllowAnalysisOnEdt::class)
internal object BrikkDuckdbKotlinContext {
    private val annotation = ClassId.topLevel(FqName("dev.brikk.house.sql.runtime.BrikkSql"))
    private const val entryPoint = "dev.brikk.house.sql.runtime.Sql.duckdb"
    private const val relType = "dev.brikk.house.sql.runtime.Rel"
    data class Context(val host: KtStringTemplateExpression, val trims: List<String>)
    data class Piece(val range: com.intellij.openapi.util.TextRange, val replacement: String? = null)
    data class Template(val context: Context, val pieces: List<Piece>, val profile: BrikkDuckdbProfile,
                        val diagnostics: List<Pair<com.intellij.openapi.util.TextRange, String>>)

    fun find(host: KtStringTemplateExpression): Context? {
        var expression: KtExpression = host
        val trims = mutableListOf<String>()
        while (true) {
            val qualified = expression.parent as? KtDotQualifiedExpression ?: break
            if (qualified.receiverExpression !== expression) return null
            val call = qualified.selectorExpression as? KtCallExpression ?: return null
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
            if (name !in listOf("trimIndent", "trimMargin") || call.valueArguments.isNotEmpty()) return null
            trims.add(name)
            expression = qualified
        }
        val argument = expression.parent as? KtValueArgument ?: return null
        val call = argument.parent?.parent as? KtCallExpression ?: return null
        if (call.valueArguments.size != 1 || call.valueArguments.single() !== argument) return null
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        val function = PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java) ?: return null
        return allowAnalysisOnEdt { analyze(host) {
            val target = callee.mainReference.resolveToSymbol() as? KaCallableSymbol ?: return@analyze null
            if (target.callableId?.asSingleFqName()?.asString() != entryPoint) return@analyze null
            if (!function.symbol.annotations.contains(annotation)) return@analyze null
            // Only compiler-supported stdlib trimming, not lookalike extension functions.
            var receiver: KtExpression = host
            for (name in trims) {
                val qualified = receiver.parent as KtDotQualifiedExpression
                val trimCall = qualified.selectorExpression as KtCallExpression
                val trim = (trimCall.calleeExpression as KtNameReferenceExpression).mainReference.resolveToSymbol() as? KaCallableSymbol
                if (trim?.callableId?.asSingleFqName()?.asString() != "kotlin.text.$name") return@analyze null
                receiver = qualified
            }
            Context(host, trims)
        } }
    }

    fun template(context: Context): Template = allowAnalysisOnEdt { analyze(context.host) {
        val binds = mutableListOf<String>()
        val slots = mutableListOf<String>()
        val issues = mutableListOf<String>()
        val diagnostics = mutableListOf<Pair<com.intellij.openapi.util.TextRange, String>>()
        val pieces = context.host.entries.mapIndexed { index, entry ->
            val range = entry.textRange.shiftLeft(context.host.textRange.startOffset)
            fun issue(message: String) {
                issues.add(message)
                diagnostics.add(entry.textRange to message)
            }
            val expression = entry.expression
            if (expression == null) return@mapIndexed Piece(range)
            // Literal ${'$'} is an authored escape, not a bind or SQL text constant.
            if (expression is KtConstantExpression && expression.text == "'$'") return@mapIndexed Piece(range, "$")
            val name = expression as? KtNameReferenceExpression
            if (name == null) {
                issue("Unsupported Brikk SQL interpolation: extract the expression to a local val and interpolate its name.")
                return@mapIndexed Piece(range, ":__brikk_unsupported_$index")
            }
            val symbol = name.mainReference.resolveToSymbol()
            val bindName = name.getReferencedName()
            val replacement = when (symbol) {
                is KaValueParameterSymbol -> {
                    val type = symbol.returnType.fullyExpandedType
                    if (type is KaErrorType) {
                        issue("Brikk SQL interpolation '$bindName' has an unresolved Kotlin type; its bind/Rel role is not yet known.")
                        ":__brikk_unresolved_$index"
                    } else if ((type as? KaClassType)?.classId?.asSingleFqName()?.asString() == relType) {
                        slots.add(bindName)
                        val tail = context.host.text.substring(range.endOffset, context.host.text.length - 1)
                        if (!tail.trimStart().startsWith("(")) issue("Rel input '$bindName' must be called as a relation slot: \$$bindName().")
                        bindName
                    } else { binds.add(bindName); ":$bindName" }
                }
                is KaLocalVariableSymbol -> { binds.add(bindName); ":$bindName" }
                is KaKotlinPropertySymbol -> {
                    if (!symbol.isConst) { binds.add(bindName); ":$bindName" }
                    else {
                        // Evaluate only a literal initializer. Computed const expressions must
                        // refuse even when Kotlin's evaluator could fold them.
                        val property = symbol.psi as? KtProperty
                        val initializer = property?.initializer
                        val literal = when (initializer) {
                            is KtConstantExpression -> initializer.evaluate()?.value?.toString()
                            is KtStringTemplateExpression -> if (!initializer.hasInterpolation()) initializer.evaluate()?.value?.toString() else null
                            else -> null
                        }
                        if (literal != null) literal else {
                            issue("Computed or unavailable const val '$bindName' cannot be interpolated as SQL; use a literal initializer.")
                            ":__brikk_unsupported_$index"
                        }
                    }
                }
                else -> {
                    issue("Brikk SQL interpolation '$bindName' is unresolved. Retry after Kotlin symbol resolution completes.")
                    ":__brikk_unresolved_$index"
                }
            }
            Piece(range, replacement)
        }
        Template(context, pieces, BrikkDuckdbProfile(binds.distinct(), slots.distinct(), issues.distinct()), diagnostics)
    } }
}
