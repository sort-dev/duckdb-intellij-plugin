# duckdb-intellij — the full coverage plan

From the seed to THE END OF TIME AND ALL ARE HAPPY AND CHEERING OUR PLUGIN.

Written 2026-07-21 against the seed state (`c50634d`): PG-substrate dialect proven, two drivers
fact-complete, scoreboard 7/22 (greens = the PG intersection; every DuckDB invention still red),
engine-as-authority proven in-process. Doris-intellij is the playbook source throughout; the
technique names below (lenient boundaries, masking + recolor, suppression filters, golden corpus,
targeted introspection) refer to machinery already shipped there and port ~1:1.

Each stage has an exit criterion and a **cheer moment** — the thing a user actually feels.

---

## Stage 0 — Seed ✅ (done)

Dbms `DUCKDB_BRIKK` alongside stock, PG substrate, native + quack driver templates, syntax
scoreboard, `PREPARE`-validator and `duckdb_functions()` proven against an in-process DuckDB.

---

## Stage 1 — Census + Livability (the "usable in a console" release)

**Goal: every DuckDB statement has correct boundaries and no false red; dogfoodable daily.**

1. **Corpus wave 2** (~15 files; agent grunt work): trailing commas, reusable column aliases
   (`SELECT a+1 AS b, b*2`), prefix aliases (`name: expr`), `COLUMNS()` with regex/lambda/EXCLUDE,
   `UNION BY NAME` / `INSERT BY NAME`, `ASOF JOIN` / `POSITIONAL JOIN`, function chaining
   (`s.trim().lower()`), `CREATE SECRET` / `CREATE TYPE` / sequences, `INSTALL` / `LOAD`,
   `EXPORT` / `IMPORT DATABASE`, `SET` / `RESET` / `PRAGMA` forms, `DISTINCT ON`,
   `IGNORE NULLS` + window frame EXCLUDE, `COMMENT ON`, `CHECKPOINT` / transactions, `CALL`.
   Exit: the scoreboard is a **census**, not a sample.
2. **Tier-1 lenient statement boundaries** in `DuckdbPsiParser` (bounded look-ahead dispatch +
   consume-to-`;`, the doris Phase-3 pattern): `ATTACH`/`DETACH`/`USE`, `SUMMARIZE`, `DESCRIBE`,
   `PIVOT`/`UNPIVOT`, FROM-first statements, `CREATE MACRO`/`SECRET`/`TYPE`, `COPY` with modern
   options, `INSTALL`/`LOAD`, `EXPORT`/`IMPORT`, `CALL`, `SET`/`PRAGMA`. Query-tail statements
   hand `SELECT`/`WITH` to the real parser (completion inside stays alive).
3. **Suppression baseline**: port the `HighlightErrorFilter`/`HighlightInfoFilter` machinery;
   blanket-quiet inside lenient statements; nothing authoritative validates there yet, so nothing
   may scream there.
4. **`DuckdbModelFacade`** (PgMetaModel) — the ClassCast gate. Mandatory before any live
   data-source attach, i.e. before dogfooding. Small, known shape.
5. **Golden corpus discipline starts**: record goldens once boundaries stabilize; scoreboard
   keeps measuring, goldens keep it honest (the doris dual).

Exit: scoreboard strong-majority green at parse level; run-boxes correct on every statement;
consoles usable against both drivers. **Cheer moment: "I pasted my real DuckDB script and nothing
is red and every statement runs."** → publish **0.1.0 (preview)** to Marketplace; real users
early was the single best doris decision.

## Stage 2 — Friendly SQL, for real (expression-level syntax)

**Goal: DuckDB's signature expressions parse with structure, not just silence.**

- `EXCLUDE`/`REPLACE` star modifiers — the doris `EXCEPT`-mask port (lexer mask + recolor +
  count-mismatch/ambiguity suppressions come along).
- `QUALIFY` — StarRocks token-swap trick (`QUALIFY`→`HAVING` for structure) or lenient tail.
- `GROUP BY ALL` / `ORDER BY ALL` — small masks; PG grammar takes the rest of the clause.
- `TRY_CAST` — the doris cast-tail mask ports verbatim.
- Trailing commas — lexer-level swallow (safe: never legal PG, always legal DuckDB).
- `UNION BY NAME`, `ASOF`/`POSITIONAL JOIN`, `IGNORE NULLS` — keyword masks.
- Reusable column aliases — semantic, not syntactic: suppress the unresolved-reference noise,
  model later (Stage 8).
- The hard trio, assessed AFTER the above (each degrades gracefully via masking, real structure
  is parser work): struct literals `{'a': 1}`, lambdas (`x -> f(x)` currently mis-parses as the
  PG JSON operator — syntactically quiet, semantically wrong tree), list comprehensions.

Exit: full friendly-SQL corpus green; completion works around (not yet inside) masked spans.
**Cheer moment: "EXCLUDE and QUALIFY just work like in the DuckDB CLI."**

## Stage 3 — The Engine Authority (version-exact errors)

**Goal: DuckDB itself validates every file — the fe-sql-parser role, but always version-matched.**

- `DuckdbErrorAnnotator`: `PREPARE`-validate file text against an **in-process DuckDB loaded from
  the data source's own native driver jar** (isolated classloader, weak-cached per driver
  version; `:memory:` instance, schema-less prepare = syntax authority). Never bundle the engine
  (~50 MB, version drift).
- Quack-only data sources (no local jar): choose per evidence — optional one-time engine
  download (artifact mechanism), or opt-in server-side `EXPLAIN` on an idle connection, or
  suppressions-only. Dialect-mapped files with no data source: metadata-only mode, no validator.
- Error positions map 1:1 (same text, no transpile) — balloon/squiggle machinery ports directly.

Exit: real syntax errors appear with DuckDB's own messages at exact positions; false-positive
rate ~zero because the authority is the engine. **Cheer moment: "the editor caught exactly what
the server would have said, before I ran it."**

## Stage 4 — Completion + the metadata cache (extensions-aware)

**Goal: completion that follows the engine — including whatever extensions are loaded.**

Three-source function/keyword catalog, in priority order:

1. **Connected** — **DONE (Stage 4b)**: a `<database.connectionInterceptor>` harvests
   `version()`/`duckdb_extensions()`/`duckdb_functions()`/`duckdb_keywords()` on every connect
   into a per-data-source cache (`DuckdbLiveCatalog`, one TSV under `$SYSTEM/duckdb-brikk/catalog`
   so completion survives restarts offline) that REPLACES the bundled snapshot for that editor
   (`DuckdbCatalogResolver`: the console's data source, else the project's single Brikk source);
   fail-soft with a 5s deadline, never blocks connect. Refresh on observed `INSTALL`/`LOAD` —
   **DONE**: a project-root `DataAuditor` (`DataBus.addRootAuditor`, flag-free API) head-scans every
   finished request and re-harvests the source debounced ~2s (`DuckdbInstallLoadObserver`), plus a
   manual "Refresh DuckDB Catalog" action (editor menu + Find Action, balloon lists loaded
   extensions) — the action is the ONLY path that picks up AUTOLOADED extensions (no INSTALL/LOAD
   ever executes for those, so no observer can see them). Still open from this bullet: parameter
   hints/quick-doc, `duckdb_settings()`.
2. **Not connected**: bundled **base snapshot** harvested at build time in CI from the pinned
   duckdb_jdbc (self-updating with the build, zero hand-maintenance — or via brikk-sql-metadata
   if a DUCKDB catalog lands there; coordinate, don't duplicate).
3. **Extension-aware offers** — **DONE (Stage 4b, code side)**: optional
   `duckdb/extension-functions.tsv` resource (`name<TAB>kind<TAB>extension`, mixed-case names as
   duckdb_functions() reports them) adds e.g. `ST_Read(...)` labeled *"requires spatial"* — only
   names the active catalog lacks, matched case-folded; resource absent = layer silently off
   (the harvested map arrives from its own lane).

Plus: TVF named-parameter completion for `read_csv`/`read_parquet`/`read_json` (from function
metadata), `PRAGMA`/`SET` completion from settings, keyword completion, the doris
autopopup-calm rules (expression positions only).

Exit: completion correct per connected engine+extensions, useful offline. **Cheer moment:
"I LOADed spatial and st_* appeared in completion."**

## Stage 5 — Introspection + the object tree

**Goal: the schema tree tells the truth for both transports.**

- Generic-JDBC introspection shakedown on both drivers (quack `DatabaseMetaData` quality pass —
  we own the driver; fix at the source when it lies).
- **ATTACH'd databases as catalogs** — DuckDB is multi-catalog; the doris catalogs experience
  (two-level model, stepped switcher, out-of-scope degrade, targeted auto-introspection) is the
  reference. Direct-query upgrade path: `duckdb_tables()/columns()/views()/constraints()` beats
  JDBC metadata when present.
- Secrets/extensions/settings surfaced read-only in the tree (later polish).

Exit: attach a file or a GizmoSQL server, see the real tree, completion resolves against it.
**Cheer moment: "ATTACH showed up in the tree without a refresh hunt"** (targeted
auto-introspect, already invented for doris).

## Stage 6 — Run/console UX

- Multi-statement scripts with correct chunking (falls out of Stage 1 boundaries).
- **Cancel**: we own quack — put a real cancel in the driver (the thing doris had to
  reverse-engineer); native driver: `duckdb_interrupt` path if exposed, else document.
- `SUMMARIZE`/`DESCRIBE` render as grids; transaction state surfaced; COPY progress if the
  protocol offers it.

**Cheer moment: "Stop actually stopped the 40-minute aggregate."**

## Stage 7 — Ship + ecosystem

- Marketplace release train: the doris CI shape is already in the repo (release-* → zip +
  GitHub release + verifier 261/262). Publish early, iterate publicly; version discipline and
  honest change-notes per the house rules.
- Real icon, screenshots, listing copy; GizmoSQL connection recipes (docker compose for tests);
  quack relabel decision (com.gizmodata → brikk) when upstream's PR appetite is known.
- **brikk-house integration**: the optional-transpiler seam (doris-pipes.xml pattern) brings
  Doris Pipes-style authoring to DuckDB consoles when brikk-sql speaks duckdb — second surface,
  same architecture, engine stays out of this plugin.

## Stage 8 — END OF TIME (the long tail that makes it *loved*)

- Retire masks with real parser structure where it pays: navigation/rename inside `EXCLUDE`
  lists, struct fields, lambda parameters (the masking-retirement analog of doris's `|>` plans).
- Model the semantics currently only quieted: reusable aliases in resolution, `COLUMNS()`
  expansion, star-modifier column visibility.
- Quick-doc from `duckdb_functions().description` + examples; parameter info everywhere;
  DuckDB-specific inspections (e.g., `ORDER BY` in subquery smells, implicit-cast warnings).
- File-path completion inside `read_*('…')` string args (glob-aware).
- Extension-defined syntax if any extension ever grows grammar (spatial operators etc.).
- Formatter tuning; code style page; live templates for COPY/PIVOT idioms.

---

## Cross-cutting rules (from the doris ledger, non-negotiable)

- Scoreboard + golden corpus stay green in CI; no silent regressions.
- Verifier on both generations for every release branch; internal-API usage only with a defense
  note; watch-list maintained.
- No engine bundled, ever. The authority is always the user's engine version.
- Design-first with Jayson on anything user-visible; publish only on his word; version numbers
  and change-notes move only with explicit direction.
