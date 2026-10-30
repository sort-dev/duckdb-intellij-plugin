package dev.sort.duckdb.grammar

import com.google.gson.JsonParser
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.util.UUID

/** Grammar preferences never change a native connection configuration. */
@Service(Service.Level.PROJECT)
@State(name = "DuckdbProjectGrammarProfiles", storages = [Storage("duckdb-grammar-profiles.xml")])
internal class DuckdbGrammarSettings(private val project: Project) : PersistentStateComponent<DuckdbGrammarSettings.Stored> {
    class Profile {
        var enabled: MutableList<String> = mutableListOf()
        var disabled: MutableList<String> = mutableListOf()
    }
    class Stored {
        var scopeId: String = UUID.randomUUID().toString()
        var profiles: MutableMap<String, Profile> = linkedMapOf()
    }
    private var stored = Stored()
    val scopeId get() = stored.scopeId
    @Synchronized override fun getState() = copy(stored)
    @Synchronized override fun loadState(state: Stored) { stored = copy(state) }

    private fun copy(state: Stored) = Stored().also { out ->
        out.scopeId = state.scopeId
        state.profiles.forEach { (id, value) -> out.profiles[id] = Profile().also {
            it.enabled = value.enabled.toMutableList(); it.disabled = value.disabled.toMutableList()
        } }
    }

    fun profile(id: String) = source(id)?.let(::profile) ?: synchronized(this) { saved(id) ?: DuckdbGrammarProfile() }
    @Synchronized fun profile(source: LocalDataSource): DuckdbGrammarProfile = saved(source.uniqueId) ?: sourceChoice(source)
    private fun saved(id: String) = stored.profiles[id]?.let { DuckdbGrammarProfile(it.enabled.toList(), it.disabled.toList()) }

    @Synchronized fun setProfile(id: String, profile: DuckdbGrammarProfile): Boolean {
        if (profile(id) == profile) return false
        stored.profiles[id] = Profile().also { it.enabled = profile.enabled.toMutableList(); it.disabled = profile.disabled.toMutableList() }
        return true
    }

    @Synchronized fun discovered(source: LocalDataSource, available: List<String>): Boolean =
        setProfile(source.uniqueId, profile(source).discovered(available))

    private fun source(id: String) = LocalDataSourceManager.getInstance(project).dataSources.firstOrNull { it.uniqueId == id }

    // Existing source-owned choices are read-only. Removing or rewriting them would reset live connections.
    private fun sourceChoice(source: LocalDataSource): DuckdbGrammarProfile {
        val json = source.getAdditionalProperty("dev.sort.duckdb.grammar.profiles") ?: return DuckdbGrammarProfile()
        val names = JsonParser.parseString(json).asJsonObject.getAsJsonObject("projects")?.get(scopeId)
            ?: return DuckdbGrammarProfile()
        return DuckdbGrammarProfile(DuckdbGrammarDiscovery.effective(names.toString()))
    }
}
