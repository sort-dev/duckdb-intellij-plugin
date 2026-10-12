package dev.sort.duckdb

internal fun registeredSqlInspection(id: String): com.intellij.codeInspection.InspectionProfileEntry =
    requireNotNull(com.intellij.codeInspection.ex.InspectionToolRegistrar.getInstance().createTools()
        .firstOrNull { it.shortName == id }) { "Missing registered SQL inspection $id" }.tool
