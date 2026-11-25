package nl.vitaal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
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
        // Herstel als losse tegel, over de volle breedte, boven de andere tegels
        if (vm.layout.herstel && vm.fitness != null) item {
            HerstelTile(vm, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
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

/**
 * Datum, begroeting, dagringen en een motiverende regel. Licht en donker gelijk (ontwerp C):
 * de begroeting los op de achtergrond, de ringen in een tegel zoals de andere tegels op Vandaag.
 */
@Composable
private fun DialHeader(vm: MainViewModel) {
    val p = LocalPalette.current
    val now = LocalTime.now()
    val firstName = vm.profile.name.trim().substringBefore(' ')
    val greeting = when { now.hour < 12 -> "Goedemorgen"; now.hour < 18 -> "Goedemiddag"; else -> "Goedenavond" } +
        if (firstName.isNotEmpty() && vm.layout.greetName) ", $firstName" else ""
    val rings = vm.rings
    val fractions = rings.map { pref -> ((vm.today[pref.metric]?.value ?: 0.0) / pref.goal!!).toFloat() }
    // Kleur per positie, zodat twee doelen uit dezelfde categorie niet dezelfde ring krijgen
    val ringColors = RING_COLORS

    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", NL)).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium, color = p.muted)
                // Past zich aan lettergrootte, weergavegrootte en schermbreedte aan; lange namen op een tweede regel
                FitText(greeting, MaterialTheme.typography.headlineLarge, p.ink, Modifier.semantics { heading() })
            }
            RefreshButton(vm)
            IconButton(onClick = { vm.openProfile() }) {
                Icon(VIcons.Person, contentDescription = "Profiel en koppelingen", tint = p.ink)
            }
        }

        if (rings.isEmpty()) {
            Text("Kies tot drie dagdoelen via Aanpassen om hier je voortgang te zien.", style = MaterialTheme.typography.bodyMedium, color = p.muted,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp))
        } else {
            val avg = (fractions.map { it.coerceAtMost(1f) }.average() * 100).roundToInt()
            val tiny = LocalWidthClass.current == WidthClass.Tiny
            val ringSize = if (tiny) 108.dp else 150.dp
            val gap = if (tiny) 12.dp else 20.dp
            val fontScale = LocalDensity.current.fontScale
            val ringsView = @Composable {
                Rings(
                    rings.indices.map { i -> fractions[i] to ringColors[i] }, "$avg%", Modifier.size(ringSize),
                    description = "Dagringen: gemiddeld $avg procent van je doelen gehaald",
                    refreshing = vm.refresh == RefreshState.Busy,
                    track = p.ringTrack,
                    centerColor = p.ink,
                )
            }
            val legend = @Composable { mod: Modifier ->
                Column(mod, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rings.forEachIndexed { i, pref ->
                        val m = vm.metrics[pref.metric]
                        Column(Modifier.semantics(mergeDescendants = true) {}) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(ringColors[i], 9.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(m?.name ?: pref.metric, style = MaterialTheme.typography.labelMedium, color = p.muted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.weight(1f).width(8.dp))
                                // Hoeveel procent van het doel, in de kleur van de ring
                                Text("${(fractions[i] * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold, color = p.ringInk(i), maxLines = 1, softWrap = false)
                            }
                            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 17.dp)) {
                                Text(vm.today[pref.metric]?.let { fmt(pref.metric, it.value) } ?: "–",
                                    style = MaterialTheme.typography.headlineSmall, color = p.ink, maxLines = 1, softWrap = false)
                                Text("  / ${fmt(pref.metric, pref.goal!!)}", style = MaterialTheme.typography.bodySmall, color = p.muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            val body = @Composable {
                // Uitleg onder de ringen als hij ernaast niet past: bij grote letters (vanaf 130%) of een smal venster
                // (grote weergavegrootte, gesplitst scherm), zodat niets wordt afgekapt
                BoxWithConstraints {
                    val stacked = fontScale > 1.3f || ringSize + gap + 140.dp * fontScale > maxWidth
                    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ringsView(); legend(Modifier.fillMaxWidth())
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        ringsView()
                        Spacer(Modifier.width(gap))
                        legend(Modifier.weight(1f))
                    }
                }
                if (vm.layout.motivation) Motivation(vm, rings, fractions)
            }
            Column(
                Modifier.padding(end = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.card)
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) { body() }
        }

        Box(Modifier.padding(start = 4.dp)) { RefreshStatus(vm) }
    }
}

/** Knop bijwerken: draait tijdens het vernieuwen, daarna even een vinkje (gelukt) of een waarschuwing (mislukt). */
@Composable
private fun RefreshButton(vm: MainViewModel) {
    val p = LocalPalette.current
    IconButton(onClick = vm::refreshAll, enabled = vm.refresh != RefreshState.Busy) {
        Crossfade(vm.refresh, label = "bijwerken") { state ->
            when (state) {
                RefreshState.Busy -> {
                    val turn by rememberInfiniteTransition(label = "draaien")
                        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "hoek")
                    Icon(VIcons.Sync, contentDescription = "Bezig met bijwerken", tint = p.ink,
                        modifier = Modifier.graphicsLayer { rotationZ = turn })
                }
                RefreshState.Done -> {
                    val pop = remember { Animatable(0.6f) }
                    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
                    Icon(VIcons.Check, contentDescription = "Bijgewerkt", tint = p.good,
                        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
                }
                RefreshState.Failed -> Icon(VIcons.Alert, contentDescription = "Bijwerken mislukt. Tik om het opnieuw te proberen", tint = p.bad)
                RefreshState.Idle -> Icon(VIcons.Sync, contentDescription = "Bijwerken", tint = p.ink)
            }
        }
    }
}

/** Regel onder de ringen: bezig, wanneer bijgewerkt, of waarom het mislukte. Wordt voorgelezen bij een wijziging. */
@Composable
private fun RefreshStatus(vm: MainViewModel) {
    val p = LocalPalette.current
    val time = vm.lastLoaded?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm"))
    val text = when (vm.refresh) {
        RefreshState.Busy -> "Bijwerken…"
        RefreshState.Failed -> "Bijwerken mislukt: ${vm.refreshError ?: "er ging iets mis"}"
        RefreshState.Done -> time?.let { "Bijgewerkt om $it" }
        RefreshState.Idle -> time?.let { "Bijgewerkt om $it" }
    } ?: return
    val color by animateColorAsState(
        when (vm.refresh) { RefreshState.Done -> p.good; RefreshState.Failed -> p.bad; RefreshState.Busy -> p.ink; else -> p.muted },
        tween(300), label = "statuskleur",
    )
    Text(text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Eén zin die laat zien wat er vandaag nog te halen valt: onder een scheidingslijn, met een pijl in de kleur van de ring. */
@Composable
private fun Motivation(vm: MainViewModel, rings: List<Pref>, fractions: List<Float>) {
    val p = LocalPalette.current
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open == null) VIcons.Check else VIcons.ArrowRight, contentDescription = null,
                tint = if (open == null) p.good else p.ringInk(open), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = if (open == null) p.good else p.ink)
        }
    }
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
