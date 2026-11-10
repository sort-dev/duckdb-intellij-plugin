# DuckDB SQL for DataGrip and IntelliJ IDEA

A [DuckDB](https://duckdb.org) SQL dialect and connection plugin for DataGrip and IntelliJ IDEA Ultimate.
It adds DuckDB-aware statement boundaries, editing, completion and database browsing, with execution
connections for embedded database files, unnamed in-memory databases and remote Quack protocol v3 servers.
This release targets **DuckDB 2.0.0** and defaults new embedded data sources to **DuckDB JDBC 2.0.0.0**;
JDBC 1.5.5.0 remains selectable.

DuckDB editing combines the plugin's dialect with the bundled Brikk SQL parser and scope analysis. Native
syntax checking uses DuckDB's own parser through the parse-only `json_serialize_sql` function when a local
DuckDB JDBC driver is available; it never prepares, binds or executes the authored statement. On matching
file-backed connections, an isolated helper can reproduce registered custom PEG grammars such as `psql`
for native validation and keyword completion.

For data-source-backed editors, native checking and custom-grammar keyword completion require a matching
file-backed helper. They are not currently available for memory, remote or follow-console modes; those
connection types still support SQL execution.

Optional PIPE syntax is built in. Before execution, the plugin transpiles pipelines into ordinary DuckDB
SQL and checks the generated SQL; when a local DuckDB JDBC driver is available, this includes DuckDB's
parse-only syntax check. The generated statement, not the original pipeline, is sent for execution.

> [!IMPORTANT]
> **Not affiliated with DuckDB.** This is an independent plugin by Sortdev SRL. It is not developed,
> endorsed or sponsored by DuckDB Labs, the DuckDB Foundation or GizmoSQL. "DuckDB" and the DuckDB
> logo are trademarks of the DuckDB Foundation, used here only to name the database this plugin
> works with. The logo is used according to the
> [DuckDB design manual](https://duckdb.org/design/manual/).

## Why a dedicated dialect?

DuckDB has its own SQL syntax, functions and extension grammars. This plugin provides dedicated editing
and connections instead of treating DuckDB as generic PostgreSQL, and uses DuckDB itself for native
parse-only checks when a suitable local driver or matching helper is available.

## Features

- **DuckDB 2.0 connections** for embedded files, unnamed in-memory sessions and Quack v3 servers.
- **Custom SQL grammar plugins.** A per-project, per-source Grammars tab manages registered PEG
  grammars, their activation order and remembered disabled choices.
- **DuckDB syntax** including `SELECT * EXCLUDE`, `QUALIFY`, `GROUP BY ALL`, `PIVOT`, `ATTACH`,
  `SUMMARIZE`, FROM-first queries, lambdas, structs and lists.
- **Correct statement boundaries** and run buttons for multi-statement scripts.
- **Parse-only native error checking** on matching file-backed helpers. Checking never prepares,
  binds or executes your SQL; unsupported serialization or missing state remains unverified/unavailable.
- **Native grammar keyword completion** through explicitly loaded `autocomplete`, independently of
  optional PIPE transpilation. Existing identifier and function completion remains available.
- **Live function/keyword metadata** based on your connected engine and loaded extensions.
- **Database tree** with tables, views, columns, keys and attached databases.
- **Ready-made data sources** for embedded DuckDB and Quack servers. The plugin downloads the JDBC
  driver from Maven Central.
- **Optional PIPE syntax.** Write pipe queries and run them as ordinary DuckDB SQL. The plugin
  transpiles them before execution. See [PIPE syntax](#pipe-syntax).

## 0.7.0 release changes

This release moves to **Brikk SQL 1.0.1** and **SQL IntelliJ common/JDBC/test support 1.0.1**.
Quack JDBC remains **0.12.1** and DuckDB JDBC **2.0.0.0**.

- **SQLGlot 30.29 catch-up.** The bundled parser and generator gain new syntax nodes, nested XOR,
  SHA384/SHA512 handling, weighted-percentile warnings and updated operator precedence.
- **Number/keyword lexing.** `SELECT 13END` now reads as `13` followed by `END`, not the number `13E`.
- **Nested joins in PIPE lowering.** A VALUES alias stays before a nested join, so
  `LEFT JOIN (VALUES ...) t2(b) JOIN t3 ON ... ON ...` lowers to valid DuckDB SQL. DuckDB
  nested-join generation is fixed as well.
- **Brikk fragment checks** report every Brikk SQL error type as an editor diagnostic.

Existing connection settings and grammar preferences are unchanged.

## 0.6.1 release changes

This patch release uses **SQL IntelliJ common/JDBC/test support 0.4.1**. Live-catalog refresh
ordering now prevents late reads from restoring forgotten metadata or overwriting a newer complete
harvest. Failed, incomplete and cancelled reads do not displace the last complete catalog.
Brikk SQL remains **0.23.0**, Quack JDBC **0.12.1** and DuckDB JDBC **2.0.0.0**. Existing connection
settings and grammar preferences are unchanged. The published 0.6.0 assets remain unchanged.

## 0.6.0 release changes

The 0.6.0 release adopted **Brikk SQL 0.23.0**, **SQL IntelliJ support 0.4.0** and **Quack JDBC 0.12.1**.
DuckDB remains pinned to engine **2.0.0 LTS** and official JDBC **2.0.0.0**.

- **Refreshed offline completion.** Functions, overloads, keywords and types come from the DuckDB
  2.0 capture, including its JSON/VARIANT and ICU functions. Window, aggregate, scalar and table-valued
  functions retain their distinct completion kinds; same-named scalar/table functions are not collapsed.
- **Updated shared SQL analysis.** The bundled Brikk parser and scope analysis include DuckDB 2.0
  UPDATE/MERGE, side-effecting CTEs, keyed recursion, NEAREST joins, VARIANT and newer COPY forms.
  These are shared-language capabilities used by Brikk-based analysis/lowering, not a claim that every
  form has full native IDE PSI, typing or introspection coverage. See the
  [shared DuckDB language guide](https://github.com/brikk/brikk-sql/tree/main/brikk-sql#duckdb-20-native-support).
- **Engine-aware builtin types.** Shared IDE support resolves builtin scalar/table functions and
  their return-type contracts without treating ordinary scalar expressions as relation tables.
  Unknown names and genuine INSERT-layout errors still produce diagnostics; unknown reader outputs
  do not become invented column layouts.
- **Safer native checking and completion.** Syntax checks follow the 2.0 parse-only serializer;
  unsupported serialization stays unverified. Autocomplete checks its reviewed extension origin and
  signature. Helper results are rejected when the originating console, connection/driver configuration
  or module identity no longer matches. Checking never prepares or executes authored SQL.
- **Quack cancellation and result handling.** Standard JDBC cancellation works on v3 servers, with
  a bounded control deadline and a closed-session fallback when cancellation cannot be confirmed.
  SELECT/RETURNING columns named `Count` or `rows_affected` remain result sets, distinct from DML
  affected-row counts. Failed result retrieval is not a reason to retry a mutation.

Recursive schema browsing/introspection/editing is **not included**. General memory, remote and
follow-console native-helper matching remains unavailable. Shared SQL support for nested names
does not change those IDE limits, and the historical 1.5.5 parser census is not a full 2.0 IDE census.

## Requirements

- DataGrip or IntelliJ IDEA Ultimate, **2026.2 to 2026.3**.
- DuckDB JDBC **2.0.0.0** for the new grammar-profile/native-keyword features. Existing data sources
  may need their driver files upgraded; the older 1.5.5.0 artifact remains selectable.

## Install

**From the JetBrains Marketplace (recommended).** Open **Settings → Plugins → Marketplace**, search
for *SQL Dialect for DuckDB (embedded & Quack)*, install it and restart the IDE.

**From a file.** Download the zip from the
[Releases](https://github.com/sort-dev/duckdb-intellij-plugin/releases) page, then use
**Settings → Plugins → ⚙ → Install Plugin from Disk…** and restart.

## Getting started

Add a data source and pick one of the two types below. Both use the **DuckDB (sort.dev)** dialect.

### Embedded DuckDB

Create a **DuckDB Embedded (sort.dev)** data source. Choose a database file, or pick the in-memory
URL template for a temporary database.
The unnamed in-memory URL is `jdbc:duckdb:`. It supports SQL execution and live grammar activation,
but the plugin cannot currently open a matching isolated helper for native editor validation/completion.

### Remote Quack server

Create a **DuckDB via Quack (sort.dev)** data source.

- The default port is `9494`.
- Put the connection token in the **Password** field and leave **User** empty. The IDE keeps the
  token in its secure credential storage.
- Add `tls=true` to the URL parameters for HTTPS endpoints.
- You can also add `tokenEnv`, `tokenFile`, `connectTimeout` or `requestTimeout` as URL parameters.

The connection uses Sortdev's fork of GizmoSQL's `quack-jdbc` driver. The fork adds Quack protocol
v3 support for DuckDB 2.0, which the original driver doesn't have.

The preferred Quack JDBC driver is **0.12.1**. It supports `Statement.cancel()` on Quack v3 servers.
Existing sources may need their driver files upgraded under **Data Sources and Drivers > Drivers >
DuckDB via Quack > Driver files**. Older driver choices remain available; URLs, credentials and grammar
preferences are not migrated. Cancellation control requests have a two-second deadline. If the server
cannot confirm cancellation, the driver closes the session
rather than claiming success. This does not enable remote native grammar validation or completion.

## Refreshing completion

Completion refreshes when a connection opens and after `INSTALL` or `LOAD` runs. For autoloaded
extensions, right-click in the editor and choose **Refresh DuckDB Catalog**. Without a connection,
the plugin uses its bundled DuckDB function list.

## Custom SQL grammar plugins and profiles

DuckDB 2.0 exposes registered grammar names separately from installed extension modules.
New embedded drivers default to JDBC 2.0.0.0. For an existing data source, select that version
under **Data Sources and Drivers > Drivers > DuckDB Embedded > Driver files**, download it,
and reconnect. JDBC 1.5.5.0 remains selectable but does not expose these grammar-profile APIs.
The historical 1.5.5 parser census has not been rerun in full against 2.0.
Open **Data Sources and Drivers**, select the DuckDB data source, and open its **Grammars** tab.
The profile belongs to that source in the current project, not every project using the same source UUID.
Enable grammars with the table's checkboxes and use the toolbar to change their order.
Newly discovered registered names start enabled, appended in alphabetical order after your current
activation order. Unchecked names are remembered and stay disabled on later scans. Missing configured names stay visible
as unavailable. Names may also be entered before connecting. The plugin never infers a module
name, installs an extension, or replays startup SQL to make a grammar available.

**Discover grammars** reads an existing console of this exact source on its engine thread. It never
opens a connection, asks for a password, or queries a console inside a transaction. Connect the source
and run a query first. Editing the source address while discovery is pending discards that result.
The tab distinguishes the managed enabled order, registered availability, and console activation.
Use **Apply grammar changes** to save and activate the selection, or **Reset changes** to discard
pending edits. These preferences live in project storage, not the connection configuration.
Native connection-dialog Apply/OK and Test Connection never save grammar edits or reconnect sessions
because of them. Closing without the grammar Apply discards pending edits; native Cancel cannot undo
grammar changes you already explicitly applied.

The managed profile applies to new user and helper connections and updates existing idle consoles
with `SET active_grammar_extensions`, without reconnecting or replaying scripts. Busy, transactional,
routed or unsupported sessions show pending changes. Safe pending sessions retry after their next
completed request. Ordinary SQL files use the managed profile. File-backed validation opens an
isolated helper, checks required names, applies the exact activation order and reads it back
before calling DuckDB's parse-only serializer. Failed initialization means unavailable validation,
not rejection by an unrelated base parser.

Console menu actions show and refresh grammar state, or explicitly adopt the console's effective
order as the managed profile. **Follow Console DuckDB Grammars** snapshots that console without
changing its transaction or settings, but validation in this mode is currently unavailable because
the existing IDE APIs do not provide a matching isolated connection. In-memory and remote validation
are unavailable for the same reason. No fresh unnamed memory database substitutes for the source.

Discovery runs on connect, after successful `LOAD`, `SET`, `RESET`, `CONNECT` or `DISCONNECT`,
and on correlated IDE introspection requests. Use **Refresh DuckDB Grammar State** for an explicit
console refresh. Discovery skips open console transactions. Unknown or uncorrelated IDE metadata
refreshes invalidate diagnostics but cannot guarantee grammar rediscovery. Runtime facts disappear
on disconnect; managed configuration persists. Unsupported discovery is shown explicitly.

With the signed community `psql` grammar loaded and enabled, managed helpers accept original pipe SQL,
reject malformed pipe SQL and leave the source console's activation unchanged. They can parse a
sequence-backed table function without binding or executing it. This native grammar path works with
**DuckDB PIPE** off and does not transpile your SQL.

## Native grammar keyword completion

On a connected file-backed DuckDB 2.0 source, explicitly load `autocomplete` in its console:

```sql
-- INSTALL autocomplete;  -- only if you choose to install it
LOAD autocomplete;
```

With the signed `psql` grammar loaded and enabled, native keyword completion offers stages such as
`WHERE`, `SELECT` and `ORDER` in original pipe SQL. This works with **DuckDB PIPE off** and never
lowers your SQL. Existing identifier and function completion remains in place. If autocomplete is
not registered, an explicit completion request shows a notification with `LOAD autocomplete`, even
when the lookup has no items. Automatic popup requests do not produce notifications. The plugin
never invokes the missing function to trigger DuckDB autoload or installation. The **Grammars** tab
always shows the native completion mode/status, and the console menu's **Check Native Keyword
Completion** diagnoses the matching helper without running autocomplete on the live console.
**Show DuckDB Grammar State** also includes this status.

Only the current statement prefix up to the caret is passed as data to the qualified builtin, on an
isolated helper with the exact managed grammar order. No authored statement is prepared, bound or
executed. File suggestions are disabled before the filesystem provider runs. Helpers with foreign
attached catalog types are excluded because catalog metadata scans could otherwise contact remote
systems. Native completion currently returns keyword rows only.

Native completion currently declines injected SQL, oversized prefixes, non-ASCII whitespace such as
NBSP/em space, and unverified replacement offsets. Those cases keep existing completion rather than
risk corrupting text. Memory, remote, routed and follow-console helpers retain the grammar-profile
limitations above. Requests are cancellable and bounded; stale document/profile results are dropped.
In particular, `jdbc:duckdb:` is an unnamed in-memory source. Loading psql or autocomplete does not
remove that helper limitation; its status is distinct from a missing autocomplete module on a file source.

## PIPE syntax

Write queries in [pipe syntax](https://cloud.google.com/bigquery/docs/pipe-syntax-guide) and run
them as ordinary DuckDB SQL. This is client-side transpilation: your embedded engine or server does
not need a PIPE grammar for this feature.

```sql
FROM range(5) AS t(i)
|> WHERE i > 1
|> SELECT i * 2 AS doubled
|> ORDER BY doubled;
```

After normal console parameter substitution and before submission, the plugin transpiles each claimed
pipeline into DuckDB SQL. The bundled Brikk translator refuses unsupported conversions and tokenizes the
generated SQL to ensure no PIPE syntax remains. When a local DuckDB JDBC driver is available, the plugin
also checks the generated SQL with DuckDB's parse-only serializer before submission. A native parser
rejection blocks execution; unsupported serialization remains unverified. This check does not prepare,
bind or execute the generated query. Remote PIPE execution does not require a local driver.

**Turn it on** in **Settings → Tools → Database → DuckDB PIPE**. It's off by default and set per project.
No SQL Transpiler plugin is required. Ordinary FROM-first DuckDB queries are unchanged.

What you get:

- Right-click → **DuckDB PIPE → Preview Generated SQL** shows the DuckDB SQL before you run it.
- Right-click → **DuckDB PIPE → Run Stages up to Caret** runs the pipeline only up to the stage at your
  cursor.
- If transpilation fails or an available native parser rejects the generated SQL, execution is blocked.
  Raw PIPE is never submitted as a fallback.

## Known limitations

- Remote cancellation requires Quack JDBC 0.11.0 and a Quack v3 server. The older 0.8.0 driver ignores cancellation.
- Quack JDBC does not enforce `Statement.setQueryTimeout`. Its `requestTimeout` URL parameter bounds
  individual HTTP requests, not the total lifetime of a query or proof of server-side cancellation.
- The Quack data source shows a **User** field it doesn't use.
- Named constraints and indexes may be missing from the database tree, because DuckDB's JDBC
  driver doesn't report their names.

## Related plugins and tools

- [Apache Doris SQL](https://plugins.jetbrains.com/plugin/32777-sql-dialect-for-apache-doris)
  dialect for JetBrains IDEs.
- [ClickHouse SQL](https://github.com/sort-dev/clickhouse-intellij-plugin) dialect for JetBrains
  IDEs.
- [Trino SQL](https://plugins.jetbrains.com/plugin/33124-sql-dialect-for-trino) dialect for
  JetBrains IDEs.
- [DataFusion and Spice.ai SQL](https://github.com/sort-dev/datafusion-spice-intellij-plugin)
  dialect for JetBrains IDEs.
- [SQL Transpiler](https://plugins.jetbrains.com/plugin/32900-sql-transpiler) translates SQL between
  supported database dialects in JetBrains IDEs.
- [Trino Doris connector](https://github.com/brikk/trino-doris-connector) lets Trino query Doris.
- [Trino DuckLake catalog](https://github.com/brikk/ducklake-integrations/tree/main/jvm/trino-ducklake)
  with read and write support.
- [Trino Duckbridge](https://github.com/brikk/duckbridge) gives Trino remote access to DuckDB.

## Feedback

Found a bug or SQL the plugin gets wrong?
[Open an issue](https://github.com/sort-dev/duckdb-intellij-plugin/issues). A small, anonymized SQL
sample helps a lot.

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE). Copyright 2026 Sortdev SRL. Bundled
third-party parts, including the GizmoSQL `quack-jdbc` credit, are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
