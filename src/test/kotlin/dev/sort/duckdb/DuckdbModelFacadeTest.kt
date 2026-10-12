package dev.sort.duckdb

import com.intellij.database.Dbms
import com.intellij.database.dialects.postgres.model.PgMetaModel
import com.intellij.database.model.ModelFacade
import com.intellij.database.model.ObjectKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.DuckdbDbms.DUCKDB_BRIKK

class DuckdbModelFacadeTest : BasePlatformTestCase() {
    fun testRegisteredFacadeKeepsPostgresModelAndDatabaseName() {
        val facade = checkNotNull(ModelFacade.forDbms(DUCKDB_BRIKK))
        assertInstanceOf(facade, DuckdbModelFacade::class.java)
        assertSame(PgMetaModel.MODEL, facade.metaModel)
        assertSame(checkNotNull(ModelFacade.forDbms(Dbms.POSTGRES)).modelHelper, facade.modelHelper)
        assertEquals("database", facade.modelHelper.getName(ObjectKind.DATABASE, false))
        assertEquals("databases", facade.modelHelper.getName(ObjectKind.DATABASE, true))
    }
}
