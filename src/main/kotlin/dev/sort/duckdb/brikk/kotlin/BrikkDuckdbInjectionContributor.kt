package dev.sort.duckdb.brikk.kotlin

import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.psi.PsiElement
import dev.sort.duckdb.sql.DuckdbSqlDialect
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Resolved entry points also inject on first open and through compiler-supported trim calls. */
class BrikkDuckdbInjectionContributor : LanguageInjectionContributor {
    override fun getInjection(context: PsiElement): Injection? {
        val host = context as? KtStringTemplateExpression ?: return null
        if (BrikkDuckdbKotlinContext.find(host) == null) return null
        return object : Injection {
            override fun getInjectedLanguageId() = "DuckDBSQL"
            override fun getInjectedLanguage() = DuckdbSqlDialect.INSTANCE
            override fun getPrefix() = "-- dialect: duckdb\n"
            override fun getSuffix() = ""
            override fun getSupportId() = "kotlin"
        }
    }
}
