package dev.sort.duckdb.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.DataSourceResolution
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.sort.duckdb.DuckdbDbms.DUCKDB_BRIKK

class DuckdbDataSourcesTest : BasePlatformTestCase() {
    private val added = mutableListOf<LocalDataSource>()

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("resolve.sql", "SELECT 1")
    }

    override fun tearDown() {
        try {
            SqlResolveMappings.getInstance(project).apply {
                setMapping(myFixture.file.virtualFile, null)
                setMapping(null, null)
            }
            added.forEach(LocalDataSourceManager.getInstance(project)::removeDataSource)
            DbPsiFacade.getInstance(project).clearCaches()
        } catch (failure: Throwable) {
            addSuppressedException(failure)
        } finally {
            super.tearDown()
        }
    }

    fun testConsoleWinsOverScopeAndCompletionCopiesResolve() {
        mapTo(dataSource())
        RecordingConsole(project, myFixture.file.virtualFile, "duckdb-brikk-native").use { recording ->
            val source = recording.console.dataSource
            assertEquals(DataSourceResolution.Console(source), duckdbDataSourceResolver.resolve(myFixture.file))
            assertEquals(source.uniqueId, DuckdbCatalogResolver.activeCatalogFor(myFixture.file).dataSourceId)
            assertEquals(source.uniqueId, DuckdbCatalogResolver.activeCatalogFor(myFixture.file.copy() as PsiFile).dataSourceId)
        }
    }

    fun testForeignConsoleDoesNotUseDuckdbScopeOrOnlyDataSource() {
        val source = dataSource()
        RecordingConsole(project, myFixture.file.virtualFile, "postgresql").use { recording ->
            assertEquals(Dbms.POSTGRES, recording.console.dataSource.dbms)
            for (scope in listOf(null, scopeOf(source))) {
                SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile, scope)
                assertEquals(DataSourceResolution.NotFound, duckdbDataSourceResolver.resolve(myFixture.file))
                val active = DuckdbCatalogResolver.activeCatalogFor(myFixture.file)
                assertNull(active.dataSourceId)
                assertFalse(active.live)
                assertSame(DUCKDB_METADATA.default, active.snapshot)
                val event = refreshEvent(myFixture.file)
                DuckdbRefreshLiveCatalogAction().update(event)
                assertFalse(event.presentation.isEnabled)
            }
        }
    }

    fun testFileAndProjectScopesChooseDuckdbSources() {
        dataSource()
        val source = dataSource()
        val foreign = dataSource("postgresql")
        mapTo(source, foreign)
        assertEquals(DataSourceResolution.FileMapping(source), duckdbDataSourceResolver.resolve(myFixture.file))
        assertEquals(source.uniqueId, DuckdbCatalogResolver.activeCatalogFor(myFixture.file).dataSourceId)
        SqlResolveMappings.getInstance(project).apply {
            setMapping(myFixture.file.virtualFile, null)
            setMapping(null, scopeOf(source))
        }
        assertEquals(DataSourceResolution.FileMapping(source), duckdbDataSourceResolver.resolve(myFixture.file))
    }

    fun testForeignAndExcludingScopesDoNotUseOnlyDataSource() {
        val source = dataSource()
        mapTo(dataSource("postgresql"))
        assertEquals(DataSourceResolution.NotFound, duckdbDataSourceResolver.resolve(myFixture.file))
        assertNull(DuckdbCatalogResolver.activeCatalogFor(myFixture.file).dataSourceId)
        SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile,
            TreePatternUtils.minus(SqlResolveMappings.ALL_DATA_SOURCES, scopeOf(source)))
        assertEquals(DataSourceResolution.NotFound, duckdbDataSourceResolver.resolve(myFixture.file))
    }

    fun testOnlyDataSourceFallbackCountsOurDbms() {
        assertEquals(DataSourceResolution.NotFound, duckdbDataSourceResolver.resolve(myFixture.file))
        dataSource("postgresql")
        assertEquals(DataSourceResolution.NotFound, duckdbDataSourceResolver.resolve(myFixture.file))
        val source = dataSource()
        assertEquals(DataSourceResolution.OnlyDataSource(source), duckdbDataSourceResolver.resolve(myFixture.file))
        assertEquals(source.uniqueId, DuckdbCatalogResolver.activeCatalogFor(myFixture.file).dataSourceId)
        for (file in listOf(null, myFixture.file)) {
            val event = refreshEvent(file)
            DuckdbRefreshLiveCatalogAction().update(event)
            assertTrue(event.presentation.isEnabled)
        }
    }

    fun testMultipleDuckdbSourcesAreAmbiguous() {
        val first = dataSource()
        val second = dataSource("duckdb-brikk-quack")
        for (scope in listOf(null, scopeOf(first, second))) {
            SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile, scope)
            val resolution = duckdbDataSourceResolver.resolve(myFixture.file) as DataSourceResolution.Ambiguous
            assertEquals(setOf(first, second), resolution.candidates.toSet())
            assertNull(DuckdbCatalogResolver.activeCatalogFor(myFixture.file).dataSourceId)
        }
    }

    fun testConsoleLookupCancellationPropagates() {
        RecordingConsole(project, myFixture.file.virtualFile, "duckdb-brikk-native").use { recording ->
            val cancellation = ProcessCanceledException()
            recording.beforeSessionCall = { throw cancellation }
            try {
                DuckdbCatalogResolver.activeCatalogFor(myFixture.file)
                fail("Console lookup must propagate cancellation")
            } catch (actual: ProcessCanceledException) {
                assertSame(cancellation, actual)
            }
        }
    }

    private fun dataSource(driver: String = "duckdb-brikk-native"): LocalDataSource = LocalDataSource().apply {
        name = "Resolver ${added.size}"
        databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(driver))
        url = "jdbc:offline:resolver"
        isAutoSynchronize = false
    }.also {
        LocalDataSourceManager.getInstance(project).addDataSource(it)
        DbPsiFacade.getInstance(project).clearCaches()
        added += it
        assertEquals(if (driver == "postgresql") Dbms.POSTGRES else DUCKDB_BRIKK, it.dbms)
    }

    private fun scopeOf(vararg sources: LocalDataSource) =
        TreePattern(SqlImportUtil.createDataSources(sources.map { ObjectName.quoted(it.uniqueId) }.toTypedArray()))

    private fun mapTo(vararg sources: LocalDataSource) =
        SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile, scopeOf(*sources))

    private fun refreshEvent(file: PsiFile?) = AnActionEvent.createFromDataContext("Resolver test", Presentation(),
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, file).build())
}
