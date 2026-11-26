package nl.vitaal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Beheeroverzicht (RPC admin_overview): wie de app gebruikt, met welke versie en welke koppelingen. Geen gezondheidswaarden. */
class AdminOverview(
    val users: Int,
    val activeToday: Int,
    val active7: Int,
    val new7: Int,
    val twoStep: Int,
    val errors7: Int,
    val versions: List<Pair<String?, Int>>,
    val connections: List<AdminCount>,
    val errors: List<AdminError>,
    val people: List<AdminUser>,
)

class AdminCount(val source: String, val status: String, val count: Int)
class AdminError(val source: String, val count: Int, val last: Instant?, val message: String)
class AdminUser(
    val email: String,
    val name: String,
    val active: Instant?,
    val version: String?,
    val twoStep: Boolean,
    val sources: List<String>,
    val errors7: Int,
)

private fun JsonObject.objects(k: String) = (this[k] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
private fun instant(s: String) = runCatching { Instant.parse(s) }.getOrNull()
    ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()

fun parseAdminOverview(o: JsonObject?): AdminOverview? {
    o ?: return null
    val k = o["kpi"] as? JsonObject ?: JsonObject(emptyMap())
    return AdminOverview(
        users = k.int("gebruikers") ?: 0,
        activeToday = k.int("actief_vandaag") ?: 0,
        active7 = k.int("actief_7d") ?: 0,
        new7 = k.int("nieuw_7d") ?: 0,
        twoStep = k.int("tweestaps") ?: 0,
        errors7 = k.int("fouten_7d") ?: 0,
        versions = o.objects("versies").map { it.str("versie").ifEmpty { null } to (it.int("aantal") ?: 0) },
        connections = o.objects("koppelingen").map { AdminCount(it.str("bron"), it.str("status"), it.int("aantal") ?: 0) },
        errors = o.objects("fouten").map { AdminError(it.str("bron"), it.int("aantal") ?: 0, instant(it.str("laatste")), it.str("melding")) },
        people = o.objects("gebruikers").map { u ->
            AdminUser(
                email = u.str("email"), name = u.str("naam"), active = instant(u.str("actief")),
                version = u.str("versie").ifEmpty { null }, twoStep = u.bool("tweestaps") == true,
                sources = u.objects("koppelingen").filter { it.str("status") == "active" }.map { it.str("bron") },
                errors7 = u.int("fouten_7d") ?: 0,
            )
        },
    )
}

/** "vandaag 14:05", "gisteren", "3 dagen geleden", "12 nov 2026" of "nooit". */
fun activeText(t: Instant?, today: LocalDate = LocalDate.now()): String {
    t ?: return "nooit"
    val z = t.atZone(ZoneId.systemDefault())
    val days = java.time.temporal.ChronoUnit.DAYS.between(z.toLocalDate(), today)
    return when {
        days <= 0 -> "vandaag %02d:%02d".format(z.hour, z.minute)
        days == 1L -> "gisteren"
        days < 7 -> "$days dagen geleden"
        else -> fmtDate(z.toLocalDate())
    }
}

/** Tabblad Beheer in het profiel; alleen zichtbaar voor beheerders. */
fun LazyListScope.adminItems(vm: MainViewModel) {
    val a = vm.adminOverview
    if (a == null) {
        item {
            Text(if (vm.busy) "Overzicht laden…" else "Het overzicht kon niet worden geladen.",
                style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        item { OutlinedButton(onClick = vm::loadAdmin, enabled = !vm.busy) { Text("Opnieuw laden") } }
        return
    }
    item {
        VCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kpi("Gebruikers", a.users, Modifier.weight(1f))
                Kpi("Vandaag", a.activeToday, Modifier.weight(1f))
                Kpi("7 dagen", a.active7, Modifier.weight(1f))
            }
            Text("Nieuw deze week: ${a.new7} · Tweestaps aan: ${a.twoStep} · Fouten deze week: ${a.errors7}",
                style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
        }
    }
    item { SectionTitle("App-versies", Modifier.padding(start = 4.dp, top = 12.dp)) }
    item {
        VCard(Modifier.fillMaxWidth()) {
            a.versions.forEachIndexed { i, (v, n) ->
                AdminRow(v?.let { "Versie $it" } ?: "Onbekend (vóór 1.19)", people(n),
                    note = if (i == 0 && v == BuildConfig.VERSION_NAME) "deze app" else null)
            }
        }
    }
    item { SectionTitle("Koppelingen", Modifier.padding(start = 4.dp, top = 12.dp)) }
    item {
        VCard(Modifier.fillMaxWidth()) {
            if (a.connections.isEmpty()) Text("Nog geen koppelingen.", style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.muted)
            a.connections.forEach { c -> AdminRow(providerName(c.source), people(c.count), note = statusText(c.status)) }
        }
    }
    item { SectionTitle("Fouten deze week", Modifier.padding(start = 4.dp, top = 12.dp)) }
    item {
        val p = LocalPalette.current
        VCard(Modifier.fillMaxWidth()) {
            if (a.errors.isEmpty()) Text("Geen fouten bij het ophalen.", style = MaterialTheme.typography.bodyMedium, color = p.good)
            a.errors.forEach { e ->
                Column {
                    AdminRow(providerName(e.source), "${e.count}×", note = "laatst ${activeText(e.last)}")
                    if (e.message.isNotBlank()) Text(e.message, style = MaterialTheme.typography.bodySmall, color = p.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    item { SectionTitle("Gebruikers · ${a.people.size}", Modifier.padding(start = 4.dp, top = 12.dp)) }
    a.people.forEachIndexed { i, u -> item(key = "u$i") { UserCard(u) } }
    item { OutlinedButton(onClick = vm::loadAdmin, enabled = !vm.busy) { Text("Vernieuwen") } }
}

private fun people(n: Int) = if (n == 1) "1 gebruiker" else "$n gebruikers"

private fun statusText(s: String) = when (s) {
    "active" -> "actief"
    "error" -> "fout"
    "revoked" -> "ingetrokken"
    else -> s
}

@Composable
private fun Kpi(label: String, value: Int, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(fmtCompact(value.toDouble()), style = MaterialTheme.typography.headlineMedium, color = p.ink)
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.muted)
    }
}

@Composable
private fun AdminRow(title: String, value: String, note: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.weight(1f))
        if (note != null) { Text(note, style = MaterialTheme.typography.bodySmall, color = p.muted); Spacer(Modifier.width(12.dp)) }
        Text(value, style = MaterialTheme.typography.titleSmall, color = p.ink)
    }
}

@Composable
private fun UserCard(u: AdminUser) {
    val p = LocalPalette.current
    VCard(Modifier.fillMaxWidth()) {
        Text(u.name.ifEmpty { u.email }, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (u.name.isNotEmpty()) Text(u.email, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val parts = listOfNotNull(
            "Actief ${activeText(u.active)}",
            u.version?.let { "versie $it" } ?: "versie onbekend",
            if (u.twoStep) "tweestaps aan" else null,
        )
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = p.muted)
        Text(if (u.sources.isEmpty()) "Geen koppelingen" else u.sources.joinToString(", ") { providerName(it) },
            style = MaterialTheme.typography.bodySmall, color = p.ink)
        if (u.errors7 > 0) Text("${u.errors7} fout${if (u.errors7 == 1) "" else "en"} deze week",
            style = MaterialTheme.typography.bodySmall, color = p.caution)
    }
}
