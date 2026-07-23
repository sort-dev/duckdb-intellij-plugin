package dev.sort.duckdb.validate

import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.application.PathManager
import com.intellij.psi.PsiFile
import dev.sort.duckdb.DuckdbDbms
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Finds a DuckDB engine jar for the Stage-3 validator in a REAL IDE — the piece that turns the
 * grading-proven validator into visible squiggles. Resolution order (first hit wins):
 *
 *  1. Manual driver attaches on any DuckDB (Brikk) data source in the project
 *     ([com.intellij.database.dataSource.DatabaseDriver.getAdditionalClasspathElements] — public API).
 *  2. The IDE's downloaded-drivers directory (`$SYSTEM/jdbc-drivers`, the platform's DRIVER_DIR),
 *     preferring our own "DuckDB JDBC (Brikk)" artifact dir (newest version), then the stock
 *     "DuckDB" dir, then any duckdb jar in the tree.
 *
 * Every candidate is verified to actually contain `org/duckdb/DuckDBDriver.class` before use.
 * Deliberately NOT the internal artifact-loader API — paths and public driver surface only.
 * Quack-only setups have no local engine; the validator stays silent there by design (PLAN.md
 * Stage 3 lists the fallback options).
 */
object DuckdbEngineLocator {

    fun engineJarFor(file: PsiFile): Path? =
        dataSourceDriverJar(file) ?: systemDriversScan()

    private fun dataSourceDriverJar(file: PsiFile): Path? {
        val project = file.project
        for (ds in LocalDataSourceManager.getInstance(project).dataSources) {
            if (ds.dbms != DuckdbDbms.DUCKDB_BRIKK) continue
            val driver = ds.databaseDriver ?: continue
            for (element in driver.additionalClasspathElements) {
                for (url in element.classesRootUrls) {
                    val path = urlToPath(url) ?: continue
                    if (isDuckdbDriverJar(path)) return path
                }
            }
        }
        return null
    }

    /** `$SYSTEM/jdbc-drivers` scan; [baseOverride] exists for tests. */
    fun systemDriversScan(baseOverride: Path? = null): Path? {
        val base = baseOverride ?: Paths.get(PathManager.getSystemPath(), "jdbc-drivers")
        if (!base.isDirectory()) return null
        for (preferred in listOf("DuckDB JDBC (Brikk)", "DuckDB")) {
            val dir = base.resolve(preferred)
            if (dir.isDirectory()) {
                newestDriverJarUnder(dir)?.let { return it }
            }
        }
        return newestDriverJarUnder(base)
    }

    private fun newestDriverJarUnder(dir: Path): Path? =
        runCatching {
            Files.walk(dir, 4).use { stream ->
                stream.filter { it.name.endsWith(".jar") && it.name.contains("duckdb", ignoreCase = true) }
                    .filter { isDuckdbDriverJar(it) }
                    .sorted(compareByDescending { it.name })
                    .findFirst().orElse(null)
            }
        }.getOrNull()

    /** Cheap containment check: the jar really carries the native DuckDB JDBC driver. */
    fun isDuckdbDriverJar(path: Path): Boolean =
        runCatching {
            ZipFile(path.toFile()).use { it.getEntry("org/duckdb/DuckDBDriver.class") != null }
        }.getOrDefault(false)

    private fun urlToPath(url: String): Path? = runCatching {
        var s = url.removePrefix("jar://").removePrefix("file://")
        s = s.removeSuffix("!/")
        Paths.get(s).takeIf { Files.exists(it) }
    }.getOrNull()
}
