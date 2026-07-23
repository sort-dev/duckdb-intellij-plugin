package dev.sort.duckdb.validate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the driver-classpath engine path: jar classification, per-jar driver loading through an
 * isolated classloader, and the `$SYSTEM/jdbc-drivers` scan (with an injected base dir). The
 * data-source branch is thin public-API glue verified in the IDE dogfood.
 */
class DuckdbEngineLocatorTest {

    private fun classpathJar(fragment: String): Path =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .first { it.contains(fragment) && it.endsWith(".jar") }
            .let { File(it).toPath() }

    @Test
    fun `duckdb driver jar classified, others rejected`() {
        assertTrue(DuckdbEngineLocator.isDuckdbDriverJar(classpathJar("duckdb_jdbc")))
        assertFalse(DuckdbEngineLocator.isDuckdbDriverJar(classpathJar("slt-format")))
    }

    @Test
    fun `driver loads from explicit jar and validates through it`() {
        val jar = classpathJar("duckdb_jdbc").toString()
        val driver = DuckdbEngineValidator.driverForJar(jar)
        assertTrue("driver must load from jar", driver != null)
        val verdicts = DuckdbEngineValidator.verdicts(listOf("SELEC 1"), jar)
        assertTrue(
            "engine from jar must flag a parser error, got: $verdicts",
            verdicts.single() is DuckdbEngineValidator.Verdict.Flagged,
        )
    }

    @Test
    fun `system drivers scan finds the newest jar under the preferred artifact dir`() {
        val base = Files.createTempDirectory("jdbc-drivers-test")
        try {
            assertNull(DuckdbEngineLocator.systemDriversScan(base))
            val real = classpathJar("duckdb_jdbc")
            val brikkDir = base.resolve("DuckDB JDBC (Brikk)").resolve("1.5.5.0")
            Files.createDirectories(brikkDir)
            val placed = brikkDir.resolve("duckdb_jdbc-1.5.5.0.jar")
            Files.copy(real, placed)
            // decoy that must lose to the preferred dir
            val decoyDir = base.resolve("SomethingElse")
            Files.createDirectories(decoyDir)
            Files.copy(real, decoyDir.resolve("duckdb_jdbc-0.0.1.jar"))
            assertEquals(placed, DuckdbEngineLocator.systemDriversScan(base))
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
