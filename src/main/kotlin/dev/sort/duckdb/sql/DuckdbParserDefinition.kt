package dev.sort.duckdb.sql

import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.postgres.PgElementFactory
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType

/**
 * DuckDB (Brikk) parsing on the PG foundation: PG lexer + element factory + [DuckdbPsiParser].
 * A custom masking lexer (the DorisLexer pattern) is deliberately absent until the syntax
 * scoreboard proves a construct can only be fixed at the token layer.
 */
class DuckdbParserDefinition : SqlParserDefinitionBase() {
    override fun createElementFactory(): SqlElementFactoryBase = PgElementFactory()
    override fun createLexer(project: Project): Lexer = PgLexer()
    override fun createParser(project: Project): PsiParser = DuckdbPsiParser()
    override fun getFileNodeType(): IFileElementType = FILE

    private companion object {
        private val FILE = SqlFileElementType("DUCKDB_BRIKK_SQL_FILE", DuckdbSqlDialect.INSTANCE)
    }
}
