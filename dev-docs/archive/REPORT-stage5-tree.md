# Stage 5 — Object Tree / Introspection Truth Battery

Measured 2026-07-23 against duckdb_jdbc 1.5.5.0 (native, in-process), quack-jdbc 0.4.0 over a live
GizmoSQL server (engine DuckDB v1.5.3), and DataGrip 2026.1.3 DatabaseTools bytecode. Every fact is
pinned by a test in `src/test/kotlin/dev/sort/duckdb/probe/`. `// GAP:` there == a row here.

## Headline verdicts

- **getCatalogs() lists ATTACHed databases** (file + `:memory:`), native AND over the quack wire.
  Multi-database trees are viable off stock JDBC metadata.
- **The native introspection surface is strong**: tables, views, columns (with DuckDB type spellings
  incl. STRUCT/LIST), NOT NULL, DEFAULT, PRIMARY KEY, FOREIGN KEY all populate.
- **The PG (pg_catalog) introspector does NOT run for us.** `PgIntrospector.Factory.isSupported`
  requires server major >= 9; DuckDB reports major 1 and does not emulate `server_version`, so the
  platform falls back to the **generic JDBC introspector** (`JdbcIntrospector`). Good outcome — it
  reads the strong native metadata below and sidesteps DuckDB's pg_catalog gaps.

## JDBC metadata truth table — native vs quack

| Method | Native duckdb_jdbc 1.5.5 | Quack 0.4.0 (live, engine 1.5.3) |
|---|---|---|
| identity | product `DuckDB`, `v1.5.5`, driver `DuckDBJ` | product `DuckDB (via Quack)`, `v1.5.3`, driver `quack-jdbc 0.1` |
| getCatalog() | db name (file basename; `main`->`main_db`) | **null** (DIFF — tree must not rely on it) |
| getCatalogs | primary + ATTACHed DBs + `system`,`temp` | `memory`,`system`,`temp` + ATTACHed alias |
| ATTACH ':memory:' AS x -> getCatalogs | x listed | x listed (DIFF holds over the wire) |
| getSchemas | catalog+schema pairs; `main` per catalog | same |
| getTables + catalog filter | all-catalog + filter honored; TABLE/VIEW typed | same (system -> 47 rows) |
| getColumns STRUCT | `STRUCT(a INTEGER, b VARCHAR)` (type 2002) | identical |
| getColumns LIST | `VARCHAR[]` (type 1111) | identical |
| getColumns DECIMAL | `DECIMAL(10,2)` (type 3) | identical |
| getColumns NOT NULL / DEFAULT | NULLABLE=0 / COLUMN_DEF=`1` | identical |
| getPrimaryKeys | PK columns; **PK_NAME null (GAP)** | PK columns; PK_NAME null (same gap) |
| getImportedKeys | FK child.parent_id->parent.id, FK_NAME set | **richer** — also PK_NAME + UPDATE/DELETE_RULE + DEFERRABILITY |
| getIndexInfo | index named; **COLUMN_NAME null (GAP)** | index named; COLUMN_NAME null (same gap) |
| getTableTypes | TABLE, VIEW, LOCAL TEMPORARY, SYSTEM VIEW | same |
| getTypeInfo | 21 rows | 20 rows (engine-version diff) |

**Quack static audit** (`QuackMetadataFactsTest`): `com.gizmodata.quack.jdbc.sql.QuackDatabaseMetaData`
overrides **175 of 177** `DatabaseMetaData` methods (184 declared members; only JDBC-default
`supportsRefCursors`/`supportsSharding` left). Every tree-relevant method is a real query-backed
override, confirmed populated by the live suite above.

**Stage-4b harvest over the wire** (`QuackLiveTruthTest`): `version()` -> `v1.5.3` (server engine,
independent of our bundled 1.5.5); `duckdb_functions()` -> 2962; `duckdb_keywords()` -> 489;
`duckdb_extensions() WHERE loaded` -> autocomplete, core_functions, httpfs, icu, json, parquet,
quack, shell.

## Attached-DB / catalog visibility verdict

ATTACH (file + `:memory:`) -> each database is its own catalog; `getTables(null,...)` sees across all
catalogs; `getTables(alias,...)` narrows correctly — native and quack. **The tree must derive the
catalog from getCatalogs()/the ATTACH alias, never assume `"main"` and never rely on
connection.getCatalog()** (null over quack).

## Introspector determination (from bytecode)

- `DuckdbModelFacade` -> `PgMetaModel.MODEL` + `PgModelHelper`; `extensionFallback DUCKDB_BRIKK->POSTGRES`.
- `DBIntrospectorFactory.INTRO_EP` is a `DbmsExtension<DBIntrospector.Factory>` (fallback-aware).
  Selection = `INTRO_EP.forDbms(dbms).isNative() && .isSupported(version)`.
- `forDbms(DUCKDB_BRIKK)` -> `PgIntrospector.Factory` (via POSTGRES fallback); `isNative()`=true.
- `PgIntrospector.Factory.isSupported(v)` disassembles to **`v.isOrGreater(9)`**.
- DuckDB `getDatabaseMajorVersion()==1`; `current_setting('server_version')` -> *unrecognized
  configuration parameter*. => isSupported=false => native PG introspector rejected.
- Fallback = `com.intellij.database.dialects.base.introspector.jdbc.JdbcIntrospector`
  (`Factory.isNative()`=false, `isSupported`=always true) — reads JDBC `DatabaseMetaData`.

### pg_catalog emulation gap table (DuckDB 1.5.5, isolated-connection probe)

| Emulated (introspection backbone + views + fns) | Missing |
|---|---|
| pg_class, pg_namespace, pg_attribute, pg_constraint, pg_type, pg_proc, pg_depend, pg_index, pg_indexes, pg_attrdef, pg_collation, pg_enum, pg_sequence, pg_description | pg_roles, pg_authid, pg_user, pg_get_userbyid() |
| pg_tables, pg_views, pg_database, pg_settings, pg_sequences | pg_matviews, pg_inherits, pg_trigger |
| version(), current_schema(s)(), current_database(), format_type(), pg_get_constraintdef(), pg_table_is_visible(), obj_description(), has_table_privilege() | pg_relation_size(), pg_total_relation_size(), pg_encoding_to_char() |

pg_class -> pg_namespace -> pg_attribute joins run (with `format_type` + `attnotnull`). **Gap severity:
LOW today** — the generic introspector runs, so pg_catalog gaps don't touch us. They matter only
under a forced-PG introspector (missing role/ACL/size catalogs would make PG L1 queries throw).

## DECISION MEMO — object tree

**A — Stay on the generic JDBC introspector (status quo, automatic).** Cost ~= 0 (already the
effective behavior). Delivers: catalogs=ATTACHed DBs, schemas, tables/views, columns with DuckDB
type strings, NOT NULL, DEFAULT, PK, FK, index names. Limits to document: PK/index *names* null;
STRUCT/LIST shown as opaque type strings (no expandable sub-fields); sequences / macros / secrets
absent (JDBC metadata has no slot for them).

**B — Custom DuckDB introspector, doris-style.** Implement `DBIntrospector.Factory` for DUCKDB_BRIKK
over DuckDB's own `duckdb_tables()/duckdb_columns()/duckdb_constraints()/duckdb_indexes()/
duckdb_sequences()/duckdb_databases()`, registered `isSupported=true` to beat both the generic and
the (rejected) PG factory. Delivers full fidelity: sequences, macros/secrets, constraint & index
names, STRUCT/LIST field expansion, ATTACH-aware catalogs. **Cost: exposes the
`@ApiStatus.Internal com.intellij.database.introspection.DBIntrospector` API** — the exact internal
dependency our sibling **doris** plugin already carries behind a defense note and open JetBrains
ticket **IJPL-249765**. Ongoing verifier warnings + platform-bump breakage risk.

**C — Hybrid enrichment.** Keep the generic introspector, patch only the gaps (sequences, PK/index
names) from `duckdb_*` functions. Rejected: the platform exposes no clean public seam to augment a
JDBC-introspected model, so it still touches internal model-mutation APIs — most of B's internal-API
cost without B's completeness.

**Recommendation: ship A now, plan B.** A already runs and yields a genuinely useful tree from
DuckDB's strong native JDBC metadata (proven above) at zero internal-API cost — consistent with the
plugin's "measured coverage / engine-as-authority" posture. Hold B (native `duckdb_*` introspector)
until tree fidelity becomes a headline feature; it's well-trodden (doris + IJPL-249765 as cover) but
not worth the internal-API tax before it's needed.

**One residual item these headless tests can't prove**: confirm in a live IDE that `JdbcIntrospector`
populates the `PgMetaModel` facade without a ClassCast (the doris model-family lesson). PgMetaModel is
a superset of the base JDBC model, so it should hold — but it is the single thing not covered here.
