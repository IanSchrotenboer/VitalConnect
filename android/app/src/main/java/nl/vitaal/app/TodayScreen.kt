package nl.vitaal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion

@Composable
fun TodayScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val columns = LocalWidthClass.current.tileColumns
    // Kleine tegels nemen één kolom, brede twee (op telefoons de hele rij); op brede schermen meer kolommen
    val rows = remember(vm.prefs, columns) { packRows(vm.tiles, columns) { it.size == "l" } }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DialHeader(vm) }
        item {
            SectionTitle("Jouw metrics", Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
                IconLabel(VIcons.Sliders, "Aanpassen", p.primary) { vm.editing = EditTarget.Today }
            }
        }
        if (rows.isEmpty()) item {
            Text("Je dashboard is leeg. Kies metrics via Aanpassen.", style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp))
        }
        items(rows) { row ->
            Row(Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (pref, span) -> Tile(vm, pref, Modifier.weight(span.toFloat()).fillMaxHeight()) }
                val free = columns - row.sumOf { it.second }
                if (free > 0) Spacer(Modifier.weight(free.toFloat()))
            }
        }
    }
}

/** Donkere wijzerplaat met datum, dagringen en een motiverende regel. */
@Composable
private fun DialHeader(vm: MainViewModel) {
    val d = dialPalette()
    val now = LocalTime.now()
    val firstName = vm.profile.name.trim().substringBefore(' ')
    val greeting = when { now.hour < 12 -> "Goedemorgen"; now.hour < 18 -> "Goedemiddag"; else -> "Goedenavond" } +
        if (firstName.isNotEmpty() && vm.layout.greetName) ", $firstName" else ""
    val rings = vm.rings
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
            RefreshButton(vm, d)
            IconButton(onClick = { vm.openProfile() }) {
                Icon(VIcons.Person, contentDescription = "Profiel en koppelingen", tint = d.dialInk)
            }
        }

        if (rings.isEmpty()) {
            Text("Kies tot drie dagdoelen via Aanpassen om hier je voortgang te zien.", style = MaterialTheme.typography.bodyMedium, color = d.dialMuted,
                modifier = Modifier.padding(end = 12.dp))
        } else {
            val avg = (fractions.map { it.coerceAtMost(1f) }.average() * 100).roundToInt()
            val tiny = LocalWidthClass.current == WidthClass.Tiny
            // Bij grote letters (vanaf 130%) de uitleg onder de ringen, zodat niets wordt afgekapt
            val stacked = LocalDensity.current.fontScale > 1.3f
            val ringsView = @Composable {
                Rings(
                    rings.indices.map { i -> fractions[i] to ringColors[i] }, "$avg%", Modifier.size(if (tiny) 108.dp else 150.dp),
                    description = "Dagringen: gemiddeld $avg procent van je doelen gehaald",
                    refreshing = vm.refresh == RefreshState.Busy,
                )
            }
            val legend = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rings.forEachIndexed { i, pref ->
                        val m = vm.metrics[pref.metric]
                        Column(Modifier.semantics(mergeDescendants = true) {}) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(ringColors[i], 9.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(m?.name ?: pref.metric, style = MaterialTheme.typography.labelMedium, color = d.dialMuted,
                                    maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(vm.today[pref.metric]?.let { fmt(pref.metric, it.value) } ?: "–",
                                    style = MaterialTheme.typography.headlineSmall, color = d.dialInk, maxLines = 1, softWrap = false)
                                Text("  / ${fmt(pref.metric, pref.goal!!)}", style = MaterialTheme.typography.bodySmall, color = d.dialMuted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (stacked) Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ringsView(); legend()
            } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                ringsView()
                Spacer(Modifier.width(if (tiny) 12.dp else 20.dp))
                legend()
            }
            if (vm.layout.motivation) Motivation(vm, rings, fractions)
        }

        RefreshStatus(vm, d)
    }
}

/** Knop bijwerken: draait tijdens het vernieuwen, daarna even een vinkje (gelukt) of een waarschuwing (mislukt). */
@Composable
private fun RefreshButton(vm: MainViewModel, d: Palette) {
    IconButton(onClick = vm::refreshAll, enabled = vm.refresh != RefreshState.Busy) {
        Crossfade(vm.refresh, label = "bijwerken") { state ->
            when (state) {
                RefreshState.Busy -> {
                    val turn by rememberInfiniteTransition(label = "draaien")
                        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "hoek")
                    Icon(VIcons.Sync, contentDescription = "Bezig met bijwerken", tint = d.dialInk,
                        modifier = Modifier.graphicsLayer { rotationZ = turn })
                }
                RefreshState.Done -> {
                    val pop = remember { Animatable(0.6f) }
                    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
                    Icon(VIcons.Check, contentDescription = "Bijgewerkt", tint = d.good,
                        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
                }
                RefreshState.Failed -> Icon(VIcons.Alert, contentDescription = "Bijwerken mislukt. Tik om het opnieuw te proberen", tint = d.bad)
                RefreshState.Idle -> Icon(VIcons.Sync, contentDescription = "Bijwerken", tint = d.dialInk)
            }
        }
    }
}

/** Regel onder de ringen: bezig, wanneer bijgewerkt, of waarom het mislukte. Wordt voorgelezen bij een wijziging. */
@Composable
private fun RefreshStatus(vm: MainViewModel, d: Palette) {
    val time = vm.lastLoaded?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm"))
    val text = when (vm.refresh) {
        RefreshState.Busy -> "Bijwerken…"
        RefreshState.Failed -> "Bijwerken mislukt: ${vm.refreshError ?: "er ging iets mis"}"
        RefreshState.Done -> time?.let { "Bijgewerkt om $it" }
        RefreshState.Idle -> time?.let { "Bijgewerkt om $it" }
    } ?: return
    val color by animateColorAsState(
        when (vm.refresh) { RefreshState.Done -> d.good; RefreshState.Failed -> d.bad; RefreshState.Busy -> d.dialInk; else -> d.dialMuted },
        tween(300), label = "statuskleur",
    )
    Text(text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Eén zin die laat zien wat er vandaag nog te halen valt. */
@Composable
private fun Motivation(vm: MainViewModel, rings: List<Pref>, fractions: List<Float>) {
    val d = dialPalette()
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
                    color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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
            Text(v?.let { if (wide) sourceLabel(it.source) else sourceShort(it.source) } ?: "", style = MaterialTheme.typography.labelSmall, color = p.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Delta(deltaVsWeek(hist), m.lowerIsBetter, short = !wide)
        }
    }
}
