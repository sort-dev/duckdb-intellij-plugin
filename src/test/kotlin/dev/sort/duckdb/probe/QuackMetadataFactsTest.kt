package dev.sort.duckdb.probe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DatabaseMetaData

/**
 * STAGE-5 TRUTH (quack side, STATIC): the GizmoSQL quack driver has no local server in tests, so we
 * cannot invoke its [DatabaseMetaData] — but we CAN prove, from the jar on the classpath, that the
 * implementation class exists and which JDBC metadata methods it actually declares (overrides).
 * That tells us whether a GizmoSQL data source has any hope of a populated object tree at all.
 *
 * Sibling of [dev.sort.duckdb.QuackDriverFactsTest], same "make the jar identify itself" approach.
 * The full method inventory printed here feeds REPORT-stage5-tree.md; only stable facts are
 * asserted (class present, tree-relevant methods declared). Whether a declared method returns real
 * rows or throws at runtime is a per-server fact this offline test cannot reach — noted in the
 * report, not asserted.
 */
class QuackMetadataFactsTest {

    private val implName = "com.gizmodata.quack.jdbc.sql.QuackDatabaseMetaData"

    /** The metadata methods that decide whether an object tree can be built. */
    private val treeRelevant = listOf(
        "getCatalogs", "getSchemas", "getTables", "getColumns",
        "getPrimaryKeys", "getImportedKeys", "getExportedKeys", "getIndexInfo",
        "getTypeInfo", "getTableTypes", "getFunctions", "getProcedures",
        "supportsCatalogsInDataManipulation", "supportsSchemasInDataManipulation",
    )

    @Test
    fun `quack DatabaseMetaData implementation is present and self-describes`() {
        val clazz = runCatching { Class.forName(implName) }.getOrNull()
        assertTrue("quack metadata impl class not on test classpath: $implName", clazz != null)
        clazz!!

        assertTrue(
            "$implName does not implement java.sql.DatabaseMetaData",
            DatabaseMetaData::class.java.isAssignableFrom(clazz),
        )

        val declared = clazz.declaredMethods.map { it.name }.toSet()
        val ifaceMethods = DatabaseMetaData::class.java.methods.map { it.name }.toSet()
        val declaredIfaceMethods = ifaceMethods.count { it in declared }

        val jar = clazz.protectionDomain?.codeSource?.location
        val report = StringBuilder("\n=== QuackDatabaseMetaData static audit ===\n")
        report.append("jar: $jar\n")
        report.append("implements java.sql.DatabaseMetaData: true\n")
        report.append("DatabaseMetaData interface methods: ${ifaceMethods.size}\n")
        report.append("...of those DECLARED (overridden) in Quack impl: $declaredIfaceMethods\n")
        report.append("total declared members (incl. private helpers): ${clazz.declaredMethods.size}\n")
        report.append("tree-relevant method presence:\n")
        for (m in treeRelevant) report.append("  ${if (m in declared) "DECL " else "MISS "} $m\n")
        // Which interface methods are NOT overridden (would be the throwing/absent ones, if any):
        val notDeclared = ifaceMethods.filterNot { it in declared }.sorted()
        report.append("interface methods NOT overridden: ${if (notDeclared.isEmpty()) "(none)" else notDeclared}\n")
        report.append("==========================================")
        println(report)

        // Stable facts only: the whole tree-relevant set is genuinely declared on the class. A
        // concrete (non-abstract) class implementing DatabaseMetaData must supply every method —
        // there are no interface defaults — so this is a full implementation, not inherited stubs.
        val missing = treeRelevant.filterNot { it in declared }
        assertTrue("quack impl is missing tree-relevant metadata methods: $missing", missing.isEmpty())
    }
}
