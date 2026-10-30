package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.DbOptionProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.util.xmlb.XmlSerializer
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Modifier

class DuckdbGrammarEditorTest : BasePlatformTestCase() {
    private val added = mutableListOf<LocalDataSource>()
    private fun source(driver: String = "duckdb-brikk-native", url: String = "jdbc:duckdb:grammar-ui.db") = LocalDataSource().apply {
        name = "Selected source"
        databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver(driver))
        this.url = url
        isAutoSynchronize = false
    }.also { LocalDataSourceManager.getInstance(project).addDataSource(it); added += it }

    override fun tearDown() {
        try { added.forEach(LocalDataSourceManager.getInstance(project)::removeDataSource) }
        finally { super.tearDown() }
    }

    private fun editor(provider: DuckdbGrammarOptionProvider, source: LocalDataSource, owner: com.intellij.openapi.project.Project = project) =
        provider.getEditor(source, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, owner).build())

    fun testCoreOnlyHelperConnectionNeverBecomesAUserRuntimeOrChangesItsRevision() {
        val selected = source()
        val runtime = project.service<DuckdbGrammarRuntime>()
        val before = runtime.stamp
        val point = dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint(selected)
        val proto = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(com.intellij.database.dataSource.DatabaseConnectionInterceptor.ProtoConnection::class.java)) { _, method, _ ->
            when (method.name) {
                "getProject" -> project
                "getConnectionPoint" -> point
                else -> error("Unexpected proto member ${method.name}")
            }
        } as com.intellij.database.dataSource.DatabaseConnectionInterceptor.ProtoConnection
        java.sql.DriverManager.getConnection("jdbc:duckdb:").use { jdbc ->
            val remote = com.intellij.database.remote.jdbc.impl.RemoteConnectionImpl(jdbc, com.intellij.database.remote.jdbc.helpers.GenericJdbcHelper())
            // Deliberately NOT DatabaseConnection: this models a platform Core wrapper without requestor.
            val core = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(com.intellij.database.dataSource.DatabaseConnectionCore::class.java)) { _, method, _ ->
                when (method.name) {
                    "getConnectionPoint" -> point
                    "getRemoteConnection" -> remote
                    "getDbms" -> selected.dbms
                    "getAutoCommit" -> true
                    else -> error("Unexpected core member ${method.name}")
                }
            } as com.intellij.database.dataSource.DatabaseConnectionCore
            repeat(3) { kotlinx.coroutines.runBlocking { DuckdbGrammarConnectionInterceptor().handleConnected(core, proto) } }
        }
        assertEquals(before, runtime.stamp)
        assertNull(runtime.runtime(selected))
        assertEquals("jdbc:duckdb:grammar-ui.db", selected.url)
    }

    fun testNativeProviderBindsOnlyTheSelectedDuckdbSource() {
        val selected = source()
        val unrelated = source("postgresql", "jdbc:postgresql://localhost/db").apply { name = "DuckDB misleading display name" }
        val mismatched = source(url = "jdbc:postgresql://localhost/mistagged")
        val provider = DuckdbGrammarOptionProvider()
        assertTrue(provider.isApplicable(project, selected))
        assertFalse(provider.isApplicable(project, unrelated))
        assertFalse(provider.isApplicable(project, mismatched))
        assertEquals(DbOptionProvider.UiLocation.NEW_TAB, provider.location)
        assertTrue(DbOptionProvider.getProviders(project, selected).any { it is DuckdbGrammarOptionProvider })
        assertFalse(DbOptionProvider.getProviders(project, unrelated).any { it is DuckdbGrammarOptionProvider })
        assertSame(selected, (editor(provider, selected).component as DuckdbGrammarEditor).source)
    }

    fun testStoredDefaultHasErasedAbiAndNeverChangesGrammarStateOrConnections() {
        val selected = source()
        val original = selected.copy(true)
        val settings = project.service<DuckdbGrammarSettings>()
        settings.setProfile(selected.uniqueId, DuckdbGrammarProfile(listOf("psql"), listOf("disabled")))
        val saved = JDOMUtil.writeElement(XmlSerializer.serialize(settings.getState()))
        val runtime = project.service<DuckdbGrammarRuntime>()
        val revision = runtime.stamp
        val provider = DuckdbGrammarOptionProvider()
        val method = provider.javaClass.getDeclaredMethod("getStoredDefault", Any::class.java)
        assertEquals(Any::class.java, method.returnType)
        assertTrue(Modifier.isPublic(method.modifiers))
        assertFalse(Modifier.isStatic(method.modifiers))
        assertFalse(Modifier.isAbstract(method.modifiers))
        assertEquals(false, provider.getStoredDefault(selected))
        assertEquals(false, provider.getStoredDefault(null))
        assertEquals(false, provider.getStoredDefault(Any()))
        if (ApplicationInfo.getInstance().build.baselineVersion >= 263) {
            // Resolve on the actual runtime interface, not our implementation. Virtual dispatch
            // must find the Object -> Object method; a typed overload would throw AbstractMethodError.
            val call = MethodHandles.publicLookup().findVirtual(DbOptionProvider::class.java,
                "getStoredDefault", MethodType.methodType(Any::class.java, Any::class.java))
            repeat(3) { assertEquals(false, call.invokeWithArguments(provider, selected)) }
        }
        provider.store(selected, true)
        assertEquals(false, provider.load(selected))
        assertEquals(saved, JDOMUtil.writeElement(XmlSerializer.serialize(settings.getState())))
        assertTrue(selected.equalConfiguration(original))
        assertEquals(revision, runtime.stamp)
        assertNull(runtime.runtime(selected))
    }

    fun testExplicitGrammarApplyOwnsPersistenceAndNativeProviderNeverSavesEdits() {
        val selected = source()
        val settings = project.service<DuckdbGrammarSettings>()
        val before = settings.getState().scopeId
        assertEquals(before, XmlSerializer.deserialize(XmlSerializer.serialize(settings.getState()), DuckdbGrammarSettings.Stored::class.java).scopeId)
        val draft = selected.copy(true)
        assertEquals(selected.uniqueId, draft.uniqueId)
        val provider = DuckdbGrammarOptionProvider()
        val desc = editor(provider, draft)
        val panel = desc.component as DuckdbGrammarEditor
        assertEquals(listOf("Enabled", "Grammar", "Availability"), (0..2).map(panel.model::getColumnName))
        panel.addGrammar("second")
        panel.addGrammar("first")
        panel.table.setRowSelectionInterval(1, 1)
        panel.moveSelected(-1)
        assertEquals(listOf("first", "second"), panel.profile().enabled)
        panel.model.setValueAt(false, 1, 0)
        assertEquals(listOf("first"), panel.profile().enabled)
        assertEquals(listOf("second"), panel.profile().disabled)
        provider.store(draft, desc.getter.get())
        assertEquals(false, desc.getter.get())
        assertTrue(selected.equalConfiguration(draft))
        assertEquals(emptyList<String>(), settings.profile(draft).enabled)
        assertEquals(emptyList<String>(), settings.profile(selected).enabled)
        assertEquals("Cancel/editing must not change project metadata", before, settings.getState().scopeId)
        desc.setter.set(provider.load(selected))
        assertEquals("Native resets/checks do not own independent grammar edits", listOf("first"), panel.profile().enabled)
        panel.resetChanges()
        assertEquals(emptyList<String>(), panel.profile().enabled)
        panel.addGrammar("first")
        panel.addGrammar("second")
        panel.model.setValueAt(false, 1, 0)
        panel.applyChanges.doClick()
        assertEquals(listOf("second"), settings.profile(selected).disabled)
        assertEquals(listOf("first"), settings.profile(selected).enabled)
        val reloaded = XmlSerializer.deserialize(XmlSerializer.serialize(settings.getState()), DuckdbGrammarSettings.Stored::class.java)
        assertEquals(listOf("first"), reloaded.profiles[selected.uniqueId]?.enabled)
        assertEquals(listOf("second"), reloaded.profiles[selected.uniqueId]?.disabled)
        assertTrue(selected.equalConfiguration(draft))
        val revision = project.service<DuckdbGrammarRuntime>().stamp
        panel.applyChanges.doClick()
        assertEquals("A no-op grammar apply must not change state", revision, project.service<DuckdbGrammarRuntime>().stamp)
        panel.addGrammar("second")
        panel.applyChanges.doClick()
        assertEquals(listOf("first", "second"), settings.profile(selected).enabled)
        assertEquals(listOf("first", "second"), settings.profile(selected.copy(true)).enabled)
        assertTrue(selected.equalConfiguration(draft))
    }

    fun testTwoProjectsWithSameSourceUuidKeepIndependentPreferencesAndLeaveSourcePropertiesUntouched() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("grammar-second-project").fixture
        fixture.setUp()
        try {
            val other = fixture.project
            val selected = source()
            val sameUuid = selected.copy(true)
            assertEquals(selected.uniqueId, sameUuid.uniqueId)
            val first = project.service<DuckdbGrammarSettings>()
            val second = other.service<DuckdbGrammarSettings>()
            assertFalse(first.scopeId == second.scopeId)
            assertNotSame(project.service<DuckdbGrammarRuntime>(), other.service<DuckdbGrammarRuntime>())
            assertNotSame(project.service<DuckdbGrammarVerification>(), other.service<DuckdbGrammarVerification>())
            val key = "dev.sort.duckdb.grammar.profiles"
            val original = "{\"future\":{\"retain\":true},\"projects\":{\"${first.scopeId}\":[\"existing-choice\"],\"unknown\":[\"untouched\"]}}"
            selected.setAdditionalProperty(key, original)
            val provider = DuckdbGrammarOptionProvider()
            val a = editor(provider, selected.copy(true))
            val b = editor(provider, sameUuid, other)
            assertEquals(listOf("existing-choice"), (a.component as DuckdbGrammarEditor).profile().enabled)
            (a.component as DuckdbGrammarEditor).addGrammar("alpha")
            (b.component as DuckdbGrammarEditor).addGrammar("beta")
            provider.store(selected, b.getter.get())
            provider.store(selected, a.getter.get())
            assertEquals(listOf("existing-choice"), first.profile(selected).enabled)
            assertEquals(emptyList<String>(), second.profile(selected).enabled)
            first.setProfile(selected.uniqueId, DuckdbGrammarProfile(listOf("alpha"), listOf("disabled-a")))
            second.setProfile(selected.uniqueId, DuckdbGrammarProfile(listOf("beta"), listOf("disabled-b")))
            assertEquals(listOf("alpha"), first.profile(selected).enabled)
            assertEquals(listOf("beta"), second.profile(selected).enabled)
            assertEquals(original, selected.getAdditionalProperty(key))
            assertEquals(listOf("beta"), second.profile(sameUuid).enabled)
        } finally { fixture.tearDown() }
    }

}
