package dev.sort.duckdb.catalog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertFallbackExtensionsAudited
import dev.sort.duckdb.DuckdbDbms.DUCKDB_BRIKK

class DuckdbFallbackExtensionAuditTest : BasePlatformTestCase() {
    fun testInheritedExtensionsAreAudited() {
        val allowed = mutableMapOf(
            "com.intellij.database.dataImporter" to "Retain PostgreSQL import defaults; fast-mode session SQL needs a separate DuckDB review.",
            "com.intellij.database.databaseViewStructureExtension" to "Retain PostgreSQL tree grouping for the existing PostgreSQL-family model.",
            "com.intellij.database.definitionProvider" to "Retain existing source retrieval; PostgreSQL catalog queries need a separate DuckDB review.",
            "com.intellij.database.dialect" to "Retain the database dialect paired with the PostgreSQL-family model and DuckDB SQL dialect.",
            "com.intellij.database.dmlHelper" to "Retain existing grid DML; PostgreSQL row identifiers and generated SQL need a separate DuckDB review.",
            "com.intellij.database.domainRegistry" to "Retain PostgreSQL type domains used by the existing model and type system.",
            "com.intellij.database.executionEnvironmentHelper" to "Retain existing session handling; PostgreSQL SSL and time-zone probes need a separate DuckDB review.",
            "com.intellij.database.explainPlanProvider" to "Retain existing plan integration; PostgreSQL EXPLAIN syntax and result parsing need a separate DuckDB review.",
            "com.intellij.database.geoHelper" to "Retain existing client-side PostgreSQL geometry conversion without adding server queries.",
            "com.intellij.database.gridColumnsManagerFactory" to "The UNKNOWN column manager supplies generic result-grid column handling.",
            "com.intellij.database.gridHelper" to "The UNKNOWN grid helper supplies generic result-grid editing and presentation.",
            "com.intellij.database.hookUpHelper" to "The UNKNOWN hookup helper supplies generic file-to-data-source association.",
            "com.intellij.database.jdbcMetadataWrapper" to "Retain existing JDBC metadata handling; PostgreSQL catalog probes need a separate DuckDB review.",
            "com.intellij.database.jdbcSourceLoader" to "Retain existing source loading; pg_catalog routine and view queries need a separate DuckDB review.",
            "com.intellij.database.namingService" to "Retain PostgreSQL identifier quoting for the existing model and script generator.",
            "com.intellij.database.objectEditorFactory" to "Retain existing schema editors; PostgreSQL-only DDL options need a separate DuckDB review.",
            "com.intellij.database.objectEditorModelFactory" to "Retain existing schema-editor models alongside the PostgreSQL script generator.",
            "com.intellij.database.predicatesHelper" to "Retain existing grid predicates; PostgreSQL-specific predicate SQL needs a separate DuckDB review.",
            "com.intellij.database.routineExecutionHelper" to "PostgreSQL registers the generic routine request builder, not a PostgreSQL-specific executor.",
            "com.intellij.database.scriptGenerator" to "Retain existing DDL generation; PostgreSQL-specific server statements need a separate DuckDB review.",
            "com.intellij.database.sqlEffectAnalyzer" to "Retain PostgreSQL statement-effect analysis for model refreshes without changing server SQL.",
            "com.intellij.database.sqlObjectBuilder" to "Retain PostgreSQL SQL-to-model construction paired with the existing model family.",
            "com.intellij.database.typeSystem" to "Retain the PostgreSQL type system paired with the existing domain registry and model.",
            "com.intellij.sql.navigationHelper" to "The UNKNOWN navigation helper supplies generic SQL PSI navigation.",
        )
        val transactionHandler = "com.intellij.database.transactionControlLanguageHandler"
        if (ApplicationManager.getApplication().extensionArea.hasExtensionPoint(transactionHandler)) {
            allowed[transactionHandler] = "Retain the native handler for PostgreSQL-family COMMIT/ROLLBACK PSI; live console tests verify committed and rolled-back data in another session."
        }
        assertFallbackExtensionsAudited(DUCKDB_BRIKK, allowed)
    }
}
