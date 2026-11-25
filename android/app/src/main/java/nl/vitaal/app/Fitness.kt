package nl.vitaal.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import kotlin.math.roundToInt

/*
 * Herstel en fitheid. De server rekent (functie fitness in app_state); de app toont alleen.
 * Methodes en bronnen: docs/berekeningen.md.
 */

/** HRV of rusthartslag: 7-daags gemiddelde met het eigen normale bereik. */
data class Band(
    val ready: Boolean, val value: Double?, val low: Double?, val high: Double?, val min: Double?, val max: Double?,
    val state: String?, val bad: Boolean, val n7: Int, val nb: Int,
)

data class SleepFactor(val ready: Boolean, val last: Double?, val week: Double?, val need: Double, val bad: Boolean, val n7: Int)

data class HrvPoint(val day: LocalDate, val value: Double, val low: Double?, val high: Double?)

data class Herstel(val score: Int?, val stand: Int?, val hrv: Band, val rhr: Band, val sleep: SleepFactor, val trend: List<HrvPoint>)

data class Zone(val zone: Int, val from: Int, val to: Int)

data class Fitheid(
    val age: Int?, val sex: String?, val hrmax: Int?, val zones: List<Zone>, val rhr7: Double?,
    val vo2Source: Double?, val vo2Day: LocalDate?, val vo2Estimate: Double?, val vo2Factor: Double?,
)

data class Fitness(val herstel: Herstel, val fitheid: Fitheid)

private fun JsonObject.obj(k: String) = this[k] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.objs(k: String) = (this[k] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

private fun band(o: JsonObject) = Band(
    ready = o.bool("ready") == true, value = o.num("value"), low = o.num("low"), high = o.num("high"),
    min = o.num("min"), max = o.num("max"), state = o.str("state").ifEmpty { null }, bad = o.bool("bad") == true,
    n7 = o.int("n7") ?: 0, nb = o.int("nb") ?: 0,
)

/** Leest het veld fitness van app_state; null bij een oudere server. */
fun parseFitness(o: JsonObject?): Fitness? {
    if (o == null) return null
    val h = o.obj("herstel")
    val s = h.obj("sleep")
    val f = o.obj("fitheid")
    return Fitness(
        herstel = Herstel(
            score = h.int("score"), stand = h.int("stand"), hrv = band(h.obj("hrv")), rhr = band(h.obj("rhr")),
            sleep = SleepFactor(s.bool("ready") == true, s.num("last"), s.num("week"), s.num("need") ?: 8.0, s.bool("bad") == true, s.int("n7") ?: 0),
            trend = h.objs("trend").mapNotNull { t -> day(t.str("day"))?.let { d -> t.num("value")?.let { HrvPoint(d, it, t.num("low"), t.num("high")) } } },
        ),
        fitheid = Fitheid(
            age = f.int("age"), sex = f.str("sex").ifEmpty { null }, hrmax = f.int("hrmax"),
            zones = f.objs("zones").mapNotNull { z -> Zone(z.int("zone") ?: return@mapNotNull null, z.int("from") ?: 0, z.int("to") ?: 0) },
            rhr7 = f.num("rhr7"), vo2Source = f.num("vo2_source"), vo2Day = day(f.str("vo2_day")),
            vo2Estimate = f.num("vo2_estimate"), vo2Factor = f.num("vo2_factor"),
        ),
    )
}

/** Oordeel: titel, kleur van de stip en advies. Mild geformuleerd: rust is geen mislukking. */
private data class Stand(val title: String, val color: Color, val advice: String)

@Composable
private fun standOf(i: Int?): Stand {
    val p = LocalPalette.current
    return when (i) {
        0 -> Stand("Goed hersteld", p.good, "Je lichaam is hersteld. Ruimte voor een stevige training.")
        1 -> Stand("Let op", p.warn, "Eén signaal wijkt af van je normaal. Train gerust, maar luister naar je lichaam.")
        2 -> Stand("Rustig aan", p.sleep, "Meerdere signalen wijzen op vermoeidheid. Kies vandaag voor rust of een rustige training.")
        else -> Stand("Nog aan het leren", p.muted, "")
    }
}

private fun stateText(b: Band, lowerBetter: Boolean): String = when (b.state) {
    "normaal" -> "Binnen je normale bereik."
    "hoog" -> if (lowerBetter) "Hoger dan normaal: vaak een teken van vermoeidheid of een opkomende verkoudheid." else "Hoger dan normaal: goed hersteld."
    "laag" -> if (lowerBetter) "Lager dan normaal: goed hersteld." else "Lager dan normaal: je lichaam staat onder spanning."
    else -> ""
}

private fun learning(b: Band): String =
    "Nog aan het leren: ${minOf(b.n7, 3)} van 3 metingen deze week, ${minOf(b.nb, 14)} van 14 dagen basislijn."

/** Tegel op Vandaag: score, oordeel en de drie factoren. Tik voor uitleg, verloop en fitheid. */
@Composable
fun HerstelTile(vm: MainViewModel, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val h = vm.fitness?.herstel ?: return
    val st = standOf(h.stand)
    VCard(modifier, onClick = { vm.showHerstel = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(p.recovery, 9.dp)
            Spacer(Modifier.width(8.dp))
            Text("Herstel vandaag", style = MaterialTheme.typography.labelMedium, color = p.muted, modifier = Modifier.weight(1f))
        }
        if (h.score == null) {
            Text(learning(h.hrv), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            Text("Herstel volgt uit je HRV en rusthartslag van de afgelopen week, vergeleken met je eigen normaal.",
                style = MaterialTheme.typography.bodySmall, color = p.muted)
            return@VCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(st.color, 10.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(st.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
                }
            }
            Text("${h.score}", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = p.ink, modifier = Modifier.semantics { contentDescription = "Herstelscore ${h.score} van 100" })
        }
        Text(st.advice, style = MaterialTheme.typography.bodyMedium, color = p.ink)
        FactorRow("HRV, 7 dagen", h.hrv, "ms", lowerBetter = false, short = true)
        FactorRow("Rusthartslag, 7 dagen", h.rhr, "bpm", lowerBetter = true, short = true)
        SleepRow(h.sleep, short = true)
    }
}

@Composable
private fun FactorRow(name: String, b: Band, unit: String, lowerBetter: Boolean, short: Boolean) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f))
        Text(b.value?.let { "${it.roundToInt()} $unit" } ?: "–", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = p.ink)
    }
    if (!b.ready) {
        Text(learning(b), style = MaterialTheme.typography.bodySmall, color = p.muted)
        return
    }
    Text("Normaal ${b.low!!.roundToInt()}–${b.high!!.roundToInt()} $unit", style = MaterialTheme.typography.bodySmall, color = p.muted)
    BandBar(b, Modifier.fillMaxWidth(), "$name ${b.value!!.roundToInt()} $unit, normaal ${b.low.roundToInt()} tot ${b.high.roundToInt()}")
    if (!short || b.bad) Text(stateText(b, lowerBetter), style = MaterialTheme.typography.bodySmall, color = p.muted)
}

@Composable
private fun SleepRow(s: SleepFactor, short: Boolean) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("Slaap", style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f))
        Text(s.last?.let { fmtHours(it) } ?: "–", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = p.ink)
    }
    if (!s.ready) {
        Text("Nog aan het leren: ${minOf(s.n7, 3)} van 3 nachten deze week.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        return
    }
    Text("7 nachten ${fmtHours(s.week ?: 0.0)} gemiddeld, doel ${fmtHours(s.need)}", style = MaterialTheme.typography.bodySmall, color = p.muted)
    if (!short || s.bad) Text(if (s.bad) "Je slaapt deze week minder dan 90% van je doel." else "Je slaapt deze week genoeg.",
        style = MaterialTheme.typography.bodySmall, color = p.muted)
}

/** Balk met het normale bereik (band) en een stip voor het 7-daagse gemiddelde. */
@Composable
private fun BandBar(b: Band, modifier: Modifier, description: String) {
    val p = LocalPalette.current
    val lo = b.min ?: return
    val hi = b.max ?: return
    fun pos(x: Double) = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()
    BoxWithConstraints(modifier.height(16.dp).semantics { contentDescription = description }) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(10.dp).align(Alignment.CenterStart).clip(RoundedCornerShape(5.dp)).background(p.bg))
        Box(Modifier.offset(x = w * pos(b.low!!)).width(w * (pos(b.high!!) - pos(b.low))).height(10.dp)
            .align(Alignment.CenterStart).clip(RoundedCornerShape(5.dp)).background(p.recovery.copy(alpha = 0.45f)))
        Box(Modifier.offset(x = w * pos(b.value!!) - 8.dp).size(16.dp).clip(CircleShape).background(p.card).padding(2.dp)
            .clip(CircleShape).background(if (b.bad) p.bad else p.ink))
    }
}

/** HRV over 30 dagen: het 7-daagse gemiddelde als lijn, het normale bereik als band. */
@Composable
private fun HrvTrend(points: List<HrvPoint>, modifier: Modifier) {
    val p = LocalPalette.current
    val band = p.recovery.copy(alpha = 0.3f)
    val line = p.ink
    Canvas(modifier.semantics { contentDescription = "HRV over 30 dagen met je normale bereik" }) {
        if (points.size < 2) return@Canvas
        val vals = points.flatMap { listOfNotNull(it.value, it.low, it.high) }
        val lo = vals.min() * 0.95
        val hi = vals.max() * 1.05
        val first = points.first().day
        val span = (points.last().day.toEpochDay() - first.toEpochDay()).coerceAtLeast(1).toFloat()
        fun x(d: LocalDate) = (d.toEpochDay() - first.toEpochDay()) / span * size.width
        fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
        val withBand = points.filter { it.low != null && it.high != null }
        if (withBand.size >= 2) {
            val area = Path()
            withBand.forEachIndexed { i, pt -> if (i == 0) area.moveTo(x(pt.day), y(pt.high!!)) else area.lineTo(x(pt.day), y(pt.high!!)) }
            withBand.asReversed().forEach { pt -> area.lineTo(x(pt.day), y(pt.low!!)) }
            area.close()
            drawPath(area, band)
        }
        val path = Path()
        points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(x(pt.day), y(pt.value)) else path.lineTo(x(pt.day), y(pt.value)) }
        drawPath(path, line, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = points.last()
        drawCircle(line, radius = 4.dp.toPx(), center = Offset(x(last.day), y(last.value)))
    }
}

/** Detail van herstel: verloop, factoren, fitheid (max. hartslag, zones, VO2max) en de uitleg. */
@Composable
fun HerstelScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val fit = vm.fitness
    Column(Modifier.fillMaxSize()) {
        VTopBar("Herstel en fitheid", onBack = { vm.showHerstel = false })
        if (fit == null) {
            Text("Nog geen gegevens.", color = p.muted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        val h = fit.herstel
        val st = standOf(h.stand)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Herstel vandaag", style = MaterialTheme.typography.labelMedium, color = p.muted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(st.color, 10.dp); Spacer(Modifier.width(8.dp))
                            Text(st.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
                        }
                    }
                    Text(h.score?.toString() ?: "–", style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = p.ink)
                }
                if (st.advice.isNotEmpty()) Text(st.advice, style = MaterialTheme.typography.bodyMedium, color = p.ink)
                FactorRow("HRV, 7 dagen", h.hrv, "ms", lowerBetter = false, short = false)
                FactorRow("Rusthartslag, 7 dagen", h.rhr, "bpm", lowerBetter = true, short = false)
                SleepRow(h.sleep, short = false)
            }

            VCard(Modifier.fillMaxWidth()) {
                Expandable("Wat zegt dit over je lichaam?") { BODY_INFO["herstel"]?.forEach { InfoParagraph(it) } }
            }

            if (h.trend.size >= 2) VCard(Modifier.fillMaxWidth()) {
                Text("HRV, laatste 30 dagen", style = MaterialTheme.typography.titleMedium, color = p.ink)
                Text("Lijn: je 7-daagse gemiddelde. Band: je normale bereik.", style = MaterialTheme.typography.bodySmall, color = p.muted)
                HrvTrend(h.trend, Modifier.fillMaxWidth().height(160.dp).padding(vertical = 8.dp))
            }

            val f = fit.fitheid
            VCard(Modifier.fillMaxWidth()) {
                Text("Fitheid", style = MaterialTheme.typography.titleMedium, color = p.ink)
                if (f.hrmax == null) {
                    Text("Vul je geboortedatum in bij Profiel. Dan berekenen we je maximale hartslag, je hartslagzones en een schatting van je VO2max.",
                        style = MaterialTheme.typography.bodyMedium, color = p.muted)
                    ActionChip("Naar Profiel") { vm.showHerstel = false; vm.profileTab = ProfileTab.Profile; vm.showSettings = true }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("Max. hartslag (geschat)", "${f.hrmax} bpm", Modifier.weight(1f))
                        Stat("Rusthartslag, 7 dagen", f.rhr7?.let { "${it.roundToInt()} bpm" } ?: "–", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("VO2max van je horloge", f.vo2Source?.let { "${fmtOne(it)} ml/kg/min" } ?: "–", Modifier.weight(1f))
                        Stat("VO2max (schatting)", f.vo2Estimate?.let { "${fmtOne(it)} ml/kg/min" } ?: "–", Modifier.weight(1f))
                    }
                    if (f.zones.isNotEmpty()) {
                        Text("Hartslagzones", style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.padding(top = 4.dp))
                        val names = listOf("Herstel", "Basis", "Tempo", "Drempel", "Maximaal")
                        f.zones.forEach { z ->
                            Row {
                                Text("Zone ${z.zone} · ${names.getOrElse(z.zone - 1) { "" }}", style = MaterialTheme.typography.bodyMedium, color = p.ink, modifier = Modifier.weight(1f))
                                Text("${z.from}–${z.to} bpm", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = p.ink)
                            }
                        }
                    }
                    if (f.sex == null || f.sex == "other") Text("Geslacht niet ingevuld: de VO2max-schatting gebruikt de middelste factor (15).",
                        style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
            }

            VCard(Modifier.fillMaxWidth()) {
                Expandable("Hoe berekenen we dit?") {
                listOf(
                    "Herstel volgt de methode die sportwetenschappers gebruiken om herstel te volgen met HRV. Je HRV (RMSSD, gemeten tijdens je slaap) wordt logaritmisch omgerekend en over 7 dagen gemiddeld; één losse nacht zegt weinig. Dat gemiddelde zetten we tegen je eigen basislijn van de 60 dagen ervoor. Je normale bereik is die basislijn plus en min de kleinste zinvolle verandering (een halve standaardafwijking). Je rusthartslag volgen we op dezelfde manier.",
                    "De score van 0 tot 100 telt HRV voor de helft, rusthartslag en slaap elk voor een kwart. Die weging is onze keuze; het oordeel (goed, let op, rustig aan) komt uit de banden. Na 14 dagen meten is er een eerste basislijn, na 60 dagen een volledige.",
                    "Maximale hartslag: 208 − 0,7 × leeftijd (Tanaka). Hartslagzones: 50–60, 60–70, 70–80, 80–90 en 90–100% daarvan. VO2max-schatting: factor × maximale hartslag ÷ rusthartslag (Uth), met factor 15,3 voor mannen en 14,5 voor vrouwen. De schatting is ontwikkeld bij getrainde volwassenen; een waarde van je horloge of een inspanningstest is nauwkeuriger.",
                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.ink) }
                Text("Bronnen: Plews e.a. (2012, 2013, 2014); Australian Institute of Sport, richtlijn HRV; Tanaka e.a. (2001); Uth e.a. (2004, 2005).",
                    style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
                Text("Geen medisch advies.", style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        }
    }
}

private fun fmtOne(v: Double) = String.format(NL, "%.1f", v)
