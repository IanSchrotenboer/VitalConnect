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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Slaap: afgelopen nacht, de laatste twee weken en herstel tijdens de slaap. */
@Composable
fun SleepScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val hist = vm.history["sleep"].orEmpty()
    val goal = vm.prefs["sleep"]?.goal ?: 8.0
    val nights = (13 downTo 0).map { TrendPoint(today.minusDays(it.toLong()), hist[today.minusDays(it.toLong())]) }
    val lastNight = nights.lastOrNull { it.value != null }
    val week = nights.takeLast(7).mapNotNull { it.value }
    val avg7 = week.takeIf { it.isNotEmpty() }?.average()
    val debt = week.sumOf { (goal - it).coerceAtLeast(0.0) }
    var selected by remember(nights.size) { mutableIntStateOf(nights.indexOfLast { it.value != null }.coerceAtLeast(0)) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Text("Slaap", style = MaterialTheme.typography.headlineLarge, color = p.ink)
                Text("Hoe goed je rust, en hoe je lichaam herstelt", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }

        // Afgelopen nacht
        item {
            VCard(Modifier.fillMaxWidth(), color = DialPalette.dial, onClick = { vm.openDetail("sleep") }) {
                val d = DialPalette
                Text(lastNight?.let { if (it.day == today) "Afgelopen nacht" else "Laatste nacht · ${it.day.format(DateTimeFormatter.ofPattern("EEEE d MMM", NL))}" }
                    ?: "Nog geen slaapdata", style = MaterialTheme.typography.labelMedium, color = d.dialMuted)
                if (lastNight != null) {
                    val v = lastNight.value!!
                    val f = (v / goal).toFloat()
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(fmtHours(v), style = MaterialTheme.typography.displayLarge, color = d.dialInk)
                        Spacer(Modifier.weight(1f))
                        vm.history["sscore"]?.get(lastNight.day)?.let { score ->
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Score", style = MaterialTheme.typography.labelSmall, color = d.dialMuted)
                                Text(fmt("sscore", score), style = MaterialTheme.typography.headlineMedium, color = d.sleep)
                            }
                        }
                    }
                    Progress(f, d.sleep, height = 10.dp)
                    Text(
                        if (f >= 1f) "Slaapdoel van ${fmtHours(goal)} gehaald" else "${(f * 100).roundToInt()}% van je doel van ${fmtHours(goal)}",
                        style = MaterialTheme.typography.labelLarge, color = if (f >= 1f) d.good else d.dialInk,
                    )
                }
            }
        }

        // Twee weken
        item {
            VCard(Modifier.fillMaxWidth()) {
                Text("Laatste 14 nachten", style = MaterialTheme.typography.titleSmall, color = p.ink)
                nights.getOrNull(selected)?.let { n ->
                    Text(
                        n.day.format(DateTimeFormatter.ofPattern("EEEE d MMM", NL)) + ": " + (n.value?.let { fmtHours(it) } ?: "geen data"),
                        style = MaterialTheme.typography.bodyMedium, color = p.muted,
                    )
                }
                TrendChart(
                    points = nights, bars = true, goal = goal, color = p.sleep,
                    selected = selected, onSelect = { selected = it },
                    axis = { "${it.roundToInt()}u" },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Gem. 7 nachten", avg7?.let { fmtHours(it) } ?: "–", Modifier.weight(1f))
                    Stat("Tekort deze week", if (week.isEmpty()) "–" else fmtHours(debt), Modifier.weight(1f),
                        if (debt > 2) p.bad else p.ink)
                    Stat("Beste nacht", nights.mapNotNull { it.value }.maxOrNull()?.let { fmtHours(it) } ?: "–", Modifier.weight(1f), p.sleep)
                }
            }
        }

        // Advies op basis van de eigen cijfers
        if (avg7 != null) item {
            val diff = goal - avg7
            val text = when {
                diff > 0.5 -> "Je slaapt deze week gemiddeld ${fmtHours(diff)} korter dan je doel. Een vaste bedtijd, ook in het weekend, helpt je ritme."
                diff > 0 -> "Je zit dicht bij je slaapdoel. Nog een kwartier eerder naar bed en je haalt het."
                else -> "Je haalt je slaapdoel gemiddeld. Houd dit ritme vast."
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = p.ink,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.sleep.copy(alpha = 0.12f)).padding(16.dp))
        }

        // Herstel tijdens de slaap
        item { SectionTitle("Herstel tijdens je slaap", Modifier.padding(start = 4.dp, top = 8.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("hrv", "rhr", "resp").forEach { id -> RecoveryTile(vm, id, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RecoveryTile(vm: MainViewModel, id: String, modifier: Modifier) {
    val p = LocalPalette.current
    val m = vm.metrics[id]
    val hist = vm.history[id]
    val last = hist?.maxByOrNull { it.key }
    VCard(modifier, onClick = { vm.openDetail(id) }) {
        Text(m?.name ?: id, style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(last?.let { fmt(id, it.value) } ?: "–", style = MaterialTheme.typography.headlineSmall, color = p.ink)
            if (last != null && m != null) Text(" ${m.unit}", style = MaterialTheme.typography.labelSmall, color = p.muted,
                modifier = Modifier.padding(bottom = 3.dp))
        }
        Delta(deltaVsWeek(hist), m?.lowerIsBetter ?: false)
    }
}
