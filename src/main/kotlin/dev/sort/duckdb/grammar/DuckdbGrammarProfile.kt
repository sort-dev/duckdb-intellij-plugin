package dev.sort.duckdb.grammar

import com.google.gson.JsonParser

/** Grammar names are not module names. Nothing here installs or loads extension binaries. */
internal data class DuckdbGrammarProfile(val enabled: List<String> = emptyList(), val disabled: List<String> = emptyList()) {
    init {
        require(enabled.all { it.isNotBlank() }) { "Grammar names must not be blank" }
        require(disabled.all { it.isNotBlank() } && disabled.none { it in enabled }) { "Disabled grammar names must be distinct from enabled names" }
    }

    /** Keep the user's order; append only newly registered names, in a deterministic order. */
    fun discovered(available: List<String>) = copy(enabled = enabled + available.distinct().filterNot { it in enabled || it in disabled }.sorted())

    fun select(name: String, on: Boolean) = if (on) copy(
        enabled = if (name in enabled) enabled else enabled + name, disabled = disabled - name,
    ) else copy(enabled = enabled.filterNot { it == name }, disabled = (disabled + name).distinct().sorted())

    val activationSql: String
        get() = "SET active_grammar_extensions = [${enabled.joinToString(", ") { literal(it) }}]"

    companion object {
        fun literal(value: String) = "'${value.replace("'", "''")}'"
    }
}

internal data class DuckdbGrammarSnapshot(
    val available: List<String> = emptyList(),
    val effective: List<String> = emptyList(),
    val unavailable: String? = null,
) {
    val supported get() = unavailable == null

    fun entries(profile: DuckdbGrammarProfile): List<GrammarEntry> =
        profile.enabled.map { GrammarEntry(it, true, supported && it in available) } +
            (profile.disabled + available).distinct().filterNot { it in profile.enabled }
                .map { GrammarEntry(it, false, supported && it in available) }
}

internal data class GrammarEntry(val name: String, val enabled: Boolean, val available: Boolean)

internal interface GrammarConnection {
    fun rows(sql: String): List<Map<String, Any?>>
    fun execute(sql: String)
}

internal object DuckdbGrammarDiscovery {
    const val AVAILABLE = "SELECT name, description FROM system.main.duckdb_grammar_extensions()"
    const val EFFECTIVE = "SELECT system.main.to_json(system.main.current_setting('active_grammar_extensions'))"

    fun read(connection: GrammarConnection): DuckdbGrammarSnapshot {
        val available = connection.rows(AVAILABLE).map { row ->
            // The table function's first column is the registered grammar name, not an installed module.
            requireNotNull(row.values.firstOrNull()?.toString()) { "Missing registered grammar name" }
        }.distinct()
        val effective = effective(connection.rows(EFFECTIVE).firstOrNull()?.values?.firstOrNull()?.toString())
        return DuckdbGrammarSnapshot(available, effective)
    }

    fun effective(json: String?): List<String> {
        requireNotNull(json) { "Missing active grammar setting" }
        val value = JsonParser.parseString(json)
        require(value.isJsonArray) { "Invalid active grammar setting" }
        return value.asJsonArray.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid active grammar name" }
            it.asString
        }
    }

    /** Applies the exact managed order, then checks what the engine actually accepted. */
    fun apply(connection: GrammarConnection, profile: DuckdbGrammarProfile): DuckdbGrammarSnapshot {
        val before = read(connection)
        val missing = profile.enabled.filterNot { it in before.available }
        check(missing.isEmpty()) { "Configured grammars unavailable: ${missing.joinToString()}" }
        connection.execute(profile.activationSql)
        return read(connection).also {
            check(it.effective == profile.enabled) { "The connection did not retain the requested ordered grammar profile" }
        }
    }
}
