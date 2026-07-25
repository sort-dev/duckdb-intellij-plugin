package dev.sort.duckdb.catalog

/**
 * The completion-time targeting decision, factored OFF the platform model so it is unit-testable
 * with fakes: given the introspected model roots (catalogs) and the dotted path the user is typing
 * (`side_db` for `side_db.<caret>`, `side_db.main` for `side_db.main.<caret>`), resolve the
 * addressed model node and decide whether it is the "enumerated-but-childless" namespace that
 * should be deepened.
 *
 * A namespace is *enumerated-but-childless* when it exists in the model (so the name is real —
 * typo-proof by construction) but has no children loaded yet: a catalog whose schemas were never
 * introspected, or a schema whose tables were never introspected. That is exactly the moment to
 * kick a targeted introspection ([DuckdbAutoIntrospect]); a name that simply does not resolve is
 * mid-typing and must NOT trigger anything.
 *
 * ## The DuckDB-specific rule: paths may be catalog- OR schema-rooted
 *
 * Unlike Trino (no current catalog at all — every path is fully qualified), a DuckDB connection has
 * a current catalog, so `main.<caret>` usually addresses the **schema** `main` of the current
 * catalog while `side_db.<caret>` addresses an ATTACHed **catalog**. Both are one segment. The
 * resolution order is therefore:
 *
 *  1. head names a model root -> catalog-qualified (`cat` / `cat.schema`);
 *  2. else, a single segment naming a schema in EXACTLY ONE root -> schema-relative.
 *
 * Rule 2's uniqueness requirement is what keeps it honest: after `ATTACH`, *every* catalog has a
 * `main` schema, so a bare `main.` is genuinely ambiguous and resolves to nothing rather than
 * deepening the wrong catalog. (`getCatalog()` would disambiguate it, but the Stage-5 truth battery
 * measured it null over the quack wire, so it is not a foundation to build on.)
 *
 * Names in the returned [Deepen] always come from the MODEL, not from what the user typed — DuckDB
 * resolves identifiers case-insensitively, but `TreePattern` matching does not.
 */
object DuckdbNamespacePath {

    /** A model namespace: its name and its currently-loaded children. */
    interface Node {
        val name: String
        fun childNodes(): List<Node>
    }

    /** True when [node] is enumerated but has no children loaded — the deepen trigger. */
    fun isChildless(node: Node): Boolean = node.childNodes().isEmpty()

    /**
     * The introspection target for a resolved-and-childless namespace, or null when nothing should
     * be deepened (path doesn't resolve, is ambiguous, the node already has children, or the path
     * is deeper than a schema — a table's columns come with the table once its schema is deepened).
     */
    fun decideDeepen(roots: List<Node>, parts: List<String>): Deepen? {
        if (parts.isEmpty() || parts.size > 2) return null

        val root = roots.firstOrNull { it.name.equals(parts[0], ignoreCase = true) }
        if (root != null) {
            if (parts.size == 1) {
                return if (isChildless(root)) Deepen(Level.CATALOG, root.name, null, root) else null
            }
            val schema = root.childNodes().firstOrNull { it.name.equals(parts[1], ignoreCase = true) }
                ?: return null
            return if (isChildless(schema)) Deepen(Level.SCHEMA, root.name, schema.name, schema) else null
        }

        // Schema-relative: only a single segment, and only when exactly one catalog offers it.
        if (parts.size != 1) return null
        val hits = roots.mapNotNull { owner ->
            owner.childNodes().firstOrNull { it.name.equals(parts[0], ignoreCase = true) }?.let { owner to it }
        }
        val (owner, schema) = hits.singleOrNull() ?: return null
        return if (isChildless(schema)) Deepen(Level.SCHEMA, owner.name, schema.name, schema) else null
    }

    enum class Level { CATALOG, SCHEMA }

    /** A resolved deepening target: which level, the model's catalog/schema names, and the node. */
    data class Deepen(val level: Level, val catalog: String, val schema: String?, val node: Node)
}
