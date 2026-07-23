package dev.sort.duckdb.sql

/**
 * The bundled function/keyword catalog — harvested from a real DuckDB by
 * `./gradlew harvestFunctionCatalog` (duckdb_functions() / duckdb_keywords()), refreshed with
 * every duckdb_jdbc bump. This is Stage 4's "not connected" source; the live per-data-source
 * overlay (engine version + loaded extensions as the cache key) layers on top later and wins
 * when present.
 */
object DuckdbFunctionCatalog {

    enum class Kind { SCALAR, AGGREGATE, TABLE, MACRO, TABLE_MACRO, PRAGMA, OTHER }

    data class Fn(val name: String, val kind: Kind)

    /** Identifier-named functions only (operator entries like `!~~` are not completable). */
    val functions: List<Fn> by lazy {
        resource("/duckdb/functions.tsv").lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val name = line.substring(0, tab)
                if (!isCompletableName(name)) return@mapNotNull null
                Fn(name, kindOf(line.substring(tab + 1)))
            }
            .toList()
    }

    val keywords: Set<String> by lazy {
        resource("/duckdb/keywords.txt").lineSequence().filter { it.isNotBlank() }.map { it.uppercase() }.toSet()
    }

    /** The operator filter above, shared with the live harvest (same rows, same rule). */
    internal fun isCompletableName(name: String): Boolean =
        name.isNotEmpty() && (name.first().isLetter() || name.first() == '_')

    /** duckdb_functions().function_type -> [Kind]; single mapping for bundled AND live rows. */
    internal fun kindOf(raw: String): Kind = when (raw.trim().lowercase()) {
        "scalar" -> Kind.SCALAR
        "aggregate" -> Kind.AGGREGATE
        "table" -> Kind.TABLE
        "macro" -> Kind.MACRO
        "table_macro" -> Kind.TABLE_MACRO
        "pragma" -> Kind.PRAGMA
        else -> Kind.OTHER
    }

    private fun resource(path: String): String =
        DuckdbFunctionCatalog::class.java.getResourceAsStream(path)?.bufferedReader()?.readText() ?: ""
}
