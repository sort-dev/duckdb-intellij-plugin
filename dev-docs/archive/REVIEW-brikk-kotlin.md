# DuckDB Brikk Kotlin editing profile

This records the initial 0.3.1 implementation. The later helper migration,
relation-input scopes and generated-shape validation are described in
[REVIEW-shared-relations.md](REVIEW-shared-relations.md).

Implements the DuckDB-only portion of
`brikk-house/docs/IDE-brikk-house-mode.md`. No Doris integration, new dialect,
compiler execution, runtime database connection, or global auto-enablement is
introduced. The optional `duckdb-kotlin.xml` descriptor registers a scoped
Kotlin K2 injection contributor/performer and authored-host diagnostics.

## Contracts

- Resolve both the `dev.brikk.house.sql.runtime.Sql.duckdb` entry point and
  the enclosing function's exact `BrikkSql` annotation. Short names alone do
  not activate the profile. Unrelated DuckDB injections and Doris calls return
  to the existing Kotlin handlers unchanged.
- Classify actual Kotlin template entries through the Analysis API. Value
  parameters, local values and nonconstant properties become named binds;
  getters are not evaluated. Resolved `Rel` parameter types become slot names,
  including type aliases. Only literal const initializers are evaluated.
  Computed/unavailable constants and complex expressions are refused.
- Preserve host PSI references. Synthetic bind/constant text is registered as
  noneditable injection prefixes; retained decoded literal ranges map back to
  the Kotlin host. Trimming operates on rendered text with character origins.
  `${'$'}` and literal JSON dollars are not misclassified as variable names.
- Store immutable, Kotlin-free editing metadata on the injected file. That
  profile enables PIPE parsing/completion only there and parses `:name` as
  a parameter reference, not a database identifier. Native project PIPE and
  ordinary SQL diagnostics are unchanged.
- Unknown interpolation symbols/types produce an unresolved placeholder and
  a host diagnostic. Refused/uncertain templates get lenient inspections;
  supported templates keep ordinary inspections active. No guessed symbol
  classifications are cached. Standard injection invalidation reruns resolution
  after SQL, Kotlin declaration, import, or type changes.
- Inspection SQL is not executable query output. Our Execute interception and
  run-to-stage action reject these views rather than using the placeholder as
  a runtime binding. The compiler/composed relation owns real execution.

## Verification

Compile/test on the 261 IDEA SDK to obtain optional Kotlin APIs. DataGrip
does not ship that plugin, so it is also tested without Kotlin. The descriptor
declares K2 compatibility at the base plugin level, as required by the platform
before it decides whether to load the optional Kotlin descriptor.

```bash
mise exec -- ./gradlew test buildPlugin verifyEmbeddedPipes verifyPlugin
```

Tests use the real Kotlin contributor/performer pipeline and resolved source
symbols, not a fake placeholder provider. They cover initial injection, bind
versus column PSI, Kotlin rename, relation slots and type aliases, constants,
computed-expression refusal, getters, unresolved refs, external declaration
edits, multiline strings, escaped strings/dollars, JSON paths, Unicode and
default trimming. A real DDL data source and SQL constant-expression inspection
verify that scalar interpolation is not a tautology while a real column-to-column
equality still warns. Other dialect and console tests remain enabled.

For a DataGrip runtime without Kotlin, first compile on IDEA 261, then run the
compiled tests with the SDK bridge and without the Kotlin-specific tests:

```bash
mise exec -- ./gradlew test -Pduckdb.localIde=/absolute/path/to/datagrip-2026.2.5 \
  -Ptest.kotlin=absent --init-script gradle/test-sdk.init.gradle \
  -x compileKotlin -x compileJava -x compileTestKotlin -x compileTestJava \
  -x instrumentCode -x instrumentTestCode
```

## Limits

Kotlin K2 is required for this optional integration. Custom wrappers,
non-default trimMargin prefixes, complex `${...}` expressions, and schema/type
metadata for `Rel<T>` inputs are not inferred. Compiled const values without a
verifiable literal initializer refuse rather than using a general constant
evaluator. The native DuckDB dialect, console execution and PIPE project
setting work without Kotlin.

## Verified results

- Full 261 IDEA run: 132 tests, 123 passed, nine live/optional checks skipped.
- All 15 injection tests pass on the 262 IDEA runtime using the 261-compiled
  classes, including DDL-backed inspections and restoration of native injection
  after removing `@BrikkSql`.
- The DataGrip 2026.2.5 runtime passes the non-Kotlin suites with Kotlin absent.
- Plugin Verifier 1.410 reports Compatible on all five Doris targets:
  DB-261.24374.56, IU-262.8665.81, DB-262.10315.132, IU-263.4732.28 and
  IU-263.5701.42. DataGrip reports the intentionally unavailable optional Kotlin
  dependency; IDEA checks include it. Existing deprecated/experimental notices
  remain reported. The advertised install range remains 261/262.
- `buildPlugin` and `verifyEmbeddedPipes` pass. The ZIP still contains exactly
  the plugin and the two 0.18.0 brikk libraries, not Kotlin or native JDBC JARs.

Rebuilt ZIP: `build/distributions/duckdb-intellij-plugin.zip`.
SHA-256: `d3eb302cfafb5913d7841de77d9c6fb504cd872c56fb0dc31cf41806613199cb`.
The local plugin version is 0.3.1. The verifier results above tested the same
implementation as 0.3.0 before the version/change-notes-only rebuild;
`buildPlugin` and `verifyEmbeddedPipes` passed again for 0.3.1. No Marketplace
publication is performed.
