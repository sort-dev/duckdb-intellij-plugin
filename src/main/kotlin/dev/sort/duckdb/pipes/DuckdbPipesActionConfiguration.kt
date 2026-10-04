package dev.sort.duckdb.pipes

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import java.util.Collections
import java.util.IdentityHashMap

/** Capture predecessors synchronously. This composes with Doris in either installation order. */
class DuckdbPipesActionConfiguration : DynamicActionConfigurationCustomizer {
    private val installed = LinkedHashMap<String, DuckdbPipesRunQueryAction>()
    override fun registerActions(actionManager: ActionManager) {
        if (installed.isNotEmpty()) return
        for ((index, id) in EXECUTE_IDS.withIndex()) {
            val previous = requireNotNull(actionManager.getAction(id)) { "Missing Execute action $id" }
            val replacement = if (index == 3) DuckdbPipesRunSelectionAction(previous)
                else DuckdbPipesRunQueryAction(index + 1, previous)
            actionManager.replaceAction(id, replacement)
            installed[id] = replacement
        }
    }
    override fun unregisterActions(actionManager: ActionManager) {
        for ((id, action) in installed) {
            action.detach()
            if (actionManager.getAction(id) === action) actionManager.replaceAction(id, action.delegate)
        }
        installed.clear()
    }
    companion object {
        val EXECUTE_IDS = listOf("Console.Jdbc.Execute", "Console.Jdbc.Execute.2",
            "Console.Jdbc.Execute.3", "Console.Jdbc.Execute.Selection")
    }
}

/** Use the public promoter interface, never link/instantiate its package-private implementation. */
class DuckdbPipesActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        var ours = false
        val delegates = actions.map { action ->
            var current = action
            var containsUs = current is DuckdbPipesRunQueryAction
            val seen = Collections.newSetFromMap(IdentityHashMap<AnAction, Boolean>())
            while (seen.add(current)) {
                val next = (current as? ActionWithDelegate<*>)?.delegate as? AnAction ?: break
                if (next in seen) break
                current = next
                containsUs = containsUs || current is DuckdbPipesRunQueryAction
            }
            ours = ours || containsUs
            if (containsUs) current else action
        }
        if (!ours) return emptyList()
        val database = ActionPromoter.EP_NAME.extensionList.firstOrNull {
            it.javaClass.name == "com.intellij.database.actions.DatabaseActionPromoter"
        } ?: return emptyList()
        return database.promote(delegates, context).orEmpty().flatMap { promoted ->
            actions.indices.filter { delegates[it] === promoted }.map { actions[it] }
        }
    }
}
