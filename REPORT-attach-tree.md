# Auto schema introspection for DuckDB — measurements & decisions

Why the doris/trino lazy-introspection story needed a DuckDB-shaped answer, what was measured
before building it, and what the shipped behaviour is.

## The question

doris and trino both deepen the object tree lazily: path-typing into an enumerated-but-childless
namespace kicks a one-level introspection of exactly that node. DuckDB had none of that — its
`catalog/` package was entirely about the *function* catalog (`duckdb_functions()` harvest,
extension offers, INSTALL/LOAD observer), nothing about the object tree.

The trino motivation (a `hive` catalog is too big to bulk-load) does **not** transfer to a local
`.duckdb` file, so the first job was to find out whether DuckDB has a real gap at all.

## Measurement 1 — driver level: is `ATTACH` visible to other connections?

The IDE introspects over its own pooled connection, not the console's. If `ATTACH` were
connection-local, no refresh could ever see it and the whole idea would be dead.

Probe (duckdb_jdbc 1.5.5.0, one JVM, `jdbc:duckdb:<file>`):

| step | result |
|---|---|
| two connections to the same file in one JVM | both open fine (instance is shared) |
| `ATTACH ':memory:' AS mem_extra` + `ATTACH '<file>' AS side_extra` on connection A | A lists both |
| connection B, opened **before** the ATTACH | **sees both**; `getTables("side_extra",…)` returns `side_extra.main.t_side` |
| connection C, opened **after** the ATTACH | sees both |
| connection D, after every earlier connection closed | **attachments gone** (`ATTACH` is instance state, not file state) |
| `USE side_extra` | `current_catalog()` and `getCatalog()` both follow |

**Verdict:** `ATTACH` is instance-wide, so a model refresh over the pool *does* see it — provided at
least one connection stays open, which is the normal IDE situation (the console connection).

## Measurement 2 — platform level: does the IDE already handle it?

DataGrip does auto-sync after console execution. Bytecode (DataGrip 2026.1.3,
`intellij.database.impl.jar`):

- `JdbcConsole` builds `LoaderContext.selectTasks(project, ds, <collection>)` from the executed
  statement, then calls `DataSourceUtilKt.performAutoSyncTask`;
- on transaction completion it builds a *path-based* context
  (`DataSourceUiUtil.preparePathBasedLoaderContext(project, point, ObjectPath)`);
- `DatabaseOutdatedCheckBuffer.scheduleOutdatedCheck(LocalDataSource, BasicElement)` is likewise
  element-scoped.

Every one of those addresses elements **already in the model**. A catalog that did not exist a
second ago is in none of them.

**Verdict:** ordinary DDL is covered by the platform; `ATTACH`/`DETACH` cannot be.

## Measurement 3 — end to end, in a real platform introspection

`DuckdbAttachTreeLiveTest` — container-free (DuckDB is embedded, so a temp `.duckdb` file *is* the
database) and part of the normal offline gate. Printed measurements from the passing run:

```
STAGE1 connect:            primary[main] | system[] | temp[]   ; scope=@:@
STAGE2 after ATTACH:       side_db NOT in the model            (hard assertion)
STAGE3 after tree refresh: primary[main] | side_db[] | system[] | temp[]
STAGE4 after CATALOG deepen: side_db[main]
STAGE5 after SCHEMA deepen:  side_db.main tables=[side_t]
```

Which pins all four design assumptions:

1. a fresh data source's default scope is `@:@` — namespaces enumerate, contents stay lazy;
2. an ATTACHed database is genuinely **invisible** to the model until something re-lists namespaces;
3. `LoaderContext.selectListNamespacesTask` brings it in — and brings it in **childless**;
4. so the lazy deepening is not a nice-to-have here: it is what makes (3) usable.

## What ships

**ATTACH-aware tree refresh.** `DuckdbAttachDetector` (head words `ATTACH` / `DETACH`, sharing
`DuckdbStatementHeads` with the INSTALL/LOAD detector) feeds a second, independently debounced
branch of the existing execution observer, which calls `DuckdbTreeRefresh.listNamespaces` — the
platform's cheap namespace enumeration, *not* a general refresh, so `ATTACH 'ducklake:…'` never
turns into a full crawl of a remote catalog. The data source's deepen claims are dropped at the same
time (the catalog list changed, so earlier "nothing there" answers are stale).

Deliberately not matched: `USE` (search path, which the platform tracks itself) and ordinary
`CREATE`/`DROP` DDL (already covered — see Measurement 2).

**Lazy deepening.** `DuckdbAutoIntrospect` + `DuckdbCatalogScopes` + `DuckdbNamespacePath` +
`DuckdbIntrospectionTasks` (Java shim) + `DuckdbIntrospectNotifier`, triggered from a second
provider in `DuckdbCompletionContributor` — the doris/trino design, with one DuckDB-specific rule:

> A single path segment can address either an ATTACHed **catalog** or a **schema** of the current
> catalog. Resolution is catalog-first, then schema-relative *only when exactly one catalog offers
> that name*. After `ATTACH`, every catalog has a `main` schema, so a bare `main.` is genuinely
> ambiguous and deepens nothing rather than guessing. (`getCatalog()` would disambiguate, but the
> Stage-5 truth battery measured it null over the quack wire.)

Names in the deepen target come from the model, not from what the user typed — DuckDB resolves
identifiers case-insensitively, `TreePattern` matching does not.

## Cost — smaller than expected

Going in, this looked like it would cost duckdb its internal-API-free profile, because the doris and
trino sources both document `LoaderContext` as class-level `@ApiStatus.Internal`. **That claim does
not hold on DataGrip 2026.1.3.** Per-symbol javap:

- `LoaderContext` — no class-level flag; only `selectSkip` is `@ApiStatus.Internal`, only
  `selectNothing` is `@Deprecated`. `selectTask` / `selectListNamespacesTask` / `selectGeneralTask`
  are plain public API.
- `DataSourceSyncManager.tryPerform` — Kotlin-`@Deprecated` ("use coroutines"; the replacement is a
  suspend fun and these are fail-soft fire-and-forget paths).

Verifier, both generations, after the change: **Compatible**, with an **empty internal-api-usages
report**. The whole delta is two new deprecation entries (`tryPerform`, once from
`DuckdbAutoIntrospect` and once from `DuckdbTreeRefresh`):

| | before | after |
|---|---|---|
| DB-261 | 2 deprecated, 18 experimental | 2 scheduled-for-removal + 2 deprecated, 18 experimental |
| IU-262 | 2 scheduled-for-removal, 18 experimental | 4 scheduled-for-removal, 18 experimental |
| internal API | none | **still none** |

Experimental stays at 18 — all `DatabaseConnectionInterceptor`, untouched by this work.

(Worth carrying back to doris/trino: their KDoc's internal-API defense for `LoaderContext` is
inaccurate on this platform build.)

## Known behaviour worth documenting for users

DuckDB drops attachments when the last connection to an instance closes (Measurement 1, step D). If
the IDE's pool goes fully idle, an `ATTACH` made in a console is gone on the next connect and the
re-listed tree will drop the catalog again. That is DuckDB semantics, not a plugin bug.
