# DuckDB shared SQL IntelliJ support backport

This backport follows Trino's `HANDOFF-intellij-shared-extraction-duckdb.md`.
The backport was originally validated as 0.3.3. The current dogfood version is 0.3.5.
Its current dependency pins are Maven Central releases:

- `dev.brikk.house:brikk-sql-intellij-support:0.1.1`
- `dev.brikk.house:brikk-sql-jvm:0.19.1` and its metadata dependency
- `dev.brikk.house:brikk-engine-kotlin-intellij-support:0.2.0`

The initial integration and snapshot sections below are historical. The formal
release transition at the end records the current build and dependency audit.

DuckDB does not consume brikk-sql oracle or verify. Neither is bundled.
No helper, core, peer-plugin or Marketplace publication is part of this work.

## Ownership

The SQL support library now owns cooperative wrappers, synchronous action
registration, guarded restoration, detach behavior, promotion, editor snapshots
and translated ScriptModel plans. There is no copied local implementation of
those mechanics. DuckDB owns DBMS/project eligibility, stock preparation,
translation refusal, notifications, optional local native validation and the
nonexecutable Brikk inspection-template guard.

Core owns lexical statement boundaries through `SqlScriptSnapshot` and exact
named-parameter discovery through `parseParameterOccurrences`. The old local
scanner and colon heuristic have been removed. Core 0.19.0 still accepts an
unterminated block comment, so a narrow safety guard checks only gaps outside
core tokenizer tokens. It does not discover statement or parameter boundaries.
The existing incomplete-comment corpus caught this publication gap. Remove the
guard only after a released core reports that failure and passes the corpus.
Parameters remain original text
while prompting. Values, including configured IDE parameters inside literals and
LIMIT parameters, are substituted before final lowering. Every claimed statement
is finalized before any request in a selected mixed script is submitted.
Lenient DuckDB statement parsing now calls the platform's `parseUserParameter`,
so configured IDE parameters retain PSI nodes and original spans for the shared
model. The consumer does not reimplement the platform's pattern matching.

Cancellation and submission failures never delegate to a predecessor after
ownership has been claimed. Destination identity, document revision and PIPE
participation are checked after preparation. An edit followed by a revert is
still stale.

Relation editing and Kotlin injection remain consumer-owned. Core parses the
exact `rows()` slot directly. Only the fallback for other unproven reserved slot
names remains inspection-local. Named computed columns have known inventory
without fabricated native SQL types. Partial stars remain open. Run-to-stage
refuses a caret inside a nested pipeline.

The returned map belongs to the exact generated SQL and exact translation input.
Authored map-back is unavailable after parameter expansion changes that input.
DuckDB server errors still use the ordinary generated-SQL output. No exact
server-to-authored mapping is claimed. Source-preserving rendering is not used
as a substitute for the mapped execution path.

## Protected dogfood artifact

The existing `build/distributions/duckdb-intellij-plugin.zip` remains the user's
0.3.2 ZIP. Validation uses a redirected build directory and sandboxes under
`/tmp/opencode/duckdb-shared-backport`; it does not rebuild the protected path.

Protected ZIP SHA-256:
`66e46258024ae84715517ae9d45641a477758e754540a3d92daa07a6baa68b52`.
Contents, size and modification time are checked against the initial record.

## Publication audit

Direct Central downloads passed. The SQL helper's Gradle metadata lists only
Kotlin stdlib, which the consumer excludes. No SDK, Kotlin IDE, compiler, driver,
oracle or verify dependency is introduced by the helper.

| Artifact | SHA-256 |
| --- | --- |
| SQL IntelliJ support 0.1.0 | `21798b0c699dda59786d939ca51369c4417d1b724a524d823018fda9eb5ec833` |
| SQL core 0.19.0 | `fa614ee7ac29dd8cbd44777956c162c527d171dfaf88c9ae0cf9e640bc0397d2` |
| SQL metadata 0.19.0 | `6f4a506a68d16c79bb7d7de42122aa3d72eb50d3ffcc427f8804287467e29541` |
| Kotlin helper 0.1.0 | `3505484cc5cce7aa0f0b614249be234146901c1d61d542428d515dd21585ff26` |

## Validation

The 0.19.0-backed candidate passed the following consumer checks. The user has
requested a core fix for the incomplete-comment bug before final delivery, so
these results do not claim validation of that forthcoming core release.

| Lane | Result |
| --- | --- |
| Full default 261 suite | 183 tests, 174 passed, nine live/optional skips |
| DataGrip 262, Kotlin absent | 132 tests, 123 passed, nine live/optional skips |
| IDEA 263.5701.42, PIPE and Brikk consumer tests | 90 passed, none skipped |
| IDEA 262.9437.185, PIPE and Brikk consumer tests | 90 passed, none skipped |
| Genuine compiler-generated shapes on reviewed IDEA 262 | Both tests passed |
| Real product loaders with Trino, Doris and SQL Transpiler | Both tests passed, all six registration orders and four variants |
| ZIP inventory, Java 21 bytecode and notices | Passed, five expected JARs |
| Direct Central artifact comparison | All four dependencies identical |
| Plugin Verifier 1.410 against the frozen candidate | Compatible on all five pinned targets |

Verifier targets were DB-261.24374.56, IU-262.8665.81, DB-262.10315.132,
IU-263.4732.28 and IU-263.5701.42. The existing notices remain visible and
unchanged: 15 experimental uses, two scheduled-for-removal uses and one
deprecated use. No verifier exclusions were added.

Candidate ZIP SHA-256:
`55903ebefd4c7ef9aab52e45ee2a6acf94ccd8381feb6b6bc4406d34f06c3177`.
The frozen copy is `/tmp/opencode/duckdb-shared-backport/frozen/duckdb-intellij-plugin.zip`.
Evidence XML and logs are in `/tmp/opencode/duckdb-shared-backport`.

### Core bug reported to the user

In published core 0.19.0:

```kotlin
SqlScriptSnapshot.parse("FROM t |> LIMIT 1 /* open", "duckdb")
    .statements.single().boundaryError // null, should refuse
```

`Tokenizer.scanComment` does not check its remaining nesting depth on EOF.
The fix must cover unclosed ordinary/nested comments and retain a known earlier
statement while marking the unfinished remainder opaque. Quoted comment markers
and line-comment contents must not create false failures. The consumer guard is
temporary, and no core source or publication was changed by this backport.

## Maven Local fix validation

At the user's request, `-PbrikkSqlVersion=0.19.0-SNAPSHOT` opts into the local
core and metadata snapshot. The Maven Local repository is enabled only for
that exact version of the four core/metadata root and JVM coordinates. The SQL
IntelliJ helper and Kotlin helper still resolve from Central. The default pin
remains 0.19.0 until the fix has a released coordinate.

The original reproducer now returns `Missing */ from 1:18` from core itself.
The direct-core `BrikkSqlBoundaryFixTest` covers ordinary/nested unfinished
comments, preserved earlier statements, opaque tails, inert literal/comment
markers and parameter discovery refusal. These tests cannot use the consumer
guard to hide a missing library fix. The known-buggy default 0.19.0 excludes
that class; a snapshot override or a later release includes it.

Snapshot core SHA-256:
`2aa649146851023523c203753c938fa51f722b5683ea480ab1e8b84e83576bf2`.
Only `Tokenizer.class` and `Tokenizer$Companion.class` differ from the released
core. The class inventory is unchanged, and the snapshot metadata JAR is
byte-for-byte identical to released metadata 0.19.0.

Snapshot validation uses `/tmp/opencode/duckdb-sql-019-snapshot`, separate from
both the protected dogfood ZIP and the earlier Central-backed candidate.
Packaged notices record the actual snapshot coordinate and its Maven Local
origin. Snapshot consumer validation completed as 0.3.3:

- 188 default tests, 179 passed and nine live/optional skips. All five direct-core
  fix regressions passed.
- DataGrip 262 without Kotlin: 137 tests, 128 passed and nine skips.
- IDEA 263 PIPE/Brikk consumer checks: 95 passed, none skipped.
- Both genuine compiler-generated-shape tests passed on the reviewed IDEA 262.
- Both real-product-loader tests passed with Trino, Doris and SQL Transpiler,
  including all six registration orders and all four Execute actions. The isolated
  test also asserted the unfinished-comment fix in the product-owned core copy.
- Snapshot packaging, actual notices and exact Maven Local JAR comparison passed.
- Plugin Verifier 1.410 reported Compatible on all five pinned targets with the
  same visible API-use notices and no exclusions.

Snapshot 0.3.3 ZIP SHA-256:
`8d1baf0914b577eac663fc38e1ecde57c9bc37b5425954a01259dae67f2a28e2`.
The consumer's temporary guard remains only because the default Central 0.19.0
still needs it. The direct-core tests prove the snapshot fix independently.

The first snapshot test attempt failed to locate instrumented test classes in a
fresh redirected build directory. A fresh no-cache run with incremental Kotlin
compilation disabled resolved that fixture/output issue. Those initialization
failures were not SQL-library failures. The default Central-backed suite was also
rechecked after adding the opt-in snapshot build option and passed.

## Local 0.3.4 delivery

At the user's request, the validated backport is bumped to 0.3.4 and the local ZIP
is built with `-PbrikkSqlVersion=0.19.0-SNAPSHOT`. It is copied to a separate
versioned filename, leaving the protected 0.3.2 ZIP untouched. Commit/push to
main is authorized; no release branch, tag, GitHub release or publication is
requested. Main CI builds the default Central-backed pins and cannot publish
a release, which is gated on release branches.

Local ZIP:
`build/distributions/duckdb-intellij-plugin-0.3.4-local.zip`.
Initial delivery SHA-256:
`92f54febadc6a90953f165af519263ccaf8a7ea26dee378063ad6858f41691b2`.
Version 0.3.4, packaging and exact snapshot bytes were checked. All plugin class
files are byte-for-byte identical to the fully validated 0.3.3 snapshot candidate.
The original 0.3.2 ZIP still has its recorded contents, size and modification time.

At the user's request, the same 0.3.4 local ZIP was rebuilt with the completion
label `brikk column` in place of `Kotlin column`. Packaging checks passed,
the bundled label was checked directly and all dependency JARs are unchanged.
The current ZIP SHA-256 is
`4f36c43593b75741c0e8d52ec78d2c8d495d496f659b0d4b0ef7409209ba7263`.
The protected 0.3.2 ZIP remains untouched.

## Formal 0.19.1 / Kotlin helper 0.2.0 transition

Both updated artifacts are available from Maven Central. SQL IntelliJ support
stays at 0.1.0. The build now pins formal versions directly, with no property
override or Maven Local repository. Snapshot notice rewriting and the temporary
comment guard have been removed. The direct core boundary tests run by default.
The source still contains the user's `brikk column` completion label.

Released core 0.19.1 matches the validated 0.19.0-SNAPSHOT byte for byte, including
the unfinished-comment fix. Metadata is also unchanged. Kotlin helper 0.2.0
matches the peer's validated 0.2.0-SNAPSHOT bytes and adds Trino recognition.
A consumer regression checks that the shared helper recognizes a Trino host
while DuckDB declines it.

| Current artifact | SHA-256 |
| --- | --- |
| SQL core 0.19.1 | `2aa649146851023523c203753c938fa51f722b5683ea480ab1e8b84e83576bf2` |
| SQL metadata 0.19.1 | `6f4a506a68d16c79bb7d7de42122aa3d72eb50d3ffcc427f8804287467e29541` |
| SQL IntelliJ support 0.1.0 | `21798b0c699dda59786d939ca51369c4417d1b724a524d823018fda9eb5ec833` |
| Kotlin IntelliJ support 0.2.0 | `e6467167d33c11f37a291dc451d30f6f8d8df4d16e8ea2cde5b8c035bd5abb99` |

The helper publication models expose only Kotlin stdlib, which DuckDB excludes.
No IntelliJ SDK, Kotlin IDE, compiler, native driver, oracle or verifier is
introduced by either helper. DuckDB does not consume SQL oracle or verify.

Validation and the resolved dependency audit run under
`/tmp/opencode/duckdb-formal-0191-020`. The existing 0.3.2 and 0.3.4 testing ZIPs
are protected by checksum, size and modification-time records and are not
overwritten. The earlier 0.3.4 ZIP remains snapshot-backed until the user asks
to replace it with a formal-backed build.

An additional snapshot was found in the optional Trino coexistence fixture ZIP:
`brikk-engine-kotlin-intellij-support-0.2.0-SNAPSHOT.jar`. This is a peer artifact,
not part of DuckDB's distribution. Its bytes match released 0.2.0, but the peer
ZIP has not been rebuilt by this work. Doris's fixture ZIP has no snapshot JARs.
The local SQL Transpiler ZIP also contains Kotlin helper 0.2.0-SNAPSHOT and SQL
core/metadata 0.19.0-SNAPSHOT. DuckDB's optional coexistence run uses Marketplace
SQL Transpiler 0.3.0, not that local ZIP. None of these peer JARs is bundled with
DuckDB, and this work does not modify or rebuild peer distributions.
The old Quack snapshot reference in the driver XML was only a stale comment and
now names the actual formal 0.5.0 dependency. Historical reports and IDEAS notes
are not active dependency declarations.

### Completed formal-backed validation

- Full default 261 suite: 190 tests, 181 passed and nine live/optional skips.
- Both direct/inherited Trino-host non-ownership regressions passed. All five
  direct-core lexical-boundary regressions are now in the default suite.
- DataGrip 262 without Kotlin: 137 tests, 128 passed and nine skips.
- IDEA 262 and 263 PIPE/Brikk suites: 97 tests passed on each, none skipped.
- Both genuine compiler-generated-shape tests passed on reviewed IDEA 262 with
  the actual compiler-plugin fixture.
- Both real-product-loader coexistence tests passed, including all six action
  registration orders and all four variants.
- Plugin Verifier 1.410: Compatible on all five pinned targets. Notices remain
  unchanged at 15 experimental, two scheduled-for-removal and one deprecated use.
- Final ZIP has five expected JARs, Java 21-compatible bytecode and current
  license/notices. All four dependency JARs match direct Central downloads.

The resolved compile, runtime, test-runtime and tools-runtime configurations
contain zero snapshot modules. The respective module counts were 271, six,
16 and six. Detailed versions are in
`/tmp/opencode/duckdb-formal-0191-020/dependency-audit.json`.

Formal-backed 0.3.4 ZIP SHA-256:
`289bfce05701e34124dc8c40890d500b05cb0b5d93f820190c0efe80cf822951`.
Its verified frozen copy is
`/tmp/opencode/duckdb-formal-0191-020/frozen/duckdb-intellij-plugin.zip`.
A separate installable copy is
`build/distributions/duckdb-intellij-plugin-0.3.4-formal.zip`.
The validation rebuild stayed byte-for-byte identical through all runtime lanes.
Both earlier testing ZIPs are unchanged in contents, size and modification time.
No plugin release, publication or peer-plugin upgrade is performed.

## Shared column presentation 0.1.1

This adoption follows Trino's `HANDOFF-shared-column-completion-duckdb.md`.
DuckDB now calls `SqlColumnCompletion.create` from SQL IntelliJ support 0.1.1
instead of formatting type, nullability, alias, tail and priority locally. The
scope analysis, managed-relation filter and alias/name deduplication remain in
the consumer. Kotlin support stays 0.2.0, and core/metadata stay 0.19.1.

The origin labels remain `brikk column` and `relation contract`. The shared
renderer retains priority 120, explicit unknown facts and distinct lookup
identities for same-named columns under different aliases. Insertion text is
still the bare column name. The helper introduces no Kotlin IDE or SQL engine
dependency for this presentation API.

Four SQL-only renderer tests cover nullable and non-null types, fallback labels,
unknown type/nullability, blank aliases, priority and stable/distinct identities.
Two real injected-SQL completion tests check String?/Instant presentation and
both `id` suggestions in an aliased join. The isolation test also checks that
DuckDB's real plugin loader owns the 0.1.1 renderer class.

Validation uses `/tmp/opencode/duckdb-shared-column`. All existing testing ZIPs
are protected by checksum, size and modification-time records. Source and tests
are ready, and Central 0.1.1 is now available. Its JAR SHA-256 is
`7ffdbb93990507db3f7ad83bc8e2571a7ef011f6debe0dd6b03d72d1b1ebd64e`.
The publication metadata exposes only Kotlin stdlib, which DuckDB excludes;
the new renderer's class is present and all bytecode is Java 21-compatible.
The release adds only `SqlColumnCompletion` and its lookup-identity class;
every existing helper class matches 0.1.0 byte for byte.
Consumer validation is complete; results follow.

### Completed shared-renderer validation

- Default 261 suite: 196 tests, 187 passed and nine live/optional skips.
- All six new regression cases passed. Nullable String?, non-null Instant,
  fallback/unknown labels, priority 120 and alias-distinct identities are checked.
- DataGrip 262 without Kotlin: 141 tests, 132 passed and nine skips, including
  the four SQL-only presentation tests.
- IDEA 262 and 263 PIPE/Brikk/presentation suites: 103 passed on each, none skipped.
- Both genuine compiler-generated-shape tests passed on the reviewed IDEA 262.
- Both real-plugin-loader isolation tests passed with all six registration orders
  and four Execute variants. The renderer is loaded from DuckDB's own 0.1.1 JAR.
- Packaging and direct Central byte comparison passed for all four dependency JARs.
- Plugin Verifier 1.410 reported Compatible on all five pinned targets. The same
  15 experimental, two scheduled-for-removal and one deprecated notices remain.

The first empty-prefix unqualified native-SQL join fixture hit a JetBrains
injected-alias smart-pointer restoration error in its native completion provider,
before any renderer assertion. The join regression now uses the PIPE join/prefix
context used by the peer acceptance case. Qualified native-SQL rich presentation
still has its own passing fixture. No native resolver change, log suppression or
verifier exclusion was added for that unrelated platform path.

Validation ZIP SHA-256:
`c7b80abfd3b25bf956ba358693506f28d965b961915232b2536447d0564db267`.
The frozen artifact is
`/tmp/opencode/duckdb-shared-column/frozen/duckdb-intellij-plugin.zip`.
It stayed identical through the runtime checks. All three existing testing ZIPs
remain unchanged in contents, size and modification time. No version bump,
commit, push, release or publication is performed by this adoption.

## Dogfood 0.3.5 delivery

At the user's request, the current formal-backed consumer is bumped to 0.3.5.
The installable ZIP is ready at
`build/distributions/duckdb-intellij-plugin-0.3.5-local.zip`.
SHA-256:
`1fffa66905ff20696b9c9160c8bda9c310ba855419753f829fb711b0caad1956`.

The final 0.3.5 default suite passed 196 tests with nine skips, and packaging
checks passed. All plugin class files and dependency JARs match the fully
validated 0.3.4 shared-renderer candidate byte for byte. Only version/descriptor
metadata differs. The existing testing ZIPs remain untouched.

The user has requested commit/push to main before waiting for final versioned
verification. That verification uses a frozen copy under
`/tmp/opencode/duckdb-shared-column/frozen-035`, not the installable dogfood ZIP.
No release branch, tag, GitHub release or Marketplace publication is requested.

## Unification refresh, 91a5ed0

Refreshed Maven Local dependencies published 2026-10-13 00:53 from brikk-sql
`91a5ed0`, including the file-aware native-diagnostics hooks in `e7f1cc9`.
No library source changes, publication or Plugin Verifier run.

Resolved binary SHA-256 values:

- SQL core 0.20.0-SNAPSHOT: `20d0cb97404c595b6154c7067590cac1e2e1bc59e9038126958caa50b0aafb8d`.
- IntelliJ support 0.2.0-SNAPSHOT: `c1950eb271f8b089d5016429e8a204957b8fd461b65a79a4ee4f7c0dd756214f`.

Default and Kotlin-present 261/262/263 lanes pass with 269 tests and nine skips.
Kotlin-absent lanes pass with 204 tests and nine skips. Brikk passes 65 tests;
real-loader isolation passes both tests with the local peer ZIPs. The old lane
script referenced a removed transpiler ZIP; rerunning against its worktree ZIP
passes. Census remains 226/226. No corpus expectations changed.

The refresh resolves five known core execution differences on all three IDE
generations: ORDER after LIMIT, EXTEND with a window after LIMIT, SET input
aliases, and RIGHT/FULL JOIN after WHERE. The observed disagreement count is
394 instead of 399, with all 132 native witnesses passing. The committed
expectations still retain these five known differences; they were not rewritten
to match the new implementation.

## U-V3 native diagnostics

`DuckdbErrorAnnotator` now delegates to `NativeErrorAnnotator` with one DuckDB
profile. Its stable, application-owned validators wrap the located user driver
through `NativeSqlValidator.of(ParseOnlyDuckdbVerifier(driver))`; disposal closes
the parser connections before unloading drivers. The 100,000 UTF-16 statement
cutoff is explicit. Local-parser and PSI-parser diagnostics, debounce and budgets
remain off. There is no EXPLAIN, bind or execution fallback.

PIPE lowering and Brikk single-statement checks use the file-aware
`statementDiagnostics` hook. Read-side hooks copy per-file PIPE enablement,
Brikk slots and interpolation issues into user data. Changes increment that
file's diagnostic revision; background hooks do not read PSI. Shared application
checks reject results after text, validator or metadata changes and map escaped,
trimmed and supplementary-character injections to editable authored ranges.

Newly visible behavior: the annotator is also registered on the optional Kotlin
host. External annotators do not run on injected SQL fragments, so the old
SQL-only registration did not expose these checks there. Kotlin DuckDB strings
now show native parse-only errors, enabled PIPE lowering refusals, and Brikk
template SQL errors. Brikk still uses its existing core parser instead of the
engine, so valid DuckDB syntax outside that parser's coverage may receive a false
Brikk syntax error in a Kotlin string. Ordinary SQL gets no new local grammar
errors. Unsupported interpolation diagnostics stay on authored Kotlin; uncertain
Frankenstein injections are skipped, and inspection placeholders are never sent
to the engine.

Default and Kotlin-present 261/262/263 lanes pass with 277 tests and nine skips,
up from 269. Kotlin-absent lanes pass with 208 tests and nine skips. Brikk passes
69 tests; isolation passes both tests. Parse-only safety tests remain green.
Census is 226/226 and corpus expectations are unchanged. No Plugin Verifier or
publication ran.

## P6.e DuckDB catalog definitions

Registered `DuckdbDefinitionProvider`, a
`ShowCreateDefinitionProvider("duckdb", …)` subclass, for TABLE, VIEW,
ROUTINE mapped to MACRO, SEQUENCE and INDEX. Namespace paths include DATABASE
and SCHEMA, so attached catalogs and same-named objects are read independently.
The fallback audit no longer permits an inherited PostgreSQL definition provider.

Object source views now show DuckDB's catalog definitions. Macros retain
overloads, quote parameter names and disclose that DuckDB does not report
parameter defaults. Reads call qualified `system.main.duckdb_*` built-ins only;
shadowing catalog or helper macros never advances the test sequence. A live
IDE-driver test checks list-valued macro parameters without changing the
connection's namespace. No DuckDB text-explain profile or action was added.

Final default and Kotlin-present 261/262/263 lanes pass with 282 tests and nine
skips. Kotlin-absent lanes pass with 213 tests and nine skips. Brikk passes 69
tests; real-loader isolation passes both tests with all five plugins loaded.
Census remains 226/226; corpus results remain 394 disagreements, five resolved
and 132 passing native witnesses. No corpus expectations changed. No Plugin
Verifier or publication ran.

## Phase 5, step 1: dependency refresh

Refreshed core/metadata/support/test-support from Maven Local brikk-sql `efd18f1`,
published 2026-10-13 08:13, and Kotlin helper `0.3.0-SNAPSHOT`, published 06:24.
The helper depends on core only. DuckDB compares `BrikkDialect` values with `==`
and has no exhaustive enum `when` to repair. No files deleted; no consumer-visible
behavior change in this dependency-only step.

Support binary SHA-256 is
`70993432a56864f5397dec0d12bc2ffaeb0d2ef8d885cace76ad077ee96ad48a`;
helper binary SHA-256 is
`70a84a80a40b93963e4d26999e0bc80d577eb94d5ed2fd0e6890ddc29c8c9445`.
Default/present 261/262/263: 282 tests, nine skips. Absent: 213, nine skips.
Brikk: 69; isolation: two; census: 226/226. No tests added or deleted.

### Step 2: shared host injection

`BrikkDuckdbInjector` is the first contributor and performer over `DuckdbHostSql`.
The helper's `BrikkHosts.state` replaces DuckDB's remembered context and pending
projection. Shared layout replaces places, character-origin tracking, trims and
retries. Deleted `BrikkDuckdbInjectionContributor.kt` (22 lines) and
`BrikkDuckdbInjectionPerformer.kt` (101 lines); the context adapter also loses its
local projection. Colon-bind parsing uses the channel; a placeholder error filter
is registered. INFO annotations follow the helper's remembered pending projection.

Visible changes: literal custom `trimMargin` prefixes now work. Remembered SQL
keeps an inspection-only placeholder projection during indexing, without pending
errors. Never-recognized strongly evidenced Brikk strings are claimed without an
injection, instead of falling through to native `@Language` injection. Placeholder
errors alone are filtered rather than marking the whole SQL Frankenstein.

Added three injection tests, also inherited by the relation-editing fixture, for
six new test instances; none deleted. Default/present-equivalent 261/262/263 lanes:
288 tests, nine skips. Absent lanes: 213, nine skips. Isolation: two. Census: 226/226.

### Step 3: shared relation features

One `DuckdbRelationProfile` registers the five shared feature subclasses, with a
stable helper adapter from `ExternalRelationFacts.of` before `DasRelationFacts`.
The optional helper contributes Brikk labels and navigation, not a second SQL
analyzer. The legacy PIPE contributor now offers stage phrases only, preventing
duplicate column suggestions from its separate shape fallback.

Deleted nine production files (522 lines): `BrikkRelationProvider` (16),
`BrikkSqlScopes` (226), `BrikkSqlEditorSupport` (36), `BrikkSqlResolveExtension`
(86), `BrikkSqlInspectionSuppressor` (18), `BrikkSqlCompletionContributor` (54),
`BrikkSqlGotoDeclarationHandler` (31), `BrikkSqlContractAnnotator` (38) and
`KotlinBrikkRelationProvider` (17). Deleted the old scope-test file (139 lines,
15 tests), replaced by seven tests of core's analyzer. Added a physical PIPE
completion test and a lineage/icon test. Helper-inventory tests now ask the helper
directly. The corpus fixture is being moved to public core facts/analysis; its
full observation assessment is step 6.

Visible changes: physical table columns now complete in PIPE with `column
(<table>)` labels and SQL types. Known renames retain Kotlin types, Brikk labels
and bricks icons; computed columns use the column icon. Shared missing/partial
messages are `Unknown column 'x'` and `Column 'x' is not in the partial relation
contract`. Shared scope rules replace DuckDB's divergent alias/stage propagation.

The targeted feature lane passes 53 tests. The first corpus observation drops
from 394 to 179 differences, with new native-observation differences still rejected
by its old ledger. No corpus expectations were changed; the ledger and complete
repeatability assessment remain for step 6.

### Step 4: shared inspection-only execution

`PipeDialect.hostSql` and `NativeErrorProfile.hostSql` now use the channel.
Its optional `claimsHost` adapter calls `BrikkInspectionView`, including pending,
foreign-dialect and externally injected Brikk strings. Deleted the old injected-
profile-only guard (three lines); no files deleted. Added two host tests, inherited
by the relation fixture, for four test instances. The targeted injection/execution,
native-error and parse-only-safety lane passes 51 tests.

Visible change: Execute from the real Kotlin host editor is refused inside an
inspection-only string and still delegates outside strings. Pending/claimed strings
are protected even when this plugin has no injected profile. Run-to-stage is not
offered there. Native checks skip statements containing placeholders and suppress
placeholder-caused diagnostics through the shared marker policy.

### Step 5: gutter, banner deferred

`BrikkDuckdbLineMarkerProvider` uses the shared host marker provider and bricks
icon, one marker per claimed Kotlin string. Its named descriptor is togglable in
Settings > Editor > General > Gutter Icons. The tooltip names DuckDB, PIPE editing,
named binds and relation contracts, and says `inspection only, not executable`.
Pending and indexing markers are dimmed and say `waiting for indexing`, including
previously recognized injections cached before indexing started. The channel's
caller data supplies the project for that last check; no message parsing is used.

Two new gutter tests pass in the 31-test injection lane, also adding two inherited
instances to the relation-editing fixture. No files deleted. The action-needed
banner remains deferred pending the helper's typed status publication.

### Step 6: corpus observer triage

Native SQL files and genuine Kotlin injections now run independently against the
unchanged v2.2 inputs. Both get 167/167 logical cases right, with zero scope,
logical-reference, completion, order or execution differences. The corrected
native-PSI ledger has 79 differences: resolve 74, navigation four, diagnostics one.
Each entry has a classified reason and a lane-specific fingerprint. Unexpected
axes or changed known observations fail; resolved entries may improve naturally.
Input SHA-256 checks protect facts, cases and witnesses from expectation rewrites.

| Axis | Previous count | Triage |
| --- | ---: | --- |
| Scope | 8 | Harness artifacts: use core's type equivalence, unspecified computed-type comparison, display type and computed identity contracts. Now zero. |
| References | 1 | Harness artifact: a computed output's identity is not its current alias name. Now zero. |
| Completion | 53 | Harness artifacts: origin/source labels are not visible SQL qualifiers; observe real items and match actual presentation to shared candidates. Supply physical facts for real SQL-file source fixtures. Now zero. |
| Resolve | 81 | Normalize duplicate equivalent variants and visible aliases only after native identity/provenance independently agree. Never substitute core for missing native PSI. 74 remain. |
| Navigation | 6 | Physical fixture targets now come from the real DAS source. Native set-output/declaration deference still loses or fabricates provenance in four cases. |
| Diagnostics | 2 | Harness artifact: recognize the shared branch-count diagnostic as an operator problem. The stock open-catalog inspection remains separately visible. |

The 76 support-layer entries are grouped into nine minimal reproducer families
in `src/test/resources/relation-corpus/duckdb-support-reproducers.json`:

- `pipe-where`, reference/caret 24: core resolves `src.id`; no native reference
  exists. `PipeStatementGate` and `ExternalRelationResolveExtension` need a shared
  flat-PIPE reference bridge. This family accounts for 57 resolve entries.
- `self-join-same-slot`, reference/caret 7: core is ambiguous between two alias
  occurrences; native equal provider-column symbols collapse that distinction.
  `RelationColumnSymbol` / `ExternalRelationResolveExtension`.
- `quoted-case-duckdb`, reference/caret 9: native and core offsets agree, core
  resolves the quoted case-insensitive name, but native accepts no candidate.
  `ExternalRelationResolveExtension` / `RelationColumnSymbol` quote/case bridge.
- `ordinary-star-join-cte-duckdb`, reference/caret 69: core exposes `q.id_1`; native
  has none. `RelationAnalysis.manages` must not reject star-derived columns merely
  because an inner join predicate also mentions their source facts.
- `ordinary-group-alias`, reference/caret 61: core retains `src.event_kind`
  through `kind`; no native candidate. Projection lineage is excluded by
  `RelationAnalysis.manages` / `ExternalRelationResolveExtension`.
- `left-using-merged-star`, reference/caret 57: core picks the left merged key;
  the stock result disagrees. Shared merged-key management needs to retain it.
- `full-using-key-non-null-non-null`, reference/caret 9: core computes one merged
  key with no single-provider provenance; the shared native resolver supplies no
  corresponding computed symbol.
- `set-nullability-names-intersect`, reference/caret 94: core retains left-input
  provenance through `first_name`; native sees a SQL alias instead.
  `RelationAnalysis.manages` / `ExternalRelationGotoDeclarationHandler`.
- `ordinary-set-union-all`, reference/caret 61: core has computed/no provider
  provenance; native still resolves/navigates to the left input. The resolve/goto
  deference policy also retains an input after branch-count mismatch.

Three platform entries remain with evidence: computed WHERE/lateral projection
aliases have native PSI but no stock candidates, and `SqlResolveInspection` still
reports a table while the shared open-catalog analysis has no absence proof.
The JSON reproducers retain expected/actual cells, SQL, fact keys, offsets, raw
native variants and presentation. No support workaround was added.

Required feature/provider wiring was checked. The optional native slot table-type
bridge was evaluated separately, with stock scalar typing preserved. It introduced
additional lineage/navigation conflicts and a shadowed native completion item, so
it is not enabled. A consumer test pins that unchanged default. The production
parser and its scalar typing remain unchanged.

The ledger generator is `tools/triage_relation_corpus.py --write`; it writes only
the observed ledger and reproducer resources, never facts/cases/witnesses. Added
the independent native corpus test and optional-type/default-policy test; no
tests or files deleted. Full default/present 261/262/263 lanes pass 292 tests with
nine skips; absent lanes pass 214 with nine skips. Brikk passes 78; isolation two;
census 226/226; all 132 distinct native witnesses pass.

Both corpus lanes ran twice, byte-identical on every generation. Native report
SHA-256: `a25d2700ae19076544a0946842ea2b527f5f3cb3ea36ec0d08ba7e11ac47da84`.
Injected report SHA-256: `cf87eff6aeb222f3454ef88457cb51e92df5b597e7148654ae8c3a8bdaa2cebb`.

### Step 5 follow-up: typed helper actions

Refreshed the helper published 2026-10-13 09:38 from `72157d0`.
Helper JAR SHA-256:
`7e5d080a6d97b33639f9aaab642c16519a6a8f2a478dfb973a12996181492805`.
The optional `BrikkDuckdbActionBanner` uses the helper's cached typed actions,
never text matching or fresh Kotlin analysis. Missing-dialect actions are shown
only for DuckDB. Runtime/compiler actions open the nearest build file when it
exists, otherwise their documentation URL. Recognition and fact discovery refresh
editor notifications only when recorded relevant actions change. Healthy,
indexing and write-action-pending hosts show no routine banner.

Injected pending projections carry the typed cause to the gutter and INFO text.
Indexing says `waiting for indexing`; write prohibition says `waiting for analysis`;
resolution and generated-shape waits remain distinct from actionable runtime or
compiler failures. Existing rename tests and a direct write-action test pass.

One support API gap is reported, not worked around: a never-recognized
`HostSqlClaim.Unavailable` carries only a reason string, and the gutter tooltip
callback gets neither the host nor caller data. Its typed cause cannot reach the
tooltip. It remains dimmed with the neutral `waiting for recognition`, rather than
falsely claiming indexing. `Unavailable` needs caller data or the tooltip needs
the host before this last path can use the typed cause.

Added four tests, also inherited by the relation fixture, for eight new instances;
no files/tests deleted. Full default/present 261/262/263 lanes pass 300 tests with
nine skips. Absent passes 214 with nine skips; Brikk 86; isolation two; census
226/226. The two corpus lanes still match the reasoned 79-entry ledger and retain
167/167 logical passes and all 132 native witnesses.

### Step 7: isolation-contract adoption deferred

The shared kit was exercised through DuckDB's product loader, keeping Brikk jars
off fixture core. Installed-peer, ownership and language checks passed. Its Execute
check falsely requires SQL Transpiler's wrapper in the stock Execute chain merely
because that peer bundles `CooperativeExecuteAction`. The loaded chain correctly
has four dialect wrappers and the stock action; Transpiler does not register that
wrapper. Reproducer: all five local plugin ZIPs loaded, call
`PluginIsolationContract.everyExecuteChainKeepsEachPluginsWrapper()`;
`wrappingLoaders()` includes `dev.sort.sql-transpiler-intellij-plugin` by resource
presence and asserts a wrapper the plugin never installed.

Adoption needs the kit to identify actual Execute customizers or accept explicit
wrapping peers. No peer was excluded and no assertion disabled. The experiment was
removed; both existing isolation tests, including all six dialect registration
orders and detached predecessors, remain unchanged and pass. No production or
test files deleted, no tests added/deleted, no visible behavior change.

## Working-plugins gate

### Gate 1: dependency refresh

Refreshed SQL core/support/test-support from `654d6a8`, published to Maven Local
2026-10-13 12:44. Registered the shared `HostSqlRunConsoleVeto` and its real-host
contract test. The existing host execution contract now also checks selection.
Adapted the obsolete text-explain test signature without enabling DuckDB explain.

The new shared go-to fixes three provider-navigation waivers. The observer now
honors a nonempty shared SQL-declaration answer even when it has no Kotlin/provider
navigation id, instead of falling back and inventing a single input origin.
Removed resolved navigation waivers for UNION ALL and renamed INTERSECT/EXCEPT.
Both corpus lanes pass 167/167 logical cases; remaining ledger entries are 76:
resolve 74, navigation one, stock diagnostics one. Corpus expectations are unchanged.

The 262 harvested-macro completion test passed five independent reruns, without
code changes. Kotlin-absent full suites pass 214 tests with nine skips on all
three generations.

The requested helper source is `44d6d47`, but Maven Local still holds the unchanged
09:38 helper binary (`7e5d080a6d97b33639f9aaab642c16519a6a8f2a478dfb973a12996181492805`).
It called the previous `RelationFacts` default constructor and failed against
core's new search-path constructor. The owner republished helper `507fe04` at
13:16, compiled against `654d6a8`; its binary SHA-256 is
`ad0018265452a7061efff5e3acfba6f2d876f2e1bd8eca360aa145d02734380e`.
After refresh, full default/present 261/262/263 lanes pass 302 tests with nine
skips; absent lanes pass 214 with nine skips; Brikk passes 88. The constructor
failure is gone. No binary shim, library source edit or publication was done here.

### Gate 2: ZIP and Plugin Verifier

Built `build/distributions/duckdb-intellij-plugin.zip` with the rebuilt helper.
ZIP SHA-256:
`9860865fb962a19da6d11332413df2ff6f791a8a5c76311bd77c2fea6662c94f`.
The embedded helper matches Maven Local SHA-256 `ad001826…34380e`.

Plugin Verifier reports **Compatible** on every configured target:

| IDE | Verdict |
| --- | --- |
| DataGrip 261.24374.56 / 2026.1.3 | Compatible |
| IDEA 262.10968.63 | Compatible |
| DataGrip 262.10315.132 / 2026.2.5 | Compatible |
| IDEA 263.4732.28 | Compatible |
| IDEA 263.5701.42 | Compatible |

No compatibility, missing-dependency, structure, non-extendable or override-only
failures. All 15 internal-API notices come from the bundled
`GenericJdbcIntrospectorGate` implementing/delegating the platform's
`DBIntrospector.Factory` extension contract, not DuckDB-owned internal calls.
That existing introspector/fallback integration is a documented exception; the
PostgreSQL-family inheritance remains intentional. There are two scheduled-removal
notices for the connection-interceptor compatibility hook, four deprecated notices
on 261 and five on newer targets, and 662 experimental-API notices, largely for
the bundled Brikk APIs. No exclusion was added to hide a verifier problem.

Reports are under `build/reports/pluginVerifier/<IDE>/plugins/dev.sort.duckdb-intellij-plugin/0.3.5`.
No publication was performed.
