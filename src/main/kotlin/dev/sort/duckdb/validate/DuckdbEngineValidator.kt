package dev.sort.duckdb.validate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import dev.brikk.house.sql.verify.ParseOnlyDuckdbVerifier
import dev.brikk.house.sql.verify.VerifyError
import dev.brikk.house.sql.verify.VerifyResult
import java.sql.Driver

object DuckdbEngineValidator {
    val available: Boolean get() = withDrivers { it.defaultDriver != null }

    private inline fun <T> withDrivers(operation: (DuckdbEngineDrivers) -> T): T {
        val application = ApplicationManager.getApplication()
        return if (application == null) DuckdbEngineDrivers().use(operation)
        else operation(application.service<DuckdbEngineDriverService>().drivers)
    }

    fun validate(statements: List<String>, jarPath: String? = null): Map<Int, VerifyError> =
        verdicts(statements, jarPath).withIndex()
            .mapNotNull { (index, result) -> (result as? VerifyResult.Rejected)?.let { index to it.error } }
            .toMap()

    fun verdicts(statements: List<String>, jarPath: String? = null): List<VerifyResult> = withDrivers { drivers ->
        verdictsWithDriver(statements, if (jarPath == null) drivers.defaultDriver else drivers.driverForJar(jarPath))
    }

    internal fun verdictsWithDriver(statements: List<String>, driver: Driver?): List<VerifyResult> {
        ProgressManager.checkCanceled()
        if (driver == null) return statements.map { VerifyResult.Unavailable("No local DuckDB driver") }
        return ParseOnlyDuckdbVerifier(driver).use { verifier -> statements.map { sql ->
            ProgressManager.checkCanceled()
            verifier.verify(sql).also { ProgressManager.checkCanceled() }
        } }
    }
}
