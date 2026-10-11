# DuckDB shared SQL IntelliJ support backport

This backport follows Trino's `HANDOFF-intellij-shared-extraction-duckdb.md`.
The backport was validated as 0.3.3 and is now bumped to 0.3.4 for local testing.
Its default dependency pins are Maven Central releases:

- `dev.brikk.house:brikk-sql-intellij-support:0.1.0`
- `dev.brikk.house:brikk-sql-jvm:0.19.0` and its metadata dependency
- `dev.brikk.house:brikk-engine-kotlin-intellij-support:0.1.0`, unchanged

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
SHA-256:
`92f54febadc6a90953f165af519263ccaf8a7ea26dee378063ad6858f41691b2`.
Version 0.3.4, packaging and exact snapshot bytes were checked. All plugin class
files are byte-for-byte identical to the fully validated 0.3.3 snapshot candidate.
The original 0.3.2 ZIP still has its recorded contents, size and modification time.
