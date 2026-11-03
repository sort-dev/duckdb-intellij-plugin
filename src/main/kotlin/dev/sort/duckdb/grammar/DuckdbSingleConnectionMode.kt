package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.LocalDataSource

/** The old public flag/getter was removed in 263. Do not link the plugin to that
 * removed method or inspect private fields. Unexpected access failures fail closed. */
internal object DuckdbSingleConnectionMode {
    private val getter=runCatching { LocalDataSource::class.java.getMethod("isSingleConnection") }

    fun requested(source: LocalDataSource): Boolean {
        if (getter.exceptionOrNull() is NoSuchMethodException) return false // No old flag on the current SDK.
        val method=getter.getOrNull() ?: return true
        return runCatching { method.invoke(source) as? Boolean ?: true }.getOrDefault(true)
    }
}
