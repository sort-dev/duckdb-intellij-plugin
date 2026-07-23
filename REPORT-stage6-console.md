# Stage 6 — Console Truth Battery

Measured 2026-07-23. Native = duckdb_jdbc 1.5.5.0 in-process (:memory:); Quack = quack-jdbc 0.4.0
over a live GizmoSQL server (engine DuckDB v1.5.3). Pinned by `DuckdbConsoleTruthTest` (native) and
`QuackLiveTruthTest` (wire). Live quack tests are gated on `-Dquack.live.url` and Assume-out
otherwise, so the committed suite stays offline.

## Cancel verdict per driver

- **Native: WORKS.** A ~1.6e11-row cross join is aborted by `Statement.cancel()` from another thread:
  raises `java.sql.SQLTimeoutException("INTERRUPT Error: Interrupted!")` in ~300ms, and the
  **connection remains usable** afterward (`SELECT 42` -> 42 on a fresh statement). Prompt, clean,
  connection-preserving — exactly what a console cancel button needs.
- **Quack: NO-OP (not tested over the wire, by design).** `QuackStatement.cancel()` disassembles to a
  literal `return` (overriding the skeletal `throw notSupported`), so cancel silently does nothing —
  a running GizmoSQL query cannot be cancelled through the driver. **Driver-team item.**

## Grid-rendering verdicts (executeQuery -> non-empty ResultSet)

| Statement | Native | Quack (wire) |
|---|---|---|
| SUMMARIZE t | 12 cols, rows | renders (12 cols) |
| DESCRIBE t | 6 cols, rows | renders (6 cols) |
| PIVOT ... | cols per pivot, rows | not run over wire (native covers it) |
| EXPLAIN SELECT | `explain_key`,`explain_value`, 1 row | renders |
| EXPLAIN ANALYZE | 1 row | n/a |
| SHOW TABLES / CALL pragma_version() | render | n/a |

All grid-shaped statements return a real ResultSet with >=1 column and >=1 row, so the grid renders.

## Transaction facts

- **autocommit default = true** (native and quack).
- **BEGIN / INSERT / ROLLBACK issued as plain `execute()` statements round-trips** (count 3->4->3
  native; 2->3->2 quack). A console submitting transaction-control as SQL text behaves correctly on
  both drivers.
- Native quirk (informational): a statement that errors leaves a stale "pending query result" on the
  DuckDB connection; the *next* query on that same connection can fail with "Statement was closed",
  but a fresh `createStatement()` recovers. Consoles should mint a new statement after an error
  (they already do).

## Driver-team item list (quack-jdbc)

1. **`cancel()` is a no-op** (bytecode: literal `return`). No way to cancel a running query over the
   wire. Highest-impact console gap.
2. **`getDatabaseMetaData().getDriverVersion()` reports `0.1`** while the jar is 0.4.0 — cosmetic
   version mismatch.
3. **`connection.getCatalog()` returns null** (native returns the current db name). Tree/console code
   must derive the catalog from `getCatalogs()`/the ATTACH alias, not from `getCatalog()`.

## Server recipe (standing ask)

    docker run -d --name quack -p 9494:9494 -e QUACK_TOKEN=<token> brikk-ducklake-quack-server:latest

Run the live suite against it:

    ./gradlew test -Dquack.live.url='jdbc:quack://localhost:9494?token=<token>'
