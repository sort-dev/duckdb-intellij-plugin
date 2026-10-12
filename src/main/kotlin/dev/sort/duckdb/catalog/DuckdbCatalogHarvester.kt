package dev.sort.duckdb.catalog

import dev.brikk.house.sql.intellij.catalog.CatalogRowSource
import dev.brikk.house.sql.intellij.catalog.Harvest
import dev.brikk.house.sql.intellij.catalog.LiveCatalogHarvester
import dev.brikk.house.sql.intellij.catalog.LiveFunction
import dev.brikk.house.sql.intellij.catalog.LiveInventory
import dev.brikk.house.sql.intellij.catalog.SessionFacts
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_KEYWORDS
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.DuckdbDump
import dev.brikk.house.sql.metadata.DuckdbFunctionRow
import dev.brikk.house.sql.metadata.DuckdbKeywordRow
import dev.brikk.house.sql.metadata.toSnapshot
import dev.brikk.house.sql.metadata.userFunctions

object DuckdbCatalogHarvester : LiveCatalogHarvester {
    const val SQL_VERSION = "SELECT version()"
    const val SQL_LOADED_EXTENSIONS =
        "SELECT extension_name, install_mode FROM duckdb_extensions() WHERE loaded ORDER BY 1"

    override fun harvest(rows: CatalogRowSource): Harvest? {
        var version: String? = null
        rows.forEachRow(SQL_VERSION) { version = it.string(1)?.trim()?.takeIf(String::isNotEmpty) }
        val engineVersion = version ?: return null
        val functions = ArrayList<DuckdbFunctionRow>()
        rows.forEachRow(DUCKDB_FUNCTIONS.sql) { row ->
            functions += DUCKDB_FUNCTIONS.decode(DUCKDB_FUNCTIONS.columns.indices.map { row.string(it + 1) })
        }
        val keywords = ArrayList<DuckdbKeywordRow>()
        rows.forEachRow(DUCKDB_KEYWORDS.sql) { row ->
            keywords += DUCKDB_KEYWORDS.decode(listOf(row.string(1), row.string(2)))
        }
        if (functions.isEmpty() || keywords.isEmpty()) return null
        val linked = ArrayList<String>()
        val loaded = ArrayList<String>()
        rows.forEachRow(SQL_LOADED_EXTENSIONS) { row ->
            val name = row.string(1)?.takeIf(String::isNotEmpty) ?: return@forEachRow
            if (row.string(2) == "STATICALLY_LINKED") linked += name else loaded += name
        }
        val dump = DuckdbDump(engineVersion, functions, keywords)
        val snapshot = dump.toSnapshot()
        val extensionNames = DUCKDB_METADATA.forServerVersion(engineVersion).snapshot.extensionFunctions.orEmpty()
            .filter { it.extension in loaded }.mapTo(HashSet()) { it.name.lowercase() }
        val (sessionBuiltins, builtins) = snapshot.functions.functions.map(::LiveFunction)
            .partition { it.def.name.lowercase() in extensionNames }
        val (temporary, persistent) = dump.userFunctions().partition { it.database == "temp" }
        val shared = builtins + persistent.map { LiveFunction(it.def, listOf(it.database, it.schema)) }
        if (shared.isEmpty()) return null
        return Harvest(
            LiveInventory(engineVersion, shared, signatures = true, snapshot.keywords?.keywords, linked),
            SessionFacts(sessionBuiltins + temporary.map { LiveFunction(it.def, listOf(it.database, it.schema)) }, loaded),
        )
    }
}
