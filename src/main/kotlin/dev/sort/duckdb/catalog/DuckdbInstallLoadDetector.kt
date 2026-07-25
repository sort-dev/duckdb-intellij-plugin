package dev.sort.duckdb.catalog

/**
 * Cheap statement-head scan for executed SQL that changes the FUNCTION inventory: `INSTALL`,
 * `FORCE INSTALL`, or `LOAD` as the first meaningful word(s) of any statement in the script.
 *
 * The scanning discipline — split on `;`, skip leading whitespace/comments, read only the head
 * words, word boundaries so `install_log` / `INSTALLER` never match — lives in
 * [DuckdbStatementHeads] and is shared with the object-tree sibling [DuckdbAttachDetector].
 *
 * `UPDATE EXTENSIONS` intentionally does NOT match: it refreshes the extension repository
 * metadata but loads nothing, so the live function inventory is unchanged.
 */
object DuckdbInstallLoadDetector {

    /** True when [script] contains a statement whose head is INSTALL / FORCE INSTALL / LOAD. */
    fun triggersCatalogChange(script: String?): Boolean {
        if (script.isNullOrBlank()) return false
        return DuckdbStatementHeads.statements(script).any { statementTriggers(it) }
    }

    private fun statementTriggers(statement: String): Boolean {
        val head = DuckdbStatementHeads.headWords(statement, 2)
        val first = head.firstOrNull() ?: return false
        return when {
            first.equals("INSTALL", ignoreCase = true) -> true
            first.equals("LOAD", ignoreCase = true) -> true
            first.equals("FORCE", ignoreCase = true) ->
                head.getOrNull(1).equals("INSTALL", ignoreCase = true)
            else -> false
        }
    }
}
