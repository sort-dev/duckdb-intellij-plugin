package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import java.nio.file.Files
import java.util.UUID
import java.util.jar.JarOutputStream
import java.sql.SQLException

class DuckdbGrammarConfigurationTest : BasePlatformTestCase() {
    private lateinit var driver: DatabaseDriverImpl
    override fun setUp() {
        super.setUp()
        driver=DatabaseDriverManager.getInstance().createDriver("configuration-${UUID.randomUUID()}","Configuration fixture",UrlTemplate("default","jdbc:duckdb:{file}")) as DatabaseDriverImpl
        driver.setDriverClass("org.duckdb.DuckDBDriver")
    }
    override fun tearDown() {
        try { DatabaseDriverManager.getInstance().removeDriver(driver) }finally { super.tearDown() }
    }
    private fun source()=LocalDataSource().apply { databaseDriver=driver;url="jdbc:duckdb:fixture.db";username="fixture";isAutoSynchronize=false }

    fun testWorkingDirectoryPropertiesAndDriverChangesDoNotNeedAUrlChangeToInvalidate() {
        val source=source()
        var saved=DuckdbGrammarConfiguration.capture(source)
        assertTrue(saved.matches(source))
        source.workingDir="different-working-directory"
        assertFalse(saved.matches(source))
        saved=DuckdbGrammarConfiguration.capture(source)
        source.setAdditionalProperty("fixture.connection.option","changed")
        assertFalse(saved.matches(source))
        saved=DuckdbGrammarConfiguration.capture(source)
        driver.setVmOptions("-Dfixture.config=changed")
        assertFalse(saved.matches(source))
        saved=DuckdbGrammarConfiguration.capture(source)
        driver.setDriverClass("different.Driver")
        assertFalse(saved.matches(source))
        assertEquals("jdbc:duckdb:fixture.db",source.url)
    }

    fun testCapturedSourceAndChangedDriverInputsAreRejectedWithoutEchoingValues() {
        val source=source().apply { initScript="never_replay_startup";workingDir="before" }
        driver.setVmOptions("-Dfixture.config=before")
        val saved=DuckdbGrammarConfiguration.capture(source)
        val point=saved.helperPoint(source)
        source.url="jdbc:duckdb:different.db"
        source.workingDir="after"
        driver.setVmOptions("-Dfixture.config=after")
        assertEquals("jdbc:duckdb:fixture.db",point.url)
        assertSame("The data source handle owns the approved shared JDBC process",source,point.dataSource)
        assertSame("The driver handle owns the approved shared JDBC process",driver,point.databaseDriver)
        assertThrows(SQLException::class.java) { saved.helperPoint(source) }
        assertNull(point.initScript)
        assertFalse(saved.toString().contains("fixture.config"))
        assertFalse(saved.matches(source))
    }

    fun testPresentationAndIntrospectionPreferencesAreNotConnectionIdentity() {
        val source=source();val saved=DuckdbGrammarConfiguration.capture(source)
        source.name="Changed display name"
        source.isAutoSynchronize=true
        source.codeStyleName="Different formatting"
        assertTrue(saved.matches(source))
    }

    fun testManualModuleFileChangeIsDetectedWithoutLoadingIt() {
        val root=Files.createTempDirectory("grammar-config-module-")
        try {
            val module=root.resolve("fixture.jar")
            JarOutputStream(Files.newOutputStream(module)).use { }
            driver.setAdditionalClasspathElements(SimpleClasspathElementFactory.createElements("jar://$module!/"))
            val source=source();val saved=DuckdbGrammarConfiguration.capture(source)
            assertTrue(saved.matches(source));assertTrue(saved.modulesCurrent())
            Files.writeString(module,"different module contents")
            assertTrue("The URL/classpath configuration itself did not change",saved.matches(source))
            assertFalse(saved.modulesCurrent())
        }finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
