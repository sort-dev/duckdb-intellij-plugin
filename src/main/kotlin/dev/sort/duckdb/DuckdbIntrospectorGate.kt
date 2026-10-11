package dev.sort.duckdb

import com.intellij.database.Dbms
import com.intellij.database.introspection.DBIntrospectionContext
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.util.Version

/** Veto native introspection so every driver version uses JDBC metadata. */
class DuckdbIntrospectorGate : DBIntrospector.Factory {
    override fun isSupported(version: Version): Boolean = false

    override fun createIntrospector(
        context: DBIntrospectionContext,
        dbms: Dbms,
        modelFactory: ModelFactory,
    ): DBIntrospector = error("DuckDB native introspection is disabled; select the JDBC introspector")

    override fun getVersion(kind: ObjectKind): Int = 0
    override fun isOutdatedCheckSupported(e: BasicElement?): Boolean = false
}
