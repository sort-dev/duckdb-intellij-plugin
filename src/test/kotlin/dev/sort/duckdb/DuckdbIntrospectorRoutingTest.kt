package dev.sort.duckdb

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.ModelFactory
import com.intellij.database.util.Version
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DuckdbIntrospectorRoutingTest : BasePlatformTestCase() {
    // The selection functions are Kotlin-internal but public in platform bytecode.
    private val factory get() = Class.forName("com.intellij.database.introspection.DBIntrospectorFactory")

    private fun nativeSupported(dbms: Dbms, major: Int) = factory.getMethod(
        "supportedByNativeIntrospector", Dbms::class.java, Version::class.java,
    ).invoke(null, dbms, Version.of(major)) as Boolean

    private fun create(dbms: Dbms, jdbc: Boolean): Any = factory.getMethod(
        "createIntrospector", Project::class.java, ModelFactory::class.java, Dbms::class.java, Boolean::class.javaPrimitiveType,
    ).invoke(null, project, ModelFactory.BLACK_HOLE, dbms, jdbc)

    fun testBothDriversRouteToJdbcAtLowAndHighVersions() {
        val genericClass = create(Dbms.UNKNOWN, false).javaClass
        for (id in listOf("duckdb-brikk-native", "duckdb-brikk-quack")) {
            val driver = requireNotNull(DatabaseDriverManager.getInstance().getDriver(id))
            val source = LocalDataSource.create("routing-$id", driver.driverClass, driver.sampleUrl, "")
            source.databaseDriver = driver
            assertEquals(DuckdbDbms.DUCKDB_BRIKK, source.dbms)
            assertFalse(source.useJdbcIntrospector())
            for (major in listOf(1, 9, 10, 200)) {
                val native = nativeSupported(source.dbms, major)
                assertFalse(native)
                assertEquals(genericClass, create(source.dbms, source.useJdbcIntrospector() || !native).javaClass)
            }
        }
        assertTrue(nativeSupported(Dbms.POSTGRES, 17))
    }
}
