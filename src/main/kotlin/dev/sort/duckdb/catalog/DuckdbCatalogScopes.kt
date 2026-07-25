package dev.sort.duckdb.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternNode

/**
 * Introspection-scope vocabulary for DuckDB (Brikk) data sources — the TreePattern building blocks
 * [DuckdbAutoIntrospect] unions into a data source's `introspectionScope`. Ported from the sibling
 * trino/doris plugins; the vocabulary is identical because DuckDB sits on the same PG family model:
 * **catalog = [ObjectKind.DATABASE], schema = [ObjectKind.SCHEMA], table = [ObjectKind.TABLE]**
 * (Stage-5 truth battery: the generic JDBC introspector surfaces the primary database AND every
 * ATTACHed one as a catalog via `getCatalogs()`, over the native driver and the quack wire alike).
 *
 * ## Why the shapes are one level at a time
 *
 * A SELECTED scope node causes its DIRECT CHILDREN to load — a leaf `DATABASE(cat)` loads that
 * catalog's schemas but no tables; a `DATABASE(cat) → SCHEMA(sch)` loads that schema's tables. So a
 * catalog deepen must be a bare `DATABASE(cat)` leaf, never a `SCHEMA(*)` group (which selects every
 * schema and pulls all their tables). This matters far more for DuckDB than the file-on-disk case
 * suggests: `ATTACH 'ducklake:…'`, `ATTACH 'postgres:…'` and friends put arbitrarily large remote
 * catalogs one keystroke away.
 *
 * The scope union is for PERSISTENCE (freshly-enumerated nodes stay in scope instead of being
 * pruned on the next sync); the actual loading is bounded by a TARGETED one-element refresh
 * ([DuckdbIntrospectionTasks.oneElementRefresh]), never a scope-wide sync.
 */
object DuckdbCatalogScopes {

    /** A leaf pattern node matching exactly [name] at its level. */
    private fun namedLeaf(name: String): TreePatternNode =
        TreePatternNode(TreePatternNode.PositiveNaming(ObjectName.plain(name)), TreePatternNode.NO_GROUPS)

    /**
     * **Catalog deepening (`side_db.` → its schemas):** select exactly [catalog] as a DATABASE leaf
     * so a targeted refresh of the catalog node loads its schemas (its direct children) and they
     * persist — `DATABASE(catalog)`, NO schema group.
     */
    fun catalogSchemasScope(catalog: String): TreePattern =
        TreePattern(TreePatternNode.Group(ObjectKind.DATABASE, arrayOf(namedLeaf(catalog))))

    /**
     * **Schema deepening (`side_db.main.` → its tables):** select exactly [catalog].[schema] so a
     * targeted refresh of the schema node loads its tables/columns —
     * `DATABASE(catalog) → SCHEMA(schema)` (leaf schema).
     *
     * [catalog] may be null when a schema was resolved without an explicit catalog segment; then the
     * SCHEMA group is rooted directly (`SCHEMA(schema)`), matching doris.
     */
    fun schemaTablesScope(catalog: String?, schema: String): TreePattern {
        val schemaLeaf = namedLeaf(schema)
        return if (catalog != null) {
            TreePattern(
                TreePatternNode.Group(
                    ObjectKind.DATABASE,
                    arrayOf(
                        TreePatternNode(
                            TreePatternNode.PositiveNaming(ObjectName.plain(catalog)),
                            arrayOf(TreePatternNode.Group(ObjectKind.SCHEMA, arrayOf(schemaLeaf))),
                        ),
                    ),
                ),
            )
        } else {
            TreePattern(TreePatternNode.Group(ObjectKind.SCHEMA, arrayOf(schemaLeaf)))
        }
    }
}
