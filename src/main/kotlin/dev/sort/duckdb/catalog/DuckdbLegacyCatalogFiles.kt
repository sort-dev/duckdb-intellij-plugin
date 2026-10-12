package dev.sort.duckdb.catalog

import com.intellij.openapi.application.PathManager
import dev.brikk.house.sql.intellij.catalog.LegacyCatalogFiles
import dev.brikk.house.sql.intellij.catalog.LiveFunction
import dev.brikk.house.sql.intellij.catalog.LiveInventory
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.KeywordDef
import java.nio.file.Files
import java.nio.file.Path

class DuckdbLegacyCatalogFiles(
    private val directory: Path = Path.of(PathManager.getSystemPath(), "duckdb-brikk", "catalog"),
) : LegacyCatalogFiles {
    override fun file(dataSourceId: String): Path {
        val name = dataSourceId.map { if (it.isLetterOrDigit() || it in ".-_") it else '_' }
            .joinToString("").take(120).ifEmpty { "ds" }
        return directory.resolve("$name.tsv")
    }

    override fun read(file: Path): LiveInventory? {
        val lines = Files.readAllLines(file)
        if (lines.firstOrNull() !in setOf(
                "# duckdb-brikk live catalog v1 (see DuckdbLiveCatalog for the format)",
                "# duckdb-brikk live catalog v2 (see DuckdbLiveCatalog for the format)",
            )) return null
        var version: String? = null
        var extensions = emptyList<String>()
        val functions = ArrayList<LiveFunction>()
        val keywords = ArrayList<KeywordDef>()
        for (line in lines) {
            if (line.isBlank() || line.startsWith('#')) continue
            val parts = line.split('\t')
            when (parts[0]) {
                "meta" -> {
                    if (parts.size != 4 || version != null || parts[1].isBlank() || parts[3].toLongOrNull() == null) return null
                    version = parts[1]
                    extensions = parts[2].split(',').filter(String::isNotEmpty)
                }
                "fn" -> {
                    if (parts.size != 3 || parts[1].isBlank()) return null
                    val kind = when (parts[2]) {
                        "PRAGMA" -> continue
                        "AGGREGATE" -> FunctionKind.AGGREGATE
                        "WINDOW" -> FunctionKind.WINDOW
                        "TABLE", "TABLE_MACRO" -> FunctionKind.TABLE_VALUED
                        "SCALAR", "MACRO", "OTHER" -> FunctionKind.SCALAR
                        else -> return null
                    }
                    val native = when (parts[2]) {
                        "MACRO" -> "macro"
                        "TABLE_MACRO" -> "table_macro"
                        else -> null
                    }
                    functions += LiveFunction(FunctionDef(parts[1], kind = kind, nativeKind = native))
                }
                "kw" -> {
                    if (parts.size != 2 || parts[1].isBlank()) return null
                    keywords += KeywordDef(parts[1])
                }
                else -> return null
            }
        }
        val engineVersion = version ?: return null
        val snapshot = DUCKDB_METADATA.forServerVersion(engineVersion).snapshot
        val sessionExtensionNames = snapshot.extensionFunctions.orEmpty()
            .filter { fn -> fn.extension in extensions && snapshot.extensions.orEmpty().none { it.name == fn.extension && it.staticallyLinked } }
            .mapTo(HashSet()) { it.name.lowercase() }
        val shared = functions.filter { it.def.name.lowercase() !in sessionExtensionNames }
        if (shared.isEmpty() || keywords.isEmpty()) return null
        return LiveInventory(engineVersion, shared, signatures = false, keywords)
    }
}
