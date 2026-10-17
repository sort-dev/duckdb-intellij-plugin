package dev.sort.duckdb.pipes

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.ToggleSettings
import dev.sort.duckdb.sql.DuckdbSqlDialect

@Service(Service.Level.PROJECT)
@State(name = "DuckdbPipesSettings", storages = [Storage(ToggleSettings.STORAGE)])
class DuckdbPipesSettings(project: Project) : ToggleSettings(project, DuckdbSqlDialect)
