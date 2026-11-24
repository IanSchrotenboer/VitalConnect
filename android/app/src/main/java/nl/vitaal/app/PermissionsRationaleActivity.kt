package nl.vitaal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Health Connect toont dit scherm als de gebruiker wil weten wat de app met de data doet. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VitaalTheme(ThemeMode.of(getSharedPreferences("vitaal_ui", MODE_PRIVATE).getString("theme", null))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Wat $APP_NAME met je gezondheidsdata doet", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text("$APP_NAME leest alleen de gegevens die je dashboard toont: stappen, afstand, actieve calorieën, verdiepingen, trainingen, rusthartslag, HRV, zuurstofsaturatie, ademhaling, slaap, gewicht en VO2max.", style = MaterialTheme.typography.bodyLarge)
                        Text("Per dag en per bron-app wordt één waarde berekend en versleuteld naar je eigen $APP_NAME-account gestuurd (servers in de EU). Ruwe meetpunten verlaten je telefoon niet.", style = MaterialTheme.typography.bodyLarge)
                        Text("$APP_NAME schrijft niets naar Health Connect, verkoopt geen data en deelt niets met derden.", style = MaterialTheme.typography.bodyLarge)
                        Text("Je kunt de toegang altijd intrekken in Health Connect. In de app kun je je account en alle opgeslagen data verwijderen.", style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                        }) { Text("Volledige privacyverklaring") }
                    }
                }
            }
        }
    }
}
