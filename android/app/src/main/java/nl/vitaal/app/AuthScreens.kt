package nl.vitaal.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Donkere kop met de naam van de app, boven alle inlogschermen. step = (huidige, totaal) in de kennismaking. */
@Composable
private fun AuthHeader(title: String, subtitle: String, step: Pair<Int, Int>? = null) {
    val d = dialPalette()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(d.dial)
            .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(76.dp))
        Spacer(Modifier.height(4.dp))
        step?.let { (n, total) ->
            Text("Stap $n van $total", style = MaterialTheme.typography.labelMedium, color = d.dialMuted)
        }
        Text(title, style = MaterialTheme.typography.displaySmall, color = d.dialInk, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = d.dialMuted)
    }
}

@Composable
fun LoginScreen(vm: MainViewModel, onGoogleSignIn: () -> Unit, onOpenUrl: (String) -> Unit) {
    val p = LocalPalette.current
    var showEmail by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        AuthHeader(APP_NAME, "Je gezondheid op één plek: beweging, hart en slaap.")
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Log in met je Google-account en importeer direct je gezondheidsgegevens.",
                style = MaterialTheme.typography.titleMedium, color = p.ink)
            GoogleButton(onGoogleSignIn, enabled = !vm.busy)
            listOf(
                "Geen nieuw wachtwoord nodig",
                "Koppel Google Health, Health Connect of Garmin in de volgende stap",
                "Vul daarna je profiel aan; je naam staat er al in",
            ).forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(p.good, 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = p.muted)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!showEmail) {
                OutlinedButton(onClick = { showEmail = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Inloggen met e-mail")
                }
            } else {
                Text("Met je e-mailadres", style = MaterialTheme.typography.titleSmall, color = p.ink)
                OutlinedTextField(email, { email = it }, label = { Text("E-mailadres") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Wachtwoord (min. 8 tekens)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                val emailOk = email.contains("@") && !vm.busy
                val ok = emailOk && password.length >= 8
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.signIn(email, password) }, enabled = ok, modifier = Modifier.weight(1f)) { Text("Inloggen", maxLines = 1) }
                    OutlinedButton(onClick = { vm.signUp(email, password) }, enabled = ok, modifier = Modifier.weight(1f)) { Text("Account maken", maxLines = 1) }
                }
                TextButton(onClick = { vm.requestLoginCode(email) }, enabled = emailOk) { Text("Inloggen met een code per e-mail") }
                TextButton(onClick = { vm.requestPasswordReset(email) }, enabled = emailOk) { Text("Wachtwoord vergeten?") }
            }
            TextButton(onClick = { onOpenUrl(PRIVACY_URL) }) { Text("Privacyverklaring", color = p.muted) }
        }
    }
}

@Composable
fun CodeScreen(vm: MainViewModel, step: AuthStep.Code) {
    val p = LocalPalette.current
    var code by rememberSaveable(step.email, step.type) { mutableStateOf("") }
    val title = when (step.type) {
        "signup" -> "Bevestig je e-mail"
        "recovery" -> "Nieuw wachtwoord"
        else -> "Inloggen met code"
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        AuthHeader(title, "We hebben een code van 6 cijfers gestuurd naar ${step.email}.")
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(code, { v -> code = v.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (code.length == 6) vm.verifyCode(code) }),
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.verifyCode(code) }, enabled = code.length == 6 && !vm.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Bevestigen") }
            Row {
                TextButton(onClick = vm::resendCode, enabled = !vm.busy) { Text("Code opnieuw sturen") }
                TextButton(onClick = vm::backToLogin) { Text("Terug", color = p.muted) }
            }
        }
    }
}

@Composable
fun NewPasswordScreen(vm: MainViewModel) {
    var pw by rememberSaveable { mutableStateOf("") }
    var pw2 by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        AuthHeader("Nieuw wachtwoord", "Kies een wachtwoord van minimaal 8 tekens.")
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(pw, { pw = it }, label = { Text("Nieuw wachtwoord") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw2, { pw2 = it }, label = { Text("Herhaal wachtwoord") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), isError = pw2.isNotEmpty() && pw2 != pw,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setNewPassword(pw) }, enabled = pw.length >= 8 && pw == pw2 && !vm.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Opslaan") }
        }
    }
}

@Composable
fun ConsentScreen(vm: MainViewModel, onSignOut: () -> Unit) {
    val p = LocalPalette.current
    var agreed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        AuthHeader("Toestemming", "Voordat we beginnen.", step = 1 to 3)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("$APP_NAME verwerkt gezondheidsgegevens: stappen, hartslag, slaap, gewicht en vergelijkbare metingen. " +
                "Dat zijn bijzondere persoonsgegevens volgens de AVG.", style = MaterialTheme.typography.bodyLarge, color = p.ink)
            Text("We gebruiken ze alleen om jouw dashboard te tonen. Ze staan op servers in de EU, worden niet gedeeld of verkocht, " +
                "en je kunt je account met alle data op elk moment verwijderen.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(agreed, { agreed = it })
                Text("Ik geef uitdrukkelijk toestemming voor het verwerken van mijn gezondheidsgegevens.",
                    style = MaterialTheme.typography.bodyMedium, color = p.ink)
            }
            Button(onClick = vm::giveConsent, enabled = agreed && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Doorgaan") }
            TextButton(onClick = onSignOut) { Text("Niet nu, uitloggen", color = p.muted) }
        }
    }
}

/** Kennismaking stap 2: direct gegevens importeren uit de bronnen die je gebruikt. */
@Composable
fun ImportScreen(vm: MainViewModel, onRequestHc: () -> Unit, onOpenUrl: (String) -> Unit) {
    val p = LocalPalette.current
    val hcOn = vm.hcStatus == HcStatus.OK || vm.hcStatus == HcStatus.FOREGROUND_ONLY
    val count = listOf(hcOn, vm.isConnected("google_health"), vm.isConnected("garmin")).count { it }
    // Wie met Google inlogde, ziet Google Health bovenaan
    val order = if (vm.signedInWithGoogle) listOf("google_health", "health_connect", "garmin") else listOf("health_connect", "google_health", "garmin")
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        AuthHeader("Gegevens importeren", "Haal je stappen, hartslag en slaap direct binnen.", step = 2 to 3)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kies een of meer bronnen. Je kunt dit later altijd aanpassen via het persoon-icoon → Koppelingen.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp))
            order.forEach { SourceCard(vm, it, onRequestHc, onOpenUrl) { provider -> vm.disconnect(provider, deleteData = false) } }
            if (count > 0) Text(
                if (vm.today.isEmpty()) "Gekoppeld. Je gegevens worden opgehaald…" else "Gekoppeld: ${vm.today.size} metrics van vandaag zijn binnen.",
                style = MaterialTheme.typography.bodyMedium, color = p.good, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Button(onClick = { vm.onboarding = Onboarding.Profile }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (count > 0) "Volgende" else "Overslaan en verder")
            }
        }
    }
}

/** Kennismaking stap 3: persoonlijke gegevens aanvullen, voorgevuld met wat we al weten. */
@Composable
fun OnboardingProfileScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val suggested = vm.suggestedProfile
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        AuthHeader("Over jou", "Vul je profiel aan. Alles is optioneel.", step = 3 to 3)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (suggested.weightKg != null && vm.profile.weightKg == null) Text(
                "Je gewicht hebben we ingevuld met je laatste meting. Klopt het niet, pas het dan aan.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
            PersonalCard(
                vm, saved = suggested, saveLabel = "Klaar", requireChange = false,
                secondary = { TextButton(onClick = { vm.finishOnboarding(null) }, enabled = !vm.busy) { Text("Later", color = p.muted) } },
                onSave = { vm.finishOnboarding(it) },
            )
            TextButton(onClick = { vm.onboarding = Onboarding.Import }) { Text("Terug naar importeren", color = p.muted) }
        }
    }
}

/**
 * Inlogknop volgens de richtlijnen van Google voor "Sign in with Google": licht of donker thema,
 * vaste kleuren, ronde vorm en de tekst "Doorgaan met Google". Het G-logo van Google voeg je toe als
 * res/drawable/google_g.xml uit het officiële pakket (developers.google.com/identity/branding-guidelines);
 * zolang dat bestand er niet is, toont de knop alleen tekst.
 */
@Composable
private fun GoogleButton(onClick: () -> Unit, enabled: Boolean) {
    val dark = LocalPalette.current.dark
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val logo = remember { ctx.resources.getIdentifier("google_g", "drawable", ctx.packageName) }
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, if (dark) Color(0xFF8E918F) else Color(0xFF747775)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (dark) Color(0xFF131314) else Color.White,
            contentColor = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F),
        ),
    ) {
        if (logo != 0) {
            Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text("Doorgaan met Google", style = MaterialTheme.typography.titleMedium)
    }
}
