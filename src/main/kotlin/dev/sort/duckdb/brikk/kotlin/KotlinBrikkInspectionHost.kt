package dev.sort.duckdb.brikk.kotlin

import com.intellij.psi.PsiLanguageInjectionHost
import dev.brikk.house.intellij.BrikkInspectionView
import dev.sort.duckdb.brikk.DuckdbInspectionHost

class KotlinBrikkInspectionHost : DuckdbInspectionHost {
    override fun claims(host: PsiLanguageInjectionHost) = BrikkInspectionView.isInspectionOnly(host)
}
