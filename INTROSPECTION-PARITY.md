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
| Attached databases/catalogs | Yes, JDBC metadata and database-level model | Yes | Model levels retained | All eight metadata tests kept; current completion-trigger tests manually load models. Add a real IDE introspection test. |
| Schemas and catalog filters | Yes | Yes | JDBC contract retained | `DuckdbJdbcMetadataTruthTest` kept; exercise real IDE loading and same-named tables. |
| Native introspector selection | Inherited through the fallback | Explicit generic-JDBC gate | Safer routing; no persistent per-source flag required | `DuckdbIntrospectorRoutingTest`; actual generic introspection still needs end-to-end proof. |
| `USE` and namespace readback | No custom database dialect | Yes, catalog/schema-aware dialect | Added | Real JDBC-process console namespace test exists. |
| Auto-introspect on qualified completion | No custom service/contributor | Yes, enabled | Added | Eight current request/namespace tests, but the refresh is held and models are filled manually. |
| Auto-introspect on object references | No custom implementation | Disabled; no reference trigger registered | Missing requested behavior | `testOutOfScopeReferenceBehaviorStaysOff` explicitly pins the disabled setting. Restore under the new owner requirement. |
| Introspection banner | No custom banner | Registered | Added | Active-only banner/retry tests; need real refresh proof. |
| Live function/keyword harvest | Yes, on connect | Yes, shared service/harvester | Retained, richer signatures and callable kinds | In-process harvest, persisted catalog and real console tests exist. |
| Manual catalog refresh | Yes | Yes | Retained; now session-aware | Editor action and autoload tests use the real JDBC process. Seven baseline refresh tests were removed as named tests. |
| Model refresh after DDL/ATTACH/DETACH | No custom auto-model observer | Shared execution observer registered | Added | Held-refresh tests prove dispatch, not generic JDBC model population. |
| Highlighting restart after refresh | Refresh dispatch existed; no custom namespace reload | Shared auto service restarts the file daemon after successful sync | Added for namespace refresh | Need real loading followed by clean highlighting, not a direct annotator call alone. |
| Cancellation | Driver cancellation test and fail-soft harvesting | Driver cancellation retained; shared runner propagates cancellation | Retained/improved | Driver cancel test unchanged. Old clock/deadline test names were removed; restore equivalent service/runner coverage. |
| Transaction guard | No explicit harvest guard | Shared service refuses harvesting an active transaction | Added | Real JDBC-process commit/rollback and manual/observer guard tests exist. |
| Persistence and corrupt-cache fallback | Yes | Identity-keyed shared store plus legacy migration | Retained, safer connection identity and session separation | Three baseline persistence tests removed as named tests; current migration tests cover much of their behavior. |
| Refresh debounce | Yes, per-key window | Shared queued/coalesced refresh | Mechanism changed | Four baseline deterministic tests removed as named tests; restore equivalent behavior tests. |

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

Coverage requiring restoration or explicit remapping:

| Baseline file | Baseline cases | Audit-start status |
| --- | ---: | --- |
| `DuckdbCatalogRefreshTest.kt` | 7 | File deleted. Current resolver/console tests cover replacement, persistence and failure retention, but not all named text/outcome cases. |
| `DuckdbInstallLoadDetectorTest.kt` | 14 | File deleted. Two current observer-profile tests group most cases; restore all named statement-head cases. |
| `DuckdbLiveCatalogPersistenceTest.kt` | 3 | File deleted. Current legacy/store tests cover round-trip and corrupt fallback; restore all named cases. |
| `DuckdbRefreshDebouncerTest.kt` | 4 | File deleted. Shared service owns coalescing; restore equivalent named behavior tests without reviving a second production debouncer. |
| `DuckdbLiveHarvestTest.kt` | 6 | File retained, every baseline case renamed/replaced. Real harvest and incomplete capture remain; deterministic deadline cases need explicit service coverage. |
| `DuckdbCatalogResolverTest.kt` | 4 | One baseline name kept; live replacement, extension offers and extension-row parsing renamed/replaced. Restore named assertions or document intentional changes. |

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
