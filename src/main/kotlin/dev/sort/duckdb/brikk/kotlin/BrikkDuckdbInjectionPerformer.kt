package dev.sort.duckdb.brikk.kotlin

import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import dev.sort.duckdb.sql.DuckdbSqlDialect
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Own only resolved DuckDB Brikk hosts; return false for Doris and unrelated Kotlin SQL. */
class BrikkDuckdbInjectionPerformer : LanguageInjectionPerformer {
    override fun isPrimary() = true
    override fun performInjection(registrar: MultiHostRegistrar, injection: Injection, context: PsiElement): Boolean {
        if (injection.injectedLanguageId != "DuckDBSQL") return false
        val host = context as? KtStringTemplateExpression ?: return false
        val brikk = BrikkDuckdbKotlinContext.find(host) ?: return false
        val template = BrikkDuckdbKotlinContext.template(brikk)
        val places = injectionPlaces(template)
        if (places.isEmpty()) return false
        registrar.startInjecting(DuckdbSqlDialect.INSTANCE)
        registrar.putInjectedFileUserData(BrikkDuckdbProfile.KEY, template.profile)
        // Synthetic text has no Kotlin runtime value; leave template refs in the host language.
        registrar.frankensteinInjection(template.profile.issues.isNotEmpty())
        registrar.makeInspectionsLenient(template.profile.issues.isNotEmpty())
        places.forEachIndexed { index, place ->
            registrar.addPlace((if (index == 0) injection.prefix else "") + place.prefix,
                if (index == places.lastIndex) injection.suffix else "", host, place.range)
        }
        registrar.doneInjecting()
        return true
    }

    internal data class Place(val range: TextRange, val prefix: String = "")
    private data class Character(val char: Char, val start: Int, val end: Int, val synthetic: Boolean)

    /** Trim the rendered SQL but keep each retained literal character's host range. */
    internal fun injectionPlaces(template: BrikkDuckdbKotlinContext.Template): List<Place> {
        val host = template.context.host
        val escaper = host.createLiteralTextEscaper()
        var chars = template.pieces.flatMap { piece ->
            if (piece.replacement != null) piece.replacement.map { Character(it, piece.range.startOffset, piece.range.startOffset, true) }
            else {
                val decoded = StringBuilder()
                check(escaper.decode(piece.range, decoded)) { "Cannot decode Kotlin SQL literal" }
                decoded.mapIndexed { index, char -> Character(char,
                    escaper.getOffsetInHost(index, piece.range), escaper.getOffsetInHost(index + 1, piece.range), false) }
            }
        }
        for (trim in template.context.trims) chars = trim(chars, trim == "trimMargin")
        val places = mutableListOf<Place>()
        var synthetic = StringBuilder()
        var anchor = 0
        var i = 0
        while (i < chars.size) {
            val char = chars[i]
            if (char.synthetic) { synthetic.append(char.char); anchor = char.start; i++; continue }
            val start = char.start
            var end = char.end
            i++
            while (i < chars.size && !chars[i].synthetic && chars[i].start == end) end = chars[i++].end
            places.add(Place(TextRange(start, end), synthetic.toString()))
            synthetic = StringBuilder()
        }
        if (synthetic.isNotEmpty()) places.add(Place(TextRange(anchor, anchor), synthetic.toString()))
        if (places.isEmpty()) {
            val start = if (host.text.startsWith("\"\"\"")) 3 else 1
            places.add(Place(TextRange(start, start)))
        }
        return places
    }

    private fun trim(chars: List<Character>, margin: Boolean): List<Character> {
        val lines = mutableListOf<List<Character>>()
        var start = 0
        var i = 0
        while (i < chars.size) {
            if (chars[i].char == '\n' || chars[i].char == '\r') {
                lines.add(chars.subList(start, i))
                if (chars[i].char == '\r' && chars.getOrNull(i + 1)?.char == '\n') i++
                start = i + 1
            }
            i++
        }
        lines.add(chars.subList(start, chars.size))
        val indent = lines.filter { line -> line.any { !it.char.isWhitespace() } }
            .minOfOrNull { line -> line.takeWhile { it.char.isWhitespace() }.size } ?: 0
        val retained = lines.mapIndexedNotNull { index, line ->
            if ((index == 0 || index == lines.lastIndex) && line.all { it.char.isWhitespace() }) null
            else if (margin) {
                val first = line.indexOfFirst { !it.char.isWhitespace() }
                if (first >= 0 && line[first].char == '|') line.drop(first + 1) else line
            } else line.drop(indent)
        }
        return retained.flatMapIndexed { index, line ->
            if (index == 0) line else listOf(Character('\n', line.firstOrNull()?.start ?: 0,
                line.firstOrNull()?.start ?: 0, true)) + line
        }
    }
}
