# Third-party notices

## Quack JDBC driver

GizmoSQL created the original `quack-jdbc` driver. The remote data source downloads
Sortdev's fork, `dev.brikk.duckdb:quack-jdbc`, from Maven Central. Sortdev has extended
the fork through Quack protocol v3 for DuckDB 2.0; the original GizmoSQL driver does not
support protocol v3. The driver is distributed under the MIT License.

## Brikk Kotlin IntelliJ support 0.3.0

This plugin embeds `dev.brikk.house:brikk-engine-kotlin-intellij-support:0.3.0`
from Maven Central. Jayson Minard and Sortdev SRL,
https://github.com/brikk/brikk-house, Apache License 2.0. The helper contains
Brikk context/interpolation and copied relation-contract APIs. Kotlin/IntelliJ
APIs and compiler plugins are not bundled by this dependency.

## Brikk SQL IntelliJ support 0.2.3

This plugin embeds `dev.brikk.house:brikk-sql-intellij-support:0.2.3`.
Jayson Minard and Sortdev SRL, https://github.com/brikk/brikk-sql,
Apache License 2.0. The helper provides cooperative Execute actions, promotion,
document snapshots, translated script models and shared column presentation. No IntelliJ SDK, SQL engine,
native driver or verifier is supplied transitively by this library.

## Brikk SQL IntelliJ JDBC support 0.2.3

This plugin embeds `dev.brikk.house:brikk-sql-intellij-jdbc-support:0.2.3`,
Copyright 2026 Sortdev SRL, https://github.com/brikk/brikk-sql, Apache License 2.0.
This optional adapter routes DuckDB to JDBC introspection. It contains its LICENSE and NOTICE;
IntelliJ SDK classes are compile-only and are not bundled.

## brikk-sql and metadata 0.20.0

Bundled translator artifacts: `dev.brikk.house:brikk-sql-jvm:0.20.0` and
`dev.brikk.house:brikk-sql-metadata-jvm:0.20.0`, by Jayson Minard and Sortdev SRL.
Project: https://github.com/brikk/brikk-sql. Apache License 2.0, with the
third-party-derived portions below. No native database engine is bundled.
These artifacts are resolved from Maven Central.

Bundled parse-only verifier artifacts: `dev.brikk.house:brikk-sql-verify-api:0.20.0`
and `dev.brikk.house:brikk-sql-verify-duckdb:0.20.0`, by Jayson Minard and
Sortdev SRL, https://github.com/brikk/brikk-sql, Apache License 2.0. The DuckDB JDBC
dependency is excluded; verification uses the user's driver and never executes authored SQL.

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

The statement-dispatch and lenient-parsing approach used by
`src/main/kotlin/dev/sort/duckdb/sql/DuckdbPsiParser.kt`, with bounded scanning and lenient
consumption now provided by Brikk SQL IntelliJ support, is adapted from
StarRocks Support (https://github.com/ycyz97/starrocks-datagrip-plugin), Copyright the StarRocks
Support contributors, licensed under the Apache License, Version 2.0 — by way of our own
doris-intellij-plugin (https://github.com/sort-dev/doris-intellij-plugin), where the adaptation
was first made. https://www.apache.org/licenses/LICENSE-2.0

## DuckDB

This plugin references DuckDB test corpora (MIT-licensed, © DuckDB contributors) for syntax
conformance measurement; no DuckDB source code is bundled. "DuckDB" is a trademark of the DuckDB
Foundation.
