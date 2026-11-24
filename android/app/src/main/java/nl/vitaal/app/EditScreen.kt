package nl.vitaal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Dashboard aanpassen: metrics aan/uit, volgorde, grootte, bron, doel en dagringen. */
@Composable
fun EditScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dashboard aanpassen", style = MaterialTheme.typography.headlineSmall, color = p.ink, modifier = Modifier.weight(1f))
                Button(onClick = { vm.editing = false }) { Text("Klaar") }
            }
            Text("Zet metrics aan of uit en tik op een metric voor bron, grootte en dagdoel. Maximaal drie doelen komen in de ringen.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(top = 6.dp))
        }
        items(vm.allForEdit, key = { it.metric }) { pref ->
            EditRow(vm, pref, open == pref.metric) { open = if (open == pref.metric) null else pref.metric }
        }
    }
}

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
                Text(categoryOf(pref.metric).label + (pref.preferredSource?.let { " · ${providerName(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
            IconButton(onClick = onToggle) {
                Icon(if (expanded) VIcons.ChevronUp else VIcons.ChevronDown, contentDescription = "Instellingen ${m.name}", tint = p.muted)
            }
            Switch(
                checked = pref.visible, onCheckedChange = { vm.setVisible(pref.metric, it) }, enabled = !vm.busy,
                colors = SwitchDefaults.colors(checkedTrackColor = p.primary, checkedThumbColor = p.onPrimary),
            )
        }
        if (!expanded) return@VCard

        if (pref.visible) {
            Text("Volgorde en grootte", style = MaterialTheme.typography.labelMedium, color = p.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Omhoog", false) { vm.move(pref.metric, -1) }
                Pill("Omlaag", false) { vm.move(pref.metric, 1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Klein", pref.size != "l") { vm.save(pref.copy(size = "s")) }
                Pill("Breed", pref.size == "l") { vm.save(pref.copy(size = "l")) }
            }
        }
        Text("Bron", style = MaterialTheme.typography.labelMedium, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Automatisch", pref.preferredSource == null) { vm.save(pref.copy(preferredSource = null)) }
            Pill("Garmin", pref.preferredSource == "garmin") { vm.save(pref.copy(preferredSource = "garmin")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Google Health", pref.preferredSource == "google_health") { vm.save(pref.copy(preferredSource = "google_health")) }
            Pill("Health Connect", pref.preferredSource == "health_connect") { vm.save(pref.copy(preferredSource = "health_connect")) }
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
                vm.save(pref.copy(goal = g, inRings = pref.inRings && g != null))
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!m.lowerIsBetter && pref.goal != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(pref.inRings, { on ->
                if (on && vm.visible.count { it.inRings } >= 3) vm.message = "Er passen drie doelen in de ringen. Zet er eerst één uit."
                else vm.save(pref.copy(inRings = on))
            })
            Text("Tonen in de dagringen", style = MaterialTheme.typography.bodyMedium, color = p.ink)
        }
    }
}

private fun fmtGoal(g: Double): String =
    if (g % 1.0 == 0.0) g.toLong().toString() else g.toString().replace('.', ',')
