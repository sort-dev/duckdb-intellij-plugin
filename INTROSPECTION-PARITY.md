# Introspection parity

## Baseline and scope

Baseline: local `main` at `f2617c61ae242ee9196c38f7b40f63125b65ba0f`.
Audit start: `grand-unification` at `880e00a34a8b63a8c345ca884773fffa13976551`.
This is a source/test inventory first, not proof that a simulated refresh loaded
the IDE model. Real JDBC-process evidence is recorded below as it is obtained.
Scratch databases, reports and driver temporary files live under `build/`.

## Behavior inventory

| Behavior | In baseline? | In current branch? | Same? | Existing evidence and remaining work |
| --- | --- | --- | --- | --- |
| Attached databases/catalogs | Yes, JDBC metadata and database-level model | Yes | Model levels retained | All eight metadata tests kept; real IDE JDBC process loads primary and two attached catalogs. |
| Schemas and catalog filters | Yes | Yes | JDBC contract retained | Metadata tests kept; real loading and same-named tables across catalogs and schemas are exercised. |
| Native introspector selection | Inherited through the fallback | Explicit generic-JDBC gate and source loader | Safer routing | Real generic JDBC sync and source-loader routing tests pass. |
| `USE` and namespace readback | No custom database dialect | Yes, catalog/schema-aware dialect | Added | Real JDBC-process console namespace test exists. |
| Auto-introspect on qualified completion | No custom service/contributor | Yes, enabled | Added | Held routing/retry tests retained; real completion requests now load tables and columns. |
| Auto-introspect on object references | No custom implementation | Enabled and registered | Requested behavior restored | Qualified references request only listed childless namespaces; current-console namespace handles unqualified references. Real JDBC loading is tested. |
| Introspection banner | No custom banner | Registered | Added | Held retry tests plus active/cleared banner around a real reference refresh. |
| Live function/keyword harvest | Yes, on connect | Yes, shared service/harvester | Retained, richer signatures and callable kinds | In-process harvest, persisted catalog and real console tests exist. |
| Manual catalog refresh | Yes | Yes | Retained; now session-aware | Seven baseline names restored; editor action and autoload tests use the real JDBC process. |
| Model refresh after DDL/ATTACH/DETACH | No custom auto-model observer | Shared execution observer registered with table/view/schema rules | Added/fixed | Real CREATE and ALTER update loaded tables/columns; attached list refresh dispatch kept. |
| Highlighting restart after refresh | Refresh dispatch existed; no custom namespace reload | Shared auto service restarts the file daemon after successful sync | Added for namespace refresh | Shared restart call retained; real refresh followed by actual clean highlighting is tested. The light fixture has no live-daemon cancellation event to count. |
| Cancellation | Driver cancellation test and fail-soft harvesting | Driver cancellation retained; shared runner propagates cancellation | Retained/improved | Driver cancel unchanged; both baseline deadline names restored against the shared runner. |
| Transaction guard | No explicit harvest guard | Shared service refuses harvesting an active transaction | Added | Real JDBC-process commit/rollback and manual/observer guard tests exist. |
| Persistence and corrupt-cache fallback | Yes | Identity-keyed shared store plus legacy migration | Retained, safer connection identity and session separation | All three baseline names restored; current migration/session tests also pass. |
| Refresh debounce | Yes, per-key window | Shared queued/coalesced refresh | Mechanism changed | All four baseline deterministic cases restored through the real shared service and scheduler. |

## Baseline tests

Unchanged named tests retained in full:

- `DuckdbJdbcMetadataTruthTest`: catalogs, schemas, tables/catalog filter,
  columns/types/nullability/defaults, primary keys, foreign keys, indexes,
  table/type inventories.
- `DuckdbConsoleTruthTest`: cancellation with reusable connection; result sets
  for summary/describe/pivot/explain; autocommit and rollback.
- `DuckdbPgCatalogEmulationTest`: all four metadata compatibility tests.
- `DuckdbDirectMetadataTest`: function and keyword inventories.
- The live remote-driver harvest and eight live metadata/console tests remain;
  they require an explicitly configured endpoint and are not local-engine proof.

Audit-start gaps and their restoration:

| Baseline file | Baseline cases | Audit-start status | Restoration |
| --- | ---: | --- | --- |
| `DuckdbCatalogRefreshTest.kt` | 7 | File deleted. | All seven names exercise the shared refresh service, outcomes and notification text. |
| `DuckdbInstallLoadDetectorTest.kt` | 14 | File deleted; cases grouped into two tests. | All 14 names exercise the shared statement detector. |
| `DuckdbLiveCatalogPersistenceTest.kt` | 3 | File deleted. | All three names exercise round-trip and corrupt fallback through the shared store. |
| `DuckdbRefreshDebouncerTest.kt` | 4 | File deleted; implementation moved into the shared service. | All four names exercise the shared service with deterministic coroutine time. |
| `DuckdbLiveHarvestTest.kt` | 6 | All six names replaced. | All six restored, including both shared-runner deadline cases. |
| `DuckdbCatalogResolverTest.kt` | 4 | Three names replaced. | All four retained or restored against current resolver/harvest APIs. |

Intentional differences must not be hidden as parity: callable kinds are now
retained separately rather than first-name-wins, unknown future kinds are skipped,
and temporary/session facts are not written into the shared persistent inventory.
These are safer completion/session contracts, not evidence that a missing test
may simply be dropped.

## Findings at audit start

1. Reference-triggered automatic introspection is off. This is a requested missing
   behavior, not a claim that the baseline contained that feature.
2. The pre-unification test names/classes listed above were lost during migration.
   Grouped newer tests are not a complete test-retention record.
3. The auto-introspection tests substitute held refreshes and handwritten model
   updates. They can pass even if the real platform introspector loads no objects.
4. Actual JDBC metadata routing, catalog/schema loading, model completion,
   highlighting and daemon restart must be proven together through the IDE driver
   process before introspection parity is declared restored.

## Validation and remediation log

Inventory-only validation: present 307 tests with nine optional skips, absent 219
with nine optional skips, and isolation two with no skips, on 261/262/263.
Logs/results are under `build/introspection-audit/inventory-*`. No production
behavior changed in this inventory commit. Restoration and real-engine proof
follow in separate commits.

### Restoration step

- Restored every baseline catalog test name: the four removed test classes and
  the renamed harvest/resolver cases now execute against the current shared APIs.
  The refresh tests exercise the real service/interceptor, the debounce tests use
  its public inventory-change path with a deterministic coroutine scheduler, and
  deadline tests check the shared runner rather than a test-only harvest replica.
- Enabled out-of-scope references, registered the shared unresolved-report filter
  and namespace intention, and added the missing reference-trigger annotator.
  Qualified table-reference names go through `requestPath`; it refuses namespaces
  not listed in the model or already loaded. Unqualified references use the shared
  classifier and console namespace. The shared queue/retry/scope/banner owns sync.
- A native unresolved reference can resolve to an inline placeholder. Treating
  every non-null native answer as a real database object suppressed the trigger;
  the shim therefore asks the shared model path resolver directly for qualified
  table references. It does not parse SQL text or load models itself.
- A registered-annotator test queues a reference without invoking completion.
  The held fixture drives the registered annotator outside its strict immutable
  highlighting pass; the real model-changing refresh is asynchronous by design.
- `DuckdbRealIntrospectionTest` now opens actual file data sources through the IDE
  JDBC process, with connection initialization attaching two file databases.
  Namespace listing followed by one-element database refresh loads all three
  catalogs and their schemas. No held executor or handwritten model update is
  used. A namespace-list task alone lists catalogs, not all nested schemas.

Restoration lanes: present 346/nine optional skips, absent 258/nine optional skips,
isolation two/no skips, on 261/262/263. All baseline catalog names are accounted
for; all eight JDBC metadata and three driver/transaction/cancellation tests remain.
No company labels or source-directory scratch artifacts are required.

### Real-engine tests and fixes

`DuckdbRealIntrospectionTest` uses file databases, the IDE JDBC process, actual
console requests and the registered generic introspector. It does not install
table/column snapshots or hold introspection tasks. It checks:

- The primary catalog, two attached catalogs and their schemas, including two
  schemas in the primary catalog.
- Completion-triggered loading of tables, columns and views in an unloaded schema.
- Reference-triggered loading of a different schema, with the active banner
  appearing and then clearing after the real refresh.
- `USE` namespace readback, same-named tables with different columns across
  catalogs and schemas, live macro completion and actual resolve highlighting
  without false errors after loading.
- CREATE and ALTER console requests updating the real table/column model,
  followed by clean highlighting of the new columns.

Three failures in these tests required production fixes:

1. The inherited source loader issued a routine-source query joining
   `pg_language`, which this engine does not provide. Explicit generic source-loader
   registration avoids that query. The routing test covers 261/262/263, including
   the base-class move in 263.
2. The shared statement pack had no table/view/schema DDL namespace effects.
   The plugin adds these effects to the existing detector and observer, rather
   than implementing another refresh path. Detector tests and real CREATE/ALTER
   refreshes reproduce the gap and verify the adapter.
3. After `USE lake_two.sales`, then `USE "primary".audit`, native completion for
   `SELECT e.<caret> FROM events e` retained physical columns of the previous
   catalog. Shared analysis correctly offered `audit_only`, but its completion
   adapter hid only provider light symbols and passed the unmatched physical
   `two_only` through. Clearing database PSI caches did not fix it. A small outer
   completion filter now asks the shared scope analyzer which physical columns
   are visible and drops unmatched physical items only when that scope is fully
   known. It never filters partial/unknown scopes or implements its own SQL parser.
   The same-named-table assertions reproduce and guard this shared-adapter gap.

The default test lane also runs the shared everyday-SQL contract with the standard
model and every applicable feature, including star removal/replacement, lateral
aliases, GROUP BY aliases and UNNEST. The model-state contract installs every
shared model state and runs the external-relation cases. Installed namespaces are
explicitly selected in the data source scope. These are intentionally model-based
contracts, separate from the JDBC-process tests above.

Shared-kit gap: star-removal cases and their completion inputs spell the operation
`* EXCEPT (...)`. The engine requires `* EXCLUDE (...)`. The adapter supplies valid
dialect variants for those cases and leaves native syntax/error checking enabled.
The unadapted contract reproduces false errors in `star-except`,
`star-except-in-cte-with-expression` and `external-except-star-in-cte-of-derived`.
The contract's completion checks require expected names but do not reject extra
names. The real-engine test also asserts absence of other namespaces' unique
columns, which caught the physical-column leak above.

Test boundary: the reference test invokes the registered annotator with the
platform annotation holder, then waits for real asynchronous JDBC/model loading
and runs normal highlighting. Running a model-changing sync inside the light
fixture's immutable `doHighlighting()` pass trips its model-change assertion.
This is not proof of a continuously running editor daemon or a counted daemon
restart. The production shared restart call remains in place.

### Final validation

Using the refreshed local dependency publish from 2026-10-13 20:55, all lanes pass.
Counts include the explicitly skipped remote-endpoint tests.

| Lane | 261 tests / skipped | 262 tests / skipped | 263 tests / skipped |
| --- | ---: | ---: | ---: |
| Optional language plugin present | 350 / 9 | 350 / 9 | 350 / 9 |
| Optional language plugin absent | 262 / 9 | 262 / 9 | 262 / 9 |
| Plugin isolation | 2 / 0 | 2 / 0 | 2 / 0 |

The separate relation-integration selection passes 88 tests on 261. Both census
tests pass on all three IDE generations, with 226/226 families parsing and lexer
restarts matching full lexing. Both shared SQL contract tests pass in the present
and absent lanes: a 37-case everyday pack and a four-case external pack over four
model states, filtered by the explicitly enabled engine features.

The final matrix also runs package building and distribution verification in both
present and absent lanes. Full lane logs and copied test XML are under
`build/introspection-audit/verified-*`; the summary is
`build/introspection-audit/verified-summary.log`.

All 39 baseline catalog test names, including the optional remote-driver case,
were matched against passing-matrix test XML; none are missing. The 38 local
baseline catalog cases execute, and the remote case remains explicitly optional.

## Morning shared-layer refresh

The 2026-10-14 02:58 local dependency publish replaces the plugin's DDL namespace
rules, physical-column completion filter and star-removal syntax rewrite. Those
local copies are removed. Source-loader routing and the reference trigger remain
plugin responsibilities. The shared SQL contract now supplies the engine's
`EXCLUDE` variants and checks forbidden completion names as well as required ones.

After dependency refresh, all seven targeted tests pass: the two real-JDBC tests,
both SQL/model-state contract tests and all three observer-profile tests. The
real-JDBC test still rejects the previous catalog's unique column after `USE` and
proves actual CREATE/ALTER model refresh. Evidence is in
`build/introspection-audit/morning-shared-parity.log`.

### Table-function provider

The relation profile now includes shared `TableFunctionFacts`, reading the active
catalog snapshot alongside physical and optional host-language facts. The shared
table-function pack runs the applicable `generate_series` cases, including joins
and alias stars. All five targeted JDBC/contract tests pass; evidence is in
`build/introspection-audit/morning-table-function-provider.log`.

Native table-function typing is deferred, not reported as complete. A trial of
`RelationTableTypes.ofTableFunction` found no fixed `range` output in the current
metadata, and the native resolve inspection still reports unknown database
function for `read_parquet`, `read_csv` and `range` in the shared DuckDB-specific
pack. Reproducers include `SELECT * FROM read_parquet('data/orders.parquet')` and
`SELECT r.range FROM range(3) AS r`. The trial hook was removed; existing scalar
and table-call PSI typing is retained. `DUCKDB_TABLE_FUNCTIONS` remains disabled
in the contract rather than hiding these reports or claiming those cases pass.
The failing trial log remains under `build/introspection-audit/morning-table-functions.log`.

### Morning matrix and verifier

The corpus presentation matcher now follows the shared label contract: nullable
Kotlin display types may show `?`; SQL types do not, and an unspecified supplied
type leaves a native decorated type intact. No semantic corpus expectation,
known-difference list or fingerprint was changed. Explicit consumer assertions
check SQL labels and one completion per visible column through a CTE chain.

| Lane | 261 tests / skipped | 262 tests / skipped | 263 tests / skipped |
| --- | ---: | ---: | ---: |
| Optional language plugin present | 352 / 9 | 352 / 9 | 352 / 9 |
| Optional language plugin absent | 264 / 9 | 264 / 9 | 264 / 9 |
| Plugin isolation | 2 / 0 | 2 / 0 | 2 / 0 |

Every lane has zero failures. The separate relation selection passes 88 tests.
Its first attempt was interrupted by an external Gradle stop command and passed
unchanged on retry. Logs/XML are under `build/introspection-audit/morning-final-*`
and `build/introspection-audit/morning-brikk-retry-*`.

Census parsing remains 226/226 on all three generations. Both native and injected
relation corpus modes pass all 167 logical cases, retaining 76 recorded native
adapter differences and introducing none.

Plugin verification reports Compatible on DB-261.24374.56, DB-262.10315.132,
IU-262.10968.63, IU-263.4732.28 and IU-263.5701.42. The configured compatibility
gates pass; non-gating deprecated, scheduled-removal, experimental and internal
API notices remain in the reports. Evidence is in
`build/introspection-audit/morning-verifier.log` and `build/reports/pluginVerifier/`.

### Owner handoff since the previous parity build

1. Refreshed the morning shared dependency publish from 2026-10-14 02:58.
2. Removed local DDL rules, the physical-column filter and the EXCLUDE rewrite after real-JDBC proof.
3. Wired shared table-function facts; kept existing native typing and documented the reader/generator gap.
4. Verified unique CTE column suggestions and SQL type labels without Kotlin nullability notation.
5. Passed all IDE/isolation lanes and five verifier targets; package from the clean pushed head, without publishing.
