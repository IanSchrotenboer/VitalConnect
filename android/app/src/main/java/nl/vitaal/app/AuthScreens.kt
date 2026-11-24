package nl.vitaal.app

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Donkere kop met de naam van de app, boven alle inlogschermen. */
@Composable
private fun AuthHeader(title: String, subtitle: String) {
    val d = DialPalette
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(d.dial)
            .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Rings(
            listOf(0.82f to d.activity, 0.64f to d.sleep, 0.9f to d.recovery),
            center = "",
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, color = d.dialInk)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = d.dialMuted)
    }
}

@Composable
fun LoginScreen(vm: MainViewModel, onGoogleSignIn: () -> Unit) {
    val p = LocalPalette.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AuthHeader(APP_NAME, "Je gezondheid op één plek: beweging, hart en slaap.")
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onGoogleSignIn, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Inloggen met Google")
            }
            Text("of met je e-mailadres", style = MaterialTheme.typography.bodySmall, color = p.muted,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedTextField(email, { email = it }, label = { Text("E-mailadres") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Wachtwoord (min. 8 tekens)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            val emailOk = email.contains("@") && !vm.busy
            val ok = emailOk && password.length >= 8
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.signIn(email, password) }, enabled = ok, modifier = Modifier.weight(1f)) { Text("Inloggen") }
                OutlinedButton(onClick = { vm.signUp(email, password) }, enabled = ok, modifier = Modifier.weight(1f)) { Text("Account maken") }
            }
            TextButton(onClick = { vm.requestLoginCode(email) }, enabled = emailOk) { Text("Inloggen met een code per e-mail") }
            TextButton(onClick = { vm.requestPasswordReset(email) }, enabled = emailOk) { Text("Wachtwoord vergeten?") }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AuthHeader("Toestemming", "Voordat we beginnen.")
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
