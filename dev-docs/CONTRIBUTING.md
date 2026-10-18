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

## Versioning and releases

The base version is set in `build.gradle.kts`. Normal source builds add the commit time and SHA. Dirty worktrees also receive a `.dirty` suffix.

Build a release-labelled artifact with:

```bash
./gradlew buildPlugin -Prelease=true
```

Release preparation must land on `main` before creating `release/<version>` from that commit. Pushing the release branch runs `.github/workflows/release.yml`, verifies the plugin, and creates the GitHub release. It does not upload to JetBrains Marketplace.

## Updating DuckDB

When changing the DuckDB engine version:

1. Update `duckdb-jdbc` in `settings.gradle.kts` and the artifact version in `src/main/resources/config/duckdb-brikk-artifacts.xml`.
2. Run `./gradlew harvestCensus` and review the corpus changes.
3. Refresh the bundled function, keyword, and extension metadata.
4. Run the full test and verification commands above.
5. Check both embedded DuckDB and a matching Quack server.

Read the [DuckDB 2.0 watch](DUCKDB-2.0-WATCH.md) and [work list](TODO-duckdb-v2.md) before updating to DuckDB 2.0.
