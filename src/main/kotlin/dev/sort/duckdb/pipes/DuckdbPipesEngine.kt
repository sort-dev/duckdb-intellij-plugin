package dev.sort.duckdb.pipes

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.PipeStageSplitter
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.TranspileResult

/** Adapter to our own bundled translator; no dependency on another plugin's classes. */
object DuckdbPipesEngine {
    sealed interface Transpile {
        data class Ok(val sql: String, val result: TranspileResult) : Transpile {
            val executionError: Err? get() = result.unsupportedMessages.takeIf { it.isNotEmpty() }?.let {
                Err(null, null, "PIPE execution blocked: unsupported or lossy translation.\n" + it.joinToString("\n"))
            }
        }
        data class Err(val line: Int?, val col: Int?, val message: String) : Transpile
        data object NotPipe : Transpile
    }

    fun transpile(text: String, allowFirstFromStage: Boolean = false): Transpile {
        val chunks = DuckdbPipes.chunks(text)
        if (chunks.none { it.hasPipeOperator } && !allowFirstFromStage) return Transpile.NotPipe
        chunks.firstOrNull { it.boundaryError != null }?.let { return Transpile.Err(null, null, it.boundaryError!!) }
        if (chunks.count { it.hasSql } != 1) return Transpile.Err(null, null, "Select exactly one PIPE statement.")
        return try {
            val fragment = SqlFragment(text.trim(), "duckdb")
            // Nested pipes are supported too; isPipe alone checks only the root AST.
            if (!fragment.ast.findAll<PipeQuery>().any() && !(allowFirstFromStage && fragment.ast is Select))
                return Transpile.Err(null, null, "PIPE operator was not recognized as a query pipeline.")
            val result = fragment.toExecutable("duckdb", pretty = true)
            if (result.isRawPassthroughStatement) return Transpile.Err(null, null, "PIPE translation returned raw SQL.")
            if (result.unsupportedMessages.isEmpty()) {
                val generated = Dialects.DUCKDB.parseOne(result.sql)
                if (generated.findAll<PipeQuery>().any()) return Transpile.Err(null, null, "Translation left unlowered PIPE syntax.")
            }
            Transpile.Ok(result.sql, result)
        } catch (e: ParseError) {
            val first = e.errors.firstOrNull()
            Transpile.Err(first?.line, first?.col, first?.description ?: e.message ?: "PIPE parse error")
        } catch (e: UnsupportedError) {
            Transpile.Err(null, null, e.message ?: "Unsupported PIPE translation")
        } catch (e: TokenError) {
            Transpile.Err(null, null, e.message ?: "Invalid PIPE token")
        } catch (e: ShapeError) {
            Transpile.Err(null, null, e.message ?: "Invalid PIPE statement shape")
        }
    }

    data class StagePrefix(val text: String, val stage: Int, val total: Int)
    fun stagePrefixAt(text: String, offset: Int): StagePrefix? = runPipeCatching {
        val stages = PipeStageSplitter.split(text, "duckdb").stages
        val index = stages.indexOfLast { offset >= it.start }
        if (index < 0 || stages.size < 2) return null
        StagePrefix(text.substring(0, stages[index].endInclusive + 1), index + 1, stages.size)
    }.getOrNull()
}
