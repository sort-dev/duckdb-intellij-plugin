package dev.sort.duckdb.brikk

import dev.brikk.house.intellij.*
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.SqlFragment
import dev.sort.duckdb.pipes.runPipeCatching
import java.util.IdentityHashMap

/** Statement-local SQL scopes over copied Kotlin input contracts, never upstream SQL or caller unions. */
object BrikkSqlScopes {
    data class Relation(val alias: String, val columns: List<ColumnContract>, val knowledge: ShapeKnowledge,
                        val managed: Boolean, val available: Boolean, val slot: String? = null,
                        val navigation: NavigationTarget? = null, val sourceStart: Int = -1)
    data class Scope(val relations: List<Relation>, val outer: Scope? = null) {
        fun candidates(name: String, qualifier: String = ""): List<Pair<Relation, ColumnContract>> {
            val selected = if (qualifier.isBlank()) relations else relations.filter { it.alias.equals(qualifier, true) }
            val own = selected.flatMap { relation -> relation.columns.filter { it.name.equals(name, true) }.map { relation to it } }
            if (qualifier.isNotBlank() && selected.isNotEmpty() || own.isNotEmpty()) return own
            if (qualifier.isBlank() && selected.any { !it.available || it.knowledge == ShapeKnowledge.PARTIAL }) return emptyList()
            return outer?.candidates(name, qualifier).orEmpty()
        }
        fun relation(qualifier: String): Relation? = relations.firstOrNull { it.alias.equals(qualifier, true) }
            ?: outer?.relation(qualifier)
        val columns get() = relations.flatMap { relation -> relation.columns.map { relation to it } }
        val managed get() = relations.any { it.managed }
        val complete get() = relations.isNotEmpty() && relations.all { it.available && it.knowledge == ShapeKnowledge.FULL }
    }
    enum class Kind { COLUMN, QUALIFIER, SLOT }
    data class Reference(val name: String, val qualifier: String, val start: Int, val end: Int,
                         val scope: Scope, val kind: Kind = Kind.COLUMN)
    data class Analysis(val references: List<Reference>) {
        fun at(offset: Int): Reference? = references.filter { offset >= it.start && offset < it.end }
            .minByOrNull { it.end - it.start }
    }

    fun analyze(sql: String, contracts: Map<String, ShapeResult>): Analysis? = runPipeCatching {
        Analyzer(contracts).analyze(parse(sql, contracts.keys))
    }.getOrNull()

    /** Core 0.19 owns rows(). Keep the inspection-only fallback for other unproven
     * reserved slots, preserving original text and UTF-16 coordinates. */
    fun parse(sql: String, slots: Set<String>): Expression = try {
        SqlFragment(sql, "duckdb").ast
    } catch (failure: ParseError) {
        val tokens = Dialects.DUCKDB.tokenize(sql)
        var changed = false
        val adjusted = tokens.mapIndexed { index, token ->
            if (!token.text.equals("rows", true) && slots.any { it.equals(token.text, true) } &&
                tokens.getOrNull(index + 1)?.tokenType == TokenType.L_PAREN &&
                token.tokenType !in setOf(TokenType.VAR, TokenType.IDENTIFIER, TokenType.STRING) && !token.tokenType.name.endsWith("_STRING")) {
                changed = true
                Token(TokenType.VAR, token.text, token.line, token.col, token.start, token.end,
                    token.comments.toMutableList(), token.lineStart, token.colStart)
            } else token
        }
        if (!changed) throw failure
        val statements = Dialects.DUCKDB.parser().parse(adjusted, sql).filterNotNull().filterNot { it is Semicolon }
        if (statements.size != 1) throw failure
        statements.single()
    }

    private class Analyzer(private val contracts: Map<String, ShapeResult>) {
        private val references = mutableListOf<Reference>()
        private val active = java.util.Collections.newSetFromMap(IdentityHashMap<Expression, Boolean>())
        fun analyze(root: Expression): Analysis { query(root, emptyMap(), null); return Analysis(references) }
        private fun range(node: Expression): Pair<Int, Int>? = SourceMap.sourcePosOf(node)
            ?.takeIf { it.start >= 0 && it.end >= it.start }?.let { it.start to it.end + 1 }
        private fun reference(node: Expression, name: String, qualifier: String, scope: Scope, kind: Kind = Kind.COLUMN) {
            val position = range(node) ?: return
            references.add(Reference(name, qualifier, position.first, position.second, scope, kind))
        }
        private fun mark(node: Expression?, scope: Scope, ctes: Map<String, Relation>) {
            if (node == null) return
            if (node is Select || node is PipeQuery || node is Subquery) { query(node, ctes, scope); return }
            if (node is Column) {
                val id = node.thisArg as? Expression
                if (id != null) reference(id, node.name, node.table, scope)
                (node.args["table"] as? Expression)?.let { reference(it, node.table, "", scope, Kind.QUALIFIER) }
            }
            node.iterExpressions().forEach { mark(it, scope, ctes) }
        }
        private fun unknown(alias: String, managed: Boolean = false, slot: String? = null) =
            Relation(alias, emptyList(), ShapeKnowledge.PARTIAL, managed, false, slot)
        private fun nullable(relation: Relation) = relation.copy(columns = relation.columns.map { it.copy(nullability = ColumnNullability.NULLABLE) })
        private fun flatten(scope: Scope, alias: String = ""): Relation = Relation(alias, scope.columns.map { it.second },
            if (scope.complete) ShapeKnowledge.FULL else ShapeKnowledge.PARTIAL, scope.managed,
            scope.relations.all { it.available }, sourceStart = scope.relations.minOfOrNull { it.sourceStart } ?: -1)

        private fun source(node: Expression?, ctes: Map<String, Relation>, outer: Scope?): Relation {
            if (node == null) return unknown("")
            if (node is From) return source(node.thisArg as? Expression, ctes, outer)
            val alias = node.aliasOrName
            if (node is Subquery) return flatten(query(node.thisArg as? Expression ?: return unknown(alias), ctes, outer), alias)
            if (node is Table) {
                val function = node.thisArg as? Anonymous
                if (function != null && function.expressionsArg.isEmpty() && node.db.isBlank() && node.catalog.isBlank() &&
                    function.name in contracts) {
                    val result = contracts.getValue(function.name)
                    val shape = (result as? ShapeResult.Available)?.shape
                    val relation = if (shape == null) unknown(alias.ifBlank { function.name }, true, function.name)
                        else Relation(alias.ifBlank { function.name }, shape.columns, shape.knowledge, true, true, function.name, shape.navigation)
                    val located = relation.copy(sourceStart = range(function)?.first ?: -1)
                    reference(function, function.name, "", Scope(listOf(located), outer), Kind.SLOT)
                    return located
                }
                return ctes.entries.firstOrNull { it.key.equals(node.name, true) }?.value?.copy(alias = alias,
                    sourceStart = (node.thisArg as? Expression)?.let { range(it)?.first } ?: -1) ?: unknown(alias)
            }
            return unknown(alias)
        }
        private fun join(scope: Scope, node: Expression, ctes: Map<String, Relation>): Scope {
            val right = source(node.thisArg as? Expression, ctes, scope.outer)
            val side = node.text("side").uppercase()
            val left = if (side in setOf("RIGHT", "FULL")) scope.relations.map(::nullable) else scope.relations
            val newRight = if (side in setOf("LEFT", "FULL")) nullable(right) else right
            val joined = Scope(left + newRight, scope.outer)
            mark(node.args["on"] as? Expression, joined, ctes)
            return if (node.text("kind").uppercase() in setOf("SEMI", "ANTI")) scope else joined
        }
        private fun expressionColumn(node: Expression, scope: Scope): ColumnContract {
            val expression = if (node is Alias) node.thisArg as? Expression ?: node else node
            val name = node.aliasOrName
            if (expression is Column) {
                val candidates = scope.candidates(expression.name, expression.table)
                if (candidates.size == 1) return candidates.single().second.copy(name = name)
            }
            if (expression is Count) return ColumnContract(name, null, ColumnNullability.NON_NULL, SqlFamily.WIDE_INTEGER)
            return ColumnContract(name, null, ColumnNullability.UNKNOWN, SqlFamily.UNKNOWN)
        }
        private fun additionAvailable(node: Expression, scope: Scope): Boolean {
            val expression = if (node is Alias) node.thisArg as? Expression ?: node else node
            return expression !is Column || scope.candidates(expression.name, expression.table)
                .singleOrNull()?.first?.available == true
        }
        private fun project(expressions: List<Expression>, scope: Scope, ctes: Map<String, Relation>, extend: Boolean = false): Scope {
            val result = mutableListOf<ColumnContract>()
            var open = false
            if (extend) {
                expressions.forEach { mark(it, scope, ctes) }
                val additions = expressions.map { expressionColumn(it, scope) to additionAvailable(it, scope) }
                    .filter { it.first.name.isNotBlank() }
                val retained = scope.relations.map { relation -> relation.copy(columns = relation.columns.filterNot { existing ->
                    additions.any { it.first.name.equals(existing.name, true) }
                }) }
                val added = additions.groupBy { it.second }.map { (available, columns) ->
                    Relation("", columns.map { it.first }, ShapeKnowledge.FULL, scope.managed, available)
                }
                return Scope(retained + added, scope.outer)
            }
            for (expression in expressions) {
                mark(expression, scope, ctes)
                val star = expression is Star || expression is Column && expression.thisArg is Star
                if (star) {
                    val selected = if (expression is Column && expression.table.isNotBlank())
                        listOfNotNull(scope.relation(expression.table)) else scope.relations
                    result.addAll(selected.flatMap { it.columns })
                    open = open || selected.isEmpty() || selected.any { it.knowledge == ShapeKnowledge.PARTIAL || !it.available }
                } else {
                    val column = expressionColumn(expression, scope)
                    if (column.name.isNotBlank()) {
                        result.add(column)
                    } else open = true
                }
            }
            // SELECT * preserves namespaces. Explicit projection creates a new row contract.
            if (!extend && expressions.size == 1 && expressions.single() is Star) return scope
            return Scope(listOf(Relation("", result, if (open) ShapeKnowledge.PARTIAL else ShapeKnowledge.FULL,
                scope.managed, scope.relations.all { it.available })), scope.outer)
        }

        private fun query(node: Expression, inheritedCtes: Map<String, Relation>, outer: Scope?): Scope {
            if (!active.add(node)) return Scope(emptyList(), outer)
            try {
                if (node is Subquery) return query(node.thisArg as? Expression ?: return Scope(emptyList()), inheritedCtes, outer)
                val ctes = inheritedCtes.toMutableMap()
                val with = node.args["with_"] as? Expression
                with?.expressionsArg?.filterIsInstance<Expression>()?.forEach { cte ->
                    val body = cte.thisArg as? Expression ?: return@forEach
                    ctes[cte.alias] = flatten(query(body, ctes, null), cte.alias)
                }
                if (node is PipeQuery) {
                    var scope = query(node.thisArg as Expression, ctes, outer)
                    for (stage in node.expressionsArg.filterIsInstance<Expression>()) {
                        when (stage.key) {
                            "pipeselect" -> scope = project(stage.expressionsArg.filterIsInstance<Expression>(), scope, ctes)
                            "pipeextend" -> scope = project(stage.expressionsArg.filterIsInstance<Expression>(), scope, ctes, extend = true)
                            "pipeaggregate" -> {
                                val group = (stage.args["group"] as? List<*>)?.filterIsInstance<Expression>().orEmpty()
                                scope = project(stage.expressionsArg.filterIsInstance<Expression>() + group, scope, ctes)
                            }
                            "pipejoin" -> scope = join(scope, stage.thisArg as Expression, ctes)
                            "pipeas" -> scope = Scope(listOf(flatten(scope, stage.alias)), outer)
                            "pipewhere", "pipeorderby", "pipelimit", "pipeoffset", "pipedistinct" -> mark(stage, scope, ctes)
                            else -> { mark(stage, scope, ctes); scope = Scope(listOf(unknown("", scope.managed)), outer) }
                        }
                    }
                    return scope
                }
                if (node !is Select) return Scope(emptyList(), outer)
                var input = Scope(node.args["from_"]?.let { listOf(source(it as Expression, ctes, outer)) }.orEmpty(), outer)
                (node.args["joins"] as? List<*>)?.filterIsInstance<Expression>()?.forEach { input = join(input, it, ctes) }
                val expressions = node.expressionsArg.filterIsInstance<Expression>()
                val output = project(expressions, input, ctes)
                for (key in listOf("where", "group", "having", "qualify")) mark(node.args[key] as? Expression, input, ctes)
                // ORDER BY may reference projected names; input namespaces remain visible too.
                val projectedNames = output.columns.filter { pair -> input.candidates(pair.second.name).isEmpty() }.map { it.second }
                val orderScope = if (projectedNames.isEmpty()) input else Scope(input.relations + Relation("", projectedNames, ShapeKnowledge.FULL, input.managed, true), outer)
                mark(node.args["order"] as? Expression, orderScope, ctes)
                return output
            } finally { active.remove(node) }
        }
    }
}
