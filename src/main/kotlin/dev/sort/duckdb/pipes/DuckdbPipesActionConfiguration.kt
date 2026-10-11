package dev.sort.duckdb.pipes

import dev.brikk.house.sql.intellij.CooperativeExecuteConfiguration
import dev.brikk.house.sql.intellij.CooperativeExecutePromoter
import dev.brikk.house.sql.intellij.ExecuteInterceptor

class DuckdbPipesActionConfiguration : CooperativeExecuteConfiguration() {
    override val interceptor = ExecuteInterceptor(PipesExecuteInterceptor::handle)

    companion object {
        val EXECUTE_IDS = IDS
    }
}

class DuckdbPipesActionPromoter : CooperativeExecutePromoter()
