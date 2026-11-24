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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset

/**
 * Profiel, bereikbaar via het persoon-icoon op Vandaag.
 * Twee tabbladen: Profiel (persoonlijke gegevens, weergave, account) en Koppelingen (gekoppeld en niet gekoppeld).
 */
@Composable
fun ProfileScreen(
    vm: MainViewModel,
    onRequestHc: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onShare: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val p = LocalPalette.current
    var disconnecting by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showSettings = false }) { Icon(VIcons.Back, contentDescription = "Terug", tint = p.ink) }
                Text("Profiel", style = MaterialTheme.typography.headlineSmall, color = p.ink)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                Pill("Profiel", vm.profileTab == ProfileTab.Profile, Modifier.weight(1f)) { vm.profileTab = ProfileTab.Profile }
                Pill("Koppelingen", vm.profileTab == ProfileTab.Connections, Modifier.weight(1f)) { vm.profileTab = ProfileTab.Connections }
            }
        }
        when (vm.profileTab) {
            ProfileTab.Profile -> {
                item { PersonalCard(vm) }
                item { SectionTitle("Weergave", Modifier.padding(start = 4.dp, top = 12.dp)) }
                item { ThemeCard(vm) }
                item { SectionTitle("Account", Modifier.padding(start = 4.dp, top = 12.dp)) }
                item { AccountCard(vm, onOpenUrl, onShare, onSignOut) }
                item {
                    Text("$APP_NAME ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = p.muted,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
            }
            ProfileTab.Connections -> {
                val hcOn = vm.hcStatus == HcStatus.OK || vm.hcStatus == HcStatus.FOREGROUND_ONLY
                val sources = listOf("health_connect", "google_health", "garmin")
                val on = sources.filter { if (it == "health_connect") hcOn else vm.isConnected(it) }
                val off = sources - on.toSet()
                item {
                    Text("Kies welke bronnen je data leveren. Per metric stel je de voorkeursbron in onder Vandaag → Aanpassen.",
                        style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp))
                }
                item { SectionTitle("Gekoppeld · ${on.size}", Modifier.padding(start = 4.dp, top = 8.dp), dot = p.good) }
                if (on.isEmpty()) item {
                    Text("Nog niets gekoppeld. Kies hieronder een bron om je dashboard te vullen.",
                        style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp))
                }
                on.forEach { s -> item(key = "on-$s") { SourceCard(vm, s, onRequestHc, onOpenUrl) { disconnecting = it } } }
                if (off.isNotEmpty()) item { SectionTitle("Niet gekoppeld · ${off.size}", Modifier.padding(start = 4.dp, top = 12.dp), dot = p.muted) }
                off.forEach { s -> item(key = "off-$s") { SourceCard(vm, s, onRequestHc, onOpenUrl) { disconnecting = it } } }
            }
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
}

// ---------- Profiel ----------

private fun parseNumber(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

private fun fmtNumber(v: Double?): String =
    v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else String.format(NL, "%.1f", it) }.orEmpty()

/**
 * Persoonlijke gegevens invullen. Ook gebruikt in de kennismaking na het eerste inloggen:
 * dan met voorgestelde waarden, een eigen knoptekst en een knop om over te slaan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalCard(
    vm: MainViewModel,
    saved: Profile = vm.profile,
    saveLabel: String = "Opslaan",
    requireChange: Boolean = true,
    secondary: (@Composable () -> Unit)? = null,
    onSave: (Profile) -> Unit = { vm.saveProfile(it) },
) {
    val p = LocalPalette.current
    // rememberSaveable: ingevulde waarden blijven staan bij draaien, in- en uitvouwen van een foldable
    var name by rememberSaveable(saved) { mutableStateOf(saved.name) }
    var birth by rememberSaveable(saved) { mutableStateOf(saved.birthDate) }
    var sex by rememberSaveable(saved) { mutableStateOf(saved.sex) }
    var height by rememberSaveable(saved) { mutableStateOf(fmtNumber(saved.heightCm)) }
    var weight by rememberSaveable(saved) { mutableStateOf(fmtNumber(saved.weightKg)) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    val h = parseNumber(height)
    val w = parseNumber(weight)
    val heightOk = height.isBlank() || (h != null && h in 50.0..260.0)
    val weightOk = weight.isBlank() || (w != null && w in 20.0..400.0)
    val edited = Profile(saved.email, name, birth, sex, h, w)
    val changed = !requireChange || edited.copy(name = edited.name.trim()) != saved

    VCard(Modifier.fillMaxWidth()) {
        Text("Persoonlijke gegevens", style = MaterialTheme.typography.titleMedium, color = p.ink)
        if (saved.email.isNotEmpty()) Text(saved.email, style = MaterialTheme.typography.bodySmall, color = p.muted)
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(60) }, label = { Text("Naam") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                birth?.let { "Geboortedatum: ${fmtDate(it)} (${Period.between(it, LocalDate.now()).years} jaar)" }
                    ?: "Geboortedatum kiezen",
                modifier = Modifier.weight(1f),
            )
        }
        Text("Geslacht", style = MaterialTheme.typography.labelMedium, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("female" to "Vrouw", "male" to "Man", "other" to "Anders").forEach { (id, label) ->
                Pill(label, sex == id, Modifier.weight(1f)) { sex = if (sex == id) null else id }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = height, onValueChange = { height = it.take(5) }, label = { Text("Lengte (cm)") }, singleLine = true,
                isError = !heightOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = weight, onValueChange = { weight = it.take(5) }, label = { Text("Gewicht (kg)") }, singleLine = true,
                isError = !weightOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
        }
        if (!heightOk || !weightOk) Text("Vul een lengte tussen 50 en 260 cm en een gewicht tussen 20 en 400 kg in.",
            style = MaterialTheme.typography.bodySmall, color = p.bad)
        Text("Alle velden zijn optioneel. Alleen jij ziet deze gegevens.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(edited) }, enabled = changed && heightOk && weightOk && !vm.busy) { Text(saveLabel) }
            secondary?.invoke()
        }
    }

    if (pickDate) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (birth ?: today.minusYears(35)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1900..today.year,
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (!d.isAfter(today)) birth = d
                    }
                    pickDate = false
                }) { Text("Kiezen") }
            },
            dismissButton = {
                TextButton(onClick = { birth = null; pickDate = false }) { Text("Wissen") }
            },
        ) { DatePicker(state) }
    }
}

@Composable
private fun ThemeCard(vm: MainViewModel) {
    val p = LocalPalette.current
    VCard(Modifier.fillMaxWidth()) {
        Text("Kies licht of donker, of laat het je telefoon volgen.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                Pill(m.label, vm.themeMode == m, Modifier.weight(1f)) { vm.setTheme(m) }
            }
        }
    }
}

@Composable
private fun AccountCard(vm: MainViewModel, onOpenUrl: (String) -> Unit, onShare: (String) -> Unit, onSignOut: () -> Unit) {
    val p = LocalPalette.current
    var confirmDelete by remember { mutableStateOf(false) }
    VCard(Modifier.fillMaxWidth()) {
        Text("Je data is van jou. Je kunt alles exporteren of je account met alle data verwijderen.",
            style = MaterialTheme.typography.bodySmall, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.exportData(onShare) }, enabled = !vm.busy) { Text("Mijn data exporteren") }
            OutlinedButton(onClick = onSignOut) { Text("Uitloggen") }
        }
        TextButton(onClick = { onOpenUrl(PRIVACY_URL) }) { Text("Privacyverklaring") }
        TextButton(onClick = { confirmDelete = true }) { Text("Account verwijderen", color = p.bad) }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Account verwijderen?") },
        text = { Text("Je account, je profiel, je dashboard en alle opgeslagen gezondheidsdata worden definitief verwijderd. Koppelingen worden ingetrokken.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAccount() }) { Text("Verwijderen", color = p.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
    )
}

// ---------- Koppelingen ----------

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
fun SourceCard(vm: MainViewModel, source: String, onRequestHc: () -> Unit, onOpenUrl: (String) -> Unit, onDisconnect: (String) -> Unit) {
    if (source == "health_connect") HealthConnectCard(vm, onRequestHc) else CloudCard(vm, source, onOpenUrl, onDisconnect)
}

/** "Bijgewerkt om 14:05" of "Bijgewerkt op 3 nov". */
private fun syncedText(iso: String?): String? = iso?.let {
    try {
        val t = Instant.parse(it).atZone(java.time.ZoneId.systemDefault())
        if (t.toLocalDate() == LocalDate.now()) "bijgewerkt om %02d:%02d".format(t.hour, t.minute)
        else "bijgewerkt op ${fmtDate(t.toLocalDate())}"
    } catch (_: Exception) { null }
}

@Composable
private fun HealthConnectCard(vm: MainViewModel, onRequestHc: () -> Unit) {
    val p = LocalPalette.current
    val ok = vm.hcStatus == HcStatus.OK || vm.hcStatus == HcStatus.FOREGROUND_ONLY
    val synced = vm.lastSync.takeIf { it > 0 }?.let { syncedText(Instant.ofEpochMilli(it).toString()) }
    val status = when (vm.hcStatus) {
        HcStatus.UNKNOWN -> "Status wordt gecontroleerd…"
        HcStatus.UNAVAILABLE -> "Niet beschikbaar op dit toestel"
        HcStatus.UPDATE_REQUIRED -> "Installeer of update Health Connect"
        HcStatus.NO_PERMISSION -> "Nog geen toegang gegeven"
        HcStatus.FOREGROUND_ONLY -> "Alleen als de app open is" + (synced?.let { " · $it" } ?: "")
        HcStatus.OK -> "Elk uur op de achtergrond" + (synced?.let { " · $it" } ?: "")
    }
    VCard(Modifier.fillMaxWidth()) {
        SourceHeader("H", Color(0xFF2F7D6E), "Health Connect", status, ok)
        Text("Data van apps op deze telefoon, zoals Google Fit, Samsung Health en de Suunto-app.",
            style = MaterialTheme.typography.bodySmall, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (vm.hcStatus) {
                HcStatus.UPDATE_REQUIRED, HcStatus.NO_PERMISSION ->
                    Button(onClick = onRequestHc) { Text(if (vm.hcStatus == HcStatus.UPDATE_REQUIRED) "Installeren" else "Koppelen") }
                HcStatus.FOREGROUND_ONLY -> OutlinedButton(onClick = onRequestHc) { Text("Achtergrond toestaan") }
                else -> Unit
            }
            if (ok) Button(onClick = vm::syncNow, enabled = !vm.busy) { Text("Nu synchroniseren") }
        }
        if (ok) Text("Ontkoppelen doe je in de Health Connect-instellingen van je telefoon.",
            style = MaterialTheme.typography.bodySmall, color = p.muted)
    }
}

@Composable
private fun CloudCard(vm: MainViewModel, provider: String, onOpenUrl: (String) -> Unit, onDisconnect: (String) -> Unit) {
    val p = LocalPalette.current
    val c = vm.connections[provider]
    val connected = vm.isConnected(provider)
    val synced = syncedText(c?.lastSync)
    val status = when {
        c == null || c.status == "revoked" -> "Niet gekoppeld"
        c.status == "error" -> "Werkt niet meer · koppel opnieuw"
        provider == "garmin" -> "Na elke sync van je horloge" + (synced?.let { " · $it" } ?: "")
        else -> "Elk uur opgehaald" + (synced?.let { " · $it" } ?: "")
    }
    VCard(Modifier.fillMaxWidth()) {
        SourceHeader(if (provider == "garmin") "G" else "F", if (provider == "garmin") Color(0xFF2B5FA8) else Color(0xFF3F7F4A),
            providerName(provider), status, connected)
        Text(if (provider == "garmin") "Data van je Garmin-horloge via Garmin Connect." else "Data van Fitbit, Pixel Watch en de Google Health-app.",
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
