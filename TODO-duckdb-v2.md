# DuckDB 2.0 TODO for the IntelliJ plugin

September 23, 2026. This is the work list for this repository, based on
[DUCKDB-2.0-WATCH.md](DUCKDB-2.0-WATCH.md). DuckDB 2.0 is still in alpha. Keep
the 1.5.5 pins until the final release and the checks below are complete.
The engine's new PEG parser does not replace our IntelliJ `PgParser`.

## First, establish a comparison

- [ ] Keep a small SQL fixture set that runs against pinned DuckDB 1.5.5 and a
  separate 2.0 build. Include window functions such as `lag` and `row_number`,
  `json_set`/`json_remove`, `variant_*`, `lambda x: x + 1`, a legacy `x -> x + 1`
  lambda with and without its opt-in setting, JSON `->`, `SET VARIABLE` with a
  `$name` reference, DML in a CTE, `APPROX NEAREST` joins, nested schemas,
  triggers with transition tables, `CONNECT`/`DISCONNECT`, external-resource
  statements, expression-only statements, and new `COPY` options. Use upstream
  2.0 examples for forms that did not exist in 1.5.5.
- [ ] For each fixture, record four separate results: PSI/run-block boundary,
  completion/highlighting, acceptance by the editor's actual connection, and
  the isolated validator's verdict. Test adjacent statements as well as single
  statements. A green engine parse alone is not enough.
- [ ] Repeat the extension cases with a grammar extension available but inactive,
  then active via `active_grammar_extensions`. Record `version()`,
  `duckdb_grammar_extensions()`, and the active setting from the same session
  that executes the SQL. Do not treat a fresh helper connection as that session.

## Fix catalog information loss

- [ ] Add `WINDOW` to `DuckdbFunctionCatalog.Kind` and give it an appropriate
  completion label and icon in `DuckdbCompletionContributor`. Test against the
  2.0 inventory; do not hardcode the alpha's count of 13 as a final-release
  invariant.
- [ ] Stop discarding additional `(name, function_type)` records in
  `DuckdbCatalogHarvester` and `src/tools/kotlin/dev/sort/duckdb/tools/FunctionCatalogHarvest.kt`.
  Carry kinds through bundled resources, `DuckdbLiveCatalog` persistence, and
  completion without offering indistinguishable duplicate lookup items. Update
  the first-wins tests in `DuckdbLiveHarvestTest` to check a name with multiple
  kinds. Check that a stored catalog still loads after the format change.
- [ ] Confirm the 2.0 live harvest picks up new JSON and VARIANT functions
  without special completion lists. Offline names come from the regenerated
  catalog only when we bump the pinned JDBC engine.

## Make validation honest about session state

- [ ] Compare `DuckdbEngineValidator` on its fresh in-memory JDBC connection
  with the active embedded or Quack console for the same SQL. Record mismatched
  engine versions, loaded extensions, active grammars, macros, and variables.
  `DuckdbEngineLocator` finding a jar does not make that jar the remote server.
- [ ] Give the validator an explicit "cannot verify in this session" outcome
  when its grammar or engine is not known to match the active session. Today a
  missing connection and several skipped statements end up looking `Clean`.
  `DuckdbErrorAnnotator` must not turn an isolated parser rejection into an
  editor error when the session may accept the syntax. Do not execute editor SQL
  to recreate session state.
- [ ] Decide what the UI can truthfully say when no local DuckDB jar exists for
  a Quack-only data source. Revise the README's "engine-exact" claim to match
  tested behavior on both drivers.

## Handle grammar changes without guessing

- [ ] Check whether the IDE can read `active_grammar_extensions` from the same
  console session. `DuckdbCatalogRefresh` opens a helper connection, so its
  answer is not automatically the editor's answer. If we use grammar state for
  completion, highlighting, or validation, keep it session-scoped or mark it
  unknown; do not persist it as a fact about the whole data source.
- [ ] Extend the execution observer's invalidation path for successful
  `SET active_grammar_extensions` and `RESET active_grammar_extensions`.
  `DuckdbInstallLoadDetector` currently notices only `INSTALL`/`LOAD`.
  A `LOAD` alone must not turn on extension grammar in the editor.
- [ ] Check `DuckdbLexer`'s `lambda x: ...` mask and the highlighting of legacy
  `->` lambdas against JSON `->`. Legacy lambda acceptance depends on the
  engine setting. Never label every `->` as an obsolete lambda.
- [ ] Test the new statement forms in `DuckdbPsiParser` and
  `DuckdbStatementBoundaryTest`, especially `WITH ... DELETE`, triggers with
  inner DML, `CONNECT`/`DISCONNECT`, and expression-only statements. Check
  `DuckdbEngineValidator`'s `EXPLAINABLE_HEADS` and known-head classification
  against each form. Add a head only after checking whether `EXPLAIN` accepts
  it; a valid non-explainable head must not get a false red squiggle.
- [ ] Keep `|>` out of the default dialect. Test it only with a real, active
  pipe grammar extension and confirm syntax and execution behavior before
  offering native pipe tokens or changing any Brikk lowering assumptions.
- [ ] Check how `CONNECT` changes the effective engine for later console SQL.
  Do not claim that a local DuckDB parser validates SQL routed to PostgreSQL or
  MySQL.

## Release gate for the engine bump

- [ ] Re-read the final DuckDB 2.0 notes for lambda defaults, storage-format
  changes, grammar-extension behavior, and any differences from the alpha.
  Update this list based on the released engine, not the preview alone.
- [ ] Bump `duckdb_jdbc` in `build.gradle.kts` for tests and tools and the native
  pin in `src/main/resources/config/duckdb-brikk-artifacts.xml` together.
  Run `./gradlew harvestCensus harvestFunctionCatalog harvestExtensionCatalog`
  and review the committed corpus and catalog diffs. The extension harvest
  needs network access.
- [ ] Run `./gradlew test` and `./gradlew buildPlugin`; check the 1.5.5/2.0
  comparison in the IDE on both supported platform builds. Run the native and
  live Quack suites against a matching 2.0 server and driver before changing
  the Quack artifact pin. Re-check JDBC metadata and statement execution over
  Quack; a local `EXPLAIN` does not prove remote behavior.
- [ ] Update the README's pinned-engine, offline catalog, coverage, and Quack
  version statements with measured results. Follow the release decision rule
  in `PLAN.md` before changing the plugin's own version or publishing it.
