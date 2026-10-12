package dev.sort.duckdb.catalog

/**
 * One query's rows, decoupled from where they come from: in the IDE the connect-time interceptor
 * feeds `RemoteResultSet` rows (out-of-process JDBC), tests feed a plain `java.sql.ResultSet`
 * from the in-process engine — the same harvest code runs over both.
 */
fun interface CatalogRowSource {
    /** Execute [sql] and invoke [onRow] once per row with a 1-based string-column accessor. */
    fun forEachRow(sql: String, onRow: (col: (Int) -> String?) -> Unit)
}

/**
 * Turns a live DuckDB's self-description into a [DuckdbLiveCatalog.Entry] — the connected half of
 * the Stage-4 catalog (PLAN.md): the same duckdb_functions()/duckdb_keywords() queries the
 * build-time harvest runs against the pinned engine, now against *the user's* engine, so
 * completion is version- and extension-exact for that data source.
 *
 * Fail-soft by contract: any surprise (query error, empty inventory, deadline blown) yields
 * `null` — the caller keeps whatever catalog it already had, never a half-harvested one.
 */
object DuckdbCatalogHarvester {

    const val SQL_VERSION = "SELECT version()"
    const val SQL_LOADED_EXTENSIONS = "SELECT extension_name FROM duckdb_extensions() WHERE loaded ORDER BY 1"

    const val SQL_FUNCTIONS = "SELECT DISTINCT function_name, function_type FROM duckdb_functions() ORDER BY 1, 2"

    /** Column name `keyword_name` proven against the in-process 1.5.5 engine by DuckdbLiveHarvestTest. */
    const val SQL_KEYWORDS = "SELECT keyword_name FROM duckdb_keywords() ORDER BY 1"

    /** Whole-harvest wall-clock budget; blown -> abandon (the queries answer in ms on a sane engine). */
    const val DEADLINE_MILLIS = 5_000L

    /**
     * Run the four catalog queries over [rows]; `null` means "abandon, keep the previous cache".
     * [clock] exists for the deadline test — production uses wall-clock time.
     */
    fun harvest(
        rows: CatalogRowSource,
        deadlineMillis: Long = DEADLINE_MILLIS,
        clock: () -> Long = System::currentTimeMillis,
    ): DuckdbLiveCatalog.Entry? {
        val start = clock()
        fun overBudget() = clock() - start > deadlineMillis

        if (overBudget()) return null
        var version: String? = null
        rows.forEachRow(SQL_VERSION) { col -> version = version ?: col(1)?.trim() }
        val engineVersion = version?.takeIf { it.isNotEmpty() } ?: return null

        if (overBudget()) return null
        val extensions = ArrayList<String>()
        rows.forEachRow(SQL_LOADED_EXTENSIONS) { col -> col(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(extensions::add) }

        if (overBudget()) return null
        val fns = LinkedHashSet<DuckdbLiveCatalog.Fn>()
        rows.forEachRow(SQL_FUNCTIONS) { col ->
            val name = col(1) ?: return@forEachRow
            if (!isCompletableName(name)) return@forEachRow
            fns.add(DuckdbLiveCatalog.Fn(name, kindOf(col(2).orEmpty())))
        }
        if (fns.isEmpty()) return null // an engine with no functions is a broken read, not a truth

        if (overBudget()) return null
        val keywords = LinkedHashSet<String>()
        rows.forEachRow(SQL_KEYWORDS) { col -> col(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { keywords.add(it.uppercase()) } }
        if (keywords.isEmpty()) return null

        return DuckdbLiveCatalog.Entry(
            engineVersion = engineVersion,
            loadedExtensions = extensions.sorted(),
            functions = fns.toList(),
            keywords = keywords,
            harvestedAtMillis = clock(),
        )
    }

    internal fun isCompletableName(name: String): Boolean =
        name.isNotEmpty() && (name.first().isLetter() || name.first() == '_')

    private fun kindOf(raw: String): DuckdbLiveCatalog.Kind = when (raw.trim().lowercase()) {
        "scalar" -> DuckdbLiveCatalog.Kind.SCALAR
        "aggregate" -> DuckdbLiveCatalog.Kind.AGGREGATE
        "window" -> DuckdbLiveCatalog.Kind.WINDOW
        "table" -> DuckdbLiveCatalog.Kind.TABLE
        "macro" -> DuckdbLiveCatalog.Kind.MACRO
        "table_macro" -> DuckdbLiveCatalog.Kind.TABLE_MACRO
        "pragma" -> DuckdbLiveCatalog.Kind.PRAGMA
        else -> DuckdbLiveCatalog.Kind.OTHER
    }
}
