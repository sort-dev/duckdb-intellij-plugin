# duckdb-intellij — working notes

Seeded 2026-07-16 from the doris-intellij playbook. Decisions and open threads.

## Settled at seed time

- **Coexist, never override**: new dbms `DUCKDB_BRIKK` ("DuckDB (Brikk)") alongside the stock
  `GenericDbms.DUCKDB`. Stock data sources untouched; no collision if JetBrains ships a real
  DuckDB dialect later.
- **Substrate = Postgres** (`PgDialectBase`): DuckDB's parser is PG-derived (`::`, `$$`-quoting,
  PG operators). The syntax scoreboard test measures what PG can't parse; that list drives the
  lenient-parse/masking work in `DuckdbPsiParser` (doris pattern).
- **Two drivers, one dialect**: `duckdb-brikk-native` (org.duckdb.DuckDBDriver; reuses the
  built-in downloadable "DuckDB" artifact by NAME) and `duckdb-brikk-quack` (GizmoSQL;
  `dev.brikk.duckdb:quack-jdbc` — our patched build, NOT Arrow/ADBC based, no --add-opens).
  Driver = transport; `forced-dbms` binds both to the dialect. If upstream GizmoSQL won't take
  the patches, relabel the driver as brikk later.
- **Engine as authority**: no vendored grammar. `PREPARE` against an in-process DuckDB =
  version-exact validator (proven headless in DuckdbDirectMetadataTest); `duckdb_functions()` /
  `duckdb_keywords()` = completion inventory. brikk-sql-metadata is the fallback if we ever want
  engine-free catalogs (doris pattern), but DuckDB self-describes better than any sidecar.

## Blocked on facts (ask Jayson)

- **quack-jdbc coordinates**: `dev.brikk.duckdb:quack-jdbc:0.3.0-brikk-SNAPSHOT` is not in
  mavenLocal here. Need: repo URL (or `mvn install` it locally), the driver CLASS name, and the
  JDBC URL scheme/prefix. `config/duckdb-brikk-drivers.xml` carries clearly-marked placeholders
  (`dev.brikk.duckdb.jdbc.QuackDriver`, `jdbc:quack://host:31337`) until then.
- **GizmoSQL endpoint** for live quack testing (or docker-compose recipe).
- **GitHub repo**: local git only until the org repo (sort-dev/duckdb-intellij-plugin?) is
  created and public/private is decided.

## Known follow-ups (ordered)

1. **Before ANY live data-source testing**: `DuckdbModelFacade` — the model facade / dialect /
   introspector trio must agree or connecting throws ClassCast (`GenericImplModel$Root cannot be
   cast ...` — the doris lesson). Facade → `PgMetaModel.MODEL` (public, per doris catalogs
   research). Deliberately absent from the headless seed.
2. Scoreboard-driven parser work: lenient statement boundaries for FROM-first, PIVOT/UNPIVOT,
   ATTACH/DETACH, COPY, SUMMARIZE; then per-construct upgrades (EXCLUDE masking is the known
   doris-EXCEPT analog if the grammar can't be taught).
3. Runtime validator plumbing: run PREPARE/duckdb_functions() through the DATA SOURCE's own
   driver classpath (never bundle the engine; ~50 MB and version-drift). Doris's
   fe-sql-parser-annotator architecture, but the "parser" is the user's actual engine version.
4. Completion contributor fed by duckdb_functions() (cache per data source + version).
5. Error suppression pass once real red-noise is observed in dogfooding (HighlightInfoFilter
   machinery ports 1:1).
6. Marketplace prep: real icon, screenshots, description pass, `intellijPlatformPublishingToken`.
7. Doris Pipes / brikk-sql integration: same optional-transpiler-plugin seam as doris
   (`doris-pipes.xml` pattern) once the dialect is stable — DuckDB is a brikk-house surface too.
