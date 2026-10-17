package dev.sort.duckdb.pipes

import dev.brikk.house.sql.intellij.pipe.PipePreviewAction
import dev.brikk.house.sql.intellij.pipe.PipePreviewIntention
import dev.brikk.house.sql.intellij.pipe.PipeRunToStageAction
import dev.brikk.house.sql.intellij.pipe.PipeRunToStageIntention

class PreviewPipeSqlIntention : PipePreviewIntention(DuckdbPipeDialect)
class RunPipesToStageIntention : PipeRunToStageIntention(DuckdbPipeDialect)
class PreviewPipeSqlAction : PipePreviewAction(DuckdbPipeDialect)
class RunPipesToStageAction : PipeRunToStageAction(DuckdbPipeDialect)
