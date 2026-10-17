package dev.sort.duckdb.validate

import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.DatabaseDriverListener
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.model.RawDataSource
import com.intellij.database.psi.DataSourceManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Path

@Service(Service.Level.PROJECT)
internal class DuckdbEngineLocation(private val project: Project) : Disposable {
    private sealed interface Location {
        data object Unknown : Location
        data class Located(val path: Path?) : Location
    }

    private var location: Location = Location.Unknown

    init {
        project.messageBus.connect(this).subscribe(DataSourceManager.TOPIC, object : DataSourceManager.Listener {
            override fun <T : RawDataSource> dataSourceAdded(manager: DataSourceManager<T>, source: T) = invalidate()
            override fun <T : RawDataSource> dataSourceRemoved(manager: DataSourceManager<T>, source: T) = invalidate()
            override fun <T : RawDataSource> dataSourceChanged(manager: DataSourceManager<T>?, source: T?) = invalidate()
        })
        DatabaseDriverManager.getInstance().addDriverListener(object : DatabaseDriverListener {
            override fun driverAdded(driver: DatabaseDriver) = invalidate()
            override fun driverRemoved(driver: DatabaseDriver) = invalidate()
            override fun driverUpdated(driver: DatabaseDriver) = invalidate()
        }, this)
    }

    @Synchronized
    fun engineJar(locate: () -> Path? = { DuckdbEngineLocator.locate(project) }): Path? =
        when (val cached = location) {
            Location.Unknown -> locate().also { location = Location.Located(it) }
            is Location.Located -> cached.path
        }

    @Synchronized
    private fun invalidate() {
        location = Location.Unknown
    }

    override fun dispose() = Unit
}
