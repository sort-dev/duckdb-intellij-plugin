# DuckDB support for DataGrip & IntelliJ IDEA

A plugin that adds a real [DuckDB](https://duckdb.org) SQL dialect to DataGrip and IntelliJ IDEA
Ultimate, with ready-to-use connections for embedded DuckDB and remote Quack servers.

> [!IMPORTANT]
> **Not affiliated with DuckDB.** This is an independent plugin by Sortdev SRL. It is not developed,
> endorsed or sponsored by DuckDB Labs, the DuckDB Foundation or GizmoSQL. "DuckDB" and the DuckDB
> logo are trademarks of the DuckDB Foundation, used here only to name the database this plugin
> works with. The logo is used according to the
> [DuckDB design manual](https://duckdb.org/design/manual/).

## Why

The IDE's built-in DuckDB connection uses generic SQL support, which doesn't understand DuckDB
syntax. This plugin adds a full DuckDB dialect and keeps statements, completion and database
objects in sync with your DuckDB version.

## Features

- **DuckDB syntax** including `SELECT * EXCLUDE`, `QUALIFY`, `GROUP BY ALL`, `PIVOT`, `ATTACH`,
  `SUMMARIZE`, FROM-first queries, lambdas, structs and lists.
- **Correct statement boundaries** and run buttons for multi-statement scripts.
- **DuckDB's own error checking** where native validation is available. Checking never runs your
  SQL.
- **Completion** for functions and keywords, based on your DuckDB version and loaded extensions.
- **Database tree** with tables, views, columns, keys and attached databases.
- **Ready-made data sources** for embedded DuckDB and Quack servers. The plugin downloads the JDBC
  driver from Maven Central.
- **DuckDB PIPE (optional).** Write pipe-syntax queries and run them in the normal console. See
  [DuckDB PIPE](#duckdb-pipe).

## Requirements

- DataGrip or IntelliJ IDEA Ultimate, **2026.1 to 2026.3**.

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

### Remote Quack server

Create a **DuckDB via Quack (sort.dev)** data source.

- The default port is `9494`.
- Put the connection token in the **Password** field and leave **User** empty. The IDE keeps the
  token in its secure credential storage.
- Add `tls=true` to the URL parameters for HTTPS endpoints.
- You can also add `tokenEnv`, `tokenFile`, `connectTimeout` or `requestTimeout` as URL parameters.

The connection uses Sortdev's fork of GizmoSQL's `quack-jdbc` driver. The fork adds Quack protocol
v3 support for DuckDB 2.0, which the original driver doesn't have.

## Refreshing completion

Completion refreshes when a connection opens and after `INSTALL` or `LOAD` runs. For autoloaded
extensions, right-click in the editor and choose **Refresh DuckDB Catalog**. Without a connection,
the plugin uses its bundled DuckDB function list.

## DuckDB PIPE

Write queries in [pipe syntax](https://cloud.google.com/bigquery/docs/pipe-syntax-guide) and run
them on DuckDB.

```sql
FROM range(5) AS t(i)
|> WHERE i > 1
|> SELECT i * 2 AS doubled
|> ORDER BY doubled;
```

When you run it, the plugin converts the pipe query to plain DuckDB SQL and sends that to the
database.

**Turn it on** in **Settings → Tools → Database → DuckDB PIPE**. It's off by default.

What you get:

- Right-click → **DuckDB PIPE → Preview Generated SQL** shows the DuckDB SQL before you run
  it.
- Right-click → **DuckDB PIPE → Run Stages up to Caret** runs the pipeline only up to the stage
  at your cursor.
- If the conversion fails, nothing runs. The plugin never sends invalid pipe syntax to the
  database.

## Known limitations

- Stop doesn't cancel a query on a Quack 0.8.0 server. Cancel works for embedded DuckDB.
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
- [SQL Transpiler](https://plugins.jetbrains.com/plugin/32900-sql-transpiler) converts SQL between
  dialects in JetBrains IDEs.
- [Trino Doris connector](https://github.com/brikk/trino-doris-connector) lets Trino query Doris.
- [Trino DuckLake catalog](https://github.com/brikk/ducklake-integrations/tree/main/jvm/trino-ducklake)
  with read and write support.
- [Trino Duckbridge](https://github.com/brikk/duckbridge) gives Trino remote access to DuckDB.

## Feedback

Found a bug or SQL the plugin gets wrong?
[Open an issue](https://github.com/sort-dev/duckdb-intellij-plugin/issues). A small, anonymized SQL
sample helps a lot. Want to build or change the plugin? See
[dev-docs/CONTRIBUTING.md](dev-docs/CONTRIBUTING.md).

## License

Apache License 2.0, see [LICENSE](LICENSE). Copyright 2026 Sortdev SRL. Bundled third-party parts,
including the GizmoSQL `quack-jdbc` credit, are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
