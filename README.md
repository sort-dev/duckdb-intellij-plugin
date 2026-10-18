# SQL Dialect for DuckDB

A DuckDB SQL plugin for DataGrip and IntelliJ IDEA Ultimate. It adds a proper DuckDB
editor and ready-to-use connections for embedded DuckDB and remote Quack servers.

JetBrains' built-in DuckDB connection uses generic SQL support. This plugin understands
DuckDB syntax and keeps statements, completion, and database objects in sync with DuckDB.

Use the `DuckDB (sort.dev)` dialect and data sources after installing the plugin.

## Requirements

- DataGrip or IntelliJ IDEA Ultimate
- IDE versions 2026.1 through 2026.3

## Install

1. Open **Settings > Plugins > Marketplace**.
2. Search for **SQL Dialect for DuckDB (embedded & Quack)**.
3. Install the plugin and restart the IDE if prompted.
4. Add either **DuckDB Embedded (sort.dev)** or **DuckDB via Quack (sort.dev)** as a data source.

The plugin downloads the required JDBC driver from Maven Central.

## What you get

- DuckDB syntax support for `SELECT * EXCLUDE`, `QUALIFY`, `GROUP BY ALL`, `PIVOT`,
  `ATTACH`, `SUMMARIZE`, FROM-first queries, lambdas, structs, lists, and other DuckDB SQL.
- Correct statement boundaries and run buttons for multi-statement scripts.
- Parser errors from DuckDB itself where native validation is available. Validation does
  not execute your SQL.
- Function and keyword completion based on the connected DuckDB version and loaded extensions.
- Tables, views, columns, keys, and attached databases in the database tree.
- Optional PIPE queries that run in the normal console and results grid.

## Embedded DuckDB

Create a **DuckDB Embedded (sort.dev)** data source. Choose a database file, or select
the in-memory URL template for a temporary database.

## Remote Quack connection

Create a **DuckDB via Quack (sort.dev)** data source.

- The default port is `9494`.
- Put the connection token in the **Password** field.
- Leave the **User** field empty.
- Add `tls=true` to the URL parameters for HTTPS endpoints.
- You can also add `tokenEnv`, `tokenFile`, `connectTimeout`, or `requestTimeout` as URL parameters.

The IDE stores a token entered in the Password field in its secure credential storage.

## PIPE queries

PIPE syntax is off by default. Enable it under **Settings > Tools > Database > DuckDB PIPE**.

```sql
FROM range(5) AS t(i)
|> WHERE i > 1
|> SELECT i * 2 AS doubled
|> ORDER BY doubled;
```

Use **Preview Generated SQL** to inspect the DuckDB SQL before running it. **Run Stages
up to Caret** executes only the pipeline through the current stage. Translation errors
block execution instead of sending invalid PIPE syntax to the database.

## Refresh completion

Completion refreshes after a connection opens and after `INSTALL` or `LOAD` runs. For
autoloaded extensions, right-click in the editor and choose **Refresh DuckDB Catalog**.

When no connection is available, the plugin uses its bundled DuckDB function catalog.

## Known limitations

- Stopping a query over Quack 0.8.0 does not cancel it on the server. Embedded DuckDB
  cancellation works.
- The Quack data source uses the standard User and Password fields, so the unused User
  field remains visible.
- Named constraints and indexes may not appear in the database tree because DuckDB JDBC
  metadata does not return their names.

## Credits

- [DuckDB](https://duckdb.org) is developed by DuckDB Labs and the DuckDB Foundation
  community. This plugin is an independent project and is not affiliated with them.
  "DuckDB" and the DuckDB logo are trademarks of the DuckDB Foundation. The logo is used
  according to the [DuckDB design manual](https://duckdb.org/design/manual/).
- [GizmoSQL](https://gizmosql.com) created the `quack-jdbc` driver, a JDBC implementation
  of the Quack wire protocol.
- See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for full third-party attributions.

## License

Apache-2.0. Independent community plugin by Sortdev SRL.
