package nl.vitaal.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * Schermbreedte-klassen, zodat de app past op elke telefoon en foldable:
 * - Tiny: smalle schermen en buitenschermen van klaptelefoons (< 340 dp)
 * - Compact: gewone telefoons en dichtgevouwen foldables (< 600 dp)
 * - Medium: opengevouwen foldables staand en kleine tablets (< 840 dp)
 * - Expanded: opengevouwen foldables liggend en tablets
 * De breedte is die van het venster, dus ook gesplitst scherm en in- en uitvouwen werken.
 */
enum class WidthClass { Tiny, Compact, Medium, Expanded }

val LocalWidthClass = staticCompositionLocalOf { WidthClass.Compact }

@Composable
fun currentWidthClass(): WidthClass {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w < 340 -> WidthClass.Tiny
        w < 600 -> WidthClass.Compact
        w < 840 -> WidthClass.Medium
        else -> WidthClass.Expanded
    }
}

/** Brede schermen: navigatie aan de zijkant en meer kolommen. */
val WidthClass.wide: Boolean get() = this >= WidthClass.Medium

/** Aantal kolommen voor de tegels op Vandaag. */
val WidthClass.tileColumns: Int
    get() = when (this) {
        WidthClass.Tiny -> 1
        WidthClass.Compact -> 2
        WidthClass.Medium -> 3
        WidthClass.Expanded -> 4
    }

/**
 * Kop die altijd past. Meet de echte ruimte, dus volgt lettergrootte, weergavegrootte, vet lettertype,
 * schermbreedte, liggend en gesplitst scherm. Volgorde:
 * 1. één regel, zo groot mogelijk, tot [minOneLine] van de stijlgrootte;
 * 2. twee regels, afgebroken na [breakAfter] (bijvoorbeeld na de komma vóór de naam), van 80% tot [minTwoLines];
 * 3. kleinste maat, afbreken toegestaan, hoogstens drie regels (heel lange naam op een heel smal scherm).
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    breakAfter: String = ", ",
    minOneLine: Float = 0.72f,
    minTwoLines: Float = 0.6f,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        val fit = remember(text, style, maxW, measurer) {
            fun width(t: String, s: Float) = measurer.measure(t, style.scaled(s), softWrap = false, maxLines = 1).size.width
            fun steps(from: Float, to: Float) = generateSequence(from) { it - 0.04f }.takeWhile { it >= to - 0.001f }
            val one = steps(1f, minOneLine).firstOrNull { width(text, it) <= maxW }
            if (one != null) return@remember Triple(text, one, 1)
            val i = text.indexOf(breakAfter)
            val lines = if (i > 0) listOf(text.substring(0, i + breakAfter.trimEnd().length), text.substring(i + breakAfter.length)) else listOf(text)
            val two = steps(0.8f, minTwoLines).firstOrNull { s -> lines.all { width(it, s) <= maxW } }
            if (two != null && lines.size == 2) return@remember Triple(lines.joinToString("\n"), two, 2)
            // Laatste uitweg (heel lange naam op een heel smal scherm): kleinste maat, mag afbreken, hoogstens drie regels
            Triple(lines.joinToString("\n"), minTwoLines, 3)
        }
        val (shown, scale, lines) = fit
        Text(shown, style = style.scaled(scale), color = color, maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1)
    }
}

private fun TextStyle.scaled(s: Float) = copy(
    fontSize = fontSize * s,
    lineHeight = if (lineHeight.isSpecified) lineHeight * s else lineHeight,
)

/** Maximale breedte van lees- en invulschermen, zodat regels niet te lang worden. */
val FormMaxWidth: Dp = 640.dp

/** Maximale breedte van de tabbladen met tegels en grafieken. */
val DashboardMaxWidth: Dp = 1200.dp

/**
 * Verdeelt tegels over rijen. Een kleine tegel neemt één kolom, een brede twee
 * (of de hele rij als er maar twee kolommen zijn). Past een brede tegel niet meer in de rij,
 * dan schuift de eerstvolgende kleine tegel naar voren, zodat er geen gaten vallen.
 * Geeft per rij de tegels met hun breedte.
 */
fun <T> packRows(items: List<T>, columns: Int, isWide: (T) -> Boolean): List<List<Pair<T, Int>>> {
    val span = { item: T -> if (isWide(item)) minOf(2, columns) else 1 }
    val rest = items.toMutableList()
    val rows = mutableListOf<List<Pair<T, Int>>>()
    while (rest.isNotEmpty()) {
        val row = mutableListOf<Pair<T, Int>>()
        var free = columns
        val it = rest.iterator()
        while (it.hasNext() && free > 0) {
            val item = it.next()
            val s = span(item)
            if (s <= free) { row += item to s; free -= s; it.remove() }
        }
        rows += row
    }
    return rows
}
