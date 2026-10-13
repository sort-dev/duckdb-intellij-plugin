package dev.sort.duckdb.brikk

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.model.ObjectKind
import com.intellij.database.symbols.DasColumnSymbol
import com.intellij.database.util.DasUtil
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.database.SqlDataSourceManager
import com.intellij.database.model.DasColumn
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlNameElement
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.ExtensionTestUtil
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.intellij.relation.*
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.generator.UnsupportedError
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbSqlDialect
import dev.sort.duckdb.pipes.DuckdbPipesSettings
import dev.sort.duckdb.syncAndWait
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkRelationCorpusTest : BasePlatformTestCase() {
    private val facts by lazy { resource("facts.json").let(Json::parseToJsonElement).jsonObject }
    private val columns get() = facts.getValue("columns").jsonObject
    private val shapes get() = facts.getValue("facts").jsonObject
    private val cases by lazy { resource("cases.jsonl").lineSequence().filter(String::isNotBlank)
        .map { Json.parseToJsonElement(it).jsonObject }.toList() }
    private val witnesses by lazy { resource("witnesses.json").let(Json::parseToJsonElement).jsonObject }
    private lateinit var errors: BackgroundErrors
    private var relationFacts = RelationFacts()
    private var logicalAnalysis: RelationScope? = null
    private var nativeSql = false
    private val declarations = LinkedHashMap<String, PsiElement>()
    private var physicalColumns = emptySet<String>()
    private var physicalFacts = emptyMap<Pair<String, String>, JsonObject>()
    private var physicalTargets = emptyMap<String, PsiElement>()
    private var previousPipes = false
    private var previousAutocomplete = false
    private val brikkLabels = RelationColumnLabels(declared = "brikk column",
        declaredKind = dev.brikk.house.sql.intellij.completion.SqlLookupKind.BRIKK_COLUMN,
        contractKind = dev.brikk.house.sql.intellij.completion.SqlLookupKind.BRIKK_COLUMN)

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        previousAutocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        previousPipes = project.service<DuckdbPipesSettings>().enabled
        project.service<DuckdbPipesSettings>().enabled = true
        setSqlDialectMapping(project, null, DuckdbSqlDialect, testRootDisposable)
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(object : MultiHostInjector {
            override fun elementsToInjectIn() = listOf(KtStringTemplateExpression::class.java)
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                if (!context.containingFile.name.startsWith("RelationCase-")) return
                val host = context as KtStringTemplateExpression
                registrar.startInjecting(DuckdbSqlDialect)
                    .addPlace(null, null, host, TextRange(3, host.textLength - 3)).doneInjecting()
            }
        }, testRootDisposable)
        ExtensionTestUtil.maskExtensions(DuckdbRelationProfile.FACTS, listOf(ExternalRelationFacts.of(
            labels = brikkLabels,
            navigationTarget = { _, id -> columns[id]?.jsonObject?.stringOrNull("navigationId")?.let(declarations::get) },
        ) { RelationFactsResult.Available(relationFacts.copy(tables = emptyMap(), slots = presentationFacts(relationFacts.slots),
            calls = presentationFacts(relationFacts.calls)), relationFacts.hashCode().toLong()) },
            ExternalRelationFacts.of(platformResolves = true, navigationTarget = { _, id -> physicalTargets[id] }) {
                // The native corpus uses real SQL-file data sources. Their physical facts are supplied to the
                // shared layer explicitly; DasRelationFacts' LocalDataSource path has its own consumer test.
                RelationFactsResult.Available(relationFacts.copy(slots = emptyMap(), calls = emptyMap()), relationFacts.hashCode().toLong())
            }), testRootDisposable)
        val ids = columns.values.filter { it.jsonObject.string("origin") != "PHYSICAL_TABLE" }
            .mapNotNull { it.jsonObject.stringOrNull("navigationId") }.distinct()
        val navigationFile = myFixture.addFileToProject("corpus/Declarations.kt", ids.joinToString("\n") {
            "val ${declarationName(it)} = 0"
        })
        val properties = PsiTreeUtil.findChildrenOfType(navigationFile, KtProperty::class.java).associateBy { it.name }
        ids.forEach { id ->
            val declaration = properties.getValue(declarationName(id))
            declarations[id] = declaration
        }
    }

    override fun tearDown() = RunAll(
        { LookupManager.getInstance(project).hideActiveLookup() },
        { CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = previousAutocomplete },
        { project.service<DuckdbPipesSettings>().enabled = previousPipes },
        { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() },
        { errors.assertNone() },
        { super.tearDown() },
    ).run()

    fun testCurrentRelationImplementationsAgainstAcceptanceCorpus() {
        checkCorpus()
    }

    fun testNativeSqlAgainstAcceptanceCorpus() {
        nativeSql = true
        checkCorpus()
    }

    private fun checkCorpus() {
        assertEquals("2.2", facts.string("version"))
        assertEquals(cases.size, cases.map { it.string("id") }.toSet().size)
        val usedWitnesses = cases.flatMap { case ->
            assertTrue(case.string("id"), case.string("catalogKnowledge") in setOf("CLOSED", "UNKNOWN"))
            case.getValue("engine").jsonObject.mapNotNull { (dialect, witness) ->
                if (witness == JsonNull) null else {
                    assertTrue(case.string("id"), JsonPrimitive(dialect) in case.array("dialects"))
                    val key = witness.jsonPrimitive.content
                    val oracle = witnesses.getValue(key).jsonObject
                    assertFalse(key, "error" in oracle || "expectFailure" in oracle)
                    assertEquals(key, dialect == "duckdb", oracle.getValue("verified").jsonPrimitive.boolean)
                    key
                }
            }
        }.toSet()
        assertEquals("No orphan native witnesses", usedWitnesses, witnesses.keys)
        val baseline = Json.parseToJsonElement(resource("duckdb-disagreements.json")).jsonObject
        baseline["sourceHashes"]?.jsonObject?.forEach { (name, checksum) ->
            assertEquals("Corpus expectations changed: $name", checksum.jsonPrimitive.content,
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(resource(name).toByteArray(Charsets.UTF_8))))
        }
        val lane = if (nativeSql) "native" else "brikk"
        val laneBaseline = baseline["lanes"]?.jsonObject?.get(lane)?.jsonObject ?: baseline
        val shared = baseline.getValue("shared").jsonObject
        val known = shared
        assertTrue(known.keys.all { id -> cases.any { it.string("id") == id } })
        baseline["reasons"]?.jsonObject?.let { reasons ->
            for ((id, axes) in known) for (axis in axes.jsonArray.map { it.jsonPrimitive.content }) {
                val reason = reasons.getValue(id).jsonObject.getValue(axis).jsonObject
                assertTrue("$id/$axis needs a classified reason", reason.string("classification") in setOf("b", "c") && reason.string("reason").isNotBlank())
                assertEquals("$lane/$id/$axis needs an observation fingerprint", 64,
                    laneBaseline.getValue("fingerprints").jsonObject.getValue(id).jsonObject.string(axis).length)
            }
        }
        val differences = ArrayList<JsonObject>()
        val results = ArrayList<JsonObject>()
        val unexpected = ArrayList<String>()
        val resolved = ArrayList<String>()
        val stageCoverage = cases.flatMap { it.array("stages").map { stage -> stage.jsonPrimitive.content } }.toSet()
        assertTrue(stageCoverage.toString(), stageCoverage.containsAll(setOf("SELECT", "EXTEND", "SET", "DROP", "RENAME", "AS",
            "WHERE", "AGGREGATE", "ORDER BY", "LIMIT", "OFFSET", "DISTINCT", "PIVOT", "UNPIVOT", "TABLESAMPLE", "WINDOW", "CALL",
            "INNER JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "CROSS JOIN", "SEMI JOIN", "ANTI JOIN", "RIGHT SEMI JOIN", "RIGHT ANTI JOIN",
            "UNION ALL", "UNION DISTINCT", "INTERSECT", "EXCEPT")))
        val applicable = cases.filter { "duckdb" in it.array("dialects").map { dialect -> dialect.jsonPrimitive.content } }
        for (case in applicable) {
            val id = case.string("id")
            val sql = case.string("sql")
            relationFacts = RelationFacts(
                slots = case.getValue("relations").jsonObject.mapValues { fact(it.value.jsonPrimitive.content) },
                calls = case.getValue("callContracts").jsonObject.mapValues { fact(it.value.jsonPrimitive.content) },
                tables = case.getValue("physicalTables").jsonObject.mapValues { fact(it.value.jsonPrimitive.content) },
                catalog = if (case.string("catalogKnowledge") == "CLOSED") CatalogKnowledge.CLOSED else CatalogKnowledge.OPEN,
            )
            val expected = expected(case)
            val actual = withPhysicalTables(case) {
                val file = configure(id, sql)
                val analysis = RelationScope.analyze(sql, "duckdb", relationFacts)
                logicalAnalysis = analysis
                val references = case.array("references").map { it.jsonObject }
                val observed = linkedMapOf<String, JsonElement>(
                    "references" to JsonArray(references.map { reference ->
                        val start = reference.int("start")
                        val scoped = analysis.referenceAt(start)
                        reference(reference, targets(scoped?.columns.orEmpty()))
                    }),
                    "resolve" to JsonArray(references.map { reference -> reference(reference, nativeTargets(file, reference.int("start"))) }),
                    "navigation" to JsonArray(references.map { reference -> navigationReference(reference, navigation(file, reference.int("start"))) }),
                    "diagnostics" to diagnostics(file, case),
                    "resolveEvidence" to JsonArray(references.map { reference -> resolveEvidence(file, reference.int("start")) }),
                )
                val scopes = ArrayList<JsonObject>()
                val completions = ArrayList<JsonObject>()
                val presentations = ArrayList<JsonObject>()
                val orders = ArrayList<JsonObject>()
                for (caret in case.array("carets").map { it.jsonObject }) {
                    val offset = caret.int("offset")
                    assertTrue("$id caret", offset in 0..sql.length)
                    val scoped = RelationScope.complete(sql, "duckdb", relationFacts, offset)
                    val selected = scoped.items.mapNotNull { it.column }
                    scopes += buildJsonObject {
                        put("offset", offset)
                        put("knowledge", scoped.knowledge?.name ?: "NONE")
                        put("columns", sorted(selected.map(::observedColumn)))
                    }
                    if (caret.string("order") == "SIGNIFICANT") orders += buildJsonObject {
                        put("offset", offset); put("columns", strings(selected.map { it.name }))
                    }
                    configure(id, sql.substring(0, offset) + "<caret>" + sql.substring(offset))
                    val items = myFixture.completeBasic().orEmpty()
                    completions += buildJsonObject {
                        put("offset", offset)
                        put("columns", sorted(items.mapNotNull { completion(it, selected) }))
                    }
                    presentations += buildJsonObject {
                        put("offset", offset)
                        put("items", JsonArray(items.mapNotNull { item ->
                            val shown = LookupElementPresentation().also(item::renderElement)
                            if (!isColumn(item, shown)) null else buildJsonObject {
                                put("name", item.lookupString); put("type", nullable(shown.typeText)); put("tail", nullable(shown.tailText))
                            }
                        }.sortedBy(JsonElement::toString)))
                    }
                    LookupManager.getInstance(project).hideActiveLookup()
                }
                observed["scope"] = JsonArray(scopes)
                observed["completion"] = JsonArray(completions)
                // Raw presentation is evidence, not the semantic alias/origin/identity projection.
                observed["presentationEvidence"] = JsonArray(presentations)
                if (orders.isNotEmpty()) observed["order"] = JsonArray(orders)
                case["coreOrder"]?.let { observed["coreOrder"] = coreOrder(case) }
                case["coreExecution"]?.let { observed["coreExecution"] = coreExecution(case) }
                case["declarationRanges"]?.let { observed["declarationRanges"] = declarationRanges(file, it.jsonArray) }
                case["coreRefusal"]?.let {
                    observed["coreRefusal"] = JsonPrimitive(try {
                        SqlFragment(sql, "duckdb").toExecutable("duckdb")
                        false
                    } catch (_: UnsupportedError) {
                        true
                    })
                }
                observed
            }
            val allowed = known[id]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            assertTrue(id, allowed.all { it in expected.keys })
            val axes = ArrayList<JsonObject>()
            for ((axis, wanted) in expected) {
                val got = actual.getValue(axis)
                val equal = if (axis in setOf("order", "coreOrder", "coreExecution", "declarationRanges", "coreRefusal")) wanted == got else matches(wanted, got)
                axes += buildJsonObject { put("axis", axis); put("expected", wanted); put("actual", got); put("matches", equal); put("fingerprint", fingerprint(got)) }
                if (equal) {
                    if (axis in allowed) resolved += "$id/$axis"
                } else {
                    differences += buildJsonObject {
                        put("id", id); put("category", case.string("category")); put("axis", axis); put("input", sql)
                        put("expected", wanted); put("actual", got); put("known", axis in allowed)
                        put("judgement", facts.getValue("evidence").jsonObject.getValue(case.string("category")))
                        baseline["reasons"]?.jsonObject?.get(id)?.jsonObject?.get(axis)?.let { put("triage", it) }
                    }
                    if (axis !in allowed) unexpected += "$id/$axis"
                    else laneBaseline["fingerprints"]?.jsonObject?.get(id)?.jsonObject?.get(axis)?.let { previous ->
                        assertEquals("Changed known difference $lane/$id/$axis must be triaged", previous.jsonPrimitive.content, fingerprint(got))
                    }
                }
            }
            results += buildJsonObject {
                put("id", id); put("input", sql); put("axes", JsonArray(axes))
                put("presentationEvidence", actual.getValue("presentationEvidence"))
                put("resolveEvidence", actual.getValue("resolveEvidence"))
                put("changes", case.getValue("changes"))
                put("serializableResults", serializableResults(case, axes))
                put("dialects", case.getValue("dialects")); put("positions", JsonArray(case.array("carets").map { caret ->
                    buildJsonObject { put("offset", caret.jsonObject.int("offset")); put("kind", caret.jsonObject.string("positionKind")) }
                }))
                put("catalogKnowledge", case.getValue("catalogKnowledge"))
                case.getValue("engine").jsonObject["duckdb"]?.takeUnless { it == JsonNull }?.let { witness ->
                    put("engine", engine(witnesses.getValue(witness.jsonPrimitive.content).jsonObject, id))
                }
            }
            errors.assertNone()
        }
        val report = buildJsonObject {
            put("corpusSize", cases.size); put("disagreementCount", differences.size)
            put("lane", lane)
            put("logicalPassingCases", applicable.count { case ->
                results.first { it.string("id") == case.string("id") }.array("axes").all { axis ->
                    val entry = axis.jsonObject
                    entry.string("axis") !in setOf("references", "scope", "order", "coreOrder", "coreExecution", "coreRefusal") || entry.getValue("matches").jsonPrimitive.boolean
                }
            })
            put("applicableCases", applicable.size)
            put("notApplicable", strings((cases - applicable.toSet()).map { it.string("id") }))
            put("engineWitnessCount", applicable.count { it.getValue("engine").jsonObject["duckdb"] != JsonNull })
            put("disagreeingCases", differences.map { it.string("id") }.toSet().size)
            put("resolvedKnownDisagreements", strings(resolved))
            put("prunedV1Flags", baseline.getValue("prunedV1Flags"))
            put("baselineNotes", baseline.getValue("notes"))
            put("stageCoverage", strings(stageCoverage.sorted()))
            put("representationNotes", facts.getValue("representationNotes"))
            put("dialectNotes", facts.getValue("dialectNotes"))
            put("stageAliasPolicy", facts.getValue("stageAliasPolicy"))
            put("resultContract", facts.getValue("resultContract"))
            put("inputReviews", facts.getValue("inputReviews"))
            put("caseChanges", JsonObject(cases.associate { it.string("id") to it.getValue("changes") }))
            put("adapter", "Post-replacement SQL in a genuine DuckDB injection into a raw Kotlin string. Scope and references use core RelationScope with the corpus's facts. Registered shared features use the same facts with simple Kotlin display types. Completion observes real lookup items and matches their actual labels/types to core candidates; raw presentation remains in presentationEvidence. Resolve uses native SQL PSI only, with no analyzer substitution for flat PIPE PSI. Navigation uses the registered shared handler and native fallback. Diagnostics include the shared annotator and separately observed platform inspections. Canonical SQL types, computed identity and unspecified computed SQL types follow core's corpus comparison contract.")
            put("categories", JsonObject(differences.groupBy { it.string("category") }.mapValues { JsonArray(it.value) }))
            put("categorySummary", JsonObject(applicable.groupBy { it.string("category") }.mapValues { (category, entries) ->
                val mismatches = differences.filter { it.string("category") == category }
                buildJsonObject {
                    put("cases", entries.size); put("disagreements", mismatches.size)
                    put("disagreeingCases", mismatches.map { it.string("id") }.toSet().size)
                }
            }))
            put("results", JsonArray(results))
        }
        val output = Path.of("build", "relation-corpus", "duckdb-$lane-${ApplicationInfo.getInstance().build.baselineVersion}.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report))
        if (!nativeSql) Files.writeString(output.resolveSibling("duckdb-${ApplicationInfo.getInstance().build.baselineVersion}.json"),
            Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report))
        println("DuckDB relation corpus v2: ${cases.size} cases, ${applicable.size} applicable, ${differences.size} disagreements in ${differences.map { it.string("id") }.toSet().size} cases, ${resolved.size} resolved; $output")
        assertTrue("Unlisted relation disagreements: ${unexpected.joinToString()}; see $output", unexpected.isEmpty())
    }

    private fun fingerprint(value: JsonElement) = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(Charsets.UTF_8)))

    private fun resource(name: String) = Files.readString(Path.of(System.getProperty("corpus.dir"))
        .resolveSibling("relation-corpus").resolve(name))
    private fun declarationName(id: String) = id.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun serializableResults(case: JsonObject, axes: List<JsonObject>): JsonObject {
        val sql = case.string("sql")
        fun range(start: Int, end: Int) = buildJsonObject { put("start", start); put("end", end) }
        fun completionItems(value: JsonElement): JsonArray = JsonArray(value.jsonArray.flatMap { row ->
            val caret = row.jsonObject.int("offset")
            val prefix = sql.substring(0, caret).takeLastWhile { Character.isUnicodeIdentifierPart(it) }
            row.jsonObject.array("columns").map { column ->
                val fields = column.jsonObject
                buildJsonObject {
                    put("name", fields.getValue("name")); put("kind", "COLUMN")
                    put("insertText", fields.getValue("name")); put("range", range(caret - prefix.length, caret))
                    put("detail", "${fields.string("origin")} ${fields.string("relation")} ${fields.stringOrNull("kotlinType") ?: fields.stringOrNull("sqlType") ?: fields.stringOrNull("type").orEmpty()}".trim())
                }
            }
        })
        fun diagnosticItems(value: JsonElement) = JsonArray(value.jsonArray.map { item ->
            val diagnostic = item.jsonObject
            val start = diagnostic.int("start"); val end = diagnostic.int("end")
            buildJsonObject {
                put("kind", diagnostic.getValue("kind")); put("range", range(start, end))
                put("severity", if (diagnostic.string("kind") == "PARTIAL_CONTRACT") "WARNING" else "ERROR")
                put("message", diagnostic.string("kind").lowercase().replace('_', ' ') + ": " + sql.substring(start, end))
            }
        })
        return buildJsonObject {
            for (axis in axes.filter { it.string("axis") in setOf("completion", "diagnostics", "references") }) {
                put(axis.string("axis"), buildJsonObject {
                    for (side in listOf("expected", "actual")) {
                        val value = axis.getValue(side)
                        put(side, when (axis.string("axis")) {
                            "completion" -> completionItems(value)
                            "diagnostics" -> diagnosticItems(value)
                            else -> JsonArray(value.jsonArray.map { item ->
                                val reference = item.jsonObject
                                val start = reference.int("start"); val end = reference.int("end")
                                val probe = case.array("references").map { it.jsonObject }.single { it.int("start") == start && it.int("end") == end }
                                buildJsonObject {
                                    put("name", sql.substring(start, end)); put("kind", probe.getValue("kind"))
                                    put("range", range(start, end)); put("targets", reference.getValue("targets"))
                                }
                            })
                        })
                    }
                })
            }
        }
    }

    private fun fact(id: String): RelationFact {
        val shape = shapes.getValue(id).jsonObject
        val copied = shape.array("columns").map { key ->
            val column = columns.getValue(key.jsonPrimitive.content).jsonObject
            ColumnFact(column.string("name"), column.stringOrNull("sqlType"), column.stringOrNull("kotlinType"),
                Nullability.valueOf(column.string("nullability")), when (column.string("origin")) {
                    "KOTLIN_RELATION" -> ColumnOrigin.DECLARED
                    "RELATION_CONTRACT" -> ColumnOrigin.RELATION_CONTRACT
                    "PHYSICAL_TABLE" -> ColumnOrigin.PHYSICAL_TABLE
                    else -> ColumnOrigin.COMPUTED
                }, key.jsonPrimitive.content)
        }
        return RelationFact(copied, Knowledge.valueOf(shape.string("knowledge")))
    }

    private fun presentationFacts(input: Map<String, RelationFact>) = input.mapValues { (_, fact) ->
        fact.copy(columns = fact.columns.map { it.copy(displayType = it.displayType?.substringAfterLast('.')) })
    }

    private fun configure(id: String, sql: String): PsiFile {
        if (nativeSql) {
            val file = myFixture.configureByText("NativeRelationCase-$id.sql", sql)
            assertEquals(DuckdbSqlDialect, file.language)
            return file
        }
        val hostFile = myFixture.configureByText("RelationCase-$id.kt", "val sql = \"\"\"$sql\"\"\"")
        val manager = InjectedLanguageManager.getInstance(project)
        val host = requireNotNull(PsiTreeUtil.findChildOfType(manager.getTopLevelFile(hostFile), KtStringTemplateExpression::class.java))
        val file = requireNotNull(manager.getInjectedPsiFiles(host)?.single()).first.containingFile
        file.putUserData(BrikkDuckdbProfile.KEY, BrikkDuckdbProfile(listOf("min_id"), relationFacts.slots.keys.toList(), emptyList()))
        assertEquals(id, sql.replace("<caret>", ""), file.text)
        return file
    }

    private fun <T> withPhysicalTables(case: JsonObject, action: () -> T): T {
        val tables = case.getValue("physicalTables").jsonObject
        physicalColumns = tables.values.flatMap { fact -> shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns") }
            .map { columns.getValue(it.jsonPrimitive.content).jsonObject.string("name") }.toSet()
        physicalFacts = tables.entries.flatMap { (table, fact) ->
            shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns").map { key ->
                val column = columns.getValue(key.jsonPrimitive.content).jsonObject
                (table to column.string("name")) to column
            }
        }.toMap()
        if (tables.isEmpty()) return action()
        physicalTargets = emptyMap()
        val schemas = tables.keys.filter { '.' in it }.map { it.substringBeforeLast('.') }.distinct()
        val ddl = schemas.joinToString("\n") { "CREATE SCHEMA $it;" } + "\n" + tables.entries.joinToString("\n") { (name, fact) ->
            val fields = shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns").joinToString(", ") { key ->
                val column = columns.getValue(key.jsonPrimitive.content).jsonObject
                "${column.string("name")} ${column.string("sqlType")}" + if (column.string("nullability") == "NON_NULL") " NOT NULL" else ""
            }
            "CREATE TABLE $name ($fields);"
        }
        val schema = myFixture.addFileToProject("corpus/${case.string("id")}-schema.sql", ddl)
        setSqlDialectMapping(project, schema.virtualFile, DuckdbSqlDialect, testRootDisposable)
        val manager = SqlDataSourceManager.getInstance(project)
        val source = manager.createEmpty().apply {
            name = "Relation acceptance ${case.string("id")}"; definedDbms = DuckdbDbms.DUCKDB_BRIKK
            setFiles(arrayOf(schema.virtualFile))
        }
        manager.addDataSource(source)
        try {
            source.syncAndWait()
            DbPsiFacade.getInstance(project).clearCaches()
            val db = requireNotNull(DbPsiFacade.getInstance(project).findDataSource(source.uniqueId))
            physicalTargets = DasUtil.getTables(db).flatMap { table -> DasUtil.getColumns(table).mapNotNull { column ->
                val fact = physicalFact(column)
                val key = columns.entries.singleOrNull { it.value.jsonObject == fact }?.key
                key?.let { db.findElement(column)?.let { target -> key to target } }
            } }.toMap()
            SqlResolveMappings.getInstance(project).setMapping(null, SqlResolveMappings.ALL_DATA_SOURCES)
            return action()
        } finally {
            SqlResolveMappings.getInstance(project).setMapping(null, null)
            manager.removeDataSource(source)
            DbPsiFacade.getInstance(project).clearCaches()
        }
    }

    private fun expected(case: JsonObject): Map<String, JsonElement> {
        val references = case.array("references")
        val carets = case.array("carets").map { it.jsonObject }
        val resolved = JsonArray(references.map { value ->
            val reference = value.jsonObject
            reference(reference, reference.array("targets").map { value ->
                val column = expectedColumn(value)
                target(column.string("relation"), column.string("name"), column.string("identity"), column.stringOrNull("navigationId"))
            })
        })
        return linkedMapOf<String, JsonElement>(
            "references" to resolved,
            "resolve" to resolved,
            "navigation" to JsonArray(references.map { ref ->
                val reference = ref.jsonObject
                navigationReference(reference, reference["navigationTargets"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: reference.array("targets").mapNotNull { expectedColumn(it).stringOrNull("navigationId") })
            }),
            "diagnostics" to sorted(case.array("diagnostics")),
            "scope" to JsonArray(carets.map { caret -> buildJsonObject {
                put("offset", caret.int("offset")); put("knowledge", caret.getValue("knowledge"))
                put("columns", sorted(caret.array("columns").map(::expectedColumn)))
            } }),
            "completion" to JsonArray(carets.map { caret -> buildJsonObject {
                put("offset", caret.int("offset"))
                put("columns", sorted((caret["completionColumns"] ?: caret.getValue("columns")).jsonArray.map(::expectedColumn)))
            } }),
        ).apply {
            val ordered = carets.filter { it.string("order") == "SIGNIFICANT" }
            if (ordered.isNotEmpty()) put("order", JsonArray(ordered.map { caret -> buildJsonObject {
                put("offset", caret.int("offset")); put("columns", strings(caret.array("columns").map { expectedColumn(it).string("name") }))
            } }))
            case["coreOrder"]?.let { put("coreOrder", it) }
            case["coreExecution"]?.let { put("coreExecution", it) }
            case["declarationRanges"]?.let { put("declarationRanges", it) }
            case["coreRefusal"]?.let { put("coreRefusal", it) }
        }
    }

    private fun expectedColumn(value: JsonElement): JsonObject {
        val tuple = value.jsonArray
        val column = columns.getValue(tuple[0].jsonPrimitive.content).jsonObject
        fun override(index: Int, key: String) = tuple.getOrNull(index)?.takeUnless { it == JsonNull } ?: column.getValue(key)
        return buildJsonObject {
            put("name", override(2, "name")); put("relation", tuple[1]); put("origin", override(4, "origin"))
            val computed = override(4, "origin") == JsonPrimitive("COMPUTED")
            put("kotlinType", column.getValue("kotlinType"))
            put("sqlType", if (computed && column.getValue("sqlType") == JsonNull) JsonPrimitive("*") else type(column.stringOrNull("sqlType")))
            put("nullability", override(3, "nullability")); put("navigationId", override(5, "navigationId"))
            put("identity", if (computed) JsonPrimitive("computed") else override(6, "identity"))
        }
    }

    private fun observedColumn(column: ScopeColumn) = buildJsonObject {
        val source = column.source?.column?.provenance?.let { columns[it]?.jsonObject }
        put("name", column.name); put("relation", column.relation)
        put("origin", when (column.origin) {
            ColumnOrigin.DECLARED -> "KOTLIN_RELATION"
            ColumnOrigin.RELATION_CONTRACT -> "RELATION_CONTRACT"
            ColumnOrigin.PHYSICAL_TABLE -> "PHYSICAL_TABLE"
            ColumnOrigin.COMPUTED -> "COMPUTED"
        })
        put("kotlinType", nullable(column.displayType)); put("sqlType", type(column.sqlType))
        put("nullability", column.nullability.name); put("navigationId", nullable(source?.stringOrNull("navigationId")))
        put("identity", source?.string("identity") ?: "computed")
    }

    private fun targets(candidates: List<ScopeColumn>): List<JsonObject> =
        candidates.singleOrNull()?.let { column ->
            val source = column.source?.column?.provenance?.let { columns[it]?.jsonObject }
            listOf(target(column.relation, column.name, source?.string("identity") ?: "computed.${column.name}", source?.stringOrNull("navigationId")))
        }.orEmpty()

    private fun target(relation: String, column: String, identity: String, navigation: String?) = buildJsonObject {
        put("relation", relation); put("column", column); put("identity", if (identity.startsWith("computed.")) "computed" else identity); put("navigationId", nullable(navigation))
    }

    private fun coreOrder(case: JsonObject): JsonElement {
        val inputs = case.getValue("relations").jsonObject.mapValues { (_, fact) ->
            Shape(shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns").map { value ->
                val column = columns.getValue(value.jsonPrimitive.content).jsonObject
                ColumnShape(column.string("name"), column.stringOrNull("sqlType") ?: "UNKNOWN", when (column.string("nullability")) {
                    "NON_NULL" -> false; "NULLABLE" -> true; else -> null
                })
            })
        }
        return strings(SqlFragment(case.string("sql"), "duckdb").outputShape(ShapeCatalog(emptyMap(), inputs)).names())
    }

    private fun coreExecution(case: JsonObject): JsonObject {
        val input = case.getValue("engineRelations").jsonObject.entries.fold(case.string("sql")) { text, (slot, table) ->
            text.replace(Regex("\\b${Regex.escape(slot)}\\s*\\(\\s*\\)"), table.jsonPrimitive.content)
        }
        val executable = try {
            SqlFragment(input, "duckdb").toExecutable("duckdb").sql
        } catch (_: UnsupportedError) {
            return buildJsonObject { put("failure", "LOWERING_REFUSED") }
        }
        return DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            facts.getValue("engineSetup").jsonObject.getValue("duckdb").jsonArray.forEach { setup ->
                connection.createStatement().use { it.execute(setup.jsonPrimitive.content) }
            }
            try {
                connection.createStatement().use { statement -> statement.executeQuery(executable).use { rows ->
                    val data = ArrayList<JsonArray>()
                    while (rows.next()) data += JsonArray((1..rows.metaData.columnCount).map { index ->
                        when (val value = rows.getObject(index)) {
                            null -> JsonNull
                            is Number -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            else -> JsonPrimitive(value.toString())
                        }
                    })
                    buildJsonObject {
                        put("rows", sorted(data))
                        put("columns", strings((1..rows.metaData.columnCount).map(rows.metaData::getColumnLabel)))
                    }
                } }
            } catch (failure: SQLException) {
                println("${case.string("id")} executable SQL rejected: $executable\n${failure.message}")
                buildJsonObject { put("failure", "SQL_REJECTION") }
            }
        }
    }

    private fun idOf(element: PsiElement?): String? {
        val column = (element as? DasColumnSymbol)?.dasObject ?: (element as? com.intellij.database.psi.DbElement)?.delegate as? DasColumn
        if (column != null) return physicalFact(column)?.stringOrNull("navigationId")
        return declarations.entries.firstOrNull { (_, declaration) ->
            element != null && element.containingFile === declaration.containingFile && declaration.textRange.contains(element.textRange)
        }?.key
    }

    private fun physicalFact(column: DasColumn): JsonObject? {
        val table = listOfNotNull(DasUtil.getCatalog(column), DasUtil.getSchema(column), column.table?.name).filter(String::isNotBlank).joinToString(".")
        return physicalFacts.filter { (key, _) -> key.second == column.name && (table == key.first || table.endsWith(".${key.first}")) }.values.singleOrNull()
    }

    private fun reference(expected: JsonObject, targets: List<JsonObject>) = buildJsonObject {
        put("start", expected.int("start")); put("end", expected.int("end")); put("targets", sorted(targets))
    }

    private fun navigationReference(expected: JsonObject, targets: List<String>) = buildJsonObject {
        put("start", expected.int("start")); put("end", expected.int("end")); put("targets", strings(targets.sorted()))
    }

    private fun nativeReference(file: PsiFile, start: Int): SqlReferenceExpression? =
        PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java)
            .filter { it.identifier?.textRange?.startOffset == start }.minByOrNull { it.textLength }

    private fun declarationRanges(file: PsiFile, probes: JsonArray) = JsonArray(probes.map { probe ->
        val reference = probe.jsonObject
        val resolved = nativeReference(file, reference.int("start"))?.reference?.resolve()
        val declaration = (resolved as? PsiNameIdentifierOwner)?.nameIdentifier ?: resolved
        buildJsonObject {
            put("start", reference.int("start")); put("end", reference.int("end"))
            put("targets", JsonArray(listOfNotNull(declaration?.takeIf { it.containingFile === file }?.let {
                buildJsonObject { put("start", it.textRange.startOffset); put("end", it.textRange.endOffset) }
            })))
        }
    })

    private fun nativeTargets(file: PsiFile, start: Int): List<JsonObject> {
        val reference = nativeReference(file, start) ?: return emptyList()
        // An ambiguous polyvariant result is not the first candidate the platform's resolve() picks.
        val variants = (reference.reference as? com.intellij.psi.PsiPolyVariantReference)?.multiResolve(false)
        val distinct = variants?.mapNotNull { it.element }?.fold(mutableListOf<PsiElement>()) { targets, element ->
            if (targets.none { it == element || file.manager.areElementsEquivalent(it, element) }) targets += element
            targets
        }
        if (distinct != null && distinct.size != 1) return emptyList()
        val resolved = reference.reference.resolve() ?: return emptyList()
        val result = reference.resolveSingle() as? SqlResolveResult
        val qualifier = (reference.qualifierExpression as? SqlNameElement)?.name ?: reference.qualifierExpression?.text
            ?: (result?.immediateQualifier as? SqlNameElement)?.name
            ?: (result?.qualifier as? SqlNameElement)?.name
        val symbol = result?.targetSymbol as? DasColumnSymbol ?: resolved as? DasColumnSymbol ?: reference.resolveSymbol()
        val navigationId = idOf((symbol as? PsiElement)?.navigationElement ?: resolved.navigationElement)
        if (navigationId != null && (symbol as? DasColumnSymbol)?.dasObject?.let(::physicalFact) == null) {
            val fact = columns.values.map { it.jsonObject }.firstOrNull { it.stringOrNull("navigationId") == navigationId }
            if (fact != null) return listOf(contextualTarget(start, target(qualifier ?: (symbol as? DasColumnSymbol)?.dasObject?.table?.name.orEmpty(),
                (symbol as? PsiNamedElement)?.name ?: (resolved as? PsiNamedElement)?.name.orEmpty(), fact.string("identity"), navigationId)))
        }
        if (symbol is DasColumnSymbol) return symbol.dasObject?.let { column ->
            val fact = physicalFact(column)
            listOf(contextualTarget(start, target(qualifier.orEmpty(), column.name, fact?.string("identity") ?: "physical.${column.name}",
                fact?.stringOrNull("navigationId") ?: "decl:physical.${column.name}")))
        }.orEmpty()
        val name = (resolved as? PsiNamedElement)?.name ?: return emptyList()
        return listOf(contextualTarget(start, target("", name, "computed.$name", idOf(resolved.navigationElement))))
    }

    /** A native symbol names the source declaration, not necessarily its visible alias/renamed name.
     * Use the current shared scope only after native identity and navigation independently agree. */
    private fun contextualTarget(start: Int, native: JsonObject): JsonObject {
        val candidates = logicalAnalysis?.referenceAt(start)?.columns.orEmpty().map(::observedColumn).filter {
            it.getValue("identity") == native.getValue("identity") && it.getValue("navigationId") == native.getValue("navigationId")
                && (native.string("identity") != "computed" || it.string("name") == native.string("column"))
        }.distinct()
        val current = candidates.singleOrNull() ?: return native
        return target(current.string("relation"), current.string("name"), native.string("identity"), native.stringOrNull("navigationId"))
    }

    private fun resolveEvidence(file: PsiFile, start: Int): JsonObject = buildJsonObject {
        val reference = nativeReference(file, start)
        put("start", start); put("nativeReference", reference != null)
        if (reference != null) {
            val result = reference.resolveSingle() as? SqlResolveResult
            val symbol = result?.targetSymbol
            put("symbolClass", nullable(symbol?.javaClass?.name))
            put("symbolName", nullable((symbol as? PsiNamedElement)?.name))
            put("nativeReferenceStart", reference.reference.element.textRange.startOffset + reference.reference.rangeInElement.startOffset)
            put("identifierStart", reference.identifier?.textRange?.startOffset ?: -1)
            val variants = (reference.reference as? com.intellij.psi.PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }
                ?: listOfNotNull(reference.reference.resolve())
            put("variants", JsonArray(variants.map { element -> buildJsonObject {
                put("class", element.javaClass.name); put("name", nullable((element as? PsiNamedElement)?.name))
                put("text", element.text); put("offset", element.textOffset)
                put("navigation", nullable(idOf(element.navigationElement)))
                val column = (element as? DasColumnSymbol)?.dasObject ?: (element as? com.intellij.database.psi.DbElement)?.delegate as? DasColumn
                put("table", nullable(column?.table?.name)); put("column", nullable(column?.name))
            } }))
        }
        val logical = logicalAnalysis?.referenceAt(start)
        put("logicalStart", logical?.start ?: -1)
        put("logicalEnd", logical?.end ?: -1)
        put("logicalResolution", nullable(logical?.resolution?.name))
    }

    private fun navigation(file: PsiFile, start: Int): List<String> {
        val element = file.findElementAt(start) ?: return emptyList()
        val targets = DuckdbRelationGotoDeclarationHandler().getGotoDeclarationTargets(element, start, myFixture.editor)
        // SQL declarations/branch outputs have no provider navigation id. A real shared answer
        // must still win over the stock fallback, rather than inventing a single input provenance.
        if (!targets.isNullOrEmpty()) return targets.mapNotNull { idOf(it) }.distinct()
        return nativeTargets(file, start).mapNotNull { it.stringOrNull("navigationId") }
    }

    private fun diagnostics(file: PsiFile, case: JsonObject): JsonArray {
        val holder = AnnotationHolderImpl(AnnotationSession(file), false)
        holder.runAnnotatorWithContext(file, DuckdbRelationAnnotator())
        holder.assertAllAnnotationsCreated()
        val result = holder.map { annotation -> buildJsonObject {
            put("kind", when {
                annotation.message.startsWith("Ambiguous") -> "AMBIGUOUS"
                annotation.message.contains("partial relation contract") -> "PARTIAL_CONTRACT"
                annotation.message.startsWith("Unknown relation") -> "UNKNOWN_QUALIFIER"
                annotation.message.startsWith("Unknown table") -> "UNKNOWN_TABLE"
                annotation.message.contains("branches have") -> "COLUMN_COUNT_MISMATCH"
                annotation.message.contains("cannot lower") -> "PIPE_UNSUPPORTED"
                else -> "MISSING"
            })
            put("start", annotation.startOffset); put("end", annotation.endOffset)
            put("subject", when {
                annotation.message.startsWith("Unknown relation") -> "QUALIFIER"
                annotation.message.startsWith("Unknown table") -> "TABLE"
                annotation.message.contains("branches have") -> "OPERATOR"
                else -> "COLUMN"
            })
        } }.toMutableList()
        val probes = case.array("references").map { it.jsonObject }.filter { it.string("kind") in setOf("QUALIFIER", "TABLE") }
        if (probes.isNotEmpty()) {
            myFixture.enableInspections(dev.sort.duckdb.registeredSqlInspection("SqlResolveInspection"))
            val manager = InjectedLanguageManager.getInstance(project)
            for (info in myFixture.doHighlighting().filter { it.inspectionToolId == "SqlResolveInspection" }) {
                for (probe in probes) {
                    val start = manager.injectedToHost(file, probe.int("start"))
                    val end = manager.injectedToHost(file, probe.int("end"))
                    if (info.startOffset == start && info.endOffset == end) result += buildJsonObject {
                        put("kind", if (probe.string("kind") == "QUALIFIER") "UNKNOWN_QUALIFIER" else "UNKNOWN_TABLE")
                        put("subject", probe.getValue("kind")); put("start", probe.int("start")); put("end", probe.int("end"))
                    }
                }
            }
        }
        return sorted(result.distinct())
    }

    private fun isColumn(item: LookupElement, shown: LookupElementPresentation): Boolean {
        val symbol = (item.`object` as? SqlResolveResult)?.targetSymbol ?: item.psiElement
        return shown.tailText.orEmpty().contains("column") || shown.tailText.orEmpty().contains("relation contract") ||
            symbol is DasColumnSymbol || ((symbol as? com.intellij.database.psi.DbElement)?.delegate is com.intellij.database.model.DasColumn)
    }

    private fun completion(item: LookupElement, candidates: List<ScopeColumn>): JsonObject? {
        val shown = LookupElementPresentation().also(item::renderElement)
        if (!isColumn(item, shown)) return null
        val matched = candidates.filter { column ->
            if (column.name != item.lookupString) false else {
                val short = column.copy(displayType = column.displayType?.substringAfterLast('.'))
                val label = brikkLabels.label(short)
                val tail = "  ${label.origin}" + if (label.relation.isNotBlank()) " (${label.relation})" else ""
                val typeText = (label.type ?: "unknown type") + when (label.nullable) {
                    true -> "?"; false -> ""; null -> " (nullability unknown)"
                }
                shown.tailText == tail && shown.typeText == typeText
            }
        }
        // Match actual output, not the corpus's expected columns. Preserve an unmatched/ambiguous item as a difference.
        if (matched.size == 1) return observedColumn(matched.single())
        if (matched.isNotEmpty() && matched.map(::observedColumn).distinct().size == 1) return observedColumn(matched.first())
        val tail = shown.tailText.orEmpty()
        val symbol = (item.`object` as? SqlResolveResult)?.targetSymbol ?: item.psiElement as? DasColumnSymbol
        val physical = (symbol as? DasColumnSymbol)?.dasObject
        val origin = when {
            tail.contains("relation contract") -> "RELATION_CONTRACT"
            tail.contains("brikk column") -> "KOTLIN_RELATION"
            tail.contains("computed column") -> "COMPUTED"
            physical != null && physical.kind == ObjectKind.COLUMN && item.lookupString == physical.name && physical.name in physicalColumns -> "PHYSICAL_TABLE"
            shown.typeText == "PIPE input column" -> "COMPUTED"
            else -> return null
        }
        val relation = Regex("\\(([^()]*)\\)$").find(tail)?.groupValues?.get(1)
            ?: (symbol as? DasColumnSymbol)?.dasObject?.table?.name.orEmpty()
        return buildJsonObject {
            put("name", item.lookupString); put("relation", relation); put("origin", origin)
            put("unmatchedPresentation", true); put("type", nullable(shown.typeText)); put("tail", nullable(shown.tailText))
        }
    }

    private fun type(sql: String?): JsonElement = nullable(sql?.let {
        try { Dialects.BASE.generate(dataTypeFromStr(it)) } catch (_: dev.brikk.house.sql.parser.ParseError) { it }
    })

    /** Same comparison contract as core's RelationCorpusTest: computed unspecified types are not assertions. */
    private fun matches(expected: JsonElement, actual: JsonElement): Boolean = when {
        expected == JsonPrimitive("*") -> true
        expected is JsonObject && actual is JsonObject -> expected.keys == actual.keys && expected.all { (key, value) -> matches(value, actual.getValue(key)) }
        expected is JsonArray && actual is JsonArray -> expected.size == actual.size && run {
            val unused = actual.toMutableList()
            expected.all { wanted -> unused.firstOrNull { matches(wanted, it) }?.let(unused::remove) == true }
        }
        else -> expected == actual
    }

    private fun engine(oracle: JsonObject, id: String): JsonObject = DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        facts.getValue("engineSetup").jsonObject.getValue("duckdb").jsonArray.forEach { sql -> connection.createStatement().use { it.execute(sql.jsonPrimitive.content) } }
        val wantedFailure = oracle["failure"]?.takeUnless { it == JsonNull }?.jsonObject
        try {
            connection.createStatement().use { statement -> statement.executeQuery(oracle.string("sql")).use { rows ->
                assertNull("$id engine must reject the authored SQL", wantedFailure)
                for (index in 1..rows.metaData.columnCount) {
                    columns["computed.${rows.metaData.getColumnLabel(index)}"]?.jsonObject?.stringOrNull("sqlType")?.let { type ->
                        assertEquals("$id native computed SQL type", type, rows.metaData.getColumnTypeName(index))
                    }
                }
                val data = ArrayList<JsonArray>()
                while (rows.next()) data += JsonArray((1..rows.metaData.columnCount).map { index ->
                    val value = rows.getObject(index)
                    when (value) { null -> JsonNull; is Number -> JsonPrimitive(value); is Boolean -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) }
                })
                oracle["rows"]?.takeUnless { it == JsonNull }?.let { assertEquals("$id engine rows", sorted(it.jsonArray), sorted(data)) }
                oracle["columns"]?.let { assertEquals("$id engine column order", it, strings((1..rows.metaData.columnCount).map(rows.metaData::getColumnLabel))) }
                buildJsonObject {
                    put("verified", true); put("failure", JsonNull)
                    put("sql", oracle.getValue("sql")); put("rows", JsonArray(data))
                    put("columns", strings((1..rows.metaData.columnCount).map(rows.metaData::getColumnLabel)))
                    put("types", strings((1..rows.metaData.columnCount).map(rows.metaData::getColumnTypeName)))
                }
            } }
        } catch (failure: SQLException) {
            assertNotNull("$id unexpected native failure: ${failure.message}", wantedFailure)
            wantedFailure?.stringOrNull("messageContains")?.let { message ->
                assertTrue("$id native failure: ${failure.message}", failure.message.orEmpty().contains(message, ignoreCase = true))
            }
            buildJsonObject {
                put("sql", oracle.getValue("sql")); put("verified", true)
                put("failure", buildJsonObject { put("kind", "REJECTION"); put("message", failure.message) })
            }
        }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.stringOrNull(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.array(key: String) = getValue(key).jsonArray
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun strings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun sorted(values: Collection<JsonElement>) = JsonArray(values.sortedBy(JsonElement::toString))
}
