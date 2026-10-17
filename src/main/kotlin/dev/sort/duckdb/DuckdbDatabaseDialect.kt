package dev.sort.duckdb

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dialects.base.TypeHelper
import com.intellij.database.dialects.postgres.PgDialect
import com.intellij.database.dialects.postgresbase.PgDialectBase
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasUserDefinedType
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.DdlBuilder
import com.intellij.database.util.SearchPath
import dev.brikk.house.sql.intellij.catalog.CatalogSchemaSearchPath
import dev.brikk.house.sql.intellij.catalog.NamespaceProbe

class DuckdbDatabaseDialect : PgDialectBase(TypeHelper.PgTypeHelper()) {
    private val postgres = PgDialect(DuckdbDbms.DUCKDB_BRIKK)
    private val current = "SELECT current_database() AS catalog, current_schema() AS schema"
    private val searchPath = CatalogSchemaSearchPath('"', "USE {catalog}.{schema}",
        useBareSchema = "USE {schema}", useCatalog = "USE {catalog}",
        catalogProbes = listOf(NamespaceProbe.Column(current, "catalog")),
        schemaProbes = listOf(NamespaceProbe.Column(current, "schema")))

    override fun getDbms(): Dbms = DuckdbDbms.DUCKDB_BRIKK
    override fun getDisplayName(): String = postgres.displayName
    override fun canUnquoteAlias(name: String, kind: ObjectKind?, quoted: Boolean) = postgres.canUnquoteAlias(name, kind, quoted)
    override fun qualifiedIdentifier(builder: DdlBuilder, identifier: String, definition: DasObject?, qualifier: DasObject): DdlBuilder =
        postgres.qualifiedIdentifier(builder, identifier, definition, qualifier)
    override fun convertUDTToDataType(type: DasUserDefinedType) = postgres.convertUDTToDataType(type)

    override fun sqlSetSearchPath(path: SearchPath): String? = searchPath.switchSql(path)
    override fun getSearchPathQuery(): String = current
    override fun tryToLoadSearchPath(connection: DatabaseConnectionCore): SearchPath? = searchPath.readBack(connection)
}
