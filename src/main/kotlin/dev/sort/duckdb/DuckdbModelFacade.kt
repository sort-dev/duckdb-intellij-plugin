package dev.sort.duckdb

import com.intellij.database.Dbms
import com.intellij.database.dialects.postgres.model.PgMetaModel
import com.intellij.database.model.ModelFacade
import com.intellij.database.model.ModelHelper
import com.intellij.database.model.meta.BasicMetaModel

/**
 * Model shape for DuckDB (Brikk) data sources: the public PG meta-model + helper, matching the
 * dialect substrate and the `extensionFallback → POSTGRES` line in plugin.xml.
 *
 * This class is the ClassCast gate (doris lesson): the model facade, the parsing dialect's
 * metadata family, and the introspector must AGREE on a model family, or attaching a data source
 * dies with `...ImplModel$Root cannot be cast ...`. PG is also the right long-term family:
 * DuckDB is multi-catalog (ATTACH), and PG's model carries a database level — the Stage-5
 * catalogs work (ATTACH'd databases in the tree) builds on this same choice.
 */
class DuckdbModelFacade(dbms: Dbms) : ModelFacade(dbms) {
    override fun getMetaModel(): BasicMetaModel<*> = PgMetaModel.MODEL

    // Via the Java shim: PgModelHelper is Kotlin-internal in metadata (public bytecode).
    override fun getModelHelper(): ModelHelper = PgModelAccess.pgModelHelper()
}
