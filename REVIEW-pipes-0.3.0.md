# DuckDB PIPE 0.3.0 verification

The DuckDB plugin now owns brikk-sql and metadata 0.18.0. SQL Transpiler is
neither a dependency nor an enablement signal. PIPE is opt-in per project under
Settings > Tools > DuckDB PIPE. The production compatibility range remains
261 through 262; no Marketplace publication is performed by this change.

## Implementation

- `DuckdbPipes` defines SQL-aware UTF-16 statement ranges, unquoted operator
  ownership, and incomplete-boundary refusal independently of PSI masks.
- `DuckdbPipesEngine` lowers top-level/nested pipes to DuckDB SQL and retains
  source maps. Intentional refusals and unsupported warnings block execution.
- `DuckdbPipesActionConfiguration` uses the public dynamic customizer and
  captured predecessors. It does not use XML action overrides or the internal
  `ActionConfigurationCustomizer`. Promotion calls the registered platform
  promoter through its public interface.
- `PipeScriptModel` transforms only claimed statements, preserves normal
  parameter substitution and request chaining, and preflights the entire
  selected script before its first request. Native syntax preflight uses a
  reachable local driver without requiring one for remote PIPE execution.
- PIPE diagnostics work without a JDBC validator. Ordinary SQL retains its
  native validation; FROM-first syntax stays ordinary SQL. Preview,
  run-to-stage and stage-operator/prior-output-alias completion are included.

## Verification commands

```bash
mise exec -- ./gradlew test buildPlugin verifyEmbeddedPipes
mise exec -- ./gradlew verifyPlugin
mise exec -- ./gradlew test -Ptest.pluginIsolation=true -Ptest.sqlTranspiler=installed \
  -Ptest.dorisPluginZip=/absolute/path/to/doris-intellij-plugin.zip
```

The isolation lane uses actual PluginClassLoaders, checks the 0.18.0 artifact
resource, and verifies independent engine identities for installed peers and
the captured Execute chain down to stock. Normal flattened-fixture companion
tests exclude peers' brikk JARs rather than accidentally testing their older
engines. The installed companion is verification-only.

For current 262 runtime tests, first compile/instrument on the default 261 SDK:

```bash
mise exec -- ./gradlew test -Pduckdb.localIde=/absolute/path/to/datagrip-2026.2.5 \
  --tests 'dev.sort.duckdb.pipes.*' --init-script gradle/test-sdk.init.gradle \
  -x compileKotlin -x compileJava -x compileTestKotlin -x compileTestJava \
  -x instrumentCode -x instrumentTestCode
```

The candidate passed Plugin Verifier 1.410 with no compatibility problems on
all five targets used by Doris:

| Target | Verdict |
| --- | --- |
| DB-261.24374.56 | Compatible |
| IU-262.8665.81 | Compatible |
| DB-262.10315.132 | Compatible |
| IU-263.4732.28 | Compatible |
| IU-263.5701.42 | Compatible |

The two 263 builds are forward-compatibility checks; the advertised install
range remains 261/262. These exact targets are now configured in the build.
Reports
retain 15 experimental API uses, two scheduled-for-removal uses, and one
deprecated use. These are reported, not hidden or claimed approved by
Marketplace. `verifyEmbeddedPipes` checks the exact three-JAR ZIP, bundled
notices, Java 21 bytecode ceiling, and absence of the SQL Transpiler dependency.

The full 261 run has 117 tests: 108 passed and nine live/optional checks skipped.
All 23 PIPE tests pass on the current 262 runtime and in a 261 fixture with
Doris and SQL Transpiler installed. A separate real-classloader lane passed
with both peers installed. The 262 checks run the 261-compiled classes rather
than recompiling production code against a newer API.

Installable artifact: `build/distributions/duckdb-intellij-plugin.zip`.
SHA-256: `9af1684bbbf93333aa12f19af403fc3ee9434f973bc19c31e035de66b2820cc6`.

Real-console tests use offline recording sessions, not a live remote server.
The generated SQL smoke test executes against the pinned embedded test JDBC
driver. Live Quack suites remain opt-in.

## Remaining limits

Server errors are shown against the generated SQL through normal console
output. Exact DuckDB server-error map-back is not yet enabled. Completion
offers known prior-stage output aliases, not schema-backed base-table columns.
If another plugin captures our wrapper before unload, detaching preserves
delegation but can retain a loader reference and require an IDE restart, as in
the Doris cooperative execution contract. Remote Quack cancel remains limited
by the driver, unchanged by PIPE support.
