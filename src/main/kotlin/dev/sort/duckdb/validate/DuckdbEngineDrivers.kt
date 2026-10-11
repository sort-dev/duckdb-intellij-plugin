package dev.sort.duckdb.validate

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import dev.brikk.house.sql.intellij.recoverEditorOperation
import java.io.File
import java.net.URLClassLoader
import java.sql.Driver

internal class DuckdbEngineDrivers : AutoCloseable {
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
        val driver = try {
            recoverEditorOperation {
                loader.loadClass("org.duckdb.DuckDBDriver").getDeclaredConstructor().newInstance() as Driver
            }.getOrNull()
        } catch (_: LinkageError) {
            null
        } catch (e: Throwable) {
            loader.close()
            throw e
        }
        if (driver == null) loader.close()
        Loaded(loader, driver)
    }.driver

    @Synchronized
    override fun close() {
        jars.values.forEach { it.loader.close() }
        jars.clear()
    }
}

@Service(Service.Level.APP)
internal class DuckdbEngineDriverService : Disposable {
    val drivers = DuckdbEngineDrivers()
    override fun dispose() = drivers.close()
}
