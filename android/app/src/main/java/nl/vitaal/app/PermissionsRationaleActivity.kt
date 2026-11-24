package nl.vitaal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.dp

/** Health Connect toont dit scherm als de gebruiker wil weten wat de app met de data doet. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VitaalTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Wat $APP_NAME met je gezondheidsdata doet", style = MaterialTheme.typography.headlineSmall)
                        Text("$APP_NAME leest alleen de gegevens die je dashboard toont: stappen, afstand, actieve calorieën, verdiepingen, trainingen, rusthartslag, HRV, zuurstofsaturatie, ademhaling, slaap, gewicht en VO2max.")
                        Text("Per dag en per bron-app wordt één waarde berekend en versleuteld naar je eigen $APP_NAME-account gestuurd (servers in de EU). Ruwe meetpunten verlaten je telefoon niet.")
                        Text("$APP_NAME schrijft niets naar Health Connect, verkoopt geen data en deelt niets met derden.")
                        Text("Je kunt de toegang altijd intrekken in Health Connect. In de app kun je je account en alle opgeslagen data verwijderen.")
                    }
                }
            }
        }
    }
}
