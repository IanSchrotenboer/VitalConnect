package nl.vitaal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Alle statistieken over je hele geschiedenis, per categorie. */
@Composable
fun HealthScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val first = vm.stats.values.mapNotNull { it.firstDay }.minOrNull()
    // Eigen indeling van Gezondheid; los van de tegels op Vandaag
    val groups = Category.entries.map { c -> c to vm.metrics.keys.filter { categoryOf(it) == c && it !in vm.layout.healthHidden }.sorted() }
        .filter { it.second.isNotEmpty() }

    val perRow = if (LocalWidthClass.current.wide) 2 else 1
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) {
                    ScreenTitle("Gezondheid")
                    Text(if (first != null) "Al je statistieken sinds ${fmtDate(first)}" else "Al je statistieken, zodra er data binnenkomt",
                        style = MaterialTheme.typography.bodyMedium, color = p.muted)
                }
                IconLabel(VIcons.Sliders, "Aanpassen", p.primary) { vm.editing = EditTarget.Health }
            }
        }
        if (vm.layout.healthTotals) item { Highlights(vm) }
        groups.forEach { (cat, ids) ->
            item {
                SectionTitle(cat.label, Modifier.padding(start = 4.dp, top = 12.dp), dot = p.colorOf(cat))
            }
            // Op brede schermen twee kaarten naast elkaar
            items(ids.chunked(perRow)) { row ->
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { id -> StatCard(vm, id, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Bovenaan: wat je in totaal al hebt gedaan. */
@Composable
private fun Highlights(vm: MainViewModel) {
    val p = LocalPalette.current
    val steps = vm.stats["steps"]
    val dist = vm.stats["dist"]
    val sleep = vm.stats["sleep"]
    if (steps == null && dist == null && sleep == null) return
    VCard(color = p.primary) {
        Text("Tot nu toe", style = MaterialTheme.typography.labelMedium, color = p.onPrimary.copy(alpha = 0.8f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            steps?.totalAll?.let { Stat("Stappen", fmtCompact(it), Modifier.weight(1f), p.onPrimary) }
            dist?.totalAll?.let { Stat("Kilometer", fmtCompact(it), Modifier.weight(1f), p.onPrimary) }
            sleep?.avgAll?.let { Stat("Gem. slaap", fmtHours(it), Modifier.weight(1f), p.onPrimary) }
        }
    }
}

@Composable
private fun StatCard(vm: MainViewModel, id: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val m = vm.metrics[id] ?: return
    val s = vm.stats[id]
    val color = p.colorOf(id)
    val unit = unitOf(id, m.unit)
    fun f(v: Double?) = v?.let { fmt(id, it) } ?: "–"

    VCard(modifier.fillMaxWidth(), onClick = { vm.openDetail(id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 9.dp)
            Spacer(Modifier.width(8.dp))
            Text(m.name, style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f))
            Icon(VIcons.ChevronRight, contentDescription = "Grafiek", tint = p.muted, modifier = Modifier.size(20.dp))
        }
        if (s == null || s.days == 0) {
            Text("Nog geen metingen", style = MaterialTheme.typography.bodySmall, color = p.muted)
            return@VCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(f(s.last), style = MaterialTheme.typography.headlineMedium, color = p.ink)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            s.lastDay?.let { Text(fmtDate(it), style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        val isSum = m.aggregation == "sum" && id != "sleep"
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(if (isSum) "Gem. per dag" else "Gemiddeld", f(s.avgAll), Modifier.weight(1f))
            Stat("Laatste 30 d.", f(s.avg30), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(if (m.lowerIsBetter) "Beste (laagste)" else "Record", f(if (m.lowerIsBetter) s.minAll else s.maxAll), Modifier.weight(1f), p.inkOf(id))
            if (isSum) Stat("Totaal", s.totalAll?.let { fmtCompact(it) } ?: "–", Modifier.weight(1f))
            else Stat(if (m.lowerIsBetter) "Hoogste" else "Laagste", f(if (m.lowerIsBetter) s.maxAll else s.minAll), Modifier.weight(1f))
        }
        Text("${s.days} ${if (s.days == 1) "dag" else "dagen"} gemeten", style = MaterialTheme.typography.labelSmall, color = p.muted)
    }
}
