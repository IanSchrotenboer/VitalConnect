package nl.vitaal.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Grafiek van één metric over 7 dagen tot een jaar, met vergelijking met de periode ervoor. */
@Composable
fun DetailScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val id = vm.trendMetric
    val m = vm.metrics[id]
    val color = p.colorOf(id)
    val all = vm.trendSeries
    val shown = all.takeLast(vm.trendDays)
    val prev = all.dropLast(vm.trendDays).mapNotNull { it.value }
    val vals = shown.mapNotNull { it.value }
    var selected by remember(shown.size, id) { mutableIntStateOf(shown.indexOfLast { it.value != null }.coerceAtLeast(0)) }
    val unit = unitOf(id, m?.unit ?: "")
    val bars = m?.aggregation == "sum"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeDetail) { Icon(VIcons.Back, contentDescription = "Terug", tint = p.ink) }
            Column(Modifier.weight(1f)) {
                Text(m?.name ?: id, style = MaterialTheme.typography.headlineSmall, color = p.ink)
                Text(categoryOf(id).label, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(7 to "7 dagen", 30 to "30 dagen", 90 to "90 dagen", 365 to "Jaar").forEach { (d, label) ->
                Pill(label, vm.trendDays == d) { vm.setTrendPeriod(d) }
            }
        }

        VCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val pt = shown.getOrNull(selected)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(pt?.value?.let { fmt(id, it) } ?: "–", style = MaterialTheme.typography.displaySmall, color = p.ink)
                if (unit.isNotEmpty() && pt?.value != null) Text(" $unit", style = MaterialTheme.typography.bodyMedium, color = p.muted,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(
                (pt?.day?.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", NL)) ?: "") +
                    (vm.trendSource?.let { " · ${sourceLabel(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = p.muted,
            )
            TrendChart(
                points = shown, bars = bars, goal = vm.prefs[id]?.goal, color = color,
                selected = selected, onSelect = { selected = it },
                axis = { v -> if (id == "sleep") "${v.roundToInt()}u" else fmtCompact(v) },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        VCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Gemiddelde", vals.takeIf { it.isNotEmpty() }?.average()?.let { fmt(id, it) } ?: "–", Modifier.weight(1f))
                Stat("Laagste", vals.minOrNull()?.let { fmt(id, it) } ?: "–", Modifier.weight(1f))
                Stat("Hoogste", vals.maxOrNull()?.let { fmt(id, it) } ?: "–", Modifier.weight(1f))
            }
            val change = if (vals.isNotEmpty() && prev.isNotEmpty() && prev.average() != 0.0)
                (vals.average() - prev.average()) / prev.average() * 100 else null
            change?.let { c ->
                val better = if (m?.lowerIsBetter == true) c < 0 else c > 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vergeleken met de periode ervoor", style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("${if (c >= 0) "+" else "−"}${String.format(NL, "%.1f", abs(c))}%",
                        style = MaterialTheme.typography.titleMedium, color = if (abs(c) < 1) p.ink else if (better) p.good else p.bad)
                }
            }
            if (bars && vals.isNotEmpty()) {
                Text("Totaal in deze periode: ${fmtCompact(vals.sum())} ${unit.ifEmpty { if (id == "steps") "stappen" else "" }}".trim(),
                    style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        }
    }
}
