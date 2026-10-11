# Shared Brikk helper and DuckDB relation scopes

Development integration of
`dev.brikk.house:brikk-engine-kotlin-intellij-support:0.1.0-SNAPSHOT` from Maven
Local. No helper release, plugin publication, push, compiler rebuild, or peer
plugin modification is performed by this work.

The snapshot section below records the initial integration. The later Central
transition is recorded at the end of this report.

## Ownership and migration

`BrikkDuckdbKotlinContext` now projects the helper's copied context. It no longer
implements Analysis API recognition, trimming recognition, entry classification,
constant semantics or Rel type discovery. The existing DuckDB injection shreds,
character-origin trimming and host diagnostics stay here.

Hot recognition uses `BrikkKotlinContext.inspect(includeShapes = false)`.
The optional Kotlin relation provider requests fresh function-local shapes when
SQL editing needs them. It does not cache unavailable results or store slots in
a global database/catalog. Traits, imports, signature edits and schema-source
changes are therefore re-read, not inferred from the SQL string alone.

The helper JAR is copied into this plugin's classloader with its Kotlin stdlib
excluded. Kotlin-aware entry points remain behind the optional descriptor;
Kotlin-free contracts can be used by the dialect implementation. Maven Local is
restricted to this one development coordinate. Both libraries' objects stay
inside this plugin; no cross-plugin helper service is registered.

## SQL behavior

`BrikkSqlScopes` parses authored virtual SQL into a statement-local graph using
the bundled SQL engine. It reads only declared input contracts, not upstream SQL
or the enclosing function's final output. It supports slot aliases, CTEs and
subqueries, ambiguous join columns, nullable outer-join sides, and core PIPE
filter/order/limit/distinct/select/extend/aggregate/join/as scopes. Explicit
projections hide prior namespaces; extension preserves input namespaces. Missing
types remain unknown. Unsupported stages withdraw scope information rather than
inventing an output inventory.

The native SQL resolver receives temporary contract column symbols; these are
not physical datasource objects. Completion and Go to Declaration use the same
scope graph, including lenient PIPE statements without inner PG PSI. Declared
columns navigate to the Kotlin property. Generated columns use the helper's
relation-declaration fallback, explicitly labelled as a relation-contract target.

Resolution/type/signature inspections are suppressed only for slot-owned
references. Contract diagnostics preserve ambiguity and known full-inventory
errors; a missing partial column says "not guaranteed", not "does not exist".
Unavailable shapes produce no cascading invented missing-column diagnostics.
Unrelated physical-table SQL remains with the IDE's normal resolver/inspections.

Kotlin type families do not prove exact DuckDB types, precision/scale, quote
identity or SQL ordinal order. Column model types remain UNKNOWN; Kotlin
type/nullability hints are presented separately. Query-local join nullability
does not mutate the Kotlin input declaration.

brikk-sql 0.18.0 rejects a reserved called slot name such as `rows()` in a SELECT
FROM clause. On that parse failure only, this adapter retags known called-slot
tokens for inspection parsing. It changes neither the authored text nor token
offsets, and it does not retag strings/comments or enable execution.

## Validation lanes

```bash
mise exec -- ./gradlew test buildPlugin verifyEmbeddedPipes verifyPlugin
```

The normal suite covers source traits, inherited/generic inputs, aliases,
join nullability, stage visibility, native references, Kotlin navigation,
completion, partial/unavailable contracts and edits without SQL changes.
Existing scalar-injection and physical DDL inspection tests remain enabled.

The generated-shape lane must use the genuine reviewed compiler artifact and
its matching installed IDEA runtime. Compile/instrument on 261 first, then:

```bash
mise exec -- ./gradlew test -Pduckdb.localIde=/path/to/idea-262.9437.185 \
  -Ptest.brikkCompilerPlugin=/path/to/brikk-engine-kotlin-compiler-plugin-2.4.20-ij262-34-0.2.0.jar \
  --tests '*BrikkCompilerRelationTest.testActual*' \
  --init-script gradle/test-sdk.init.gradle \
  -x compileKotlin -x compileJava -x compileTestKotlin -x compileTestJava \
  -x instrumentCode -x instrumentTestCode
```

The tests assert the actual IDE compiler version. They use a test-only public
compiler-plugin provider and the genuine JAR, not hand-authored output shapes,
renamed binaries, runtime relations or private FIR/classloader reflection.
The generated fixture class is explicitly excluded from the normal suite when
no matching compiler fixture is requested. Its two generated cases run only in
the opt-in lane and are not counted as ordinary passing tests.

The generated tests have passed on IDEA 262.9437.185:

- Cold `Rel<SourceOut>` discovery from actual compiler-generated members,
  followed by `r.code` resolution inside the DuckDB injection.
- Refined generic-call expression with extra `id`, `code`, `added` columns,
  while the generic body's `Rel<T : HasId>` exposes only the declared `id` bound.

Formal helper publication should follow final plugin ZIP, verifier and optional
Kotlin/classloader checks, not substitute for them. The plugin version remains
0.3.1 during this development integration.

## Verified on 2026-10-10

- Default IDEA 261 suite: 167 tests, 158 passed, nine live/optional checks skipped.
- Both genuine generated-shape cases pass on IDEA 262.9437.185 with compiler
  `2.4.20-ij262-34`, using 261-compiled fixture/production classes.
- DataGrip 2026.2.5 passes the native suites with Kotlin absent.
- Real PluginClassLoader isolation passes with Doris and SQL Transpiler installed;
  the SQL engine and the shared helper contract classes belong to DuckDB's loader.
- `buildPlugin` and `verifyEmbeddedPipes` pass, including the exact four-JAR set,
  Java 21 bytecode and bundled notices. Kotlin/SDK/compiler/native JDBC JARs are
  not bundled.
- Plugin Verifier 1.410 reports Compatible on all five Doris targets:
  DB-261.24374.56, IU-262.8665.81, DB-262.10315.132, IU-263.4732.28 and
  IU-263.5701.42. API-use notices are unchanged from the prior implementation:
  15 experimental uses, two scheduled-for-removal uses, one deprecated use.
  An unnecessary deprecated column-method override and experimental symbol API
  calls were removed rather than adding ignored-problems entries.

The repeated DDL fixture previously waited on `waitComputed()` on the EDT while
automatic introspection required the same lock. The wait now runs with the
pooled sync task; the combined suite completes. Contract column symbols also
have stable statement-occurrence-aware equality for the IDE resolve cache,
without identifying two aliases of the same input as the same query row.

Validated helper JAR SHA-256:
`d4d0d3f45ba6c83fe081e459d383e7183657149b0b3a53c0dba7d51e0a889149`.

Rebuilt ZIP: `build/distributions/duckdb-intellij-plugin.zip`.
SHA-256: `ceae4bf21c29e82d8ca6dd1f6053924430f7d5a0b273e6d31da1d9340664fd55`.

Next publication step: publish the validated helper formally, then override or
update `brikkIdeSupportVersion` and repeat the consumer gates. Maven Local is
restricted to the development snapshot version, so it cannot shadow a released
Central coordinate.

## Central 0.1.0 transition

The published `dev.brikk.house:brikk-engine-kotlin-intellij-support:0.1.0`
POM and JAR are available directly from Maven Central. The default consumer
coordinate is now 0.1.0, and Maven Local has been removed from the build.
Packaged notices and the real-classloader assertion now name the release JAR.

Central JAR SHA-256:
`3505484cc5cce7aa0f0b614249be234146901c1d61d542428d515dd21585ff26`.
All 31 class files match the previously validated snapshot byte for byte.

The local consumer ZIP is now version 0.3.2 so it is distinguishable during IDE
testing. This version bump and Central dependency transition are committed
without waiting for the remaining verification lane; no push or publication
is performed. The snapshot validation results above are historical, not a
claim that the final 0.3.2 artifact has completed verification.

### Completed 0.3.2 validation

Validation ran in `/tmp/opencode/duckdb-032-validation`, with a separate build
directory, sandboxes and reports. The user's installable ZIP was not rebuilt,
overwritten or touched. A validation copy passed the exact dependency/notices/
Java 21 checks, and the isolated rebuild is byte-for-byte identical to that ZIP.

- 167 default tests: 158 passed, nine live/optional skips.
- Plugin Verifier 1.410: Compatible on all five pinned Doris targets, including
  both 263 EAPs. API-use notices remain unchanged.
- Both genuine generated-shape tests passed with the reviewed 262 compiler.
- Product-classloader isolation passed alongside Doris and SQL Transpiler.
- DataGrip 2026.2.5 native suites passed with Kotlin absent.

Protected user ZIP SHA-256:
`66e46258024ae84715517ae9d45641a477758e754540a3d92daa07a6baa68b52`.
Its checksum and modification time were verified unchanged after validation.
