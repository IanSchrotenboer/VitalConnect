package nl.vitaal.app

import androidx.compose.foundation.background
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
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
    onRequestNotifications: () -> Unit,
) {
    val p = LocalPalette.current
    var disconnecting by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
    VTopBar("Profiel", onBack = { vm.showSettings = false })
    // imePadding: invulvelden blijven boven het toetsenbord
    LazyColumn(Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Segmented(listOf(ProfileTab.Profile to "Profiel", ProfileTab.Connections to "Koppelingen"), vm.profileTab,
                Modifier.padding(horizontal = 4.dp)) { vm.profileTab = it }
        }
        when (vm.profileTab) {
            ProfileTab.Profile -> {
                item { PersonalCard(vm) }
                item { SectionTitle("Weergave", Modifier.padding(start = 4.dp, top = 12.dp)) }
                item { ThemeCard(vm) }
                item { SectionTitle("Meldingen", Modifier.padding(start = 4.dp, top = 12.dp)) }
                item { NotificationsCard(vm, onRequestNotifications) }
                item { SectionTitle("Account", Modifier.padding(start = 4.dp, top = 12.dp)) }
                item { AccountCard(vm, onOpenUrl, onShare, onSignOut) }
                item {
                    Text("$APP_NAME ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = p.muted,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
            }
            ProfileTab.Connections -> {
                val hcOn = vm.hcStatus == HcStatus.OK || vm.hcStatus == HcStatus.FOREGROUND_ONLY
                val sources = KNOWN_SOURCES
                val on = sources.filter { if (it == "health_connect") hcOn else vm.isConnected(it) }
                // Eerst wat je nu kunt koppelen, daarna wat binnenkort open gaat
                val off = (sources - on.toSet()).sortedBy { !vm.isAvailable(it) }
                item {
                    Text("Kies welke bronnen je data leveren. Per metric stel je de voorkeursbron in onder Vandaag → Aanpassen.",
                        style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp))
                }
                item { SyncCard(vm) }
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
        // Nog een keer tikken op de gekozen optie maakt het veld weer leeg
        Segmented(listOf("female" to "Vrouw", "male" to "Man", "other" to "Anders"), sex) { sex = if (sex == it) null else it }
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
        Segmented(ThemeMode.entries.map { it to it.label }, vm.themeMode) { vm.setTheme(it) }
    }
}

/** Hoe vaak alle bronnen op de achtergrond worden bijgewerkt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncCard(vm: MainViewModel) {
    val p = LocalPalette.current
    VCard(Modifier.fillMaxWidth()) {
        Text("Synchroniseren", style = MaterialTheme.typography.titleMedium, color = p.ink)
        Text("Hoe vaak $APP_NAME Health Connect, Google Health en Suunto bijwerkt, ook als de app dicht is. " +
            "Garmin stuurt nieuwe data zelf na elke sync van je horloge. Vaker kost iets meer batterij; Android kan een ronde soms iets uitstellen.",
            style = MaterialTheme.typography.bodySmall, color = p.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SYNC_CHOICES.forEach { (min, label) -> Pill(label, vm.syncMinutes == min) { vm.setSyncInterval(min) } }
        }
        Text("Nu: ${syncLabel(vm.syncMinutes)}.", style = MaterialTheme.typography.labelMedium, color = p.ink)
    }
}

/** Meldingen bij gehaalde doelen en de stille melding dat de app meedraait. */
@Composable
private fun NotificationsCard(vm: MainViewModel, onRequestNotifications: () -> Unit) {
    val p = LocalPalette.current
    VCard(Modifier.fillMaxWidth()) {
        SwitchRow("Doel gehaald", "Een melding als je een dagdoel haalt, zoals je stappendoel. Eén keer per doel per dag.", vm.notifyGoals) {
            if (vm.setNotify(goals = it)) onRequestNotifications()
        }
        SwitchRow("Statusmelding", "Een stille melding met wanneer je gegevens voor het laatst zijn bijgewerkt.",
            vm.notifyStatus && vm.syncMinutes != 0, enabled = vm.syncMinutes != 0) {
            if (vm.setNotify(status = it)) onRequestNotifications()
        }
        if (vm.syncMinutes == 0) Text("Zet synchroniseren op de achtergrond aan (Koppelingen) om deze melding te gebruiken.",
            style = MaterialTheme.typography.bodySmall, color = p.muted)
        if ((vm.notifyGoals || vm.notifyStatus) && !vm.notificationsAllowed) {
            Text("Meldingen staan uit voor $APP_NAME.", style = MaterialTheme.typography.bodySmall, color = p.bad)
            OutlinedButton(onClick = onRequestNotifications) { Text("Meldingen toestaan") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    // De hele rij is één schakelaar: TalkBack leest "Doel gehaald, schakelaar, aan" en de hele rij is aantikbaar
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) p.ink else p.muted)
            Text(text, style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
        HcStatus.OK -> "Bijwerken: ${syncLabel(vm.syncMinutes)}" + (synced?.let { " · $it" } ?: "")
    }
    VCard(Modifier.fillMaxWidth()) {
        SourceHeader("H", p.srcHealthConnect, "Health Connect", status, ok)
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

/** Hoe een cloudbron eruitziet in Koppelingen: letter, kleur en wat hij levert. */
private class SourceLook(val letter: String, val color: Color, val about: String)

@Composable
private fun sourceLook(provider: String): SourceLook {
    val p = LocalPalette.current
    return when (provider) {
        "garmin" -> SourceLook("G", p.srcGarmin, "Data van je Garmin-horloge via Garmin Connect.")
        "suunto" -> SourceLook("S", p.srcSuunto, "Stappen, slaap en workouts van je Suunto-horloge via de Suunto-app.")
        else -> SourceLook("F", p.srcGoogle, "Data van Fitbit, Pixel Watch en de Google Health-app.")
    }
}

@Composable
private fun CloudCard(vm: MainViewModel, provider: String, onOpenUrl: (String) -> Unit, onDisconnect: (String) -> Unit) {
    val p = LocalPalette.current
    val c = vm.connections[provider]
    val connected = vm.isConnected(provider)
    val synced = syncedText(c?.lastSync)
    var disclose by remember { mutableStateOf(false) }
    // Verplichte uitleg (Google API-beleid, Limited Use) vóórdat het toestemmingsscherm van de bron opent
    if (disclose) AlertDialog(
        onDismissRequest = { disclose = false },
        title = { Text("${providerName(provider)} koppelen") },
        text = {
            Text(
                "$APP_NAME leest uit ${providerName(provider)} je activiteit (stappen, afstand, calorieën, actieve minuten), " +
                    "hart en herstel (rusthartslag, HRV, zuurstof, ademhaling), slaap en lichaamsmetingen (gewicht, VO2max). " +
                    "We gebruiken deze gegevens alleen om jouw dashboard, grafieken en statistieken te tonen. " +
                    "We delen of verkopen ze niet en gebruiken ze niet voor advertenties. Je kunt de koppeling altijd intrekken en je gegevens verwijderen.",
            )
        },
        confirmButton = { TextButton(onClick = { disclose = false; vm.connect(provider, onOpenUrl) }) { Text("Doorgaan") } },
        dismissButton = { TextButton(onClick = { disclose = false }) { Text("Annuleren") } },
    )
    val available = vm.isAvailable(provider)
    val pull = (vm.sources[provider]?.sync ?: if (provider == "garmin") "push" else "pull") == "pull"
    val look = sourceLook(provider)
    val status = when {
        !available && !connected -> "Binnenkort beschikbaar"
        c == null || c.status == "revoked" -> "Niet gekoppeld"
        c.status == "error" -> "Werkt niet meer · koppel opnieuw"
        !pull -> "Na elke sync van je horloge" + (synced?.let { " · $it" } ?: "")
        else -> "Bijwerken: ${syncLabel(vm.syncMinutes)}" + (synced?.let { " · $it" } ?: "")
    }
    VCard(Modifier.fillMaxWidth()) {
        SourceHeader(look.letter, look.color, providerName(provider), status, connected)
        Text(look.about, style = MaterialTheme.typography.bodySmall, color = p.muted)
        if (!available && !connected) {
            Text("We wachten op toegang van ${providerName(provider)}. Zodra die er is, kun je hier koppelen.",
                style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        c?.takeIf { it.status != "revoked" }?.lastError?.let {
            Text("Laatste fout: ${it.take(140)}", style = MaterialTheme.typography.bodySmall, color = p.bad)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) {
                if (pull) Button(onClick = { vm.syncSourceNow(provider) }, enabled = !vm.busy) { Text("Nu ophalen") }
                OutlinedButton(onClick = { onDisconnect(provider) }, enabled = !vm.busy) { Text("Ontkoppelen") }
            } else if (available) {
                Button(onClick = { disclose = true }, enabled = !vm.busy) {
                    Text(if (c?.status == "error") "Opnieuw koppelen" else "Koppelen")
                }
            }
        }
    }
}
