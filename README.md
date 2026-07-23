# DuckDB (Brikk) for DataGrip & IntelliJ IDEA

A real **DuckDB SQL dialect** for JetBrains IDEs, with ready-made data sources for **local DuckDB
files** and **[GizmoSQL](https://gizmosql.com) servers** (token-auth `quack-jdbc` driver — plain
JDBC, no Arrow).

## Why

Stock DataGrip has no DuckDB dialect — DuckDB connects as a *generic* data source with the
Generic SQL editor. Everything that makes DuckDB SQL worth using red-flags or breaks statement
boundaries: `SELECT * EXCLUDE (…)`, `QUALIFY`, `GROUP BY ALL`, struct/list literals, lambdas,
`PIVOT`, FROM-first queries, `ATTACH`, `SUMMARIZE`. This plugin gives DuckDB the first-class
treatment, using the architecture proven by our
[SQL Dialect for Apache Doris](https://github.com/sort-dev/doris-intellij-plugin).

## What it does

- **Correct statement & run-block boundaries** for the whole DuckDB statement surface — every
  statement gets a working run box, nothing bleeds into its neighbor.
- **Engine-exact error checking**: your SQL is validated by a real DuckDB (`EXPLAIN` against an
  in-memory instance found on your data source's driver — parses and binds, never executes).
  Squiggles carry the engine's own messages, so there is zero grammar drift, ever. Only true
  parser errors are shown; schema resolution stays with the IDE.
- **Function completion with kinds**: scalar / aggregate / table / macro functions with distinct
  icons and parens-with-caret insertion; table functions (`read_parquet`, …) complete in FROM.
- **Two data-source templates, one dialect**: local/embedded DuckDB and GizmoSQL (`jdbc:quack://`,
  token in the password field → secure storage, default port 9494). Driver versions are pinned by
  the plugin and auto-download from Maven Central — always matching the engine version the plugin
  is built and tested against (currently **DuckDB 1.5.5**).

## SQL coverage — measured, not claimed

Coverage is scored in CI against a census sampled from **DuckDB's own test suite**
(`test/sql/` at v1.5.5, via sqllogictest parsing): **226/226 syntax families / 880 statements
parse clean (100%)**, plus a curated corpus of DuckDB idioms. The census re-harvests mechanically
at every engine bump, so coverage is re-proven per DuckDB version, not asserted once.

Two known degraded shapes (parsed leniently, no false errors, reduced inner structure):
suffix-form `UNPIVOT` inside a `SELECT`, and `QUALIFY` after a named `WINDOW` clause.

## Function coverage — honest status

**943 built-in functions** (with kinds) and **489 keywords**, harvested from
`duckdb_functions()` / `duckdb_keywords()` of DuckDB 1.5.5 itself — regenerated on every engine
bump, never hand-maintained. **Not yet included:** functions from extensions you `INSTALL`/`LOAD`
(e.g. `spatial`); a live per-connection catalog keyed on engine version + loaded extensions is the
next milestone.

## Requirements

**DataGrip** or **IntelliJ IDEA Ultimate**, **2026.1 or 2026.2** (platform builds 261/262, one
artifact for both). A DuckDB engine for validation comes from your data source's driver —
no bundled engine, no configuration.

## Building from source

```bash
./gradlew buildPlugin   # → build/distributions/duckdb-intellij-plugin.zip
./gradlew test          # census scoreboard + validator grading + boundary contracts
```

DataGrip 2026.1 SDK (auto-downloaded), Kotlin 2.3.0, JVM 21.

## License

Apache-2.0. Independent community plugin by Sortdev SRL — not affiliated with DuckDB Labs, the
DuckDB Foundation, or GizmoSQL. "DuckDB" is a trademark of the DuckDB Foundation, used only to
identify the database this plugin supports. See THIRD_PARTY_NOTICES.md.
