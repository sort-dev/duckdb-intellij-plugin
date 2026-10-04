# Third-party notices

## brikk-sql and metadata 0.18.0

Bundled translator artifacts: `dev.brikk.house:brikk-sql-jvm:0.18.0` and
`dev.brikk.house:brikk-sql-metadata-jvm:0.18.0`, by Jayson Minard and Sortdev SRL.
Project: https://github.com/brikk/brikk-sql. Apache License 2.0, with the
third-party-derived portions below. No native database engine or verifier is bundled.
Published sources and license provenance are available from Maven Central.

The parser, tokenizer, AST, optimizer and SQL generators include a Kotlin port of
SQLGlot by Toby Mao and contributors, https://github.com/tobymao/sqlglot.
The DataFusion dialect references polyglot by TobiLG,
https://github.com/tobilg/polyglot. Both use the MIT License:

Copyright (c) 2026 Toby Mao
Copyright (c) 2026 TobiLG <github@tobilg.com>
Copyright 2018-2025 Stichting DuckDB Foundation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

Generated function catalogs contain facts from DuckDB, Doris, StarRocks, Trino
and ClickHouse. DuckDB's catalog derives from DuckDB 1.5.5 (MIT, DuckDB contributors).
Doris/StarRocks/Trino catalogs derive from Apache-2.0 projects; the ClickHouse
catalog derives from the Apache-2.0 ClickHouse source and documentation.
SQLGlot-derived function and token registries are covered by the MIT notice above.

Apache-2.0 source/catalog references: https://github.com/apache/doris,
https://github.com/trinodb/trino, https://github.com/ClickHouse/ClickHouse,
and https://github.com/StarRocks/starrocks. The bundled catalogs include no
native server code. Relevant upstream notices:

StarRocks
Copyright 2021-present, StarRocks Inc.

Apache Doris (incubating)
Copyright 2018-2021 The Apache Software Foundation

This product includes software developed at The Apache Software Foundation
(http://www.apache.org/).

Copyright 2016-2026 ClickHouse, Inc.

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
