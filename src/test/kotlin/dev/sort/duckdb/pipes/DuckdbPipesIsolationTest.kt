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
        assertTrue(fragment.getResource("SqlFragment.class").toString().contains("0.18.0"))
        val value = fragment.getConstructor(String::class.java, String::class.java)
            .newInstance("FROM range(3) |> LIMIT 1", "duckdb")
        val result = fragment.getMethod("toExecutable", String::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).invoke(value, "duckdb", true, true)
        assertFalse((result.javaClass.getMethod("getSql").invoke(result) as String).contains("|>"))
        for (peerId in listOf("dev.sort.doris-intellij-plugin", "dev.sort.sql-transpiler-intellij-plugin")) {
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
            assertTrue(types.toString(), types.any { it.startsWith("dev.sort.duckdb.pipes.") })
            assertTrue(types.toString(), types.last().startsWith("com.intellij.database.actions.RunQueryAction"))
            if (PluginManagerCore.getPlugin(PluginId.getId("dev.sort.doris-intellij-plugin")) != null)
                assertTrue(types.toString(), types.any { it.startsWith("dev.sort.doris.pipes.") })
        }
    }
}
