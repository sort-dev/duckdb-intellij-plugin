package dev.sort.duckdb

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.util.FileContentUtil
import java.lang.reflect.InvocationTargetException

/** 263 fixtures need the public workspace setter and a reparse. */
internal fun setSqlDialectMapping(project: Project, file: VirtualFile?, dialect: SqlLanguageDialect?) {
    val setter = try {
        Class.forName("com.intellij.sql.dialects.SqlDialectMappingUtilsKt").methods.singleOrNull {
            it.name == "setDialectMappingSync" && it.parameterCount == 3
        }
    } catch (_: ClassNotFoundException) { null }
    if (setter == null) SqlDialectMappings.getInstance(project).setMapping(file, dialect)
    else WriteAction.run<RuntimeException> {
        try {
            setter.invoke(null, project, file, dialect)
            FileContentUtil.reparseFiles(project, listOfNotNull(file), true)
        } catch (e: InvocationTargetException) { throw e.targetException }
    }
}

internal fun registeredSqlInspection(id: String): com.intellij.codeInspection.InspectionProfileEntry =
    requireNotNull(com.intellij.codeInspection.ex.InspectionToolRegistrar.getInstance().createTools()
        .firstOrNull { it.shortName == id }) { "Missing registered SQL inspection $id" }.tool
