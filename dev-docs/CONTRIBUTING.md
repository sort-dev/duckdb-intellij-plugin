# Contributing

## Requirements

- JDK 21
- Git
- Network access for Gradle to download the IntelliJ SDK and dependencies

The build targets IntelliJ Platform 2026.1 and checks compatibility with 2026.2 and 2026.3.

## Build and test

Run the main test suite:

```bash
./gradlew test
```

Build an installable plugin ZIP:

```bash
./gradlew buildPlugin
```

The ZIP is written to `build/distributions/`.

Before a release, run the platform and distribution checks:

```bash
./gradlew test262 test263
./gradlew verifyDistribution verifyPlugin
```

To run the optional live Quack tests, provide a server URL with a token:

```bash
./gradlew test -Dquack.live.url='jdbc:quack://localhost:9494?token=<token>'
```

## Isolated Quack v3 cancellation test

This gate uses the actual Quack JDBC 0.12.1 client and DuckDB JDBC 2.0.0.0 to start its own
signed-extension server in a child JVM. Provide signed, matching DuckDB 2.0 Linux extension binaries:

```bash
./gradlew test --tests 'dev.sort.duckdb.probe.QuackV3CancellationTest' \
  -Ptest.quackExtension=/absolute/quack.duckdb_extension \
  -Ptest.httpfsExtension=/absolute/httpfs.duckdb_extension
```

The fixture uses a random loopback port and token, disables extension auto-install/autoload and
persistent secret creation, and loads only the supplied signed binaries. It never contacts a user's
server or installs into the user's extension store. Teardown stops or kills its own child process.
Without both properties the gate is excluded; supplying only one is a configuration error.

The tests cancel an observed running query from another thread through standard `Statement.cancel()`
and the IDE's `GenericJdbcHelper`/`RemoteStatement` wrapper. Both require SQLSTATE `57014`, prompt
termination and `SELECT 42` on the same Quack session afterward. This does not automate the IDE Stop
button or a remote JDBC helper process. A separate real-query check bounds `requestTimeout=PT1S`;
an injected stalled cancellation transport checks the control deadline and closed-session fallback.
`Statement.setQueryTimeout` is still a no-op and an HTTP timeout is not proof of server cancellation.
The older external-URL metadata suite retains its historical major-1 server expectations.
The same isolated fixture also checks 0.12.0 result classification through standard JDBC and the
IDE wrapper: SELECT/RETURNING count-shaped columns remain rows, DML returns affected-row counts,
and draining results terminates with update count -1.

## Native grammar integration test

The grammar-profile regression uses DuckDB JDBC 2.0.0.0 and an already installed, signed community
`psql` extension. Supply the binary from its normal installed layout, including its adjacent
installation metadata. The test derives the extension-directory root and explicitly runs `LOAD psql`
on its own temporary file-backed source. It does not install extensions or disable signature checks.
Loading an absolute binary path is not equivalent to loading the installed community module.

```bash
./gradlew test \
  --tests 'dev.sort.duckdb.catalog.DuckdbLiveCatalogConsoleTest.testNativePsql*' \
  -Ptest.psqlExtension=/absolute/extensions/v2.0.0/linux_amd64/psql.duckdb_extension
```

The tests disable Brikk PIPE editing and check native acceptance, malformed syntax, activation-off
rejection, editor diagnostics, helper-local activation and absence of binding/execution side effects.
The real `DataSourceConfigurable` Apply path also runs with live file and in-memory sessions.
Those regressions preserve the exact connection objects, TEMP tables, session variables and loaded
psql registration across native Apply/Cancel, explicit grammar Apply, repeated scans and deferred
transaction changes. The host controller is a public-interface test double; the native Apply and
reconnect classification code, option editors, remote JDBC process and connections are real.
Without this property, the native binary gate is excluded from the ordinary network-free suite.
The extension binary is not vendored or packaged with the plugin.

The native completion gate also requires the signed `autocomplete` extension installed beside psql
in that isolated extension directory. Its test explicitly loads the module, verifies the missing-module
guard does not autoload it, and exercises original pipe keywords, UTF-8 offsets, IDE insertion,
disabled profiles, cancellation and parse-without-binding behavior. It is included by `testNativePsql*`.

## Versioning and releases

The base version is set in `build.gradle.kts`. Normal source builds add the commit time and SHA. Dirty worktrees also receive a `.dirty` suffix.

Build a release-labelled artifact with:

```bash
./gradlew buildPlugin -Prelease=true
```

Release preparation must land on `main` before creating `release/<version>` from that commit. Pushing the release branch runs `.github/workflows/release.yml`, verifies the plugin, and creates the GitHub release. It does not upload to JetBrains Marketplace.

Marketplace publication is a separate, explicit action. With the Marketplace token configured,
`./gradlew publishPlugin -Prelease=true` publishes a locally built release, not a development ZIP.
To publish the exact GitHub asset instead, use the checked download and archive override supplied
with the release handoff. Local and CI rebuilds can differ in JVM/OS manifest metadata even when
all production classes and dependencies match. Do not assume their ZIP hashes are interchangeable.

## Updating DuckDB

When changing the DuckDB engine version:

1. Update `duckdb-jdbc` in `settings.gradle.kts` and the artifact version in `src/main/resources/config/duckdb-brikk-artifacts.xml`.
2. Run `./gradlew harvestCensus` and review the corpus changes.
3. Refresh the bundled function, keyword, and extension metadata.
4. Run the full test and verification commands above.
5. Check both embedded DuckDB and a matching Quack server.

Read the [DuckDB 2.0 watch](DUCKDB-2.0-WATCH.md) and [work list](TODO-duckdb-v2.md) before updating to DuckDB 2.0.
