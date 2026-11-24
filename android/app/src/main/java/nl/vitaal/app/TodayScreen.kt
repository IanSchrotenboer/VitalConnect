package nl.vitaal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun TodayScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val rows = remember(vm.prefs) {
        // Brede tegels krijgen een eigen rij, kleine tegels gaan per twee
        val out = mutableListOf<List<Pref>>()
        var pair = mutableListOf<Pref>()
        for (pref in vm.visible) {
            if (pref.size == "l") {
                if (pair.isNotEmpty()) { out += pair; pair = mutableListOf() }
                out += listOf(pref)
            } else {
                pair += pref
                if (pair.size == 2) { out += pair; pair = mutableListOf() }
            }
        }
        if (pair.isNotEmpty()) out += pair
        out.toList()
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DialHeader(vm) }
        item {
            SectionTitle("Jouw metrics", Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
                IconLabel(VIcons.Sliders, "Aanpassen", p.primary) { vm.editing = true }
            }
        }
        if (rows.isEmpty()) item {
            Text("Je dashboard is leeg. Kies metrics via Aanpassen.", color = p.muted, modifier = Modifier.padding(horizontal = 20.dp))
        }
        items(rows) { row ->
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { pref -> Tile(vm, pref, Modifier.weight(1f)) }
                if (row.size == 1 && row[0].size != "l") Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Donkere wijzerplaat met datum, dagringen en een motiverende regel. */
@Composable
private fun DialHeader(vm: MainViewModel) {
    val d = DialPalette
    val now = LocalTime.now()
    val firstName = vm.profile.name.trim().substringBefore(' ')
    val greeting = when { now.hour < 12 -> "Goedemorgen"; now.hour < 18 -> "Goedemiddag"; else -> "Goedenavond" } +
        if (firstName.isNotEmpty()) ", $firstName" else ""
    val rings = vm.visible.filter { it.inRings && it.goal != null && !(vm.metrics[it.metric]?.lowerIsBetter ?: false) }.take(3)
    val fractions = rings.map { pref -> ((vm.today[pref.metric]?.value ?: 0.0) / pref.goal!!).toFloat() }
    // Kleur per positie, zodat twee doelen uit dezelfde categorie niet dezelfde ring krijgen
    val ringColors = listOf(d.activity, d.recovery, d.sleep)

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(d.dial)
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", NL)).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium, color = d.dialMuted)
                Text(greeting, style = MaterialTheme.typography.headlineLarge, color = d.dialInk, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            IconButton(onClick = vm::refreshAll, enabled = !vm.busy) {
                Icon(VIcons.Sync, contentDescription = "Bijwerken", tint = d.dialInk)
            }
            IconButton(onClick = { vm.openProfile() }) {
                Icon(VIcons.Person, contentDescription = "Profiel en koppelingen", tint = d.dialInk)
            }
        }

        if (rings.isEmpty()) {
            Text("Kies tot drie dagdoelen via Aanpassen om hier je voortgang te zien.", color = d.dialMuted,
                modifier = Modifier.padding(end = 12.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                val avg = (fractions.map { it.coerceAtMost(1f) }.average() * 100).roundToInt()
                Rings(rings.indices.map { i -> fractions[i] to ringColors[i] }, "$avg%", Modifier.size(150.dp))
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rings.forEachIndexed { i, pref ->
                        val m = vm.metrics[pref.metric]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(ringColors[i], 9.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(m?.name ?: pref.metric, style = MaterialTheme.typography.labelMedium, color = d.dialMuted, maxLines = 1)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(vm.today[pref.metric]?.let { fmt(pref.metric, it.value) } ?: "–",
                                style = MaterialTheme.typography.headlineSmall, color = d.dialInk)
                            Text("  / ${fmt(pref.metric, pref.goal!!)}", style = MaterialTheme.typography.bodySmall, color = d.dialMuted)
                        }
                    }
                }
            }
            Motivation(vm, rings, fractions)
        }

        vm.lastLoaded?.let {
            Text("Bijgewerkt om " + it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.labelSmall, color = d.dialMuted)
        }
    }
}

/** Eén zin die laat zien wat er vandaag nog te halen valt. */
@Composable
private fun Motivation(vm: MainViewModel, rings: List<Pref>, fractions: List<Float>) {
    val d = DialPalette
    val open = rings.indices.filter { fractions[it] < 1f }.minByOrNull { 1f - fractions[it] }
    val text = if (open == null) {
        "Alle dagdoelen gehaald. Mooi werk!"
    } else {
        val pref = rings[open]
        val m = vm.metrics[pref.metric]
        val rest = pref.goal!! - (vm.today[pref.metric]?.value ?: 0.0)
        val unit = unitOf(pref.metric, m?.unit ?: "").let { if (it.isEmpty() && pref.metric == "steps") "stappen" else it }
        "Nog ${fmt(pref.metric, rest)} $unit tot je doel voor ${m?.name?.lowercase() ?: pref.metric}.".replace("  ", " ")
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (open == null) d.good else d.dialInk,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(d.dialInk.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun Tile(vm: MainViewModel, pref: Pref, modifier: Modifier) {
    val p = LocalPalette.current
    val m = vm.metrics[pref.metric] ?: return
    val v = vm.today[pref.metric]
    val hist = vm.history[pref.metric]
    val wide = pref.size == "l"
    val color = p.colorOf(pref.metric)
    val days = if (wide) 14 else 7
    val spark = (days - 1 downTo 0).map { hist?.get(LocalDate.now().minusDays(it.toLong())) }

    VCard(modifier, onClick = { vm.openDetail(pref.metric) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 9.dp)
            Spacer(Modifier.width(8.dp))
            Text(m.name, style = MaterialTheme.typography.labelMedium, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (v == null) {
            Text("Nog geen data vandaag", style = MaterialTheme.typography.bodySmall, color = p.muted,
                modifier = Modifier.padding(vertical = 6.dp))
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(pref.metric, v.value), style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                    color = p.ink, maxLines = 1)
                val unit = unitOf(pref.metric, m.unit)
                if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = p.muted,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            pref.goal?.let { g ->
                val f = (if (m.lowerIsBetter) g / v.value else v.value / g).toFloat()
                Progress(f, color)
                Text(if (f >= 1f) "Doel gehaald" else "${(f * 100).roundToInt()}% van ${fmt(pref.metric, g)}",
                    style = MaterialTheme.typography.labelSmall, color = if (f >= 1f) p.good else p.muted)
            }
        }
        Sparkline(spark, color, Modifier.fillMaxWidth().height(if (wide) 52.dp else 34.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v?.let { sourceLabel(it.source) } ?: "", style = MaterialTheme.typography.labelSmall, color = p.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Delta(deltaVsWeek(hist), m.lowerIsBetter)
        }
    }
}
