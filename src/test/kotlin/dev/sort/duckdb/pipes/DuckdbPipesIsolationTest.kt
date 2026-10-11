package dev.sort.duckdb.pipes

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Reflection deliberately avoids linking fixture-core copies of product implementations. */
class DuckdbPipesIsolationTest : BasePlatformTestCase() {
    fun testProductOwnsItsTranslatorAndCooperatesWithPeerActions() {
        if (System.getProperty("test.pluginIsolation") != "true") return
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("dev.sort.duckdb-intellij-plugin"))!!
        val loader = descriptor.pluginClassLoader!!
        assertEquals("com.intellij.ide.plugins.cl.PluginClassLoader", loader.javaClass.name)
        val fragment = loader.loadClass("dev.brikk.house.sql.shape.SqlFragment")
        assertSame(loader, fragment.classLoader)
        val contract = loader.loadClass("dev.brikk.house.intellij.ColumnContract")
        assertSame("The embedded helper is product-owned, not borrowed from fixture core", loader, contract.classLoader)
        assertTrue(contract.getResource("ColumnContract.class").toString().contains("brikk-engine-kotlin-intellij-support-0.2.0.jar"))
        val engineVersion = System.getProperty("test.brikkSqlVersion")
        assertNotNull(engineVersion)
        assertTrue(fragment.getResource("SqlFragment.class").toString().contains("brikk-sql-jvmMain-$engineVersion.jar"))
        val actionClass = loader.loadClass("dev.brikk.house.sql.intellij.CooperativeExecuteAction")
        assertSame(loader, actionClass.classLoader)
        assertTrue(actionClass.getResource("CooperativeExecuteAction.class").toString().contains("brikk-sql-intellij-support-0.1.1.jar"))
        val completionClass = loader.loadClass("dev.brikk.house.sql.intellij.SqlColumnCompletion")
        assertSame(loader, completionClass.classLoader)
        assertTrue(completionClass.getResource("SqlColumnCompletion.class").toString().contains("brikk-sql-intellij-support-0.1.1.jar"))
        val value = fragment.getConstructor(String::class.java, String::class.java)
            .newInstance("FROM range(3) |> LIMIT 1", "duckdb")
        val result = fragment.getMethod("toExecutable", String::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).invoke(value, "duckdb", true, true)
        assertFalse((result.javaClass.getMethod("getSql").invoke(result) as String).contains("|>"))
        if (engineVersion != "0.19.0") {
            val snapshots = loader.loadClass("dev.brikk.house.sql.parser.SqlScriptSnapshot")
            assertSame(loader, snapshots.classLoader)
            val companion = snapshots.getField("Companion").get(null)
            val snapshot = companion.javaClass.getMethod("parse", String::class.java, String::class.java)
                .invoke(companion, "FROM t |> LIMIT 1 /* open", "duckdb")
            val statement = (snapshot.javaClass.getMethod("getStatements").invoke(snapshot) as List<*>).single()!!
            assertNotNull("The product-owned core must contain the fix, not a fixture-core fallback",
                statement.javaClass.getMethod("getBoundaryError").invoke(statement))
        }
        for (peerId in listOf("dev.sort.doris-intellij-plugin", "dev.sort.trino-intellij-plugin", "dev.sort.sql-transpiler-intellij-plugin")) {
            val peer = PluginManagerCore.getPlugin(PluginId.getId(peerId)) ?: continue
            assertNotSame(fragment, peer.pluginClassLoader!!.loadClass(fragment.name))
        }
        for (id in listOf("Console.Jdbc.Execute", "Console.Jdbc.Execute.2", "Console.Jdbc.Execute.3", "Console.Jdbc.Execute.Selection")) {
            var action = ActionManager.getInstance().getAction(id)!!
            val types = mutableListOf<String>()
            while (true) {
                types.add(action.javaClass.name)
                action = (action as? ActionWithDelegate<*>)?.delegate as? com.intellij.openapi.actionSystem.AnAction ?: break
            }
            assertTrue(types.toString(), generateSequence(ActionManager.getInstance().getAction(id)!!) {
                (it as? ActionWithDelegate<*>)?.delegate as? com.intellij.openapi.actionSystem.AnAction
            }.any { it.javaClass === actionClass })
            assertTrue(types.toString(), types.last().startsWith("com.intellij.database.actions.RunQueryAction"))
            if (PluginManagerCore.getPlugin(PluginId.getId("dev.sort.doris-intellij-plugin")) != null)
                assertTrue(types.toString(), types.any { it.startsWith("dev.sort.doris.pipes.") })
        }
    }

    fun testActualConfigurationsInAllSixOrdersAndDetachedPredecessors() {
        if (System.getProperty("test.pluginIsolation") != "true") return
        val names = mapOf("duckdb" to "dev.sort.duckdb.pipes.DuckdbPipesActionConfiguration",
            "trino" to "dev.sort.trino.pipes.TrinoPipesActionConfiguration",
            "doris" to "dev.sort.doris.pipes.DorisPipesActionConfiguration")
        val loaders = names.keys.associateWith {
            PluginManagerCore.getPlugin(PluginId.getId("dev.sort.$it-intellij-plugin"))?.pluginClassLoader
        }
        org.junit.Assume.assumeTrue("All three distributions are required", loaders.values.all { it != null })
        val manager = ActionManager.getInstance()
        val ids = listOf("Console.Jdbc.Execute", "Console.Jdbc.Execute.2", "Console.Jdbc.Execute.3", "Console.Jdbc.Execute.Selection")
        val originals = ids.associateWith { manager.getAction(it)!! }
        val orders = listOf(listOf("duckdb", "trino", "doris"), listOf("duckdb", "doris", "trino"),
            listOf("trino", "duckdb", "doris"), listOf("trino", "doris", "duckdb"),
            listOf("doris", "duckdb", "trino"), listOf("doris", "trino", "duckdb"))
        try {
            for (order in orders) {
                var calls = 0
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext("Peer chain",
                    com.intellij.openapi.actionSystem.Presentation(),
                    com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project))
                ids.forEach { id -> manager.replaceAction(id, object : com.intellij.openapi.actionSystem.AnAction() {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) { assertSame(event, e); calls++ }
                }) }
                val configurations = order.map { name -> loaders.getValue(name)!!.loadClass(names.getValue(name))
                    .getConstructor().newInstance() as com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer }
                try {
                    configurations.forEach { it.registerActions(manager); it.registerActions(manager) }
                    ids.forEach { manager.getAction(it)!!.actionPerformed(event) }
                    assertEquals(order.toString(), 4, calls)
                    configurations.first().unregisterActions(manager)
                    ids.forEach { manager.getAction(it)!!.actionPerformed(event) }
                    assertEquals("Detached $order", 8, calls)
                } finally { configurations.asReversed().forEach { it.unregisterActions(manager) } }
            }
        } finally { originals.forEach { (id, action) -> manager.replaceAction(id, action) } }
    }
}
