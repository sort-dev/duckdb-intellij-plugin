package dev.sort.duckdb

import com.intellij.database.DbmsExtension
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.remote.jdbc.helpers.JdbcHelperImpl
import com.intellij.database.remote.jdbc.helpers.JdbcSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.sql.Driver
import java.util.Properties

class DuckdbJdbcHelperTest : BasePlatformTestCase() {
    fun testSslModeDoesNotInjectPostgresPropertiesIntoEitherDriver() {
        val helpers = DbmsExtension<JdbcHelperImpl>("com.intellij.database.jdbcHelper")
        val drivers = DatabaseDriverManager.getInstance()
        val enableSsl = JdbcHelperImpl::class.java.getDeclaredMethod(
            "enableSsl", Driver::class.java, Properties::class.java, JdbcSettings.SslMode::class.java,
        ).apply { isAccessible = true }
        val injected = mutableListOf<String>()

        for (id in listOf("duckdb-brikk-native", "duckdb-brikk-quack")) {
            val configured = requireNotNull(drivers.getDriver(id))
            val source = LocalDataSource.create("ssl-$id", configured.driverClass, configured.sampleUrl, "")
            source.databaseDriver = configured
            val helper = helpers.forDbms(source.dbms)
            val driver = Class.forName(configured.driverClass).getDeclaredConstructor().newInstance() as Driver
            for (mode in JdbcSettings.SslMode.entries) {
                val properties = Properties()
                enableSsl.invoke(helper, driver, properties, mode)
                if (properties.containsKey("ssl") || properties.containsKey("sslmode"))
                    injected.add("$id $mode: $properties")
            }
        }
        assertTrue("Postgres properties injected: $injected", injected.isEmpty())
    }
}
