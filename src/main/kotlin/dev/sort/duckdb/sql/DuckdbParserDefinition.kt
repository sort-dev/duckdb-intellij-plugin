package dev.sort.duckdb.sql

import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.postgres.PgElementFactory
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType

/**
 * DuckDB (Brikk) parsing on the PG foundation: [DuckdbLexer] (token-layer DuckDB bridge) + PG
 * element factory + [DuckdbPsiParser] (statement-boundary dispatch). The census scoreboard proved
 * which constructs need the token layer — see DuckdbLexer's rule list.
 */
class DuckdbParserDefinition : SqlParserDefinitionBase() {
    override fun createElementFactory(): SqlElementFactoryBase = PgElementFactory()
    override fun createLexer(project: Project): Lexer = DuckdbLexer()
    override fun createParser(project: Project): PsiParser = DuckdbPsiParser()
    override fun getFileNodeType(): IFileElementType = FILE

    private companion object {
        private val FILE = SqlFileElementType("DUCKDB_BRIKK_SQL_FILE", DuckdbSqlDialect.INSTANCE)
    }
}
