package nl.vitaal.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
