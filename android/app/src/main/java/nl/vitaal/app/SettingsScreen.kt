package nl.vitaal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Koppelingen en account, bereikbaar via het persoon-icoon in de wijzerplaat. */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onRequestHc: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onShare: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val p = LocalPalette.current
    var confirmDelete by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showSettings = false }) { Icon(VIcons.Back, contentDescription = "Terug", tint = p.ink) }
                Text("Koppelingen en account", style = MaterialTheme.typography.headlineSmall, color = p.ink)
            }
        }
        item {
            Text("Kies welke bronnen je data leveren. Per metric stel je de voorkeursbron in onder Vandaag → Aanpassen.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        item { HealthConnectCard(vm, onRequestHc) }
        item { CloudCard(vm, "google_health", onOpenUrl) { disconnecting = it } }
        item { CloudCard(vm, "garmin", onOpenUrl) { disconnecting = it } }

        item { SectionTitle("Account", Modifier.padding(start = 4.dp, top = 12.dp)) }
        item {
            VCard(Modifier.fillMaxWidth()) {
                Text("Je data is van jou. Je kunt alles exporteren of je account met alle data verwijderen.",
                    style = MaterialTheme.typography.bodySmall, color = p.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.exportData(onShare) }, enabled = !vm.busy) { Text("Mijn data exporteren") }
                    OutlinedButton(onClick = onSignOut) { Text("Uitloggen") }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Account verwijderen", color = p.bad) }
            }
        }
        item {
            Text("$APP_NAME ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = p.muted,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }
    }

    disconnecting?.let { provider ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text("${providerName(provider)} ontkoppelen?") },
            text = { Text("$APP_NAME haalt geen nieuwe data meer op en trekt de toestemming in. Wil je de al opgehaalde data ook verwijderen?") },
            confirmButton = { TextButton(onClick = { disconnecting = null; vm.disconnect(provider, deleteData = true) }) { Text("Ontkoppelen en wissen") } },
            dismissButton = { TextButton(onClick = { disconnecting = null; vm.disconnect(provider, deleteData = false) }) { Text("Alleen ontkoppelen") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Account verwijderen?") },
        text = { Text("Je account, je dashboard en alle opgeslagen gezondheidsdata worden definitief verwijderd. Koppelingen worden ingetrokken.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAccount() }) { Text("Verwijderen", color = p.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
    )
}

/** Gekleurd blokje met een letter, als herkenning per bron. */
@Composable
private fun Mark(letter: String, color: Color) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun SourceHeader(letter: String, color: Color, name: String, status: String, ok: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mark(letter, color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = p.ink)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (ok) p.good else p.muted)
        }
    }
}

@Composable
private fun HealthConnectCard(vm: MainViewModel, onRequestHc: () -> Unit) {
    val status = when (vm.hcStatus) {
        HcStatus.UNKNOWN -> "Status wordt gecontroleerd…"
        HcStatus.UNAVAILABLE -> "Niet beschikbaar op dit toestel"
        HcStatus.UPDATE_REQUIRED -> "Installeer of update Health Connect"
        HcStatus.NO_PERMISSION -> "Nog geen toegang gegeven"
        HcStatus.FOREGROUND_ONLY -> "Gekoppeld · alleen als de app open is"
        HcStatus.OK -> "Gekoppeld · elk uur op de achtergrond"
    }
    val ok = vm.hcStatus == HcStatus.OK || vm.hcStatus == HcStatus.FOREGROUND_ONLY
    VCard(Modifier.fillMaxWidth()) {
        SourceHeader("H", Color(0xFF2F7D6E), "Health Connect", status, ok)
        Text("Data van apps op deze telefoon, zoals Google Fit, Samsung Health en de Suunto-app.",
            style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (vm.hcStatus) {
                HcStatus.UPDATE_REQUIRED, HcStatus.NO_PERMISSION, HcStatus.FOREGROUND_ONLY ->
                    Button(onClick = onRequestHc) { Text(if (vm.hcStatus == HcStatus.UPDATE_REQUIRED) "Installeren" else "Toegang geven") }
                else -> Unit
            }
            if (ok) OutlinedButton(onClick = vm::syncNow, enabled = !vm.busy) { Text("Nu synchroniseren") }
        }
    }
}

@Composable
private fun CloudCard(vm: MainViewModel, provider: String, onOpenUrl: (String) -> Unit, onDisconnect: (String) -> Unit) {
    val p = LocalPalette.current
    val c = vm.connections[provider]
    val connected = vm.isConnected(provider)
    val status = when {
        c == null || c.status == "revoked" -> "Niet gekoppeld"
        c.status == "error" -> "Werkt niet meer · koppel opnieuw"
        provider == "garmin" -> "Gekoppeld · data na elke sync van je horloge"
        else -> "Gekoppeld · elk uur opgehaald"
    }
    VCard(Modifier.fillMaxWidth()) {
        SourceHeader(if (provider == "garmin") "G" else "F", if (provider == "garmin") Color(0xFF2B5FA8) else Color(0xFF3F7F4A),
            providerName(provider), status, connected)
        if (provider == "google_health") Text("Data van Fitbit, Pixel Watch en de Google Health-app.",
            style = MaterialTheme.typography.bodySmall, color = p.muted)
        c?.takeIf { it.status != "revoked" }?.lastError?.let {
            Text("Laatste fout: ${it.take(140)}", style = MaterialTheme.typography.bodySmall, color = p.bad)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) {
                if (provider == "google_health") Button(onClick = vm::googleHealthSyncNow, enabled = !vm.busy) { Text("Nu ophalen") }
                OutlinedButton(onClick = { onDisconnect(provider) }, enabled = !vm.busy) { Text("Ontkoppelen") }
            } else {
                Button(onClick = { vm.connect(provider, onOpenUrl) }, enabled = !vm.busy) {
                    Text(if (c?.status == "error") "Opnieuw koppelen" else "Koppelen")
                }
            }
        }
    }
}
