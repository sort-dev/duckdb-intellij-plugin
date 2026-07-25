package dev.sort.duckdb.catalog;

import com.intellij.database.introspection.IntrospectionTask;
import com.intellij.database.introspection.IntrospectionTasks;
import com.intellij.database.model.basic.BasicElement;

/**
 * Java shim for {@link IntrospectionTasks} — the factory is Kotlin-{@code internal} in metadata
 * (public in bytecode), so Kotlin sources cannot reference it while Java can (the same technique as
 * {@link dev.sort.duckdb.PgModelAccess} and the sibling doris/trino plugins).
 *
 * <p>{@code DuckdbAutoIntrospect} uses this to build the TARGETED one-element refresh — the doris
 * round-18 lesson: a general/scope task re-introspects the ENTIRE widened scope and hangs on slow
 * externals. For DuckDB that risk arrives with {@code ATTACH 'ducklake:…'} / {@code 'postgres:…'},
 * where a single ATTACHed catalog can front an arbitrarily large remote database.
 */
public final class DuckdbIntrospectionTasks {
    private DuckdbIntrospectionTasks() {}

    /** One-element (one-level) refresh of exactly {@code element} — never a scope-wide sync. */
    public static IntrospectionTask oneElementRefresh(String dataSourceId, BasicElement element) {
        return IntrospectionTasks.prepareOneElementRefreshTask(dataSourceId, element);
    }
}
