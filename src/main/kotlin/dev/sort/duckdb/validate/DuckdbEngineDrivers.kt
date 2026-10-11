package dev.sort.duckdb.validate

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import dev.brikk.house.sql.intellij.recoverEditorOperation
import java.io.File
import java.net.URLClassLoader
import java.sql.Driver

@Service(Service.Level.APP)
internal class DuckdbEngineDrivers : Disposable {
    private data class Loaded(val loader: URLClassLoader, val driver: Driver?)
    private val jars = mutableMapOf<String, Loaded>()

    val defaultDriver: Driver? by lazy {
        System.getProperty("duckdb.validator.jar")?.let(::driverForJar) ?: recoverEditorOperation {
            Class.forName("org.duckdb.DuckDBDriver").getDeclaredConstructor().newInstance() as Driver
        }.getOrNull()
    }

    @Synchronized
    fun driverForJar(path: String): Driver? = jars.getOrPut(path) {
        val loader = URLClassLoader(arrayOf(File(path).toURI().toURL()), ClassLoader.getPlatformClassLoader())
        var driver: Driver? = null
        try {
            driver = recoverEditorOperation {
                loader.loadClass("org.duckdb.DuckDBDriver").getDeclaredConstructor().newInstance() as Driver
            }.getOrNull()
        } catch (_: LinkageError) {
            // Native driver linkage failures make this engine unavailable.
        } finally {
            if (driver == null) loader.close()
        }
        Loaded(loader, driver)
    }.driver

    @Synchronized
    override fun dispose() {
        jars.values.forEach { it.loader.close() }
        jars.clear()
    }
}
