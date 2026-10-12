package dev.sort.duckdb.pipes

import com.intellij.database.console.session.DatabaseSession
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.parser.ColumnUnit
import dev.brikk.house.sql.verify.VerifyResult
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import dev.sort.duckdb.brikk.isBrikkInjectedSql
import dev.sort.duckdb.sql.DuckdbSqlDialect
import dev.sort.duckdb.validate.DuckdbEngineLocator
import dev.sort.duckdb.validate.DuckdbEngineValidator

object DuckdbPipeDialect : PipeDialect {
    override val dialect = "duckdb"
    override val label = "DuckDB PIPE"
    override val vetoProperty = "duckdb.pipes"
    override val language get() = DuckdbSqlDialect
    override val serverColumnUnit = ColumnUnit.UTF16
    override fun isProjectEnabled(project: Project) = project.service<DuckdbPipesSettings>().enabled
    override fun enablesEditing(file: PsiFile) = BrikkDuckdbProfile.forFile(file) != null
    override fun isInspectionHost(file: PsiFile) = isBrikkInjectedSql(file)
    override fun ownsSession(session: DatabaseSession) = session.connectionPoint.dbms === DuckdbDbms.DUCKDB_BRIKK
    override val validator get() = object : NativeSqlValidator {
        override val timing = ValidationTiming.Submission
        private var located = false
        private var jar: String? = null
        override fun validate(sql: String, file: PsiFile?): NativeVerdict {
            if (!located) {
                jar = file?.let { DuckdbEngineLocator.engineJarFor(it)?.toString() }
                located = true
            }
            return when (val verdict = DuckdbEngineValidator.verdicts(listOf(sql), jar).single()) {
                VerifyResult.Accepted -> NativeVerdict.Accepted
                is VerifyResult.Unavailable, is VerifyResult.Unverified -> NativeVerdict.Unavailable
                is VerifyResult.Rejected -> NativeVerdict.Rejected(verdict.error.message)
            }
        }
    }
}
