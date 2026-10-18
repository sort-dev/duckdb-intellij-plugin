# DuckDB PIPE support

Status: implemented for plugin 0.3.0, with remaining work listed below. Reviewed 2026-10-04 against Doris plugin 1.5.3
and the published brikk-sql 0.18.0 artifacts. This supersedes the old plan to
activate PIPE only when SQL Transpiler is installed. Doris's current reference is
`../doris-intellij-plugin/PIPE-EXECUTION-CONTRACT.md`, not its former optional
`<depends>` or four XML `overrides="true"` actions.

Compatibility is part of the feature, not a final packaging step. Doris 1.4.1
fixed binary breaks found by Marketplace on newer 262/263 builds; 1.5.1 replaced
an internal action-registration API with the public dynamic customizer. Its
subsequent 1.5.2/1.5.3 changes also fixed runtime execution and parameter bugs.
See `../doris-intellij-plugin/COMPAT-263.md` for the verifier findings, and
`../doris-intellij-plugin/PIPE-EXECUTION-CONTRACT.md` for action registration.

## Decision and constraints

- Bundle `dev.brikk.house:brikk-sql-jvm:0.18.0` and its matching
  `brikk-sql-metadata-jvm:0.18.0` in the DuckDB plugin's own classloader. Do not
  depend on SQL Transpiler or exchange brikk objects with Doris or that plugin.
  Keep the native DuckDB JDBC engine test-only; the user's driver remains the
  authority for ordinary SQL. Do not bundle brikk-sql-verify or JDBC just for PIPE.
- Default PIPE **off** in each non-default project. Add a workspace-backed Tools
  checkbox, an optional `-Dduckdb.pipes=false` emergency veto, and a setting-change
  reparse/daemon refresh for open DuckDB files. Register actions once, regardless
  of the checkbox. No plugin installation should silently enable PIPE.
- Only a real, unquoted `|>` operator claims automatic execution. `FROM t` and
  `FROM t SELECT ...` are already valid DuckDB SQL. A marker-free `FROM` or
  `SELECT` query prefix is eligible only inside an explicitly claimed
  run-to-stage operation.
- Use `SqlFragment(sql, "duckdb").toExecutable("duckdb", pretty = true)` for
  claimed statements, including nested pipes. `isPipe` detects only top-level
  pipes in 0.18.0, so it is not the sole ownership test. Preserve the result's
  source map. An `UnsupportedError`, nonempty `unsupportedMessages`, invalid
  generated SQL, or incomplete lexical boundary blocks execution. Preview may
  still show the generated SQL with its warnings; never submit raw PIPE as fallback.

The published 0.18.0 binary contains `DuckdbDialect`, `SqlFragment.toExecutable`,
`PipeStageSplitter.split`, and `SqlFragment.stageShapes`. A smoke test of the
published jars transpiled `FROM range(5) AS t(i) |> WHERE i > 1 |> SELECT i * 2 AS
doubled |> ORDER BY doubled` with no unsupported warnings; DuckDB JDBC 1.5.4.0
returned 4, 6, 8. This proves the basic path, not every DuckDB operation.

## Implementation order

1. **Packaging and isolation.** Add the pinned Maven dependency, matching
   metadata, notices, and a ZIP-content check for exactly one engine/metadata
   version and no native JDBC/verifier. Doris 1.5.3 bundles 0.16.0 and SQL
   Transpiler 0.3.0 still pins 0.6.0; test real product classloaders with all
   three installed. Check Kotlin/serialization library compatibility on supported
   IDEs before adopting Doris's transitive exclusions. Do not change this
   plugin's published version or IDE range as part of the plan.
2. **Ownership and statement ranges.** Add a cheap `|>` precheck followed by a
   DuckDB-aware token pass for operator detection and semicolon boundaries;
   quoted strings, comments, dollar quotes, parentheses, malformed/unclosed
   text, and UTF-16 offsets need tests. Reject uncertain PIPE boundaries instead
   of executing a guessed prefix. Feed the same ranges to parser, diagnostics,
   preview, and execution. `DuckdbPsiParser` already has lenient statement
   parsing: route PIPE-bearing statements there while preserving non-PIPE PG
   structure and the existing FROM-first dispatch. Do not rely on its 512-token
   lookahead for arbitrarily long PIPE statements without a fallback.
3. **Cooperative Execute interception.** Follow Doris's
   `DynamicActionConfigurationCustomizer` pattern: capture and wrap the current
   `Console.Jdbc.Execute`, `.2`, `.3`, and `.Selection` actions synchronously,
   expose the captured predecessor via `ActionWithDelegate`, delegate unclaimed
   events exactly once, and detach/restore safely on unload. Preserve the four
   stock option scopes, action presentation, shortcut promotion, and editor
   context. For claimed requests use the platform's statement selection and
   `ScriptModel` execution path, translating only PIPE statements in a mixed
   script and preflighting **all** selected PIPE statements before the first
   request. Preserve named-parameter prompting/substitution, execution tracking,
   cancel/new-tab behavior, and the initiating console. A claimed error or
   failed submission must never call the captured action or stock superclass.
4. **Validate before submitting.** Catch intentional brikk lowering refusals
   and reject all warning-bearing output. Reparse generated SQL as DuckDB and
   reject raw passthrough or residual PIPE syntax. If a safe local DuckDB
   validator is available, check syntax of generated SQL without treating
   binder/catalog errors as syntax errors. Do not require a local JDBC driver
   for PIPE execution against a connected data source, or pretend the parser
   alone proves server-version semantics.
5. **Editor support.** Route PIPE-bearing statement diagnostics to brikk-sql
   instead of `DuckdbEngineValidator`'s raw-SQL `EXPLAIN` path; keep that path
   unchanged for ordinary statements. PIPE diagnostics must work even if a
   local JDBC validator is unavailable, and enabling/disabling the setting
   must invalidate pending annotation results. Add preview and run-to-stage
   actions/intentions, stage-keyword completion, then stage-scoped columns only
   where catalog information exists. The first FROM stage must not hijack
   ordinary DuckDB completion. Map execution errors through the exact source
   map only after confirming DuckDB's reported line/column conventions; if the
   server supplies no trustworthy position, show the generated SQL and error
   without a misleading source squiggle.

## Release gates

- Headless tests for PIPE/no-PIPE ownership, string and comment markers,
  dollar-quoted/quoted semicolons, Unicode, nested PIPE, long statements,
  malformed boundaries, ordinary FROM-first queries, multiple statement
  selections, warnings/refusals, and no raw-PIPE fallback.
- Platform tests on both supported IDE generations, with the transpiler
  absent/present and with Doris installed. Test both action registration
  orders, setting toggles, script options, parameter substitution,
  cancellation, and unmapped versus mapped server errors using real console
  preparation rather than only a fake action.
- Run Plugin Verifier on the **built distribution**, not only compiled classes,
  against the oldest supported 261 SDK and a current 262 DataGrip/IDEA build,
  not just an early 262 EAP. Keep binary/structure problems, missing
  dependencies, override-only and non-extendable API violations fatal; review
  internal/experimental API notices explicitly rather than hiding them in an
  ignored-problems file. Test the actual action registration, shortcut
  promotion, and dynamic unload on those SDKs. Review the Gradle platform
  plugin version before adding the customizer; Doris uses 2.19.0 while this
  repo still uses 2.10.2. Probe newer 263 SDKs before any separate decision to
  extend this plugin's advertised `untilBuild = 262.*`; do not silently widen
  compatibility just because Doris supports 263. Both exact Doris 263 targets,
  IU-263.4732.28 and IU-263.5701.42, passed the 0.3.0 binary verifier as forward
  checks; the declared install range remains 261/262.
- Run the existing DuckDB syntax corpus, validator and completion tests. Keep
  native SQL behavior unchanged when PIPE is off. The ZIP verifier should
  confirm that the user's native DuckDB driver is not bundled, embedded library
  classes meet the Java 21 bytecode ceiling, and notices match the exact JARs.

Remaining design work: establish DuckDB's server error coordinate contract with
local and remote drivers, and supply schema-aware base-column completion.
Neither requires the SQL Transpiler dependency.

## Implementation status in 0.3.0

The plugin now bundles the translator and matching metadata, has a default-off
workspace setting, cooperatively wraps the four Execute actions, and translates
claimed statements through the normal console ScriptModel. Tests exercise real
console preparation, mixed scripts, parameter substitution, original editor
coupling, stage execution, cancellation, diagnostics and classloader isolation.
Generated SQL receives native syntax preflight when a local driver is available.

Preview and run-to-stage actions/intentions, stage-operator completion, and
known prior-stage output aliases are implemented. Exact server-error map-back
and schema-backed base-column completion are deferred. Server errors remain
visible against generated SQL in the standard console output; no guessed
source squiggles are emitted. Arbitrary clean hot unload beneath a peer wrapper
has the same restart limitation as Doris's cooperative execution contract.
