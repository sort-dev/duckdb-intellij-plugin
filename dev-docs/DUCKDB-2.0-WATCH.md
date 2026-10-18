# DuckDB 2.0: what this plugin should watch

September 23, 2026. DuckDB 2.0 (Cyanoptera) is in alpha, with a release
projected for the second half of October. These are integration findings, not
the final release notes. The engine's [preview](https://duckdb.org/2026/08/17/duckdb-20-highlights.html)
and [alpha announcement](https://duckdb.org/2026/09/02/try-duckdb-20-alpha.html)
are the current release-level sources. The longer `brikk-house/TODO-duckdb-v2.md`
in the Brikk checkout covers SQL-library implementation work; this note is about
what the **IntelliJ plugin** might get wrong when users move to 2.0.

The big distinction: DuckDB is replacing its PostgreSQL-derived parser with a
PEG parser while intending to preserve existing DuckSQL. This does **not**
replace our IntelliJ `PgParser`/masking setup. It does let DuckDB extensions add
syntax within ordinary SQL, so the language an editor connection accepts can
depend on that session's settings, not just the engine version. See DuckDB's
[parser explanation](https://duckdb.org/2026/08/20/duckdb-20-peg-parser.html)
and the [merged grammar-extension PR](https://github.com/duckdb/duckdb/pull/24919).

## What the current plugin already gets right

`DuckdbCatalogHarvester` reads `duckdb_functions()` and `duckdb_keywords()`
from the connected engine. The live inventory replaces the bundled 1.5.5
snapshot instead of merging newer names into an older connection. `LOAD`/
`INSTALL` observation and manual Refresh DuckDB Catalog already exist. We do
not need to copy Brikk's static function-catalog plan to get 2.0 function
*names* when connected. Offline completion still needs a new build-time harvest
when the bundled JDBC version changes.

The `DuckdbLexer` already masks `lambda x: ...` for the PostgreSQL-based IDE
parser. Keep that behavior, but check the semantics and highlighting under
2.0: DuckDB now rejects the old `x -> x + 1` lambda by default, while the JSON
`->` operator remains valid. The alpha permits opting back into the old lambda
syntax. Do not mark every `->` as a deprecated lambda. See the
[lambda documentation](https://duckdb.org/docs/current/sql/functions/lambda.html).

## Where 2.0 can still surprise us

**A live function harvest can lose the kind.** DuckDB 2.0 alpha
`v2.0.0-alpha43143` reports 13 functions, including `lag`, `lead`, and
`row_number`, with native `function_type = 'window'`. Our
`DuckdbFunctionCatalog.Kind` has no `WINDOW`, so `kindOf("window")` produces
`OTHER`. Both the live harvester and `harvestFunctionCatalog` collapse entries
by name with first-wins ordering. Preserve kinds when a name has several
callable forms; at minimum, label and icon window functions correctly. The
alpha also contains the new `json_set`/`json_remove` family, JSON reconciliation
functions, and `variant_*` functions. They should appear through the existing
harvest without hardcoded completion entries. The numbers in Brikk's comparison
are `(name, kind)` definitions, **not** this plugin's distinct-name count.

**The validator has the engine, but not the user's session.**
`DuckdbEngineValidator` runs `EXPLAIN` on a fresh in-memory connection loaded
from a DuckDB JDBC jar, and `DuckdbEngineLocator` can find a jar from project
drivers or the IDE's downloaded-driver directory. It does not inherit loaded
extensions, active grammar extensions, variables, macros, or a Quack server's
exact engine version. A parser error there is not automatically a parser error
in the editor's connection. On a Quack-only setup, validation may be silent.
Check the advertised "engine-exact" diagnostics under 2.0 against an actual
extension-enabled session before extending that claim. Keep a separate
"cannot verify in this session" outcome when the validator cannot reproduce
the active grammar. Never execute arbitrary editor SQL to manufacture that
session state.

**`LOAD` no longer answers the whole syntax question.** The 2.0 grammar API
registers grammar extensions that are activated separately through the
connection-local `active_grammar_extensions` setting. The available names are
visible in `duckdb_grammar_extensions()`. A stock alpha connection reported
`[]` for the active setting and no available grammar extensions. Our catalog
cache stores engine version, loaded extensions, names and keywords; it does
not track the active grammar list. `DuckdbInstallLoadDetector` observes
`INSTALL`/`LOAD`, but not a `SET active_grammar_extensions` or its reset.
Those changes need their own refresh/invalidation path if completion,
highlighting, or validation starts making grammar-dependent claims.

**More SQL reaches statement-boundary handling.** The preview includes
`APPROX NEAREST` joins, DML in CTEs, nested schemas, triggers with transition
tables, `CONNECT`/`DISCONNECT`, external-resource statements, expression-only
statements, and new `COPY` options. `DuckdbPsiParser` handles many DuckDB-only
statement heads leniently, but its dispatch list and the validator's
`EXPLAINABLE_HEADS`/known-head classification predate these forms. Check run
blocks and error squiggles independently, particularly for `WITH ... DELETE`,
`CREATE TRIGGER ... INSERT ...`, `CONNECT`, and expression-only statements.
`CONNECT` can route later SQL to PostgreSQL or MySQL, which also changes what
"DuckDB syntax" means for that console.

**Pipe syntax is opt-in at the engine.** DuckDB's parser article demonstrates
Google-style `|>` with a *sample extension*. It is not default DuckSQL.
Brikk has its own first-class pipe AST and normally lowers pipes for native
execution. A loaded, actively selected DuckDB pipe grammar could support
native pipes, but only after the plugin and Brikk verify syntax and semantics
against that extension. Do not enable pipe tokens or drop lowering merely
because the engine reports version 2.0.

**Quack moves too.** DuckDB plans to promote its `quack` extension from 0.x to
1.0 alongside server mode and `CONNECT`. Before bumping the plugin's pinned
JDBC artifacts, run the native and live Quack suites against a matching 2.0
server/driver pair. A local JDBC parser does not prove what a remote Quack
session accepts.

## Useful release check

Use the existing README engine-bump harvests and census gate, but keep a
side-by-side 1.5.5/2.0 sample that covers window-function kinds, new JSON and
VARIANT names, both lambda forms, `SET VARIABLE` with `$name`, new statement
boundaries, an extension loaded with its grammar inactive, and the same
extension with its grammar active. Run the validation samples on the *editor's*
engine as well as on the isolated validator to expose disagreements. Record
whether a difference is PSI structure, completion, server acceptance, or
validation; a green DuckDB parse alone does not prove all four.

Revisit this note against the final DuckDB 2.0 release announcement. DuckDB
has said that some breaking changes, including the completed lambda transition
and new default storage format, will be covered there.
