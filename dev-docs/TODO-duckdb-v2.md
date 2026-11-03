# DuckDB 2.0 TODO for the IntelliJ plugin

## Current non-nested release scope

User decision, 2026-11-02: ship the DuckDB 2.0 catch-up without recursive-schema IDE support,
then return to that work after release. Completed core SQL support and existing safe refusals
stay intact. No flat-name substitute or model-replacement spike is part of this release.
The stable 78-item library checklist is `brikk-sql/TODO-duckdb-2.0.md`; its completed audit
items do not by themselves qualify this plugin distribution. The preview checklist below is
historical context, not an instruction to restore the old engine pins or EXPLAIN validation.

### DDB20-067: bounded helper safety fixes

- [x] R3: require the one reviewed autocomplete-extension origin/signature; reject application
  UDFs, unknown metadata and mixed/shadowed registrations before binding or invoking completion.
  Eight focused keyword tests pass, including a real Java UDF with zero bind/init/apply callbacks.
  Registry JSON conversions are system-qualified too; a shadowed public to_json macro leaves
  its sequence sentinel untouched. Missing metadata fields are not treated as trusted NULLs.
- [x] R1: correlate every bound console with its actual runtime even in managed-profile mode;
  retain weak console correlation and session-specific cache identities. Availability is
  rechecked even before a revision notification, so another console cannot bypass routing.
  Five focused real IDE console tests pass. The routing flag is controlled by the regression;
  this does not qualify live remote routing or enable the still-deferred helper modes.
- [x] R2: capture opaque in-memory connection/driver configuration and declared module/file
  identity. Reject changed source properties, working directory, driver descriptors and manual
  module file stamps before helper use and when applying results. Snapshot helper URL/properties
  while retaining the original source/driver handles needed by the shared JDBC process.
  No user reconnect, startup-script replay, transaction change or sensitive value logging.
  Presentation/introspection preferences are not connection identity. The 45-test baseline
  integration run passes without skips, including a replaced private module and genuine native
  PSQL/autocomplete. Driver/data-source copying failed the same-file lock gate and was discarded;
  its failed evidence is retained, not treated as matching-instance proof.
  SDK 263 removed the old single-connection flag/getter. A cached lookup of that public getter
  avoids a binary reference to the removed method; unexpected access failures still fail closed.
  No private fields/APIs or warning suppressions are used. The 263 test fixture drains queued
  console UI callbacks before editor disposal; original assertions/errors remain enforced.
- [x] Qualify these guards with genuine native autocomplete/grammar integration: committed-only
  b17e63d snapshot passes 45 affected tests each on IDE 261/262/263, zero failures/errors/skips.
  Distribution checks pass; Plugin Verifier reports compatibility on DB-261.24374.56,
  IU-262.10968.63, DB-262.10315.132, IU-263.4732.28 and IU-263.7307.44. Each retains the
  existing two deprecated, sixteen experimental and one approved internal usage. Experimental/
  internal reports match the existing 0.5.1 baseline byte-for-byte on the three prior targets.
  Evidence: /tmp/opencode/ddb20-067-fixes/final-committed-gates.log and final-sdk-counts.json.
  This is historical helper-only qualification with the then-pinned libraries. Those runs excluded
  the then-unconfirmed Quack working changes; they do not prove combined Quack 0.12.0 behavior,
  manual installed-plugin smoke, live routing or the next integrated shared-library catch-up.

### Included Quack adoption and combined plugin qualification

- [x] User clarification: Quack JDBC 0.12.0 adoption is intended release content, not excluded
  work. Include the preferred downloadable artifact, test dependency, older selectable choices,
  cancellation/result-kind regressions and current documentation together with the helper fixes.
  Preserve existing URLs, credential templates, settings and project-owned grammar preferences.
  The earlier standalone adoption run passed 38 focused tests; its evidence is separate from
  the committed-only helper runs above: /tmp/opencode/quack-0.12.0-adoption/adoption-evidence.json.
- [x] Qualify the combined current candidate on IDE 261/262/263, including all 45 helper safety
  regressions and the Quack driver/default, result classification and cancellation checks. Use
  isolated signed local fixtures, never user connections. Do not retry mutations or add an executor.
  Completed in the full 2026-11-03 test matrix below, with the newly authorized released pins.
- [x] Verify the combined distribution, packaged ABI/dependencies and platform compatibility
  without increasing Internal/Experimental API risk. The interrupted 0.20.1/0.2.4 qualification
  did not complete; the fresh 0.23.0/0.4.0/Quack 0.12.1 gates below supersede that attempted run.

### Authorized released-library candidate, 2026-11-03

- [x] Adopt the user's released Maven Central versions: SQL/metadata/verifier 0.23.0;
  SQL IntelliJ common support, JDBC adapter and test support 0.4.0; Quack JDBC 0.12.1.
  Retain DuckDB engine/JDBC 2.0.0/2.0.0.0, plugin base 0.5.1 and older selectable drivers.
  These replace the prior qualification pins, not the historical evidence recorded above.
- [x] Run the full configured test suites, including native grammar/Quack fixtures and the
  CI Kotlin-present/absent runtime matrix. IDE 261: 423 tests, 9 skipped; IDE 262/263 without
  Kotlin: 335 each, 9 skipped each; Brikk tests with Kotlin on 262/263: 88 each, no skips.
  Total: 1,242 passed, 27 skipped, zero failures/errors. The skips are external-server opt-ins;
  no user server was contacted. The separately reviewed compiler-plugin fixture and sibling-plugin
  isolation remain the normal CI's existing opt-in exclusions, not silently passing tests.
  Stale assertions were reconciled against the real 2.0 serializer/function registry and published
  support 0.4.0 PSI behavior. The 263 picker regression still invokes the actual public UI method;
  only its test adapter changed, not the plugin's production API usage or safety guards.
- [x] After tests pass, verify the packaged distribution on all five configured IDE targets.
  Compare Internal/Experimental API usage by exact call site with the existing helper baseline;
  compatible verdicts alone do not prove no additional restricted API usage.
  DB-261.24374.56, IU-262.10968.63, DB-262.10315.132, IU-263.4732.28 and IU-263.7307.44 all
  report Compatible, with 2 deprecated / 16 experimental / 1 existing approved internal usage.
  All three usage-report files match the prior helper baseline byte-for-byte on all five targets.
  Distribution, build-logic and release-dependency checks pass. Packaged dependency JARs match
  Maven Central byte-for-byte. Core/metadata/verify API retain Java 17; the native verifier and
  IntelliJ adapters fit the unchanged Java 21 plugin baseline and 261 compile SDK.
  Exact candidate, hashes, commands, full test reports and API comparisons:
  /tmp/opencode/duckdb-023-040-adoption/final-evidence.json.

### Still deferred or separately unqualified

- DDB20-002: recursive IDE schema tree, introspection, editing/navigation and any new model family.
  This is explicitly post-release; retain top-level schemas, including literal dotted names.
- General DDB20-067: exact follow-console, unnamed/named-memory and remote matching helpers.
  They remain unavailable; native SQL execution and ordinary completion are separate features.
- Single-connection isolation, non-local/directory classpath identities, cryptographic binary
  attestation and arbitrary per-session/template/runtime-setting equivalence are not qualified
  by these guards. Unverifiable local module identities fail closed. No new cloning mechanism.
- DDB20-070: a future chunked/native executor and vector/composite ownership implementation.
  Review release-relevant existing client behavior separately; do not start a new executor.
- Installed-plugin smoke, the actual IDE Stop action/separate JDBC helper process, external-server
  JDBC metadata and live routing are not established by the isolated Quack fixture or helper tests.
- The released SQL 0.23.0/support 0.4.0 consumer pins are now explicitly authorized. Their full
  configured plugin test/Verifier qualification and packaged Java/ABI checks passed above. The older
  45-test helper and 38-test Quack runs are retained as history, not relabelled for new dependencies.
  The adoption request did not itself authorize a version bump or publication. The subsequent
  0.6.0 GitHub-release authorization is recorded below; Marketplace upload remains separate.

### 0.6.0 release checkpoint

The user subsequently authorized plugin 0.6.0 and its GitHub release, with the local checkout ready
for the user to run `publishPlugin` from the same release. Marketplace upload is not delegated.
The full tests and five-target API comparison above qualify the production payload; the release
version/notes change must preserve its class bytes and relevant descriptor registrations.
Release preparation lands on main, then `release/0.6.0` runs the repository's artifact-only GitHub
workflow. Preserve existing release artifacts, exact API approvals and all deferred scope above.

## Historical preview checklist (retained)

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
- [ ] Bump `duckdb-jdbc` in `settings.gradle.kts` for tests and the native
  pin in `src/main/resources/config/duckdb-brikk-artifacts.xml` together.
  Run `./gradlew harvestCensus` and review the corpus diffs. Refresh the Brikk
  metadata dependency for bundled function, keyword and extension catalogs.
- [ ] Run `./gradlew test` and `./gradlew buildPlugin`; check the 1.5.5/2.0
  comparison in the IDE on both supported platform builds. Run the native and
  live Quack suites against a matching 2.0 server and driver before changing
  the Quack artifact pin. Re-check JDBC metadata and statement execution over
  Quack; a local `EXPLAIN` does not prove remote behavior.
- [ ] Update the README's pinned-engine, offline catalog, coverage, and Quack
  version statements with measured results. Follow the release decision rule
  in [the archived coverage plan](archive/PLAN.md) before changing the plugin's
  own version or publishing it.
