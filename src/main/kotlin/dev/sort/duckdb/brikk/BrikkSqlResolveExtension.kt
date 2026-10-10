package dev.sort.duckdb.brikk

import com.intellij.database.model.*
import com.intellij.database.symbols.DasColumnSymbol
import com.intellij.database.symbols.DasPsiSymbol
import com.intellij.psi.*
import com.intellij.psi.impl.light.LightElement
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.impl.SqlResolveExtension
import com.intellij.util.containers.JBIterable
import dev.brikk.house.intellij.ColumnContract
import dev.brikk.house.intellij.ColumnNullability
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** Native SQL references receive statement-local contract symbols, never datasource objects. */
class BrikkSqlResolveExtension : SqlResolveExtension {
    override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        val psiReference: PsiReference = reference
        val element = psiReference.element
        val file = element.containingFile ?: return true
        val start = element.textRange.startOffset + psiReference.rangeInElement.startOffset
        val scoped = BrikkSqlEditorSupport.reference(element, start) ?: return true
        if (scoped.kind != BrikkSqlScopes.Kind.COLUMN) return true
        for ((relation, column) in scoped.scope.candidates(scoped.name, scoped.qualifier)) {
            if (!relation.managed) continue
            val symbol = BrikkColumnSymbol(file, relation, column)
            if (!processor.execute(symbol, ResolveState.initial())) return false
        }
        return true
    }
}

internal class BrikkColumnSymbol(private val file: PsiFile, relation: BrikkSqlScopes.Relation,
                                 val contract: ColumnContract) :
    LightElement(file.manager, DuckdbSqlDialect.INSTANCE), DasPsiSymbol, DasColumnSymbol {
    private val objectValue = ContractColumn(relation, contract)
    private val scopeIdentity = Triple(relation.alias.lowercase(java.util.Locale.ROOT), relation.slot, relation.sourceStart)
    override fun equals(other: Any?): Boolean = other is BrikkColumnSymbol && file.originalFile === other.file.originalFile &&
        scopeIdentity == other.scopeIdentity && contract.name == other.contract.name &&
        contract.kotlinClassId == other.contract.kotlinClassId && contract.nullability == other.contract.nullability &&
        contract.sqlFamily == other.contract.sqlFamily
    override fun hashCode(): Int = listOf(System.identityHashCode(file.originalFile), scopeIdentity,
        contract.name, contract.kotlinClassId, contract.nullability, contract.sqlFamily).hashCode()
    override fun getName() = contract.name
    override fun setName(name: String): PsiElement = throw com.intellij.util.IncorrectOperationException("Rename the Kotlin column declaration")
    override fun isQuoted() = false
    override fun getKind() = ObjectKind.COLUMN
    override fun getDbms() = DuckdbDbms.DUCKDB_BRIKK
    override fun getDasObject(): DasColumn = objectValue
    override fun getContainingFile() = file
    override fun getContextElement(): PsiElement = file
    override fun getNavigationElement(): PsiElement = BrikkSqlEditorSupport.navigation(file, contract.navigation) ?: this
    override fun getText() = contract.name
    override fun toString() = "Brikk relation column ${contract.name}"

    private class ContractTable(private val relation: BrikkSqlScopes.Relation) : DasTable {
        override fun getName() = relation.alias.ifBlank { relation.slot.orEmpty() }
        override fun getKind() = ObjectKind.TABLE
        override fun isQuoted() = false
        override fun isSystem() = false
        override fun isTemporary() = false
        override fun getColumnAttrs(column: DasColumn?): Set<DasColumn.Attribute> = emptySet()
        override fun getDasChildren(kind: ObjectKind?): JBIterable<out DasObject> =
            if (kind == null || kind == ObjectKind.COLUMN) JBIterable.from(relation.columns.map { ContractColumn(relation, it) })
            else JBIterable.empty()
    }
    private class ContractColumn(private val relation: BrikkSqlScopes.Relation, private val column: ColumnContract) : DasColumn {
        override fun getName() = column.name
        override fun getKind() = ObjectKind.COLUMN
        override fun isQuoted() = false
        override fun getTable(): DasTable = ContractTable(relation)
        override fun getDasParent(): DasObject = table
        override fun getPosition(): Short = -1 // Kotlin enumeration is not a SQL column ordinal.
        override fun isNotNull() = column.nullability == ColumnNullability.NON_NULL
        override fun getDefault(): String? = null
        // A Kotlin String does not prove VARCHAR/JSON/UUID. Use the modern DasType API,
        // not the getDataType override scheduled for removal in supported IDE builds.
        override fun getDasType(): com.intellij.database.types.DasType = object : com.intellij.database.types.DasType {
            override fun getTypeClass() = com.intellij.database.types.DasSimpleTypeClass("UNKNOWN")
            override fun getDescription() = "Kotlin relation contract (native SQL type unknown)"
            override fun toDataType() = DataType.UNKNOWN
        }
    }
}
