package dev.sort.duckdb

import com.intellij.openapi.application.ApplicationManager
import com.intellij.sql.database.SqlDataSource
import com.intellij.testFramework.PlatformTestUtil

internal fun SqlDataSource.syncAndWait() {
    PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread {
        sync()
        javaClass.methods.firstOrNull { it.name == "waitComputed" && it.parameterCount == 0 }?.invoke(this)
    })
}
