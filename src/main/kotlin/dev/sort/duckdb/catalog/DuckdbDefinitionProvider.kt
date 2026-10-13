package dev.sort.duckdb.catalog

import com.intellij.database.model.ObjectKind
import dev.brikk.house.sql.intellij.catalog.ShowCreateDefinitionProvider
import dev.brikk.house.sql.shape.DefinedObject

/** Read object sources from DuckDB's qualified built-in catalog functions, never user macros. */
class DuckdbDefinitionProvider : ShowCreateDefinitionProvider(
    "duckdb",
    mapOf(
        ObjectKind.TABLE to DefinedObject.TABLE,
        ObjectKind.VIEW to DefinedObject.VIEW,
        ObjectKind.ROUTINE to DefinedObject.MACRO,
        ObjectKind.SEQUENCE to DefinedObject.SEQUENCE,
        ObjectKind.INDEX to DefinedObject.INDEX,
    ),
    listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA),
)
