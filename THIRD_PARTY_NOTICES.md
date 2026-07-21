# Third-party notices

## StarRocks Support (DataGrip plugin)

The statement-dispatch and lenient-parsing approach in
`src/main/kotlin/dev/sort/duckdb/sql/DuckdbPsiParser.kt` (bounded look-ahead helpers such as
`wordAt` / `statementContainsAny`, and the lenient consume-to-`;` technique) is adapted from
StarRocks Support (https://github.com/ycyz97/starrocks-datagrip-plugin), Copyright the StarRocks
Support contributors, licensed under the Apache License, Version 2.0 — by way of our own
doris-intellij-plugin (https://github.com/sort-dev/doris-intellij-plugin), where the adaptation
was first made. https://www.apache.org/licenses/LICENSE-2.0

## DuckDB

This plugin references DuckDB test corpora (MIT-licensed, © DuckDB contributors) for syntax
conformance measurement; no DuckDB source code is bundled. "DuckDB" is a trademark of the DuckDB
Foundation.
