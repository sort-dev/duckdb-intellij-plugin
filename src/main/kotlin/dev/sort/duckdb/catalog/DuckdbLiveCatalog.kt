package dev.sort.duckdb.catalog

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import dev.sort.duckdb.sql.DuckdbFunctionCatalog
import org.jetbrains.annotations.TestOnly
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-data-source live catalogs, keyed by `LocalDataSource.getUniqueId()`. The in-memory map is
 * the completion hot path (one ConcurrentHashMap get per lookup); each successful harvest is also
 * persisted as one TSV file under `$SYSTEM/duckdb-brikk/catalog/`, loaded lazily on the first
 * query after a restart — so a data source keeps its engine-exact completion while offline.
 *
 * File format (`<sanitized dsId>.tsv`, UTF-8, one record per line, tab-separated):
 *  - `#`-prefixed lines: comments (the writer starts with a self-describing header)
 *  - `meta<TAB>engineVersion<TAB>comma-joined sorted loaded extensions<TAB>harvestedAtMillis` (exactly once)
 *  - `fn<TAB>name<TAB>KIND` (KIND = [DuckdbFunctionCatalog.Kind] enum name)
 *  - `kw<TAB>KEYWORD`
 * A file that fails to parse is treated as absent (never a crash, never a partial entry).
 */
object DuckdbLiveCatalog {

    data class Entry(
        val engineVersion: String,
        /** Sorted — part of the "which engine is this" identity the harvest captured. */
        val loadedExtensions: List<String>,
        val functions: List<DuckdbFunctionCatalog.Fn>,
        val keywords: Set<String>,
        val harvestedAtMillis: Long,
    )

    private val LOG = Logger.getInstance(DuckdbLiveCatalog::class.java)

    private val memory = ConcurrentHashMap<String, Entry>()

    /** dsIds whose disk file was already probed — a miss is cached too (no per-keystroke IO). */
    private val diskProbed = ConcurrentHashMap.newKeySet<String>()

    /** Tests point persistence at a temp dir; production always derives from [PathManager]. */
    @Volatile
    @TestOnly
    var baseDirOverride: Path? = null

    /** Synchronous and allocation-light after the first call per data source (memory hit). */
    fun entryFor(dataSourceId: String?): Entry? {
        if (dataSourceId == null) return null
        memory[dataSourceId]?.let { return it }
        if (!diskProbed.add(dataSourceId)) return null // probed before: nothing on disk either
        val loaded = runCatching { load(fileFor(dataSourceId)) }.getOrNull() ?: return null
        // putIfAbsent: a harvest racing this lazy load wins (it is fresher than the file).
        return memory.putIfAbsent(dataSourceId, loaded) ?: loaded
    }

    /** REPLACES any previous entry for the data source; persistence is best-effort. */
    fun store(dataSourceId: String, entry: Entry) {
        memory[dataSourceId] = entry
        diskProbed.add(dataSourceId)
        try {
            save(fileFor(dataSourceId), entry)
        } catch (t: Throwable) {
            LOG.warn("live catalog for $dataSourceId not persisted (in-memory copy still active): ${t.message}")
        }
    }

    @TestOnly
    fun clearForTests() {
        memory.clear()
        diskProbed.clear()
    }

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    private fun baseDir(): Path =
        baseDirOverride ?: Paths.get(PathManager.getSystemPath(), "duckdb-brikk", "catalog")

    private fun fileFor(dataSourceId: String): Path = baseDir().resolve(sanitize(dataSourceId) + ".tsv")

    /** dsIds are UUID-shaped in practice; anything filename-hostile becomes `_`. */
    private fun sanitize(dataSourceId: String): String {
        val cleaned = dataSourceId.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("").take(120)
        return cleaned.ifEmpty { "ds" }
    }

    private fun save(file: Path, entry: Entry) {
        Files.createDirectories(file.parent)
        val text = buildString {
            appendLine("# duckdb-brikk live catalog v1 (see DuckdbLiveCatalog for the format)")
            appendLine("# meta\tengineVersion\textensions-csv\tharvestedAtMillis; fn\tname\tKIND; kw\tKEYWORD")
            appendLine("meta\t${entry.engineVersion}\t${entry.loadedExtensions.joinToString(",")}\t${entry.harvestedAtMillis}")
            for (fn in entry.functions) appendLine("fn\t${fn.name}\t${fn.kind.name}")
            for (kw in entry.keywords) appendLine("kw\t$kw")
        }
        // Atomic: a reader (or a crash) never sees a half-written catalog.
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** `null` for absent/corrupt files — a bad cache degrades to the bundled snapshot. */
    private fun load(file: Path): Entry? {
        if (!Files.isRegularFile(file)) return null
        var version: String? = null
        var extensions: List<String> = emptyList()
        var harvestedAt = 0L
        val functions = ArrayList<DuckdbFunctionCatalog.Fn>()
        val keywords = LinkedHashSet<String>()
        for (line in Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            when (parts[0]) {
                "meta" -> {
                    if (parts.size < 4 || version != null) return null // missing/duplicate meta = corrupt
                    version = parts[1]
                    extensions = parts[2].split(',').filter { it.isNotEmpty() }
                    harvestedAt = parts[3].toLongOrNull() ?: return null
                }
                "fn" -> {
                    if (parts.size < 3) return null
                    val kind = runCatching { DuckdbFunctionCatalog.Kind.valueOf(parts[2]) }
                        .getOrDefault(DuckdbFunctionCatalog.Kind.OTHER)
                    functions.add(DuckdbFunctionCatalog.Fn(parts[1], kind))
                }
                "kw" -> {
                    if (parts.size < 2) return null
                    keywords.add(parts[1])
                }
                else -> return null
            }
        }
        val v = version ?: return null
        if (functions.isEmpty() || keywords.isEmpty()) return null // same validity bar as the harvest
        return Entry(v, extensions, functions, keywords, harvestedAt)
    }
}
