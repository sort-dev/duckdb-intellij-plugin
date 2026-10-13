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
