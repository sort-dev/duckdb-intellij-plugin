package dev.sort.duckdb.catalog

/**
 * Cheap statement-head scan for executed SQL that changes the CATALOG LIST — `ATTACH` / `DETACH`
 * as a statement's first meaningful word. The scanning discipline lives in [DuckdbStatementHeads];
 * the function-inventory sibling is [DuckdbInstallLoadDetector].
 *
 * ## Why exactly these two, and why the platform doesn't cover them
 *
 * DuckDB's `ATTACH` adds a whole database as a new **catalog** (measured, driver-level: an ATTACH
 * on one connection is visible to every other connection of the same instance, including ones
 * opened before it and fresh ones — so a model refresh over the data source's pool *does* see it).
 * The IDE's own post-execution auto-sync cannot: `JdbcConsole` builds either
 * `LoaderContext.selectTasks(...)` from the statement's impactees or a *path-based* context from
 * the console's search path — both address elements that are ALREADY in the model. A catalog that
 * did not exist a second ago is in neither, so nothing re-lists it. `DETACH` is the mirror case
 * (the tree keeps showing a catalog that is gone).
 *
 * Deliberately NOT matched:
 *  - `USE <catalog>` — changes the session's search path, not the set of catalogs; the platform
 *    tracks the search path itself (`DataRequest.Context.getSearchPath`).
 *  - `CREATE`/`DROP SCHEMA|TABLE|VIEW` — ordinary DDL the platform's impactee-driven auto-sync
 *    already handles on our PG-substrate PSI; re-listing namespaces for those would be duplicate
 *    work on every DDL statement.
 */
object DuckdbAttachDetector {

    /** True when [script] contains a statement whose head is ATTACH or DETACH. */
    fun triggersNamespaceChange(script: String?): Boolean {
        if (script.isNullOrBlank()) return false
        return DuckdbStatementHeads.statements(script).any { statement ->
            val head = DuckdbStatementHeads.headWords(statement, 1).firstOrNull() ?: return@any false
            head.equals("ATTACH", ignoreCase = true) || head.equals("DETACH", ignoreCase = true)
        }
    }
}
