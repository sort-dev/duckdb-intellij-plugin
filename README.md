# SQL Dialect for DuckDB (embedded & Quack)

A full **DuckDB SQL dialect** for DataGrip and IntelliJ-family IDEs, with ready-made data sources
for **local/embedded DuckDB** and via the **[Quack protocol](https://duckdb.org/quack/)** (token-auth
`quack-jdbc` driver — plain JDBC, no Arrow).

Part of our SQL-tooling family alongside:

* [**Trino native DuckLake Catalog** with full Read/Write support](https://github.com/brikk/ducklake-integrations/tree/main/jvm/trino-ducklake)
* [**Trino Duckbridge** - remote access to DuckDB via Quack](https://github.com/brikk/duckbridge)
* [**SQL Dialect Transpiler** for various datalake dialects and DataGrip & Jetbrains IDE's](https://plugins.jetbrains.com/plugin/32900-sql-transpiler)
* [**Doris SQL Dialect** for DataGrip & Jetbrains IDE's](https://plugins.jetbrains.com/plugin/32777-sql-dialect-for-apache-doris)
* [**Trino - Doris connector** allowing Trino to access your Doris data](https://github.com/brikk/trino-doris-connector)
* [**Trino SQL Dialect** for DataGrip & Jetbrains IDE's](https://plugins.jetbrains.com/plugin/33124-sql-dialect-for-trino)
* [**brikk-house** - Data engineering platform](https://github.com/brikk/brikk-house) (coming soon)

> **In the IDE, use one of the `DuckDB (sort.dev)` dialects.**

## Why

The built-in DataGrip / Intellij has only a minimal DuckDB dialect — DuckDB connects as a *generic* data source with the
Generic SQL editor. Everything that makes DuckDB SQL worth using red-flags or breaks statement
boundaries: `SELECT * EXCLUDE (…)`, `QUALIFY`, `GROUP BY ALL`, struct/list literals, lambdas,
`PIVOT`, FROM-first queries, `ATTACH`, `SUMMARIZE`. This plugin gives DuckDB the first-class
treatment, using the architecture proven by our other plugins: editing with custom parsers,
validating SQL with the real engine.

## What it does

- **Correct statement & run-block boundaries** for the whole DuckDB statement surface — every
  statement gets a working run box, nothing bleeds into its neighbor.
- **Native parser error checking** uses DuckDB's `json_serialize_sql` in a restricted in-memory
  instance from your data source's driver. SQL is never bound or executed. Unsupported serialization
  is unavailable, so valid non-SELECT statements have no native validation. Parser failures still
  carry the engine's messages; schema resolution stays with the IDE.
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

## PIPE queries

This development build uses brikk-sql 0.20.0-SNAPSHOT and SQL IntelliJ support 0.2.0-SNAPSHOT for PIPE authoring. SQL Transpiler is
not required. Enable **Settings > Tools > Database > DuckDB PIPE > Enable PIPE syntax in
this project**; it defaults off and is saved per workspace.

```sql
FROM range(5) AS t(i)
|> WHERE i > 1
|> SELECT i * 2 AS doubled
|> ORDER BY doubled;
```

Execute uses the normal console and results grid. Right-click **DuckDB PIPE**
or use Alt+Enter to preview generated SQL or run only the stages up to the
caret. Translation errors and unsupported/lossy translations block execution;
they never fall back to sending raw PIPE syntax. Mixed scripts keep ordinary
SQL unchanged. Native FROM-first DuckDB queries do not require this setting.
Stage completion offers operators and known output aliases from previous stages.
Run-to-stage refuses a caret inside a nested pipeline. Execute discovers parameters
through the core parser and substitutes values before lowering, including LIMIT
and configured IDE parameters in literals. It preflights every selected PIPE
before submitting any statement and refuses stale preparation.
Server errors remain in the normal output against the generated SQL; exact
source mapping is not yet enabled for DuckDB server errors.

Only the translator is bundled, not a native database engine. To disable PIPE
globally as an emergency override, use `-Dduckdb.pipes=false`.

## Brikk Kotlin SQL

Since 0.3.1, with the Kotlin K2 plugin installed, a resolved `Sql.duckdb(...)` call inside
`dev.brikk.house.sql.runtime.BrikkSql` activates DuckDB's Brikk editing profile
automatically. The injected language stays `DuckDBSQL`. PIPE editing is enabled
for that SQL host even when the project's PIPE checkbox is off; other files
and Doris calls are unchanged.

Scalar Kotlin interpolations such as `$tenant` and `${tenant}` appear to SQL
inspections as named binds, `:tenant`, while the original Kotlin reference
keeps usages, rename and navigation. `Rel` parameters stay relation slots,
and literal SQL constants are inserted as text. Unsupported expressions or
computed constants get host diagnostics rather than guessed SQL values. JSON
paths, escaped dollars, `trimIndent()` and default `trimMargin()` retain their
source mappings.

This is an inspection view, not runnable SQL. Run the compiled/composed Brikk
relation with its bindings. This plugin's Execute and run-to-stage handlers do
not execute the inspection placeholders. `Rel<T>` input columns now resolve,
complete and navigate through the shared Brikk Kotlin support library, including
inherited trait bounds and available compiler-generated shapes. SQL aliases,
outer-join nullability and supported PIPE stages have statement-local scopes.
Column lookup presentation comes from the shared SQL IntelliJ renderer. It shows
the Kotlin type and nullability with `brikk column` or `relation contract` and the
source alias. Same-named columns from different join aliases stay distinct.
Partial contracts supply known guarantees, not a closed runtime inventory;
unavailable shapes do not invent missing-column errors. Exact SQL types and
generated SQL-column source spans are not supplied by this contract. A DDL
data source still resolves physical tables.

## Connecting to a remote Quack DuckDB server

The remote template prefers `dev.brikk.duckdb:quack-jdbc:0.8.0`; older driver versions remain selectable.

- The **connection token** goes in the Password field (it is stored in the IDE's secure storage and injected as
  the `token=` URL parameter at connect time). While leaving the user field blank.
- **TLS is off by default** (matching the driver). For HTTPS endpoints add `tls=true` in the URL
  parameters. The IDE's generic SSH/SSL tab is not what this driver reads — the `tls` URL parameter
  is the only switch.
- Default port **9494**; `tokenEnv`/`tokenFile`/`connectTimeout`/`requestTimeout` may be appended
  as extra URL parameters.

## Known issues

- **Query cancel over Quack 0.8.0 is a no-op** — Stop returns but the
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

Live function and keyword inventories come from the connected engine. Offline completion uses
version-selected `brikk-sql-metadata`, defaulting to DuckDB 1.5.6. Grammar-only forms remain
available with a live inventory; unloaded extension functions show *"requires \<extension\>"*.

## Requirements

**DataGrip** or **IntelliJ IDEA Ultimate**, **2026.1 through 2026.3** (platform builds 261 through 263, one
artifact for the supported range). A native DuckDB engine for validation comes from your data source's
driver. The built-in PIPE translator is independent of that driver.

## Building from source

```bash
./gradlew buildPlugin   # → build/distributions/duckdb-intellij-plugin-<version>.zip
./gradlew test          # census scoreboard + validator grading + boundary contracts
./gradlew test262 test263 # same 261-compiled plugin on newer SQL and Kotlin APIs
./gradlew verifyDistribution verifyPlugin # ZIP contents, bytecode, IDE API compatibility
# optional: live wire suite against a quack server
./gradlew test -Dquack.live.url='jdbc:quack://localhost:9494?token=<token>'
```

The declared base is `0.4.1`; source builds default to
`0.4.1-dev.<commit time yyyyMMddHHmm UTC>+<12-character SHA>`, with `.dirty` for
uncommitted changes. The ZIP and the IDE's plugin version use the same label.
`-Prelease=true` uses the base unchanged, without publishing or choosing a release
number. IntelliJ sorts the requested dev format above the same bare base, so a
same-base release install needs a manual downgrade/removal.

The canonical conventions repo provides `tools/stage-plugin-builds.sh --dry-run`.
A real staging run builds clean checkouts only and places the five plugins' ZIPs
and `MANIFEST.md` under `/home/jayson/DEV/sortdev/plugin-builds/<yyyyMMdd-HHmm UTC>/`.
It never edits or commits the other plugins' sources.

IntelliJ IDEA Ultimate 2026.1 SDK (auto-downloaded), Kotlin 2.4.10, JVM 21.
The SDK supplies optional Kotlin injection APIs; Kotlin is not required to use
the dialect or PIPE consoles in DataGrip.

The plugin consumes
`dev.brikk.house:brikk-engine-kotlin-intellij-support:0.2.0` from Maven Central.
PIPE editing and execution come from
`dev.brikk.house:brikk-sql-intellij-support:0.2.0-SNAPSHOT`. DuckDB supplies its dialect
profile, session ownership and optional native validation.
The SQL support and test-support snapshots must be published to Maven Local.
Test-support supplies the shared recording console and is never bundled. Neither dependency
bundles an IntelliJ SDK, Kotlin stdlib or compiler plugin.

Maven Local resolution is restricted to Brikk House snapshot coordinates. Core and metadata
use 0.20.0-SNAPSHOT; other dependencies remain released versions.
The earlier 0.3.4 local testing ZIP still contains the previously tested snapshot
until explicitly replaced. Updating source dependencies does not alter an installed ZIP.

**Engine bump checklist** (when moving to a new DuckDB): bump `duckdb-jdbc` in settings.gradle.kts
and the artifact pin in `src/main/resources/config/duckdb-brikk-artifacts.xml`, then run
`./gradlew harvestCensus` and review the corpus changes. Refresh the Brikk metadata dependency
for bundled function, keyword and extension catalogs.

For the upcoming 2.0 release, read [the plugin integration watch note](DUCKDB-2.0-WATCH.md)
before bumping the engine. Grammar extensions and validator session state need more than a catalog
harvest.

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
