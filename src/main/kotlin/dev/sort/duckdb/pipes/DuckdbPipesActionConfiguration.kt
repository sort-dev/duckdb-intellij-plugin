package dev.sort.duckdb.pipes

import dev.brikk.house.sql.intellij.CooperativeExecuteConfiguration
import dev.brikk.house.sql.intellij.CooperativeExecutePromoter
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor

class DuckdbPipesActionConfiguration : CooperativeExecuteConfiguration() {
    override val interceptor = PipeExecuteInterceptor(DuckdbPipeDialect)

    companion object {
        val EXECUTE_IDS = IDS
    }
}

class DuckdbPipesActionPromoter : CooperativeExecutePromoter()
