# SQL Dialect for DuckDB (embedded & Quack)

A full **DuckDB SQL dialect** for DataGrip and IntelliJ-family IDEs, with ready-made data sources
for **local/embedded DuckDB** and via the **[Quack protocol](https://duckdb.org/quack/)** (token-auth
`quack-jdbc` driver — plain JDBC, no Arrow). 

Part of our SQL-tooling family alongside:

* [**Doris SQL Dialect** for DataGrip & Jetbrains IDE's](https://plugins.jetbrains.com/plugin/32777-sql-dialect-for-apache-doris)
* [**SQL Dialect Transpiler** for DataGrip & Jetbrains IDE's](https://plugins.jetbrains.com/plugin/32900-sql-transpiler)
* [**DuckDB SQL** (embedded+Quack) for DataGrip & Jetbrains IDE's](https://plugins.jetbrains.com/plugin/33098-sql-dialect-for-duckdb-embedded--quack-)
* [**Trino native DuckLake Catalog** with full Read/Write support](https://github.com/brikk/ducklake-integrations/tree/main/jvm/trino-ducklake)
* [**Trino Duckbridge** - remote access to DuckDB via Quack](https://github.com/brikk/duckbridge)
* [**Trino - Doris connector**](https://github.com/brikk/trino-doris-connector)s
* [**brikk-house**](https://github.com/brikk/brikk-house) - Data engineering platform (coming soon)

> **In the IDE, use one of the `DuckDB (sort.dev)` dialects.**

## Why

The built-in DataGrip / Intellij has only a minimal DuckDB dialect — DuckDB connects as a *generic* data source with the
Generic SQL editor. Everything that makes DuckDB SQL worth using red-flags or breaks statement
boundaries: `SELECT * EXCLUDE (…)`, `QUALIFY`, `GROUP BY ALL`, struct/list literals, lambdas,
`PIVOT`, FROM-first queries, `ATTACH`, `SUMMARIZE`. This plugin gives DuckDB the first-class
treatment, using the architecture proven by our other plugins, editing using custom parsers 
yet validating SQL with the real engines.

## What it does

- **Correct statement & run-block boundaries** for the whole DuckDB statement surface — every
  statement gets a working run box, nothing bleeds into its neighbor.
- **Engine-exact error checking**: your SQL is validated by a real DuckDB (`EXPLAIN` against an
  in-memory instance found on your data source's driver — parses and binds, never executes).
  Squiggles carry the engine's own messages, so there is zero grammar drift, ever. Only true
  parser errors are shown; schema resolution stays with the IDE.
- **Completion that follows your engine**: on connect, functions/keywords are harvested live from
  `duckdb_functions()` — your engine version, your loaded extensions — and cached per data source
  (survives restarts and offline sessions). Run `INSTALL`/`LOAD` in a console and completion
  updates itself within seconds; **autoloaded** extensions (used without INSTALL/LOAD) are picked
  up by editor right-click → **Refresh DuckDB Catalog**. With no connection you get the bundled
  snapshot (943 functions at DuckDB 1.5.5) plus *"requires \<extension\>"* hints for known
  extension functions (spatial, inet, fts, sqlite_scanner, postgres_scanner).
- **Object tree from DuckDB's JDBC metadata**: tables, views, columns (STRUCT/LIST spellings),
  PK/FK — and `ATTACH`ed databases appear as catalogs, on both drivers.
- **Two data-source templates, one dialect**: local/embedded DuckDB and Quack remote
  (`jdbc:quack://`, default port 9494). Driver versions are pinned by the plugin and
  auto-download from Maven Central — always matching the engine version the plugin is built and
  tested against (currently **DuckDB 1.5.5**).

## Connecting to a remote Quack DuckDB server

- The **connection token** goes in the Password field (it is stored in the IDE's secure storage and injected as
  the `token=` URL parameter at connect time). While leaving the user field blank.
- **TLS is off by default** (matching the driver). For HTTPS endpoints add `tls=true` in the URL
  parameters. The IDE's generic SSH/SSL tab is not what this driver reads — the `tls` URL parameter
  is the only switch.
- Default port **9494**; `tokenEnv`/`tokenFile`/`connectTimeout`/`requestTimeout` may be appended
  as extra URL parameters.

## Known issues

- **Query cancel over Quack is a no-op** in the current quack driver — Stop returns but the
  query keeps running server-side. Local/embedded cancel works correctly (interrupt in ~300 ms).
  When Quack allows cancellation, we will support it.
- The stock User/Password fields cannot be hidden or relabeled from driver config — hence the
  "leave User blank" rule above. A dedicated token auth panel is planned.
- Two known degraded parse shapes (parsed leniently, no false errors, reduced inner structure):
  suffix-form `UNPIVOT` inside a `SELECT`, and `QUALIFY` after a named `WINDOW` clause.
- Constraint and index *names* are not shown in the tree (DuckDB's JDBC metadata returns them
  as null).

## SQL coverage — measured, not claimed

Coverage is scored in CI against a census sampled from **DuckDB's own test suite**
(`test/sql/` at v1.5.5, via sqllogictest parsing): **226/226 syntax families / 880 statements
parse clean (100%)**, plus a curated corpus of DuckDB idioms. The census re-harvests mechanically
at every engine bump, so coverage is re-proven per DuckDB version, not asserted once.

## Function coverage

Live: whatever your connected engine reports — version- and extension-exact, replaced (never
merged) per connection. Offline: **943 built-in functions** (with kind icons) and **489
keywords** from `duckdb_functions()` / `duckdb_keywords()` of DuckDB 1.5.5, plus a harvested
extension→functions map (189 functions across 5 extensions) powering the
*"requires \<extension\>"* completion hints.

## Requirements

**DataGrip** or **IntelliJ IDEA Ultimate**, **2026.1 or 2026.2** (platform builds 261/262, one
artifact for both). A DuckDB engine for validation comes from your data source's driver —
no bundled engine, no configuration.

## Building from source

```bash
./gradlew buildPlugin   # → build/distributions/duckdb-intellij-plugin.zip
./gradlew test          # census scoreboard + validator grading + boundary contracts
# optional: live wire suite against a quack server
./gradlew test -Dquack.live.url='jdbc:quack://localhost:9494?token=<token>'
```

DataGrip 2026.1 SDK (auto-downloaded), Kotlin 2.4.10, JVM 21.

**Engine bump checklist** (when moving to a new DuckDB): bump `duckdb_jdbc` in build.gradle.kts
and the artifact pin in `config/duckdb-brikk-artifacts.xml`, then re-run all three harvests and
commit their output: `./gradlew harvestCensus harvestFunctionCatalog harvestExtensionCatalog`
(the last needs network for extension INSTALLs).

## Credits

- **[DuckDB](https://duckdb.org)** — the database this plugin exists for, built by DuckDB Labs
  and the DuckDB Foundation community. We are an independent project: not affiliated with them,
  and not them. "DuckDB" and the DuckDB logo are trademarks of the DuckDB Foundation; the logo is
  used unmodified, per the [DuckDB design manual](https://duckdb.org/design/manual/), solely to
  identify the database this plugin supports.
- **[GizmoSQL](https://gizmosql.com)** — the `quack-jdbc` driver, their JDBC port of the Quack
  wire protocol, is their work. The plugin currently ships a brikk-published build of the driver
  (`dev.brikk.duckdb:quack-jdbc`) carrying fixes we are contributing back upstream; we expect to
  return to the upstream artifact.
- Full third-party attributions: THIRD_PARTY_NOTICES.md.

## License

Apache-2.0. Independent community plugin by Sortdev SRL.
