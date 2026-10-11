package dev.sort.duckdb.pipes

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.SqlColumnCompletion

/** SQL-only renderer checks also run when Kotlin is absent. */
class SqlColumnCompletionTest : BasePlatformTestCase() {
    private fun render(item: LookupElement) = LookupElementPresentation().also { item.renderElement(it) }

    fun testNullablePayloadKeepsNameTypeAliasAndPriority() {
        val item = SqlColumnCompletion.create("payload", "String", true, "src", "brikk column")
        val shown = render(item)
        assertEquals("payload", item.lookupString)
        assertEquals("payload", shown.itemText)
        assertEquals("String?", shown.typeText)
        assertEquals("  brikk column (src)", shown.tailText)
        assertEquals(120.0, (item as PrioritizedLookupElement<*>).priority)
    }

    fun testNonNullInstantAndRelationFallbackAreLabelledSeparately() {
        val instant = render(SqlColumnCompletion.create("event_at", "Instant", false, "src"))
        assertEquals("Instant", instant.typeText)
        assertEquals("  brikk column (src)", instant.tailText)
        val fallback = render(SqlColumnCompletion.create("generated", "String", false, "src", "relation contract"))
        assertEquals("  relation contract (src)", fallback.tailText)
    }

    fun testUnknownFactsStayExplicitAndBlankAliasIsOmitted() {
        val shown = render(SqlColumnCompletion.create("pending", null, null, " "))
        assertEquals("unknown type (nullability unknown)", shown.typeText)
        assertEquals("  brikk column", shown.tailText)
    }

    fun testSameNamedJoinColumnsHaveDistinctLookupIdentitiesAndUnchangedInsertionText() {
        val a = SqlColumnCompletion.create("id", "Long", false, "a")
        val b = SqlColumnCompletion.create("id", "Long", false, "b")
        assertEquals(2, setOf(a, b).size)
        assertFalse(a.`object` == b.`object`)
        assertEquals(a.`object`, SqlColumnCompletion.create("id", "Long", false, "a").`object`)
        assertEquals("id", a.lookupString)
        assertEquals("id", b.lookupString)
        assertEquals("  brikk column (a)", render(a).tailText)
        assertEquals("  brikk column (b)", render(b).tailText)
    }
}
