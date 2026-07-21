# DuckDB (Brikk) for DataGrip & IntelliJ IDEA

A dedicated **DuckDB SQL dialect** and ready-to-use data sources for DataGrip and IntelliJ-family
IDEs — for **local DuckDB files** and for **remote DuckDB via [GizmoSQL](https://gizmosql.com)**
(using the Arrow-free `quack-jdbc` driver).

> **Early stage (0.1.x).** The dialect parses modern analytical SQL on a Postgres foundation —
> DuckDB's own parser lineage — and the DuckDB-only syntax surface (`SELECT * EXCLUDE`,
> `QUALIFY`, `GROUP BY ALL`, lambdas, `PIVOT`, FROM-first queries, ...) is being brought up using
> the architecture proven by our
> [SQL Dialect for Apache Doris](https://github.com/sort-dev/doris-intellij-plugin) plugin.

## Why

Stock DataGrip treats DuckDB as a *generic* data source: the Generic SQL dialect, generic JDBC
introspection, and a driver template — no DuckDB grammar at all. Everything that makes DuckDB SQL
pleasant red-flags or breaks statement boundaries. This plugin gives DuckDB the same first-class
treatment we built for Apache Doris:

- **A real dialect** (`DuckDB (Brikk)`), Postgres-based to match DuckDB's parser ancestry, minted
  *alongside* the stock DuckDB support — stock data sources are never touched.
- **Two driver templates, one dialect**: local/embedded (`org.duckdb.DuckDBDriver`) and GizmoSQL
  (`quack-jdbc`, plain JDBC — no Arrow, no `--add-opens` flags). The driver is transport; the
  editing experience is identical.
- **The engine as the authority** (planned): DuckDB itself validates DuckDB SQL — `PREPARE`
  against an in-process instance provides version-exact syntax errors, and
  `duckdb_functions()` / `duckdb_keywords()` provide the completion inventory. No hand-maintained
  grammar drift.

## Status

Seed. Headless test suite covers dialect registration, clean parsing of friendly SQL on the PG
substrate, a **syntax scoreboard** over a DuckDB-only corpus (the honest record of what the
substrate can't yet parse — and the work queue for lenient statement handling), and the
direct-metadata path against an in-process DuckDB.

## Building from source

```bash
./gradlew buildPlugin
# → build/distributions/duckdb-intellij-plugin.zip
```

DataGrip 2026.1 SDK (downloaded automatically), Kotlin 2.3.0, JVM 21. One artifact targets
platform builds 261 and 262.

## License

Apache-2.0. Independent community plugin by Sortdev SRL — not affiliated with DuckDB Labs, the
DuckDB Foundation, or GizmoSQL. "DuckDB" is a trademark of the DuckDB Foundation, used only to
identify the database this plugin supports.
