package nl.vitaal.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * "Nieuw in Vital Connect", zoals in BOA Paraat: na een update komt één keer een venster met de punten onder
 * "## Nieuw" in release-notities/v<versie>.md (de build zet dat bestand als asset nieuw.md in de app).
 * Nieuwe gebruikers krijgen het venster niet; een versie zonder "## Nieuw" (alleen verbeteringen) ook niet.
 * Via Profiel → "Wat is er nieuw" lees je het terug.
 */
data class NewItem(val title: String, val text: String)

/**
 * Leest de punten onder "## Nieuw": "- **Titel.** uitleg" (ook "- **Titel:** uitleg").
 * Onderliggende regels ("  - …") horen bij het punt erboven en komen achter de uitleg.
 */
internal fun parseNew(md: String): List<NewItem> {
    val lines = md.replace("\r", "").lines()
    val start = lines.indexOfFirst { it.trim().equals("## Nieuw", ignoreCase = true) }
    if (start < 0) return emptyList()
    val block = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
    val items = mutableListOf<Pair<String, StringBuilder>>()
    val head = Regex("""^- \*\*(.+?)\*\*\s*(.*)$""")
    for (line in block) {
        val m = head.find(line)
        when {
            m != null -> {
                val title = m.groupValues[1].trim().trimEnd('.', ':').trim()
                items += title to StringBuilder(m.groupValues[2].trim().trimStart(':').trim())
            }
            items.isNotEmpty() && line.isNotBlank() && line.startsWith(" ") -> {
                val sb = items.last().second
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(line.trim().removePrefix("- ").removePrefix("* ").trim())
            }
        }
    }
    return items.map { (t, sb) ->
        val text = sb.toString().replace("**", "").replace("`", "").replace(Regex("\\s+"), " ").trim()
        NewItem(t.replace("`", ""), text.replaceFirstChar { it.uppercase() })
    }.filter { it.title.isNotEmpty() }
}

/**
 * Moet het venster nu komen? Alleen bij nieuwe punten, één keer per versie, en niet voor wie de app net voor het
 * eerst gebruikt (die krijgt eerst de kennismaking; voor hem is alles nieuw).
 */
internal fun shouldShowNew(seenVersion: String?, current: String, items: Int, firstUse: Boolean): Boolean =
    items > 0 && seenVersion != current && !firstUse

/** De punten van deze versie uit de app zelf (leeg als de build geen notities had). */
internal fun loadNew(ctx: Context): List<NewItem> =
    runCatching { ctx.assets.open("nieuw.md").bufferedReader().use { parseNew(it.readText()) } }.getOrDefault(emptyList())

/** "1.18.0" → "1.18" (zoals BOA Paraat: een .0 aan het eind laat je weg). */
internal fun shortVersion(v: String): String = v.removeSuffix(".0")

@Composable
fun WhatsNewDialog(items: List<NewItem>, version: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.card).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Versie ${shortVersion(version)}", style = MaterialTheme.typography.labelMedium, color = p.muted)
                Text("Nieuw in $APP_NAME", style = MaterialTheme.typography.headlineSmall, color = p.ink,
                    modifier = Modifier.semantics { heading() })
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(p.bg), contentAlignment = Alignment.Center) {
                            Icon(VIcons.Check, contentDescription = null, tint = p.primary, modifier = Modifier.size(16.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
                            if (item.text.isNotEmpty()) Text(item.text, style = MaterialTheme.typography.bodyMedium, color = p.muted)
                        }
                    }
                }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = p.onPrimary)) {
                Text("Aan de slag")
            }
        }
    }
}
