package nl.vitaal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Hersteltegels die je op Slaap kunt kiezen. */
private val SLEEP_TILE_CHOICES = listOf("hrv", "rhr", "resp", "spo2", "sscore", "stress", "energy", "recov")

/**
 * Aanpassen per scherm.
 * Vandaag: dagringen (welke, volgorde), begroeting en tegels. Een metric in de ringen staat niet ook als tegel.
 * Gezondheid en Slaap hebben hun eigen indeling; wat je op Vandaag weghaalt blijft daar staan.
 */
@Composable
fun EditScreen(vm: MainViewModel, target: EditTarget) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf<String?>(null) }
    val (title, intro) = when (target) {
        EditTarget.Today -> "Vandaag aanpassen" to
            "Kies je dagringen, je begroeting en welke tegels je ziet."
        EditTarget.Health -> "Gezondheid aanpassen" to "Kies welke statistieken je in Gezondheid ziet. Dit staat los van Vandaag."
        EditTarget.Sleep -> "Slaap aanpassen" to "Kies je slaapdoel, de hersteltegels en of je slaapadvies wilt zien."
    }
    Column(Modifier.fillMaxSize()) {
    VTopBar(title, onBack = { vm.editing = null }) {
        Button(onClick = { vm.editing = null }, modifier = Modifier.padding(end = 8.dp)) { Text("Klaar") }
    }
    LazyColumn(Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(intro, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (target == EditTarget.Today) item {
            Expandable("Hoe werken ringen en tegels samen?", Modifier.padding(horizontal = 4.dp)) {
                Text("Een metric in de ringen staat niet ook als tegel. In Gezondheid blijft hij altijd staan, wat je hier ook kiest.",
                    style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        when (target) {
            EditTarget.Today -> {
                item { SectionTitle("Dagringen", Modifier.padding(start = 4.dp, top = 8.dp)) }
                item { RingsCard(vm) }
                item { SectionTitle("Begroeting", Modifier.padding(start = 4.dp, top = 8.dp)) }
                item {
                    VCard(Modifier.fillMaxWidth()) {
                        ToggleRow("Je voornaam", "Goedemorgen, Sam", vm.layout.greetName) { vm.saveLayout(vm.layout.copy(greetName = it)) }
                        ToggleRow("Motiverende zin", "Wat je vandaag nog kunt halen", vm.layout.motivation) { vm.saveLayout(vm.layout.copy(motivation = it)) }
                    }
                }
                item { SectionTitle("Tegels", Modifier.padding(start = 4.dp, top = 8.dp)) }
                item {
                    VCard(Modifier.fillMaxWidth()) {
                        ToggleRow("Herstel vandaag", "Score uit HRV, rusthartslag en slaap, boven je metrics", vm.layout.herstel) {
                            vm.saveLayout(vm.layout.copy(herstel = it))
                        }
                    }
                }
                items(vm.allForEdit, key = { it.metric }) { pref ->
                    EditRow(vm, pref, open == pref.metric) { open = if (open == pref.metric) null else pref.metric }
                }
            }
            EditTarget.Health -> {
                item {
                    VCard(Modifier.fillMaxWidth()) {
                        ToggleRow("Tot nu toe", "Totalen bovenaan: stappen, kilometers, gemiddelde slaap", vm.layout.healthTotals) {
                            vm.saveLayout(vm.layout.copy(healthTotals = it))
                        }
                    }
                }
                Category.entries.forEach { cat ->
                    val ids = vm.metrics.keys.filter { categoryOf(it) == cat }.sorted()
                    if (ids.isEmpty()) return@forEach
                    item { SectionTitle(cat.label, Modifier.padding(start = 4.dp, top = 8.dp), dot = p.colorOf(cat)) }
                    item {
                        VCard(Modifier.fillMaxWidth()) {
                            ids.forEach { id ->
                                ToggleRow(vm.metrics[id]?.name ?: id, null, id !in vm.layout.healthHidden) { on ->
                                    val hidden = if (on) vm.layout.healthHidden - id else vm.layout.healthHidden + id
                                    vm.saveLayout(vm.layout.copy(healthHidden = hidden))
                                }
                            }
                        }
                    }
                }
            }
            EditTarget.Sleep -> item { SleepEdit(vm) }
        }
    }
    }
}

/** Dagringen: maximaal drie, op volgorde (buitenste ring eerst). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RingsCard(vm: MainViewModel) {
    val p = LocalPalette.current
    val ringColors = RING_COLORS
    val rings = vm.rings
    VCard(Modifier.fillMaxWidth()) {
        if (rings.isEmpty()) Text("Nog geen ringen. Kies hieronder tot drie doelen.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        rings.forEachIndexed { i, pref ->
            val m = vm.metrics[pref.metric]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(ringColors[i], 12.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(m?.name ?: pref.metric, style = MaterialTheme.typography.titleSmall, color = p.ink)
                    Text("Doel ${pref.goal?.let { fmt(pref.metric, it) } ?: "–"} · ${if (i == 0) "buitenste ring" else if (i == rings.lastIndex) "binnenste ring" else "middelste ring"}",
                        style = MaterialTheme.typography.labelSmall, color = p.muted)
                }
                IconButton(onClick = { vm.moveRing(pref.metric, -1) }, enabled = i > 0 && !vm.busy) {
                    Icon(VIcons.ChevronUp, contentDescription = "${m?.name} naar buiten", tint = p.muted)
                }
                IconButton(onClick = { vm.moveRing(pref.metric, 1) }, enabled = i < rings.lastIndex && !vm.busy) {
                    Icon(VIcons.ChevronDown, contentDescription = "${m?.name} naar binnen", tint = p.muted)
                }
                TextButton(onClick = { vm.removeRing(pref.metric) }, enabled = !vm.busy) { Text("Weg", color = p.bad) }
            }
        }
        if (rings.size < 3) {
            Text("Toevoegen", style = MaterialTheme.typography.labelMedium, color = p.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.metrics.values.filter { !it.lowerIsBetter && rings.none { r -> r.metric == it.id } }.sortedBy { it.name }.forEach { m ->
                    ActionChip("+ ${m.name}", enabled = !vm.busy) { vm.addRing(m.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepEdit(vm: MainViewModel) {
    val p = LocalPalette.current
    val sleepPref = vm.prefs["sleep"] ?: Pref("sleep")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VCard(Modifier.fillMaxWidth()) {
            Text("Slaapdoel", style = MaterialTheme.typography.titleSmall, color = p.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7.0, 7.5, 8.0, 8.5, 9.0).forEach { h ->
                    Pill(fmtHours(h), (sleepPref.goal ?: 8.0) == h) { vm.save(sleepPref.copy(goal = h)) }
                }
            }
            ToggleRow("Slaapadvies", "Een tip op basis van je eigen week", vm.layout.sleepAdvice) { vm.saveLayout(vm.layout.copy(sleepAdvice = it)) }
        }
        VCard(Modifier.fillMaxWidth()) {
            Text("Hersteltegels", style = MaterialTheme.typography.titleSmall, color = p.ink)
            Text("Tik om aan of uit te zetten. Ze staan in de volgorde waarin je ze kiest.", style = MaterialTheme.typography.bodySmall, color = p.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SLEEP_TILE_CHOICES.filter { it in vm.metrics }.forEach { id ->
                    val on = id in vm.layout.sleepTiles
                    Pill(vm.metrics[id]?.name ?: id, on) {
                        val tiles = if (on) vm.layout.sleepTiles - id else vm.layout.sleepTiles + id
                        vm.saveLayout(vm.layout.copy(sleepTiles = tiles))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    // De hele rij is één schakelaar, zodat TalkBack de naam erbij voorleest
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.ink)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted) }
        }
        Switch(checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = p.primary, checkedThumbColor = p.onPrimary))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditRow(vm: MainViewModel, pref: Pref, expanded: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val m = vm.metrics[pref.metric] ?: return
    var goal by remember(pref.goal) { mutableStateOf(pref.goal?.let { fmtGoal(it) } ?: "") }
    VCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(p.colorOf(pref.metric), 9.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleSmall, color = p.ink)
                Text(categoryOf(pref.metric).label + (pref.preferredSource?.let { " · ${providerName(it)}" } ?: "") +
                    if (pref.inRings) " · in de ringen" else "",
                    style = MaterialTheme.typography.labelSmall, color = if (pref.inRings) p.primary else p.muted)
            }
            IconButton(onClick = onToggle) {
                Icon(if (expanded) VIcons.ChevronUp else VIcons.ChevronDown, contentDescription = "Instellingen ${m.name}", tint = p.muted)
            }
            // In de ringen: geen tegel, dus geen schakelaar
            if (!pref.inRings) Switch(
                checked = pref.visible, onCheckedChange = { vm.setVisible(pref.metric, it) }, enabled = !vm.busy,
                colors = SwitchDefaults.colors(checkedTrackColor = p.primary, checkedThumbColor = p.onPrimary),
                modifier = Modifier.semantics { contentDescription = "${m.name} als tegel tonen" },
            )
        }
        if (!expanded) return@VCard

        if (pref.visible && !pref.inRings) {
            Text("Volgorde en grootte", style = MaterialTheme.typography.labelMedium, color = p.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Omhoog", enabled = !vm.busy) { vm.move(pref.metric, -1) }
                ActionChip("Omlaag", enabled = !vm.busy) { vm.move(pref.metric, 1) }
            }
            Segmented(listOf("s" to "Klein", "l" to "Breed"), if (pref.size == "l") "l" else "s") { vm.save(pref.copy(size = it)) }
        }
        if (pref.inRings) Text("Deze metric staat in de dagringen. Haal hem daar weg om hem weer als tegel te tonen.",
            style = MaterialTheme.typography.bodySmall, color = p.muted)
        Text("Bron", style = MaterialTheme.typography.labelMedium, color = p.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Automatisch", "garmin" to "Garmin", "google_health" to "Google Health", "health_connect" to "Health Connect")
                .forEach { (src, label) -> Pill(label, pref.preferredSource == src) { vm.save(pref.copy(preferredSource = src)) } }
        }
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text(if (m.lowerIsBetter) "Streefwaarde" else "Dagdoel") },
            suffix = { Text(if (pref.metric == "sleep") "uur" else m.unit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val g = goal.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
                if (g == null && pref.inRings) vm.message = "Een dagring heeft een doel nodig. Haal hem eerst uit de ringen."
                else vm.save(pref.copy(goal = g))
            }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun fmtGoal(g: Double): String =
    if (g % 1.0 == 0.0) g.toLong().toString() else g.toString().replace('.', ',')
