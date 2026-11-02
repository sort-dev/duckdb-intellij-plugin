package dev.sort.duckdb.completion

import org.duckdb.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

class DuckdbNativeKeywordsTrustTest {
    private fun trusted(): Map<String, Any?> = mapOf("extension_name" to "autocomplete",
        "parameters" to "[\"sql\",\"max_suggestion_count\",\"max_file_suggestion_count\",\"max_exact_suggestion_count\"]",
        "parameter_types" to "[\"VARCHAR\",\"UBIGINT\",\"UBIGINT\",\"UBIGINT\"]", "varargs" to null)

    @Test fun `unknown origin signature and mixed registrations never invoke completion`() {
        val native = trusted()
        for(registrations in listOf(listOf(native + ("extension_name" to null)),listOf(native + ("extension_name" to "app")),
            listOf(native + ("parameters" to "[]")),listOf(native + ("parameter_types" to "[\"VARCHAR\",\"BIGINT\",\"UBIGINT\",\"UBIGINT\"]")),
            listOf(native + ("varargs" to "VARCHAR")),listOf(native + ("parameters" to "not-json")),
            listOf(native + ("parameter_types" to "[null]")),listOf(native,native),listOf(native,native + ("extension_name" to null)))) {
            val calls=mutableListOf<String>()
            val result=DuckdbNativeKeywords.query(requireNotNull(NativeKeywordPrefix.capture("SELECT WH",9))) { sql,_ ->
                calls+=sql
                when(sql) { DuckdbNativeKeywords.FOREIGN_CATALOGS->emptyList();DuckdbNativeKeywords.REGISTERED->registrations;else->error("Invoked untrusted function") }
            }
            assertTrue(result.items.isEmpty())
            assertTrue(result.status.orEmpty().contains("could not be verified"))
            assertEquals(listOf(DuckdbNativeKeywords.FOREIGN_CATALOGS,DuckdbNativeKeywords.REGISTERED),calls)
        }
    }

    @Test fun `same named native Java UDF is rejected before bind init or execution`() {
        val root=Files.createTempDirectory("autocomplete-origin-")
        try {
            val properties=Properties().apply {
                setProperty("extension_directory",root.resolve("extensions").toString())
                setProperty("secret_directory",root.resolve("secrets").toString())
                setProperty("autoload_known_extensions","false");setProperty("autoinstall_known_extensions","false")
                setProperty("allow_persistent_secrets","false")
            }
            val url="jdbc:duckdb:${root.resolve("fixture.duckdb")}"
            DriverManager.getConnection(url,properties).use { source -> DriverManager.getConnection(url,properties).use { helper ->
                val callbacks=AtomicInteger()
                DuckDBFunctions.tableFunction().use { builder ->
                    builder.withName("sql_auto_complete").withParameter(String::class.java)
                        .withNamedParameter("max_file_suggestion_count",DuckDBColumnType.UBIGINT)
                        .withNamedParameter("max_suggestion_count",DuckDBColumnType.UBIGINT)
                        .withNamedParameter("max_exact_suggestion_count",DuckDBColumnType.UBIGINT)
                        .withFunction(object: DuckDBTableFunction<Unit,Unit,Unit> {
                            override fun bind(info:DuckDBTableFunctionBindInfo) { callbacks.incrementAndGet();error("Completion bound an application UDF") }
                            override fun init(info:DuckDBTableFunctionInitInfo) { callbacks.incrementAndGet();error("Completion initialized an application UDF") }
                            override fun apply(info:DuckDBTableFunctionCallInfo,output:DuckDBDataChunkWriter):Long { callbacks.incrementAndGet();error("Completion executed an application UDF") }
                        }).register(source)
                }
                val calls=mutableListOf<String>()
                val result=DuckdbNativeKeywords.query(requireNotNull(NativeKeywordPrefix.capture("SELECT WH",9))) { sql,limit ->
                    calls+=sql
                    helper.createStatement().use { s -> s.executeQuery(sql).use { r -> buildList {
                        while(size<limit && r.next())add((1..r.metaData.columnCount).associate { r.metaData.getColumnLabel(it) to r.getString(it) })
                    } } }
                }
                assertTrue(result.status.orEmpty().contains("could not be verified"))
                assertEquals(0,callbacks.get())
                assertEquals(listOf(DuckdbNativeKeywords.FOREIGN_CATALOGS,DuckdbNativeKeywords.REGISTERED),calls)
            } }
        }finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
