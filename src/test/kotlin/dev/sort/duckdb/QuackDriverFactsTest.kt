package dev.sort.duckdb

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Driver
import java.util.ServiceLoader
import java.util.Properties

/**
 * Makes the quack-jdbc jar identify itself — the facts that keep
 * config/duckdb-brikk-drivers.xml honest: the registered [java.sql.Driver] implementation
 * class(es) and which JDBC URL prefixes [Driver.acceptsURL] claims (pure string parsing;
 * no server needed).
 */
class QuackDriverFactsTest {

    @Test
    fun `quack driver identifies itself`() {
        val drivers = ServiceLoader.load(Driver::class.java)
            .filterNot { it.javaClass.name.startsWith("org.duckdb") }
        assertTrue("expected the quack driver on the test classpath", drivers.isNotEmpty())
        val quack = drivers.single { it.javaClass.name == "com.gizmodata.quack.jdbc.sql.QuackDriver" }
        assertEquals(0, quack.majorVersion)
        assertEquals(8, quack.minorVersion)
        assertTrue(quack.acceptsURL("jdbc:quack://localhost:9494/"))
        val properties = quack.getPropertyInfo("jdbc:quack://localhost:9494/", Properties()).associateBy { it.name }
        assertTrue(properties.keys.toString(), properties.keys.containsAll(listOf("token", "tokenEnv", "tokenFile", "tls", "connectTimeout", "requestTimeout")))
        assertEquals("false", properties.getValue("tls").value)

        val candidates = listOf(
            "jdbc:quack://localhost:31337/",
            "jdbc:gizmosql://localhost:31337/",
            "jdbc:duckdb-quack://localhost:31337/",
            "jdbc:arrow-flight-sql://localhost:31337/",
            "jdbc:flight-sql://localhost:31337/",
            "jdbc:brikk://localhost:31337/",
        )
        val report = StringBuilder("\n=== quack-jdbc facts ===\n")
        for (d in drivers) {
            report.append("driver-class: ${d.javaClass.name} (v${d.majorVersion}.${d.minorVersion})\n")
            report.append("jar: ${d.javaClass.protectionDomain?.codeSource?.location}\n")
            for (url in candidates) {
                val ok = runCatching { d.acceptsURL(url) }.getOrDefault(false)
                if (ok) report.append("  accepts: $url\n")
            }
        }
        report.append("========================")
        println(report)

        assertTrue(
            "no probed URL scheme accepted — extend the candidate list from the report above",
            drivers.any { d -> candidates.any { runCatching { d.acceptsURL(it) }.getOrDefault(false) } },
        )
    }
}
