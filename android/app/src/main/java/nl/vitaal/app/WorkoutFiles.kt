package nl.vitaal.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Trainingen uit bestanden: FIT (Garmin, Suunto, Wahoo, Polar, Coros, Zwift …) en JSON
 * (Strava, Garmin Connect, Google Fit-export, Google Health API en de eigen export van Vital Connect).
 * Alles wordt per training gelezen: één onleesbare training slaat alleen die over.
 */
object WorkoutFiles {
    class Result(val workouts: List<Workout>, val skipped: Int, val files: Int)

    fun read(name: String, bytes: ByteArray, zone: ZoneId): Result {
        val fit = bytes.size >= 12 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte()
        return try {
            if (fit) Fit(bytes, hash(bytes), zone).parse() else JsonWorkouts(hash(bytes), zone).parse(bytes.decodeToString())
        } catch (e: Exception) {
            Result(emptyList(), 1, 1)
        } catch (e: StackOverflowError) {
            // Vangnet: een bestand mag de app nooit laten vastlopen
            Result(emptyList(), 1, 1)
        }
    }

    private fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).take(8).joinToString("") { "%02x".format(it) }
}

/** Gemeenschappelijke sportnamen → vaste sleutels (zelfde lijst als de database). */
fun sportFromText(t: String?): String {
    val s = t.orEmpty().uppercase().replace(' ', '_').replace('-', '_')
    return when {
        s.isEmpty() -> "other"
        Regex("TRAIL_RUN|RUN|JOG").containsMatchIn(s) -> "running"
        Regex("HIK|MOUNTAINEER").containsMatchIn(s) -> "hiking"
        Regex("WALK").containsMatchIn(s) -> "walking"
        Regex("BIK|CYCL|RIDE|SPIN").containsMatchIn(s) -> "cycling"
        Regex("SWIM").containsMatchIn(s) -> "swimming"
        Regex("STRENGTH|WEIGHT|CALISTHENIC|CROSSFIT|CIRCUIT|GYM").containsMatchIn(s) -> "strength"
        Regex("INTERVAL|HIIT|BOOT_CAMP|CARDIO").containsMatchIn(s) -> "hiit"
        Regex("YOGA|PILATES|STRETCH|BREATH").containsMatchIn(s) -> "yoga"
        Regex("ROW|KAYAK|CANOE|PADDL").containsMatchIn(s) -> "rowing"
        Regex("ELLIPTICAL|STAIR|STEPPER").containsMatchIn(s) -> "elliptical"
        Regex("SOCCER|FOOTBALL|BASKETBALL|VOLLEYBALL|HOCKEY|HANDBALL|RUGBY|CRICKET|BASEBALL|SOFTBALL|KORFBAL").containsMatchIn(s) -> "team"
        Regex("TENNIS|BADMINTON|SQUASH|PADEL|PICKLEBALL|RACQUET|RACKET").containsMatchIn(s) -> "racket"
        Regex("DANC|ZUMBA|BARRE").containsMatchIn(s) -> "dance"
        Regex("BOX|KARATE|JIU|JUDO|MARTIAL|TAEKWONDO").containsMatchIn(s) -> "martial"
        Regex("SKI|SNOWBOARD|SKAT|SNOWSHOE").containsMatchIn(s) -> "winter"
        else -> "other"
    }
}

/* ---------------------------------------------------------------- FIT ---------------------------------------------------------------- */

/**
 * Lezer voor het FIT-formaat (Flexible and Interoperable Data Transfer, Garmin/ANT+).
 * Leest de berichten session (18) voor de samenvatting per sport, record (20) voor de hartslag als de samenvatting die mist,
 * en activity (34) voor de lokale tijd. Ondersteunt definities in beide bytevolgordes, ontwikkelaarsvelden en
 * gecomprimeerde tijdstempels.
 */
private class Fit(private val b: ByteArray, private val hash: String, private val zone: ZoneId) {
    private class Field(val num: Int, val size: Int, val base: Int)
    private class Def(val global: Int, val big: Boolean, val fields: List<Field>, val devSize: Int) {
        val size = fields.sumOf { it.size } + devSize
    }

    private val sessions = mutableListOf<Map<Int, Double>>()
    private val hr = mutableListOf<Pair<Long, Int>>()
    private var localOffset: Long? = null

    fun parse(): WorkoutFiles.Result {
        val hs = b[0].toInt() and 0xFF
        require(hs == 12 || hs == 14) { "Geen FIT-kop" }
        val dataSize = u(hs = 4, n = 4, big = false)
        val end = minOf(hs.toLong() + dataSize, b.size.toLong()).toInt()
        val defs = arrayOfNulls<Def>(16)
        var pos = hs
        var lastTs = 0L
        while (pos < end) {
            val h = b[pos++].toInt() and 0xFF
            when {
                h and 0x80 != 0 -> { // gecomprimeerde tijdstempel: datamelding met 5 bits tijdverschil
                    val d = defs[(h shr 5) and 0x3] ?: return done()
                    val off = (h and 0x1F).toLong()
                    lastTs = (lastTs and 0x1FL.inv()) + off + if (off < (lastTs and 0x1F)) 0x20 else 0
                    if (pos + d.size > end) return done()
                    handle(d, values(d, pos), lastTs); pos += d.size
                }
                h and 0x40 != 0 -> { // definitie
                    if (pos + 5 > end) return done()
                    val big = b[pos + 1].toInt() == 1
                    val global = u(pos + 2, 2, big).toInt()
                    val n = b[pos + 4].toInt() and 0xFF
                    pos += 5
                    val fields = (0 until n).map { Field(b[pos + 3 * it].toInt() and 0xFF, b[pos + 3 * it + 1].toInt() and 0xFF, b[pos + 3 * it + 2].toInt() and 0xFF) }
                    pos += 3 * n
                    var dev = 0
                    if (h and 0x20 != 0) {
                        val nd = b[pos++].toInt() and 0xFF
                        for (i in 0 until nd) dev += b[pos + 3 * i + 1].toInt() and 0xFF
                        pos += 3 * nd
                    }
                    defs[h and 0x0F] = Def(global, big, fields, dev)
                }
                else -> { // gewone datamelding
                    val d = defs[h and 0x0F] ?: return done()
                    if (pos + d.size > end) return done()
                    val v = values(d, pos)
                    v[253]?.let { lastTs = it.toLong() }
                    handle(d, v, lastTs); pos += d.size
                }
            }
        }
        return done()
    }

    private fun handle(d: Def, v: Map<Int, Double>, ts: Long) {
        when (d.global) {
            18 -> sessions += v
            20 -> v[3]?.let { if (it in 25.0..250.0) hr += ts to it.toInt() }
            34 -> { val t = v[253]; val local = v[5]; if (t != null && local != null) localOffset = (local - t).toLong() }
        }
    }

    private fun done(): WorkoutFiles.Result {
        val out = mutableListOf<Workout>()
        var skipped = 0
        sessions.forEachIndexed { i, s ->
            val start = s[2]?.toLong() ?: run { skipped++; return@forEachIndexed }
            val dur = ((s[8] ?: s[7])?.div(1000))?.toInt() ?: run { skipped++; return@forEachIndexed }
            if (dur !in 60..86400) { skipped++; return@forEachIndexed }
            val instant = Instant.ofEpochSecond(start + FIT_EPOCH)
            val day = localOffset?.let { instant.plusSeconds(it).atOffset(ZoneOffset.UTC).toLocalDate() } ?: instant.atZone(zone).toLocalDate()
            // Hartslag uit de losse metingen als de samenvatting hem niet heeft
            val inSession = hr.filter { it.first >= start && it.first <= start + (s[7]?.div(1000) ?: dur.toDouble()).toLong() }.map { it.second }
            out += Workout(
                id = null, source = "file", externalId = "fit-$hash-$i", sport = fitSport(s[5]?.toInt(), s[6]?.toInt()),
                title = null, day = day, startedAt = instant, durationS = dur,
                distanceM = s[9]?.div(100), kcal = s[11], avgHr = s[16]?.toInt() ?: inSession.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                maxHr = s[17]?.toInt() ?: inSession.maxOrNull(), ascentM = s[22], notes = null, origin = "FIT-bestand", load = null,
            )
        }
        return WorkoutFiles.Result(out, skipped, 1)
    }

    /** Waarden van een datamelding; ongeldige FIT-waarden (alle bits 1, of 0 bij de z-types) worden overgeslagen. */
    private fun values(d: Def, at: Int): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        var p = at
        for (f in d.fields) {
            val base = f.base and 0x1F
            val width = when (base) { 0, 1, 2, 7, 10, 13 -> 1; 3, 4, 11 -> 2; 5, 6, 8, 12 -> 4; 9, 14, 15, 16 -> 8; else -> 1 }
            if (f.size == width && base != 7 && base != 13) {
                val raw = u(p, width, d.big)
                val v: Double? = when (base) {
                    1 -> raw.toByte().toDouble().takeIf { raw != 0x7FL }
                    3 -> raw.toShort().toDouble().takeIf { raw != 0x7FFFL }
                    5 -> raw.toInt().toDouble().takeIf { raw != 0x7FFFFFFFL }
                    8 -> Float.fromBits(raw.toInt()).toDouble().takeIf { raw != 0xFFFFFFFFL && it.isFinite() }
                    9 -> Double.fromBits(raw).takeIf { it.isFinite() }
                    10, 11, 12 -> raw.toDouble().takeIf { raw != 0L }
                    14 -> raw.toDouble().takeIf { raw != 0x7FFFFFFFFFFFFFFFL }
                    else -> raw.toDouble().takeIf { raw != (if (width == 8) -1L else (1L shl (8 * width)) - 1) }
                }
                if (v != null) out[f.num] = v
            }
            p += f.size
        }
        return out
    }

    private fun u(hs: Int, n: Int, big: Boolean): Long {
        var v = 0L
        for (i in 0 until n) {
            val byte = b[hs + i].toLong() and 0xFF
            v = if (big) (v shl 8) or byte else v or (byte shl (8 * i))
        }
        return v
    }

    companion object {
        /** FIT-tijd telt vanaf 31 december 1989 00:00 UTC. */
        const val FIT_EPOCH = 631065600L

        fun fitSport(sport: Int?, sub: Int?): String = when (sport) {
            1 -> "running"
            2, 21 -> "cycling"
            5 -> "swimming"
            6, 7, 9 -> "team"
            8, 64 -> "racket"
            10 -> when (sub) { 20 -> "strength"; 43, 44 -> "yoga"; 26 -> "hiit"; else -> "strength" }
            11 -> "walking"
            12, 13, 14, 33, 35 -> "winter"
            15, 19, 37, 41 -> "rowing"
            16, 17 -> "hiking"
            4 -> when (sub) { 14 -> "rowing"; 15, 16 -> "elliptical"; else -> "other" }
            47 -> "martial"
            62 -> "hiit"
            else -> "other"
        }
    }
}

/* ---------------------------------------------------------------- JSON ---------------------------------------------------------------- */

/**
 * JSON van verschillende apps. Herkent per training de gangbare veldnamen:
 * - Vital Connect: sport, started_at, day, duration_s, distance_m, kcal, avg_hr, max_hr, ascent_m, title, notes
 * - Strava: sport_type/type, start_date(_local), elapsed_time/moving_time, distance, calories, average_heartrate, max_heartrate,
 *   total_elevation_gain, name, id
 * - Garmin Connect: activityType.typeKey, startTimeGMT/startTimeLocal, duration, distance, calories, averageHR, maxHR,
 *   elevationGain, activityName, activityId
 * - Google Fit-export: fitnessActivity, startTime, endTime, duration ("1800s"), aggregate[] met calorieën en afstand
 * - Google Health API: exercise { exerciseType, interval, activeDuration, metricsSummary }
 */
private class JsonWorkouts(private val hash: String, private val zone: ZoneId) {
    private companion object { const val MAX_DEPTH = 64 }
    private var skipped = 0

    fun parse(text: String): WorkoutFiles.Result {
        val clean = text.trimStart('\uFEFF')
        // Een extreem diep genest bestand ([[[[…) laat de JSON-lezer de stack opgebruiken; zo'n bestand is nooit een export
        require(depth(clean) <= MAX_DEPTH) { "Te diep genest" }
        val root = Json.parseToJsonElement(clean)
        val items = list(root)
        val out = items.mapIndexedNotNull { i, e -> runCatching { one(e as JsonObject, i) }.getOrNull().also { if (it == null) skipped++ } }
        return WorkoutFiles.Result(out, skipped, 1)
    }

    private fun list(e: JsonElement): List<JsonElement> = when (e) {
        // Garmin-gegevensexport: [{"summarizedActivitiesExport": [ … ]}]
        is JsonArray -> e.flatMap { x -> ((x as? JsonObject)?.get("summarizedActivitiesExport") as? JsonArray)?.toList() ?: listOf(x) }
        is JsonObject -> listOf("workouts", "activities", "dataPoints", "session", "sessions", "items", "data")
            .firstNotNullOfOrNull { k -> (e[k] as? JsonArray)?.toList() } ?: listOf(e)
        else -> emptyList()
    }

    private fun one(o0: JsonObject, i: Int): Workout? {
        garminExport(o0, i)?.let { return it }
        val o = (o0["exercise"] as? JsonObject) ?: o0
        val interval = o["interval"] as? JsonObject
        val startText = str(o, "started_at", "start_date", "startTimeGMT", "startTime", "start_time", "start")
            ?: interval?.let { str(it, "startTime") }
        val startMs = num(o0, "startTimeMillis")?.toLong()
        val start = startText?.let(::time) ?: startMs?.let { Instant.ofEpochMilli(it) } ?: return null
        val endText = str(o, "endTime", "end_time", "end_date") ?: interval?.let { str(it, "endTime") }
        val dur = num(o, "duration_s", "elapsed_time", "moving_time", "duration", "activeDuration", "elapsedDuration", "movingDuration")
            ?: endText?.let(::time)?.let { (it.epochSecond - start.epochSecond).toDouble() } ?: return null
        val localText = str(o, "start_date_local", "startTimeLocal")
        val day = str(o, "day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: localText?.let { runCatching { LocalDateTime.parse(it.replace(' ', 'T').removeSuffix("Z")).toLocalDate() }.getOrNull() }
            ?: start.atZone(zone).toLocalDate()
        val type = str(o, "sport", "sport_type", "type", "fitnessActivity", "exerciseType", "activityType")
            ?: (o["activityType"] as? JsonObject)?.let { str(it, "typeKey") }
        val sport = if (type != null && type in SPORT_KEYS) type else sportFromText(type)
        val summary = (o["metricsSummary"] as? JsonObject)
        val agg = (o["aggregate"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        fun aggregate(name: String) = agg.firstOrNull { str(it, "metricName")?.contains(name) == true }?.let { num(it, "floatValue", "intValue") }
        val distance = num(o, "distance_m", "distance") ?: summary?.let { num(it, "distanceMillimeters")?.div(1000) } ?: aggregate("distance")
        val kcal = num(o, "kcal", "calories", "active_calories") ?: summary?.let { num(it, "caloriesKcal") } ?: aggregate("calories")
        val avg = num(o, "avg_hr", "average_heartrate", "averageHR", "averageHeartRate") ?: summary?.let { num(it, "averageHeartRateBeatsPerMinute") }
        val max = num(o, "max_hr", "max_heartrate", "maxHR", "maxHeartRate")
        val ascent = num(o, "ascent_m", "total_elevation_gain", "elevationGain") ?: summary?.let { num(it, "elevationGainMillimeters")?.div(1000) }
        // Sleutel voor opnieuw importeren: een id als tekst of als getal (Strava, Garmin Connect), of de naam van een
        // Google Health-datapunt. Nooit de titel: twee trainingen met dezelfde naam zouden elkaar overschrijven.
        val id = (str(o0, "external_id", "id", "activityId") ?: wholeNumber(o0, "id", "activityId")
            ?: str(o0, "name")?.takeIf { it.startsWith("users/") }?.substringAfterLast('/'))?.takeIf { it.length in 1..120 }
        return Workout(
            id = null, source = "file",
            externalId = "json-" + (id?.replace(Regex("[^A-Za-z0-9_.:@+=-]"), "_") ?: "$hash-$i"),
            sport = sport, title = str(o, "title", "displayName", "activityName")
                ?: str(o0, "name")?.takeIf { !it.startsWith("users/") }, day = day, startedAt = start,
            durationS = dur.toInt().takeIf { it in 60..86400 } ?: return null,
            distanceM = distance?.takeIf { it in 0.0..1_000_000.0 }, kcal = kcal?.takeIf { it in 0.0..20_000.0 },
            avgHr = avg?.toInt()?.takeIf { it in 25..250 }, maxHr = max?.toInt()?.takeIf { it in 25..250 },
            ascentM = ascent?.takeIf { it in 0.0..20_000.0 }, notes = str(o, "notes", "description")?.take(1000),
            origin = "JSON-bestand", load = null,
        )
    }

    /** Garmin-gegevensexport: tijd als getal (ms sinds 1970), duur in ms, afstand en hoogte in cm. */
    private fun garminExport(o: JsonObject, i: Int): Workout? {
        val startMs = (o["startTimeGmt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        val start = Instant.ofEpochMilli(startMs.toLong())
        val localMs = (o["startTimeLocal"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val dur = (num(o, "duration")?.div(1000))?.toInt()?.takeIf { it in 60..86400 } ?: return null
        val id = num(o, "activityId")?.toLong()
        return Workout(
            id = null, source = "file", externalId = "garmin-" + (id?.toString() ?: "$hash-$i"),
            sport = sportFromText(str(o, "activityType", "sportType")), title = str(o, "name"),
            day = (localMs?.let { Instant.ofEpochMilli(it.toLong()).atOffset(ZoneOffset.UTC).toLocalDate() } ?: start.atZone(zone).toLocalDate()),
            startedAt = start, durationS = dur, distanceM = num(o, "distance")?.div(100)?.takeIf { it in 0.0..1_000_000.0 },
            kcal = null, avgHr = num(o, "avgHr")?.toInt()?.takeIf { it in 25..250 }, maxHr = num(o, "maxHr")?.toInt()?.takeIf { it in 25..250 },
            ascentM = num(o, "elevationGain")?.div(100)?.takeIf { it in 0.0..20_000.0 }, notes = null, origin = "Garmin-export", load = null,
        )
    }

    private fun str(o: JsonObject, vararg keys: String) = keys.firstNotNullOfOrNull { k ->
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }

    /** Grootste nesting van [ en { buiten tekst; stopt zodra de grens is overschreden. */
    private fun depth(s: String): Int {
        var d = 0; var max = 0; var inText = false; var esc = false
        for (c in s) {
            if (inText) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inText = false; continue }
            when (c) {
                '"' -> inText = true
                '[', '{' -> { d++; if (d > max) { max = d; if (max > MAX_DEPTH) return max } }
                ']', '}' -> d--
            }
        }
        return max
    }

    /** Een id die als geheel getal is opgeslagen (Strava, Garmin Connect), als tekst. */
    private fun wholeNumber(o: JsonObject, vararg keys: String) = keys.firstNotNullOfOrNull { k ->
        (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.takeIf { it.matches(Regex("-?\\d{1,19}")) }
    }

    /** Getal, ook als tekst ("1800", "1800s", "1800.5s"). */
    private fun num(o: JsonObject, vararg keys: String) = keys.firstNotNullOfOrNull { k ->
        val p = o[k] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
        p.doubleOrNull ?: p.content.removeSuffix("s").toDoubleOrNull()
    }?.takeIf { it.isFinite() }

    /** ISO-tijd met of zonder zone, of Garmin-stijl "2024-05-01 06:30:00" (UTC). */
    private fun time(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(s) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC) }.getOrNull()
}
