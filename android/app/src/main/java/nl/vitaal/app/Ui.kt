package nl.vitaal.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val NL: Locale = Locale.forLanguageTag("nl-NL")
private val Coral = Color(0xFFE8492A)

@Composable
fun VitaalTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFFF6A45))
    else lightColorScheme(primary = Coral)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Kleur per categorie, zoals in het prototype. */
fun metricColor(id: String): Color = when (id) {
    "steps", "active", "kcal", "dist", "floors" -> Color(0xFFE8492A)
    "rhr", "hrv", "spo2" -> Color(0xFFD81B60)
    "sleep", "sscore", "resp" -> Color(0xFF5248D9)
    "stress", "energy", "recov" -> Color(0xFFB87800)
    else -> Color(0xFF0B8577)
}

fun fmt(metric: String, v: Double): String = when (metric) {
    "sleep" -> {
        var h = v.toInt(); var m = ((v - h) * 60).roundToInt()
        if (m == 60) { h++; m = 0 }
        "${h}u ${m.toString().padStart(2, '0')}m"
    }
    "dist", "weight", "resp" -> String.format(NL, "%.1f", v)
    else -> NumberFormat.getIntegerInstance(NL).format(v.roundToLong())
}

private val APPS = mapOf(
    "com.garmin.android.apps.connectmobile" to "Garmin Connect",
    "com.google.android.apps.fitness" to "Google Fit",
    "com.sec.android.app.shealth" to "Samsung Health",
    "com.fitbit.FitbitMobile" to "Fitbit",
    "com.stt.android.suunto" to "Suunto",
)

fun sourceLabel(source: String): String = when {
    source == "garmin" -> "Garmin Connect"
    source == "google_health" -> "Google Health"
    source.startsWith("health_connect:") -> source.removePrefix("health_connect:")
        .let { "Health Connect · " + (APPS[it] ?: it) }
    else -> source
}

@Composable
fun VitaalRoot(
    vm: MainViewModel,
    onRequestHc: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onShare: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                vm.authStep is AuthStep.NewPassword -> NewPasswordScreen(vm)
                vm.authStep is AuthStep.Code -> CodeScreen(vm, vm.authStep as AuthStep.Code)
                !vm.signedIn -> LoginScreen(vm, onGoogleSignIn)
                vm.needsConsent -> ConsentScreen(vm, onSignOut)
                vm.editing -> EditScreen(vm)
                else -> HomeScreen(vm, onRequestHc, onOpenUrl, onShare, onSignOut)
            }
        }
    }
}

// ---------------- Inloggen ----------------
@Composable
private fun LoginScreen(vm: MainViewModel, onGoogleSignIn: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("Vitaal", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Je gezondheidsdata uit Garmin Connect en Health Connect op één plek.")
        OutlinedTextField(email, { email = it }, label = { Text("E-mailadres") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Wachtwoord (min. 8 tekens)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        val emailOk = email.contains("@") && !vm.busy
        val ok = emailOk && password.length >= 8
        Button(onClick = { vm.signIn(email, password) }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Inloggen") }
        OutlinedButton(onClick = { vm.signUp(email, password) }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Account maken") }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { vm.requestLoginCode(email) }, enabled = emailOk) { Text("Inloggen met e-mailcode") }
            TextButton(onClick = { vm.requestPasswordReset(email) }, enabled = emailOk) { Text("Wachtwoord vergeten?") }
        }
        Text("of", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        OutlinedButton(onClick = onGoogleSignIn, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Inloggen met Google") }
    }
}

// ---------------- Code uit de e-mail ----------------
@Composable
private fun CodeScreen(vm: MainViewModel, step: AuthStep.Code) {
    var code by rememberSaveable(step) { mutableStateOf("") }
    val title = when (step.type) {
        "signup" -> "Bevestig je e-mailadres"
        "recovery" -> "Nieuw wachtwoord"
        else -> "Inloggen met code"
    }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("We hebben een code van 6 cijfers gestuurd naar ${step.email}. Vul die hieronder in.")
        OutlinedTextField(code, { v -> code = v.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.length == 6) vm.verifyCode(code) }),
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.verifyCode(code) }, enabled = code.length == 6 && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Bevestigen") }
        Row {
            TextButton(onClick = vm::resendCode, enabled = !vm.busy) { Text("Code opnieuw sturen") }
            TextButton(onClick = vm::backToLogin) { Text("Terug") }
        }
    }
}

@Composable
private fun NewPasswordScreen(vm: MainViewModel) {
    var pw by rememberSaveable { mutableStateOf("") }
    var pw2 by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("Kies een nieuw wachtwoord", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(pw, { pw = it }, label = { Text("Nieuw wachtwoord (min. 8 tekens)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw2, { pw2 = it }, label = { Text("Herhaal wachtwoord") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = pw2.isNotEmpty() && pw2 != pw,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.setNewPassword(pw) }, enabled = pw.length >= 8 && pw == pw2 && !vm.busy,
            modifier = Modifier.fillMaxWidth()) { Text("Opslaan") }
    }
}

// ---------------- AVG-toestemming ----------------
@Composable
private fun ConsentScreen(vm: MainViewModel, onSignOut: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Toestemming", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Vitaal verwerkt gezondheidsgegevens: stappen, hartslag, slaap, gewicht en vergelijkbare metingen. Dat zijn bijzondere persoonsgegevens volgens de AVG.")
        Text("We gebruiken ze alleen om jouw dashboard te tonen. Ze staan op servers in de EU, worden niet gedeeld of verkocht, en je kunt je account met alle data op elk moment verwijderen.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(agreed, { agreed = it })
            Text("Ik geef uitdrukkelijk toestemming voor het verwerken van mijn gezondheidsgegevens.")
        }
        Button(onClick = vm::giveConsent, enabled = agreed && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Doorgaan") }
        TextButton(onClick = onSignOut) { Text("Niet nu, uitloggen") }
    }
}

// ---------------- Dashboard ----------------
@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onRequestHc: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onShare: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf<String?>(null) }
    val rows = remember(vm.prefs) {
        // Brede tegels krijgen een eigen rij, kleine tegels gaan per twee
        val out = mutableListOf<List<Pref>>(); var pair = mutableListOf<Pref>()
        for (p in vm.visible) {
            if (p.size == "l") { if (pair.isNotEmpty()) { out += pair; pair = mutableListOf() }; out += listOf(p) }
            else { pair += p; if (pair.size == 2) { out += pair; pair = mutableListOf() } }
        }
        if (pair.isNotEmpty()) out += pair
        out.toList()
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", NL)),
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Vandaag", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { vm.editing = true }) { Text("Aanpassen") }
            }
        }
        item { RingsCard(vm) }
        if (rows.isEmpty()) item { Text("Je dashboard is leeg. Kies metrics via Aanpassen.") }
        items(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p -> Tile(vm, p, Modifier.weight(1f)) }
                if (row.size == 1 && row[0].size != "l") Spacer(Modifier.weight(1f))
            }
        }
        item { Text("Koppelingen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
        item { HealthConnectCard(vm, onRequestHc) }
        item { CloudCard(vm, "google_health", onOpenUrl) { disconnecting = it } }
        item { CloudCard(vm, "garmin", onOpenUrl) { disconnecting = it } }
        item { Text("Account", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSignOut) { Text("Uitloggen") }
                    OutlinedButton(onClick = { vm.exportData(onShare) }, enabled = !vm.busy) { Text("Mijn data exporteren") }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Account verwijderen") }
            }
        }
    }

    disconnecting?.let { provider ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text("${providerName(provider)} ontkoppelen?") },
            text = { Text("Vitaal haalt geen nieuwe data meer op en trekt de toestemming in. Wil je de al opgehaalde data ook verwijderen?") },
            confirmButton = { TextButton(onClick = { disconnecting = null; vm.disconnect(provider, deleteData = true) }) { Text("Ontkoppelen en data wissen") } },
            dismissButton = { TextButton(onClick = { disconnecting = null; vm.disconnect(provider, deleteData = false) }) { Text("Alleen ontkoppelen") } },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Account verwijderen?") },
        text = { Text("Je account, je dashboard en alle opgeslagen gezondheidsdata worden definitief verwijderd. Garmin wordt ook ontkoppeld.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAccount() }) { Text("Verwijderen") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
    )
}

@Composable
private fun Tile(vm: MainViewModel, p: Pref, modifier: Modifier) {
    val m = vm.metrics[p.metric] ?: return
    val v = vm.today[p.metric]
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(metricColor(p.metric), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(m.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            if (v == null) {
                Text("Nog geen data vandaag", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(fmt(p.metric, v.value) + if (m.unit.isNotEmpty() && p.metric != "sleep") " ${m.unit}" else "",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                p.goal?.let { g ->
                    val f = (if (m.lowerIsBetter) g / v.value else v.value / g).coerceIn(0.0, 1.0).toFloat()
                    LinearProgressIndicator(progress = { f }, color = metricColor(p.metric), modifier = Modifier.fillMaxWidth())
                }
                Text(sourceLabel(v.source), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RingsCard(vm: MainViewModel) {
    val rings = vm.visible.filter { it.inRings && it.goal != null }.take(3)
        .map { p -> Triple(p, vm.metrics[p.metric], ((vm.today[p.metric]?.value ?: 0.0) / p.goal!!).coerceIn(0.0, 1.0).toFloat()) }
    if (rings.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(132.dp)) {
                val w = 14.dp.toPx()
                rings.forEachIndexed { i, (p, _, f) ->
                    val inset = w / 2 + i * (w + 6.dp.toPx())
                    val tl = Offset(inset, inset)
                    val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
                    val c = metricColor(p.metric)
                    drawArc(c.copy(alpha = 0.2f), -90f, 360f, false, tl, sz, style = Stroke(w))
                    if (f > 0f) drawArc(c, -90f, 360f * f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rings.forEach { (p, m, f) ->
                    Text(m?.name ?: p.metric, style = MaterialTheme.typography.labelLarge, color = metricColor(p.metric))
                    Text("${(f * 100).roundToInt()}% van ${fmt(p.metric, p.goal!!)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun HealthConnectCard(vm: MainViewModel, onRequestHc: () -> Unit) {
    val status = when (vm.hcStatus) {
        HcStatus.UNKNOWN -> "Status wordt gecontroleerd…"
        HcStatus.UNAVAILABLE -> "Health Connect is niet beschikbaar op dit toestel."
        HcStatus.UPDATE_REQUIRED -> "Installeer of update Health Connect via de Play Store."
        HcStatus.NO_PERMISSION -> "Nog geen toegang gegeven."
        HcStatus.FOREGROUND_ONLY -> "Gekoppeld. Synchroniseert alleen als de app open is."
        HcStatus.OK -> "Gekoppeld. Synchroniseert elk uur op de achtergrond."
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (vm.lastSync > 0) Text("Laatst gesynchroniseerd: " +
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, NL).format(java.util.Date(vm.lastSync)),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (vm.hcStatus) {
                    HcStatus.UPDATE_REQUIRED, HcStatus.NO_PERMISSION, HcStatus.FOREGROUND_ONLY ->
                        Button(onClick = onRequestHc) { Text(if (vm.hcStatus == HcStatus.UPDATE_REQUIRED) "Installeren" else "Toegang geven") }
                    else -> Unit
                }
                if (vm.hcStatus == HcStatus.OK || vm.hcStatus == HcStatus.FOREGROUND_ONLY)
                    OutlinedButton(onClick = vm::syncNow, enabled = !vm.busy) { Text("Nu synchroniseren") }
            }
        }
    }
}

private fun providerName(p: String) = if (p == "garmin") "Garmin Connect" else "Google Health"

@Composable
private fun CloudCard(vm: MainViewModel, provider: String, onOpenUrl: (String) -> Unit, onDisconnect: (String) -> Unit) {
    val c = vm.connections[provider]
    val status = when {
        c == null || c.status == "revoked" -> if (provider == "garmin") "Niet gekoppeld." else "Niet gekoppeld. Voor data van Fitbit, Pixel Watch en Google Health."
        c.status == "error" -> "Koppeling werkt niet meer. Koppel opnieuw."
        provider == "garmin" -> "Gekoppeld. Garmin stuurt nieuwe data na elke synchronisatie van je horloge."
        else -> "Gekoppeld. Wordt elk uur opgehaald."
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(providerName(provider), style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            c?.takeIf { it.status != "revoked" }?.lastError?.let {
                Text("Laatste fout: ${it.take(140)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.isConnected(provider)) {
                    if (provider == "google_health") Button(onClick = vm::googleHealthSyncNow, enabled = !vm.busy) { Text("Nu ophalen") }
                    OutlinedButton(onClick = { onDisconnect(provider) }, enabled = !vm.busy) { Text("Ontkoppelen") }
                } else {
                    Button(onClick = { vm.connect(provider, onOpenUrl) }, enabled = !vm.busy) { Text(if (c?.status == "error") "Opnieuw koppelen" else "Koppelen") }
                }
            }
        }
    }
}

// ---------------- Aanpassen ----------------
@Composable
private fun EditScreen(vm: MainViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dashboard aanpassen", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = { vm.editing = false }) { Text("Klaar") }
            }
            Text("Zet metrics aan of uit en kies per metric bron, grootte en dagdoel. Maximaal drie doelen komen in de ringen.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        items(vm.allForEdit, key = { it.metric }) { p -> EditRow(vm, p) }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(text) }
    else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(text) }
}

@Composable
private fun EditRow(vm: MainViewModel, p: Pref) {
    val m = vm.metrics[p.metric] ?: return
    var goal by remember(p.goal) { mutableStateOf(p.goal?.let { fmtGoal(it) } ?: "") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(metricColor(p.metric), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = p.visible, onCheckedChange = { vm.setVisible(p.metric, it) }, enabled = !vm.busy)
            }
            if (!p.visible) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.move(p.metric, -1) }) { Text("Omhoog") }
                OutlinedButton(onClick = { vm.move(p.metric, 1) }) { Text("Omlaag") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Klein", p.size != "l") { vm.save(p.copy(size = "s")) }
                Choice("Breed", p.size == "l") { vm.save(p.copy(size = "l")) }
            }
            Text("Bron", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Automatisch", p.preferredSource == null) { vm.save(p.copy(preferredSource = null)) }
                Choice("Garmin", p.preferredSource == "garmin") { vm.save(p.copy(preferredSource = "garmin")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Google Health", p.preferredSource == "google_health") { vm.save(p.copy(preferredSource = "google_health")) }
                Choice("Health Connect", p.preferredSource == "health_connect") { vm.save(p.copy(preferredSource = "health_connect")) }
            }
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                label = { Text(if (m.lowerIsBetter) "Streefwaarde" else "Dagdoel") },
                suffix = { Text(if (p.metric == "sleep") "uur" else m.unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val g = goal.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
                    vm.save(p.copy(goal = g, inRings = p.inRings && g != null))
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!m.lowerIsBetter && p.goal != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(p.inRings, { on ->
                    if (on && vm.visible.count { it.inRings } >= 3) vm.message = "Er passen drie doelen in de ringen. Zet er eerst één uit."
                    else vm.save(p.copy(inRings = on))
                })
                Text("Tonen in dagringen")
            }
        }
    }
}

private fun fmtGoal(g: Double): String =
    if (g % 1.0 == 0.0) g.toLong().toString() else g.toString().replace('.', ',')
