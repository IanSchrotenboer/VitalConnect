package nl.vitaal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Tekst om te bewaren of te delen: één code per regel, met uitleg. */
fun recoveryCodesText(codes: List<String>) =
    "Herstelcodes voor $APP_NAME\nElke code werkt één keer. Gebruik er een als je je telefoon met de authenticator-app kwijt bent.\n\n" +
        codes.joinToString("\n")

/**
 * Herstelcodes na het aanzetten van tweestapsverificatie. Ze zijn maar één keer te zien: de gebruiker bewaart ze zelf.
 * Het venster sluit alleen met "Ik heb ze bewaard" (niet door ernaast te tikken of terug te gaan).
 */
@Composable
fun RecoveryCodesDialog(codes: List<String>, onShare: (String) -> Unit, onDone: () -> Unit) {
    val p = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.card)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Bewaar je herstelcodes", style = MaterialTheme.typography.headlineSmall, color = p.ink, modifier = Modifier.semantics { heading() })
            Text("Ben je je telefoon kwijt, dan log je met een van deze codes toch in. Elke code werkt één keer. " +
                "Bewaar ze op een veilige plek, bijvoorbeeld in je wachtwoordmanager. Je ziet ze hierna niet meer.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.bg).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                codes.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEach { c ->
                            Text(c, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = p.ink,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(recoveryCodesText(codes))) }) { Text("Kopiëren") }
                OutlinedButton(onClick = { onShare(recoveryCodesText(codes)) }) { Text("Delen") }
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = p.onPrimary)) {
                Text("Ik heb ze bewaard")
            }
        }
    }
}
