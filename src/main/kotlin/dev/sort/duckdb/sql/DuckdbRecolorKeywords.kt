package dev.sort.duckdb.sql

/**
 * Compact keyword set for the masked-span recolorer — display-layer only (never parsing).
 * Deliberately small: it colors words inside masked/collapsed spans; anything missing just
 * renders as an identifier. The duckdb_keywords()-fed full set arrives with Stage 4 metadata.
 */
object DuckdbRecolorKeywords {
    private val WORDS = setOf(
        "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET",
        "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS", "ON", "USING", "AS",
        "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "CASE", "WHEN", "THEN",
        "ELSE", "END", "UNION", "EXCEPT", "INTERSECT", "ALL", "DISTINCT", "EXISTS",
        "CREATE", "TABLE", "REPLACE", "INSERT", "INTO", "VALUES", "UPDATE", "DELETE", "SET",
        "EXCLUDE", "QUALIFY", "SAMPLE", "TABLESAMPLE", "FILTER", "GENERATED", "ALWAYS",
        "VIRTUAL", "STORED", "STRUCT", "ROW", "MAP", "INTERVAL", "CAST", "TRY_CAST", "FOR",
        "NAME", "PIVOT", "UNPIVOT", "OVER", "PARTITION", "WINDOW", "WITH", "RECURSIVE",
        "BERNOULLI", "SYSTEM", "RESERVOIR", "REPEATABLE", "PERCENT", "ROWS",
    )

    fun isKeyword(word: String): Boolean = word.uppercase() in WORDS
}
