package dev.sort.duckdb.catalog

import dev.sort.duckdb.sql.DuckdbFunctionCatalog
import org.jetbrains.annotations.TestOnly

/**
 * Extension-aware completion offers: functions that are NOT in the base snapshot but arrive with
 * a specific extension (`ST_Area` with spatial, `create_fts_index` with fts, ...), offered with a
 * `requires <extension>` type text so LOAD-able surface is discoverable before it is loaded.
 *
 * Data file: optional resource `duckdb/extension-functions.tsv`, lines `name<TAB>kind<TAB>extension`
 * (`#` comments; kinds are duckdb_functions() function_type strings), harvested in a separate lane.
 * Resource absent -> this layer is silently off. Names keep the MIXED CASE duckdb_functions()
 * reports (e.g. `ST_AsGeoJSON`); DuckDB resolves function names case-insensitively, so every
 * "already in the active catalog?" check MUST fold case — matching is folded, insertion text is not.
 */
object DuckdbExtensionOffers {

    data class Offer(val name: String, val kind: DuckdbFunctionCatalog.Kind, val extension: String)

    @Volatile
    private var testOverride: List<Offer>? = null

    /** The active offer list — the bundled resource, or whatever a test injected. */
    val offers: List<Offer>
        get() = testOverride ?: bundled

    private val bundled: List<Offer> by lazy {
        val text = DuckdbExtensionOffers::class.java.getResourceAsStream("/duckdb/extension-functions.tsv")
            ?.bufferedReader()?.readText() ?: return@lazy emptyList()
        parse(text)
    }

    /** Tolerant line parser (public seam so tests feed synthetic data, not a bundled resource). */
    fun parse(text: String): List<Offer> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val name = parts[0].trim()
                val extension = parts[2].trim()
                if (!DuckdbFunctionCatalog.isCompletableName(name) || extension.isEmpty()) return@mapNotNull null
                Offer(name, DuckdbFunctionCatalog.kindOf(parts[1]), extension)
            }
            .toList()

    @TestOnly
    fun setOffersForTests(offers: List<Offer>?) {
        testOverride = offers
    }
}
