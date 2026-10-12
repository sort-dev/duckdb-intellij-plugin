/*
 * Portions of this file are adapted from StarRocks Support
 * (https://github.com/ycyz97/starrocks-datagrip-plugin), Copyright the StarRocks Support
 * contributors, licensed under the Apache License, Version 2.0, by way of our own
 * doris-intellij-plugin (github.com/sort-dev/doris-intellij-plugin). The statement-dispatch and
 * lenient-parsing approach (wordAt / statementContainsAny / lenient consume-to-';') derives from
 * that lineage, modified for DuckDB syntax. See THIRD_PARTY_NOTICES.md.
 */
package dev.sort.duckdb.sql

import com.intellij.lang.PsiBuilder
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.sql.dialects.postgres.PgParser
import dev.brikk.house.sql.intellij.parser.containsAny
import dev.brikk.house.sql.intellij.parser.firstOf
import dev.brikk.house.sql.intellij.parser.parseColonParameter
import dev.brikk.house.sql.intellij.parser.parseLenientStatement
import dev.brikk.house.sql.intellij.parser.wordAt
import dev.brikk.house.sql.intellij.parser.wordThenToken
import dev.brikk.house.sql.intellij.parser.wordThenWord
import dev.brikk.house.sql.intellij.pipe.PipeStatementGate
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import dev.sort.duckdb.pipes.DuckdbPipeDialect

class DuckdbPsiParser : PgParser(false) {
    private val pipeGate = PipeStatementGate(DuckdbPipeDialect)

    override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean {
        if (pipeGate.parse(builder)) return true
        when (builder.wordAt(0)) {
            "ATTACH", "DETACH", "SUMMARIZE", "DESCRIBE", "PIVOT", "UNPIVOT",
            "INSTALL", "FORCE", "EXPORT", "PRAGMA", "USE",
            "LOAD", "IMPORT", "CALL", "CHECKPOINT", "FROM",
            -> return builder.parseLenientStatement()

            "CREATE" -> {
                if (builder.wordThenWord("AS", "FROM") || builder.wordThenToken("AS", "(")) {
                    return builder.parseLenientStatement()
                }
                if (builder.wordAt(1) == "TYPE" && !builder.containsAny("ENUM")) {
                    return builder.parseLenientStatement()
                }
                for (i in 1..4) {
                    when (builder.wordAt(i)) {
                        "MACRO", "SECRET" -> return builder.parseLenientStatement()
                        "OR", "REPLACE", "TEMP", "TEMPORARY", "PERSISTENT" -> continue
                        else -> break
                    }
                }
            }

            "COPY" -> {
                if (builder.containsAny(
                        "PARQUET", "BLOB", "PARTITION_BY", "COMPRESSION", "OVERWRITE_OR_IGNORE",
                        "FILE_SIZE_BYTES", "PER_THREAD_OUTPUT", "APPEND", "AUTO_DETECT",
                        "SAMPLE_SIZE", "ALL_VARCHAR", "IGNORE_ERRORS", "NULL_PADDING",
                        "NORMALIZE_NAMES", "FILENAME", "HIVE_PARTITIONING", "UNION_BY_NAME",
                        "DATEFORMAT", "TIMESTAMPFORMAT",
                    )
                ) return builder.parseLenientStatement()
            }

            "SET", "RESET" -> {
                when (builder.wordAt(1)) {
                    "GLOBAL", "SESSION", "LOCAL", "VARIABLE", "SCHEMA" -> return builder.parseLenientStatement()
                }
            }

            "EXPLAIN" -> {
                when (builder.wordAt(1)) {
                    "PRAGMA", "FROM", "SUMMARIZE", "DESCRIBE", "PIVOT", "UNPIVOT", "ANALYZE" ->
                        return builder.parseLenientStatement()
                }
            }

            "INSERT" -> {
                if (builder.wordAt(1) == "OR" || builder.firstOf("FROM", "SELECT", "VALUES", "DEFAULT") == "FROM") {
                    return builder.parseLenientStatement()
                }
            }
        }
        return super.parseSqlStatement(builder, level)
    }

    override fun parseParameterOrVariableReference(builder: PsiBuilder, allowVariable: Boolean): Boolean {
        val file = builder.getUserData(FileContextUtil.CONTAINING_FILE_KEY)
        if (BrikkDuckdbProfile.forFile(file) != null && builder.parseColonParameter()) return true
        return super.parseParameterOrVariableReference(builder, allowVariable)
    }
}
