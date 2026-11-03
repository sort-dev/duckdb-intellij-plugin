package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.LocalDataSource
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.recoverEditorOperation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.SQLException
import java.util.Properties

/** In-memory only. Never print or persist configuration/property values. */
internal class DuckdbGrammarConfiguration private constructor(
    private val sourceShape: SourceShape,
    private val driver: DatabaseDriver?,
    private val driverShape: DriverShape?,
    private val modules: Map<String, ModuleStamp?>,
) {
    private data class SourceShape(val id: String, val url: String?, val user: String?, val driverClass: String?,
        val properties: Map<Any,Any>, val workingDir: String?, val vmOptions: String?, val environment: Map<String,String>,
        val additional: Map<String,String?>, val readOnly: Boolean, val autoCommit: Boolean, val isolation: Int,
        val authentication: String?, val startup: String?, val singleConnection: Boolean)
    private data class DriverShape(val id: String, val driverClass: String?, val vmOptions: String?,
        val environment: Map<String,String>, val properties: Map<String,String>, val additional: Map<String,String?>,
        val artifacts: List<Triple<String,String?,String?>>, val roots: List<String>, val nativeRoots: List<String>)
    private data class ModuleStamp(val size: Long, val modified: String, val created: String, val key: String?)

    fun matches(source: LocalDataSource): Boolean = recoverEditorOperation {
        source.databaseDriver === driver && sourceShape(source) == sourceShape && shape(source.databaseDriver) == driverShape
    }.getOrDefault(false)

    /** Local module attributes, not a claim of cryptographic integrity or session equivalence. */
    fun modulesCurrent(): Boolean = modules.all { (url, before) -> before != null && stamp(url) == before }

    /** Snapshot URL/properties but retain the source and driver handles that identify
     * the approved shared JDBC process. This is not general session cloning. */
    fun helperPoint(source: LocalDataSource): HelperConnectionPoint {
        if (!matches(source) || !modulesCurrent()) throw SQLException("Captured connection configuration changed or module identity is unavailable")
        val originalDriver=driver ?: throw SQLException("No captured driver is available")
        val url=sourceShape.url
        val driverClass=sourceShape.driverClass ?: driverShape?.driverClass
        return HelperConnectionPoint(object: DatabaseConnectionPoint.Adapter(source) {
            override fun getUrl(): String? = url
            override fun getDriverClass(): String? = driverClass
            override fun getDatabaseDriver(): DatabaseDriver = originalDriver
            override fun getConnectionProperties(): Properties = Properties().also { it.putAll(sourceShape.properties) }
        })
    }

    override fun toString(): String = "Captured DuckDB connection configuration"

    companion object {
        fun capture(source: LocalDataSource): DuckdbGrammarConfiguration {
            val driver=source.databaseDriver
            val shape=shape(driver)
            return DuckdbGrammarConfiguration(sourceShape(source),driver,shape,
                (shape?.roots.orEmpty()+shape?.nativeRoots.orEmpty()).distinct().associateWith(::stamp))
        }

        private fun sourceShape(source: LocalDataSource) = SourceShape(source.uniqueId,source.url,source.username,source.driverClass,
            source.connectionProperties.toMap(),source.workingDir,source.vmOptions,source.vmEnv.toMap(),
            source.additionalPropertiesNames.associateWith(source::getAdditionalProperty),source.isReadOnly,source.isAutoCommit,
            source.txIsolation,source.authProviderId,source.initScript,DuckdbSingleConnectionMode.requested(source))

        private fun shape(driver: DatabaseDriver?): DriverShape? = driver?.let {
            DriverShape(it.id,it.driverClass,it.vmOptions,it.vmEnv.toMap(),it.driverProperties.toMap(),
                it.additionalPropertiesNames.associateWith(it::getAdditionalProperty),
                it.artifacts.map { artifact -> Triple(artifact.id,artifact.artifactVersion?.toString(),artifact.channel) },
                it.additionalClasspathElements.flatMap { element -> element.classesRootUrls }.toList(),
                it.nativeLibraryPaths.flatMap { element -> element.classesRootUrls }.toList())
        }

        private fun stamp(url: String): ModuleStamp? = runCatching {
            val raw=url.removePrefix("jar://").removePrefix("file://").substringBefore("!/")
            if (raw.contains("://")) return null // Never access remote module URLs to establish identity.
            val path=Path.of(raw)
            if (!path.isAbsolute) return null
            val attributes=Files.readAttributes(path,BasicFileAttributes::class.java)
            if (!attributes.isRegularFile) return null
            ModuleStamp(attributes.size(),attributes.lastModifiedTime().toString(),attributes.creationTime().toString(),attributes.fileKey()?.toString())
        }.getOrNull()
    }
}
