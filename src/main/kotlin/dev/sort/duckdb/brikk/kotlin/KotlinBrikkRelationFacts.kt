package dev.sort.duckdb.brikk.kotlin

import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkRelationFacts
import dev.brikk.house.intellij.BrikkRelationFactsResult
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.relation.ExternalRelationFacts
import dev.brikk.house.sql.intellij.relation.RelationColumnLabels
import dev.brikk.house.sql.intellij.relation.RelationFactsResult

private fun adapter(): ExternalRelationFacts {
    val brikk = BrikkRelationFacts(BrikkDialect.DUCKDB)
    val labels = BrikkRelationFacts.LABELS
    val kind = if (labels.brikkColumnStyle) SqlLookupKind.BRIKK_COLUMN else SqlLookupKind.RELATION_COLUMN
    return ExternalRelationFacts.of(RelationColumnLabels(declared = labels.declared, contract = labels.contract,
        declaredKind = kind, contractKind = kind), navigationTarget = brikk::navigationTarget) { file ->
        val result = brikk.facts(file)
        updateBrikkActionBanner(file)
        when (result) {
            is BrikkRelationFactsResult.Available -> RelationFactsResult.Available(result.facts, result.revision)
            is BrikkRelationFactsResult.Pending -> RelationFactsResult.Pending(result.reason)
            null -> null
        }
    }
}

class KotlinBrikkRelationFacts : ExternalRelationFacts by adapter()
