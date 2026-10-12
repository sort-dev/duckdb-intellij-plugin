package dev.sort.duckdb.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.ToggleConfigurable

class DuckdbPipesConfigurable(private val project: Project) : ToggleConfigurable(
    "dev.sort.duckdb.pipes", DuckdbPipeDialect.label, "DuckDB PIPE support",
    "Transpile PIPE queries to DuckDB SQL on execution. SQL Transpiler is not required.", DuckdbPipeDialect.vetoProperty,
) {
    override val settings get() = project.service<DuckdbPipesSettings>()
}
