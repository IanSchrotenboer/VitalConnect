package nl.vitaal.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/* ------------------------------------------------------------- Gegevens ------------------------------------------------------------- */

/** Sporten (zelfde sleutels als de database) met hun Nederlandse naam. */
val SPORTS: List<Pair<String, String>> = listOf(
    "running" to "Hardlopen", "walking" to "Wandelen", "hiking" to "Wandeltocht", "cycling" to "Fietsen",
    "swimming" to "Zwemmen", "strength" to "Krachttraining", "hiit" to "Intervaltraining", "yoga" to "Yoga en pilates",
    "rowing" to "Roeien en peddelen", "elliptical" to "Crosstrainer en traplopen", "team" to "Teamsport",
    "racket" to "Racketsport", "dance" to "Dansen", "martial" to "Vechtsport", "winter" to "Wintersport", "other" to "Overig",
)
val SPORT_KEYS: Set<String> = SPORTS.map { it.first }.toSet()
fun sportName(k: String) = SPORTS.firstOrNull { it.first == k }?.second ?: "Training"

/** Eén training. id is null voor een training die nog niet is opgeslagen (formulier of import). */
data class Workout(
    val id: String?, val source: String, val externalId: String, val sport: String, val title: String?,
    val day: LocalDate, val startedAt: Instant, val durationS: Int, val distanceM: Double?, val kcal: Double?,
    val avgHr: Int?, val maxHr: Int?, val ascentM: Double?, val notes: String?, val origin: String?, val load: Double?,
    /** Vastlegmethode: active (zelf gestart), passive (automatisch herkend), manual, derived of unknown */
    val recording: String? = null,
)

fun Workout.toJson(): JsonObject = buildJsonObject {
    put("source", source); put("external_id", externalId); put("sport", sport)
    title?.let { put("title", it) }; put("day", day.toString())
    put("started_at", DateTimeFormatter.ISO_INSTANT.format(startedAt.truncatedTo(ChronoUnit.SECONDS)))
    put("duration_s", durationS)
    distanceM?.let { put("distance_m", (it * 10).roundToInt() / 10.0) }; kcal?.let { put("kcal", it.roundToInt()) }
    avgHr?.let { put("avg_hr", it) }; maxHr?.let { put("max_hr", it) }; ascentM?.let { put("ascent_m", it.roundToInt()) }
    notes?.let { put("notes", it) }; origin?.let { put("origin", it) }
    (recording ?: when (source) { "manual" -> "manual"; "file" -> "active"; else -> null })?.let { put("recording", it) }
}

fun parseWorkout(o: JsonObject): Workout? {
    val day = day(o.str("day")) ?: return null
    val start = runCatching { java.time.OffsetDateTime.parse(o.str("started_at")).toInstant() }.getOrNull() ?: return null
    return Workout(
        id = o.str("id").ifEmpty { null }, source = o.str("source"), externalId = o.str("external_id"),
        sport = o.str("sport").ifEmpty { "other" }, title = o.str("title").ifEmpty { null }, day = day, startedAt = start,
        durationS = o.int("duration_s") ?: return null, distanceM = o.num("distance_m"), kcal = o.num("kcal"),
        avgHr = o.int("avg_hr"), maxHr = o.int("max_hr"), ascentM = o.num("ascent_m"), notes = o.str("notes").ifEmpty { null },
        origin = o.str("origin").ifEmpty { null }, load = o.num("load"), recording = o.str("recording").ifEmpty { null },
    )
}

data class TrainingWeek(val count: Int, val minutes: Int, val distanceKm: Double, val kcal: Int, val load: Int?)
data class WeekBar(val week: LocalDate, val count: Int, val minutes: Int, val load: Int?)
data class TrainingLoad(
    val ready: Boolean, val days: Int?, val acute: Double?, val chronic: Double?, val form: Double?,
    val hrmax: Int?, val rest: Double?,
)
/** Keuzes bij Profiel → Koppelingen → Trainingen (zelfde waarden als profiles.training_source). */
val TRAINING_SOURCES = listOf("auto" to "Automatisch", "google_health" to "Google Health", "health_connect" to "Health Connect", "all" to "Alles")

/** Welke trainingen meetellen: source = de keuze (auto, google_health, health_connect, all), mode = wat auto werd. */
data class TrainingFilter(val source: String, val mode: String, val ownOnly: Boolean, val hidden: Int)
data class Training(val workouts: List<Workout>, val week: TrainingWeek, val weeks: List<WeekBar>, val load: TrainingLoad, val filter: TrainingFilter? = null)

/** Leest het veld training van app_state; null bij een oudere server. */
fun parseTraining(o: JsonObject?): Training? {
    if (o == null) return null
    val wk = o["week"] as? JsonObject ?: JsonObject(emptyMap())
    val ld = o["load"] as? JsonObject ?: JsonObject(emptyMap())
    return Training(
        workouts = (o["workouts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull(::parseWorkout),
        week = TrainingWeek(wk.int("count") ?: 0, wk.int("minutes") ?: 0, wk.num("distance_km") ?: 0.0, wk.int("kcal") ?: 0, wk.int("load")),
        weeks = (o["weeks"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { w ->
            day(w.str("week"))?.let { WeekBar(it, w.int("count") ?: 0, w.int("minutes") ?: 0, w.int("load")) }
        },
        load = TrainingLoad(ld.bool("ready") == true, ld.int("days"), ld.num("acute"), ld.num("chronic"), ld.num("form"), ld.int("hrmax"), ld.num("rest")),
        filter = (o["filter"] as? JsonObject)?.let { f ->
            TrainingFilter(f.str("source").ifEmpty { "auto" }, f.str("mode"), f.bool("own_only") == true, f.int("hidden") ?: 0)
        },
    )
}

fun fmtDuration(s: Int): String {
    val m = (s + 30) / 60
    return if (m < 60) "$m min" else "${m / 60}u ${"%02d".format(m % 60)}m"
}

fun fmtKm(m: Double): String = String.format(NL, if (m >= 10_000) "%.0f km" else "%.1f km", m / 1000)

/** Tempo bij lopen en wandelen (min/km), snelheid bij fietsen (km/u). */
fun fmtPace(w: Workout): String? {
    val d = w.distanceM?.takeIf { it > 100 } ?: return null
    return when (w.sport) {
        "running", "walking", "hiking" -> { val sPerKm = w.durationS / (d / 1000); "${(sPerKm / 60).toInt()}:${"%02d".format((sPerKm % 60).toInt())} /km" }
        "cycling" -> String.format(NL, "%.1f km/u", d / 1000 / (w.durationS / 3600.0))
        "swimming" -> { val s100 = w.durationS / (d / 100); "${(s100 / 60).toInt()}:${"%02d".format((s100 % 60).toInt())} /100 m" }
        else -> null
    }
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Vandaag"
        today.minusDays(1) -> "Gisteren"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy", NL))
            .replaceFirstChar { it.uppercase() }
    }
}

private fun sourceLabel(w: Workout) = when (w.source) {
    "health_connect" -> "Health Connect" + (w.origin?.let { " · ${appName(it)}" } ?: "")
    "google_health" -> "Google Health"
    "manual" -> "Zelf gelogd"
    else -> w.origin ?: "Bestand"
}

/** Bekende apps in Health Connect met een leesbare naam. */
private fun appName(pkg: String) = when {
    pkg.contains("google.android.apps.fitness") -> "Google Fit"
    pkg.contains("samsung") -> "Samsung Health"
    pkg.contains("fitbit") -> "Fitbit"
    pkg.contains("garmin") -> "Garmin"
    pkg.contains("suunto") -> "Suunto"
    pkg.contains("strava") -> "Strava"
    pkg.contains("polar") -> "Polar"
    else -> pkg.substringAfterLast('.')
}

/** Vorm (chronische min acute belasting) in gewone woorden. */
private data class Vorm(val title: String, val text: String)

private fun vormOf(form: Double): Vorm = when {
    form > 5 -> Vorm("Uitgerust", "Je hebt de laatste dagen minder getraind dan je gewend bent. Goed voor een wedstrijd of een stevige training.")
    form >= -10 -> Vorm("In balans", "Je traint ongeveer zoveel als je gewend bent.")
    form >= -30 -> Vorm("Flink aan het trainen", "Je traint meer dan je gewend bent. Zo word je fitter, als je ook genoeg rust neemt.")
    else -> Vorm("Zwaar belast", "Je traint veel meer dan je gewend bent. Plan een paar rustige dagen om blessures te voorkomen.")
}

/* ------------------------------------------------------------- Tabblad ------------------------------------------------------------- */

/** Tabblad Training: deze week, belasting, verloop en de lijst met trainingen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainingScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.importWorkoutFiles(uris)
    }
    val t = vm.training
    val all = (t?.workouts.orEmpty() + vm.olderWorkouts)
    val byDay = all.groupBy { it.day }.toSortedMap(compareByDescending { it })

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) {
                ScreenTitle("Training")
                Text("Je trainingen uit al je bronnen, en wat ze met je lichaam doen", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.newWorkout() }, Modifier.weight(1f)) { Text("Training loggen", textAlign = TextAlign.Center) }
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text("Importeren", textAlign = TextAlign.Center) }
            }
        }
        if (t != null) {
            item { WeekCard(t) }
            item { LoadCard(vm, t.load) }
            t.filter?.takeIf { it.hidden > 0 }?.let { f -> item { FilterNote(vm, f) } }
        }
        if (all.isEmpty()) item {
            VCard(Modifier.fillMaxWidth()) {
                Text("Nog geen trainingen", style = MaterialTheme.typography.titleMedium, color = p.ink)
                Text("Trainingen uit Google Fit, Health Connect en Google Health verschijnen hier vanzelf. " +
                    "Je kunt ook zelf een training loggen of een FIT- of JSON-bestand van je horloge of sportapp importeren.",
                    style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        byDay.forEach { (d, list) ->
            item(key = "dag-$d") { SectionTitle(dayLabel(d), Modifier.padding(start = 4.dp, top = 4.dp)) }
            items(list, key = { it.id ?: it.externalId }) { w -> WorkoutRow(w) { vm.workoutDetail = w } }
        }
        if (all.isNotEmpty() && !vm.olderDone) item {
            TextButton(onClick = { vm.loadOlderWorkouts() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Oudere trainingen laden")
            }
        }
    }
}

@Composable
private fun WeekCard(t: Training) {
    val p = LocalPalette.current
    VCard(Modifier.fillMaxWidth()) {
        Text("Afgelopen 7 dagen", style = MaterialTheme.typography.labelMedium, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Trainingen", "${t.week.count}", Modifier.weight(1f))
            Stat("Tijd", fmtDuration(t.week.minutes * 60), Modifier.weight(1f))
            Stat("Afstand", String.format(NL, "%.1f km", t.week.distanceKm), Modifier.weight(1f))
        }
        if (t.weeks.any { it.minutes > 0 }) {
            Text("Minuten per week", style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.padding(top = 4.dp))
            WeekBars(t.weeks)
        }
    }
}

/** Acht weken als staafjes; de huidige week in de hoofdkleur. */
/** Korte regel dat de instelling trainingen verbergt, met een weg naar die instelling. */
@Composable
private fun FilterNote(vm: MainViewModel, f: TrainingFilter) {
    val p = LocalPalette.current
    val wat = when {
        f.mode == "google_health" && f.ownOnly -> "uit Health Connect en automatisch herkende trainingen"
        f.mode == "google_health" -> "die Google Health uit Health Connect overneemt, zoals de wandelingen van Google Fit,"
        f.ownOnly -> "die automatisch zijn herkend"
        else -> "uit andere bronnen"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${if (f.hidden == 1) "1 training" else "${f.hidden} trainingen"} $wat ${if (f.hidden == 1) "telt" else "tellen"} niet mee.",
            style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.openProfile(ProfileTab.Connections) }) { Text("Aanpassen") }
    }
}

@Composable
private fun WeekBars(weeks: List<WeekBar>) {
    val p = LocalPalette.current
    val max = (weeks.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(30)
    Row(
        Modifier.fillMaxWidth().height(72.dp).semantics {
            contentDescription = "Minuten per week: " + weeks.joinToString(", ") { "week van ${it.week.dayOfMonth} ${it.week.format(DateTimeFormatter.ofPattern("MMM", NL))} ${it.minutes}" }
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom,
    ) {
        weeks.forEachIndexed { i, w ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                val f = w.minutes.toFloat() / max
                Box(Modifier.fillMaxWidth().height((52 * f).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(4.dp))
                    .background(if (i == weeks.lastIndex) p.primary else p.primary.copy(alpha = 0.35f)))
                Text(w.week.format(DateTimeFormatter.ofPattern("d/M")), style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LoadCard(vm: MainViewModel, l: TrainingLoad) {
    val p = LocalPalette.current
    VCard(Modifier.fillMaxWidth()) {
        Text("Trainingsbelasting", style = MaterialTheme.typography.labelMedium, color = p.muted)
        if (!l.ready || l.form == null) {
            Text(
                when {
                    l.hrmax == null -> "Vul je geboortedatum in bij Profiel. Dan rekenen we uit hoe zwaar je trainingen voor je lichaam zijn."
                    l.rest == null -> "We hebben je rusthartslag nodig. Die komt vanzelf binnen via je horloge of Health Connect."
                    else -> "Belasting rekenen we uit je gemiddelde hartslag per training. Trainingen met hartslag (uit een FIT-bestand, " +
                        "Google Health of zelf ingevuld) tellen mee."
                }, style = MaterialTheme.typography.bodyMedium, color = p.ink,
            )
            if (l.hrmax == null) ActionChip("Naar Profiel") { vm.profileTab = ProfileTab.Profile; vm.showSettings = true }
        } else {
            val v = vormOf(l.form)
            Text(v.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
            Text(v.text, style = MaterialTheme.typography.bodyMedium, color = p.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Deze week", "${l.acute?.roundToInt() ?: 0}", Modifier.weight(1f))
                Stat("Gewend", "${l.chronic?.roundToInt() ?: 0}", Modifier.weight(1f))
                Stat("Vorm", (if (l.form > 0) "+" else "") + l.form.roundToInt(), Modifier.weight(1f))
            }
            if ((l.days ?: 0) < 42) Text("Na 6 weken trainen is dit betrouwbaar; nu ${l.days ?: 0} dagen.",
                style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        Expandable("Wat betekent dit voor je lichaam?") { BODY_INFO["load"]?.forEach { InfoParagraph(it) } }
    }
}

@Composable
private fun WorkoutRow(w: Workout, onClick: () -> Unit) {
    val p = LocalPalette.current
    VCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(p.activity, 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(w.title ?: sportName(w.sport), style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 1)
                Text(
                    listOfNotNull(
                        w.startedAt.atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                        if (w.title != null) sportName(w.sport) else null, sourceLabel(w),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1,
                )
            }
            Text(fmtDuration(w.durationS), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = p.ink)
        }
        val facts = listOfNotNull(w.distanceM?.takeIf { it > 0 }?.let(::fmtKm), fmtPace(w), w.avgHr?.let { "♥ $it" }, w.kcal?.let { "${it.roundToInt()} kcal" },
            w.load?.let { "belasting ${it.roundToInt()}" })
        if (facts.isNotEmpty()) Text(facts.joinToString("   "), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = p.ink)
    }
}

/* ------------------------------------------------------------- Detail ------------------------------------------------------------- */

@Composable
fun WorkoutScreen(vm: MainViewModel, w: Workout) {
    val p = LocalPalette.current
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        VTopBar(w.title ?: sportName(w.sport), onBack = { vm.workoutDetail = null }, subtitle = dayLabel(w.day))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VCard(Modifier.fillMaxWidth()) {
                Text(sportName(w.sport), style = MaterialTheme.typography.labelMedium, color = p.muted)
                val start = w.startedAt.atZone(ZoneId.systemDefault()).toLocalTime()
                Text("${start.format(DateTimeFormatter.ofPattern("HH:mm"))} – ${start.plusSeconds(w.durationS.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    style = MaterialTheme.typography.titleMedium, color = p.ink)
                val stats = listOfNotNull(
                    "Duur" to fmtDuration(w.durationS),
                    w.distanceM?.takeIf { it > 0 }?.let { "Afstand" to fmtKm(it) },
                    fmtPace(w)?.let { (if (w.sport == "cycling") "Snelheid" else "Tempo") to it },
                    w.kcal?.let { "Calorieën" to "${it.roundToInt()} kcal" },
                    w.avgHr?.let { "Gem. hartslag" to "$it bpm" },
                    w.maxHr?.let { "Max. hartslag" to "$it bpm" },
                    w.ascentM?.let { "Hoogtemeters" to "${it.roundToInt()} m" },
                    w.load?.let { "Belasting" to "${it.roundToInt()}" },
                )
                stats.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { (k, v) -> Stat(k, v, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                w.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.ink) }
                Text("Bron: ${sourceLabel(w)}", style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            if (w.load == null && w.avgHr == null) Text(
                "Zonder gemiddelde hartslag kunnen we de belasting van deze training niet berekenen.",
                style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
            VCard(Modifier.fillMaxWidth()) {
                Expandable("Wat doet ${sportName(w.sport).lowercase()} met je lichaam?") {
                    SPORT_INFO[w.sport]?.let { InfoParagraph(it) }
                    BODY_INFO["load"]?.firstOrNull()?.let { InfoParagraph(it) }
                }
            }
            when (w.source) {
                "manual", "file" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (w.source == "manual") Button(onClick = { vm.editWorkout(w) }) { Text("Bewerken") }
                    OutlinedButton(onClick = { confirm = true }) { Text("Verwijderen", color = p.bad) }
                }
                else -> Text("Deze training komt uit ${sourceLabel(w)}. Aanpassen of verwijderen doe je in die app; de wijziging komt hier vanzelf binnen.",
                    style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Training verwijderen?") },
        text = { Text("${w.title ?: sportName(w.sport)} van ${dayLabel(w.day).lowercase()} wordt verwijderd.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.deleteWorkout(w) }) { Text("Verwijderen", color = p.bad) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuleren") } },
    )
}

/* ------------------------------------------------------------- Zelf loggen ------------------------------------------------------------- */

/** Formulier om een training te loggen of te bewerken (bron manual). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkoutFormScreen(vm: MainViewModel, start: Workout) {
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    var sport by rememberSaveable { mutableStateOf(start.sport) }
    var title by rememberSaveable { mutableStateOf(start.title.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(start.day.toString()) }
    var time by rememberSaveable { mutableStateOf(start.startedAt.atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var hours by rememberSaveable { mutableStateOf(if (start.durationS >= 3600) "${start.durationS / 3600}" else "") }
    var minutes by rememberSaveable { mutableStateOf("${(start.durationS % 3600) / 60}") }
    var km by rememberSaveable { mutableStateOf(start.distanceM?.let { String.format(NL, "%.2f", it / 1000) }.orEmpty()) }
    var kcal by rememberSaveable { mutableStateOf(start.kcal?.roundToInt()?.toString().orEmpty()) }
    var avg by rememberSaveable { mutableStateOf(start.avgHr?.toString().orEmpty()) }
    var max by rememberSaveable { mutableStateOf(start.maxHr?.toString().orEmpty()) }
    var ascent by rememberSaveable { mutableStateOf(start.ascentM?.roundToInt()?.toString().orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(start.notes.orEmpty()) }
    var pickDate by remember { mutableStateOf(false) }

    fun dec(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
    val d = LocalDate.parse(date)
    val t = runCatching { LocalTime.parse(time.trim().padStart(5, '0')) }.getOrNull()
    val dur = ((dec(hours) ?: 0.0) * 3600 + (dec(minutes) ?: 0.0) * 60).roundToInt()
    val distOk = km.isBlank() || dec(km)?.let { it in 0.0..1000.0 } == true
    val kcalOk = kcal.isBlank() || dec(kcal)?.let { it in 0.0..20000.0 } == true
    val avgOk = avg.isBlank() || dec(avg)?.let { it in 25.0..250.0 } == true
    val maxOk = max.isBlank() || dec(max)?.let { it in 25.0..250.0 } == true
    val ascOk = ascent.isBlank() || dec(ascent)?.let { it in 0.0..20000.0 } == true
    val startAt = t?.let { d.atTime(it).atZone(zone).toInstant() }
    val valid = t != null && dur in 60..86400 && distOk && kcalOk && avgOk && maxOk && ascOk && startAt!!.isBefore(Instant.now().plusSeconds(3600))

    Column(Modifier.fillMaxSize()) {
        VTopBar(if (start.id == null) "Training loggen" else "Training bewerken", onBack = { vm.workoutForm = null })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VCard(Modifier.fillMaxWidth()) {
                Text("Sport", style = MaterialTheme.typography.titleMedium, color = p.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SPORTS.forEach { (k, n) -> Pill(n, sport == k) { sport = k } }
                }
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("Naam (optioneel)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth())
            }
            VCard(Modifier.fillMaxWidth()) {
                Text("Wanneer en hoe lang", style = MaterialTheme.typography.titleMedium, color = p.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) { Text(fmtDate(d)) }
                    OutlinedTextField(time, { time = it.take(5) }, label = { Text("Start (uu:mm)") }, singleLine = true, isError = t == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hours, { hours = it.take(2) }, label = { Text("Uren") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.weight(1f))
                    OutlinedTextField(minutes, { minutes = it.take(4) }, label = { Text("Minuten") }, singleLine = true, isError = dur !in 60..86400,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.weight(1f))
                }
                if (dur !in 60..86400) Text("Een training duurt minstens 1 minuut en hoogstens 24 uur.", style = MaterialTheme.typography.bodySmall, color = p.bad)
            }
            VCard(Modifier.fillMaxWidth()) {
                Text("Details (optioneel)", style = MaterialTheme.typography.titleMedium, color = p.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(km, { km = it.take(7) }, label = { Text("Afstand (km)") }, singleLine = true, isError = !distOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next), modifier = Modifier.weight(1f))
                    OutlinedTextField(kcal, { kcal = it.take(5) }, label = { Text("Calorieën") }, singleLine = true, isError = !kcalOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(avg, { avg = it.take(3) }, label = { Text("Gem. hartslag") }, singleLine = true, isError = !avgOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.weight(1f))
                    OutlinedTextField(max, { max = it.take(3) }, label = { Text("Max. hartslag") }, singleLine = true, isError = !maxOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(ascent, { ascent = it.take(5) }, label = { Text("Hoogtemeters") }, singleLine = true, isError = !ascOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it.take(1000) }, label = { Text("Notities") }, minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
                Text("Met je gemiddelde hartslag rekenen we ook uit hoe zwaar de training voor je lichaam was.",
                    style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            Button(
                enabled = valid && !vm.busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.saveManualWorkout(start.copy(
                        sport = sport, title = title.trim().ifEmpty { null }, day = d, startedAt = startAt!!, durationS = dur,
                        distanceM = dec(km)?.times(1000), kcal = dec(kcal), avgHr = dec(avg)?.roundToInt(), maxHr = dec(max)?.roundToInt(),
                        ascentM = dec(ascent), notes = notes.trim().ifEmpty { null },
                    ))
                },
            ) { Text("Opslaan") }
        }
    }

    if (pickDate) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val nd = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (!nd.isAfter(today)) date = nd.toString()
                    }
                    pickDate = false
                }) { Text("Kiezen") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Annuleren") } },
        ) { DatePicker(state) }
    }
}

/** Nieuwe, lege training voor het formulier: vandaag, een uur geleden, 30 minuten hardlopen. */
fun newManualWorkout(): Workout {
    val start = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MINUTES)
    return Workout(null, "manual", "m-" + UUID.randomUUID(), "running", null, start.atZone(ZoneId.systemDefault()).toLocalDate(),
        start, 1800, null, null, null, null, null, null, null, null)
}

/* ------------------------------------------------------------- Importeren ------------------------------------------------------------- */

/** Wat er in de gekozen bestanden staat, voordat het wordt opgeslagen. */
@Composable
fun WorkoutImportScreen(vm: MainViewModel, r: WorkoutFiles.Result) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        VTopBar("Importeren", onBack = { vm.workoutImport = null })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                VCard(Modifier.fillMaxWidth()) {
                    Text(
                        when (r.workouts.size) { 0 -> "Geen trainingen gevonden"; 1 -> "1 training gevonden"; else -> "${r.workouts.size} trainingen gevonden" },
                        style = MaterialTheme.typography.titleMedium, color = p.ink,
                    )
                    Text("Uit ${r.files} ${if (r.files == 1) "bestand" else "bestanden"}." +
                        (if (r.skipped > 0) " ${r.skipped} ${if (r.skipped == 1) "onderdeel kon" else "onderdelen konden"} niet worden gelezen en ${if (r.skipped == 1) "wordt" else "worden"} overgeslagen." else "") +
                        " Een bestand dat je al eerder importeerde, wordt bijgewerkt en niet dubbel opgeslagen.",
                        style = MaterialTheme.typography.bodyMedium, color = p.muted)
                    if (r.workouts.isEmpty()) Expandable("Welke bestanden werken?") { InfoParagraph(IMPORT_HELP) }
                    if (r.workouts.isNotEmpty()) Button(onClick = { vm.saveImportedWorkouts(r.workouts) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (r.workouts.size == 1) "Opslaan" else "Alle ${r.workouts.size} opslaan")
                    }
                }
            }
            items(r.workouts.take(500), key = { it.externalId }) { w -> WorkoutRow(w.copy(source = "file")) {} }
        }
    }
}

const val IMPORT_HELP = "FIT-bestanden van Garmin, Suunto, Polar, Coros, Wahoo en Zwift, en JSON uit Strava, Garmin Connect, " +
    "de Google Fit-export (Takeout) en de export van Vital Connect zelf. Je kunt meerdere bestanden tegelijk kiezen."

/** Alinea met uitleg; in een Expandable. */
@Composable
fun InfoParagraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.ink)
}
