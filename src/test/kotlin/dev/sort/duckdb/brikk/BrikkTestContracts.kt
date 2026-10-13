package dev.sort.duckdb.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import dev.brikk.house.intellij.BrikkHosts
import dev.brikk.house.intellij.BrikkHostState
import dev.brikk.house.intellij.ShapeResult

/** Tests of helper contract inventories, distinct from the shared SQL analyzer's feature tests. */
internal fun brikkContracts(file: PsiFile): Map<String, ShapeResult> {
    val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: return emptyMap()
    return (BrikkHosts.state(host, includeShapes = true) as? BrikkHostState.Recognized)?.context?.relations.orEmpty()
}

internal fun brikkScope(file: PsiFile): dev.brikk.house.sql.scope.RelationScope {
    val facts = DuckdbRelationProfile.providers.mapNotNull { it.facts(file) }
        .filterIsInstance<dev.brikk.house.sql.intellij.relation.RelationFactsResult.Available>().first().facts
    return dev.brikk.house.sql.scope.RelationScope.analyze(dev.brikk.house.sql.intellij.pipe.PipeText.of(file).text, "duckdb", facts)
}
