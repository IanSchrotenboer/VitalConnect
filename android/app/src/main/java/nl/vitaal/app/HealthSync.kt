package nl.vitaal.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/**
 * Leest Health Connect uit, maakt er dagwaarden per metric en per bron-app van
 * en stuurt die naar Supabase (RPC ingest_health_connect).
 *
 * Eerste keer: volledige synchronisatie (30 dagen, of 365 met de history-toestemming).
 * Daarna: alleen de dagen die volgens de Changes API gewijzigd zijn; zonder wijzigingen wordt niets verstuurd.
 */
class HealthSync(context: Context, private val api: Api) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("vitaal_sync", Context.MODE_PRIVATE)
    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val lastSync: Long get() = prefs.getLong(LAST_SYNC, 0)

    suspend fun granted(): Set<String> = client.permissionController.getGrantedPermissions()

    fun reset() = prefs.edit().clear().apply()

    /** Synchroniseert en geeft het aantal geüploade dagwaarden terug. */
    suspend fun sync(): Int {
        val granted = granted()
        val types = READ_TYPES.filter { HealthPermission.getReadPermission(it) in granted }
        if (types.isEmpty()) return 0

        val today = LocalDate.now(zone)
        // Een andere sleutel laat de geschiedenis één keer opnieuw uitlezen: "hrv-nacht" (1.10, HRV tijdens de slaap),
        // "training" (1.11, oefensessies als trainingen)
        val typesKey = "training;hrv-nacht;" + types.joinToString(",") { it.simpleName.orEmpty() }
        val saved = prefs.getString(CHANGES_TOKEN, null)
        val history = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted

        // Geen token of andere toestemmingen dan vorige keer: alles opnieuw
        if (saved == null || prefs.getString(TOKEN_TYPES, null) != typesKey) {
            return fullSync(types, today.minusDays(if (history) 365 else 29), today, typesKey)
        }

        val days = sortedSetOf<LocalDate>()
        val deleted = mutableListOf<String>()
        var token: String = saved
        do {
            val resp = client.getChanges(token)
            if (resp.changesTokenExpired) {
                return fullSync(types, today.minusDays(29), today, typesKey)
            }
            for (change in resp.changes) {
                when (change) {
                    is UpsertionChange -> days.addAll(recordDays(change.record))
                    // Een verwijdering zegt niet welke dag; de afgelopen week opnieuw berekenen
                    is DeletionChange -> { (0L..6L).forEach { days.add(today.minusDays(it)) }; deleted += change.recordId }
                    else -> Unit
                }
            }
            token = resp.nextChangesToken
        } while (resp.hasMore)

        val limit = today.minusDays(400)
        days.removeAll { it.isBefore(limit) || it.isAfter(today) }
        // Aaneengesloten reeksen apart uitlezen: één gewijzigde dag van maanden terug haalt niet alles daartussen op
        var n = 0
        runs(days).forEach { (a, b) -> n += upload(types, a, b) }
        // Een in Health Connect verwijderde oefensessie ook hier weghalen (andere verwijderde records negeert de server)
        deleted.chunked(1000).forEach { ids ->
            api.rpc("delete_workouts", buildJsonObject {
                put("p_source", "health_connect"); put("p_external", JsonArray(ids.map { JsonPrimitive(it) }))
            })
        }
        prefs.edit()
            .putString(CHANGES_TOKEN, token)
            .putLong(LAST_SYNC, System.currentTimeMillis())
            .apply()
        return n
    }

    private fun runs(days: Collection<LocalDate>): List<Pair<LocalDate, LocalDate>> {
        val out = mutableListOf<Pair<LocalDate, LocalDate>>()
        for (d in days.sorted()) {
            val last = out.lastOrNull()
            if (last != null && last.second.plusDays(1) == d) out[out.size - 1] = last.first to d else out += d to d
        }
        return out
    }

    private suspend fun fullSync(types: List<KClass<out Record>>, from: LocalDate, to: LocalDate, typesKey: String): Int {
        // Eerst het token, zodat wijzigingen tijdens het uitlezen niet verloren gaan
        val token = client.getChangesToken(ChangesTokenRequest(recordTypes = types.toSet()))
        val n = upload(types, from, to)
        prefs.edit()
            .putString(CHANGES_TOKEN, token)
            .putString(TOKEN_TYPES, typesKey)
            .putLong(LAST_SYNC, System.currentTimeMillis())
            .apply()
        return n
    }

    private suspend fun upload(types: List<KClass<out Record>>, from: LocalDate, to: LocalDate): Int {
        val start = from.atStartOfDay(zone).toInstant()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant()
        val acc = Accumulator(zone)
        val read = HashMap<KClass<out Record>, List<Record>>()
        for (type in types) {
            // Slaap die 's avonds begint telt voor de dag waarop je wakker wordt; HRV van die nacht ook
            val night = type == SleepSessionRecord::class || type == HeartRateVariabilityRmssdRecord::class
            val readStart = if (night) start.minus(Duration.ofHours(18)) else start
            val recs = readAll(type, readStart, end)
            read[type] = recs
            recs.forEach(acc::add)
        }
        uploadWorkouts(read, from, to)
        val rows = acc.rows().filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
        var total = 0
        rows.chunked(2000).forEach { chunk ->
            val payload = JsonArray(chunk.map { r ->
                buildJsonObject {
                    put("day", r.day.toString())
                    put("metric", r.metric)
                    put("origin", r.origin)
                    put("value", (r.value * 100).roundToLong() / 100.0)
                }
            })
            api.rpc("ingest_health_connect", buildJsonObject { put("p_rows", payload) })
            total += chunk.size
        }
        return total
    }

    /**
     * Oefensessies → trainingen (tabel workouts). Afstand en actieve calorieën komen van dezelfde app tijdens de sessie
     * (naar rato als een meting deels binnen de sessie valt). Hartslag lezen we niet: daar vraagt de app geen toestemming voor.
     */
    private suspend fun uploadWorkouts(read: Map<KClass<out Record>, List<Record>>, from: LocalDate, to: LocalDate) {
        val sessions = read[ExerciseSessionRecord::class].orEmpty().filterIsInstance<ExerciseSessionRecord>()
            .filter { val d = it.startTime.atZone(zone).toLocalDate(); !d.isBefore(from) && !d.isAfter(to) }
        if (sessions.isEmpty()) return
        val distances = read[DistanceRecord::class].orEmpty().filterIsInstance<DistanceRecord>()
        val calories = read[ActiveCaloriesBurnedRecord::class].orEmpty().filterIsInstance<ActiveCaloriesBurnedRecord>()
        val rows = sessions.mapNotNull { s ->
            val dur = Duration.between(s.startTime, s.endTime).seconds.toInt()
            if (dur !in 60..86400) return@mapNotNull null
            val origin = s.metadata.dataOrigin.packageName
            fun part(a: Instant, b: Instant): Double {
                val overlap = minOf(b, s.endTime).toEpochMilli() - maxOf(a, s.startTime).toEpochMilli()
                return if (overlap <= 0) 0.0 else overlap / (b.toEpochMilli() - a.toEpochMilli()).coerceAtLeast(1).toDouble()
            }
            val dist = distances.filter { it.metadata.dataOrigin.packageName == origin }.sumOf { it.distance.inMeters * part(it.startTime, it.endTime) }
            val kcal = calories.filter { it.metadata.dataOrigin.packageName == origin }.sumOf { it.energy.inKilocalories * part(it.startTime, it.endTime) }
            Workout(
                id = null, source = "health_connect", externalId = s.metadata.id, sport = hcSport(s.exerciseType),
                title = s.title?.take(80), day = s.startTime.atZone(zone).toLocalDate(), startedAt = s.startTime, durationS = dur,
                distanceM = dist.takeIf { it > 1 }, kcal = kcal.takeIf { it > 0.5 }, avgHr = null, maxHr = null, ascentM = null,
                notes = s.notes?.take(1000), origin = origin, load = null,
            ).toJson()
        }
        rows.chunked(500).forEach { api.rpc("save_workouts", buildJsonObject { put("p_rows", JsonArray(it)) }) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun readAll(type: KClass<out Record>, start: Instant, end: Instant): List<Record> {
        val out = mutableListOf<Record>()
        var page: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type as KClass<Record>,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1000,
                    pageToken = page,
                )
            )
            out += resp.records
            page = resp.pageToken
        } while (page != null)
        return out
    }

    /** Dagen waarop een gewijzigd record valt (begin- en einddag). */
    private fun recordDays(r: Record): List<LocalDate> {
        val (start, end) = when (r) {
            is StepsRecord -> r.startTime to r.endTime
            is DistanceRecord -> r.startTime to r.endTime
            is ActiveCaloriesBurnedRecord -> r.startTime to r.endTime
            is FloorsClimbedRecord -> r.startTime to r.endTime
            is ExerciseSessionRecord -> r.startTime to r.endTime
            is SleepSessionRecord -> r.startTime to r.endTime
            is RestingHeartRateRecord -> r.time to r.time
            is HeartRateVariabilityRmssdRecord -> r.time to r.time
            is OxygenSaturationRecord -> r.time to r.time
            is RespiratoryRateRecord -> r.time to r.time
            is WeightRecord -> r.time to r.time
            is Vo2MaxRecord -> r.time to r.time
            else -> return emptyList()
        }
        val a = start.atZone(zone).toLocalDate()
        val b = end.atZone(zone).toLocalDate()
        return if (a == b) listOf(a) else listOf(a, b)
    }

    data class Row(val day: LocalDate, val metric: String, val origin: String, val value: Double)

    /** Telt waarden op per (dag, metric, bron-app): som, gemiddelde of laatste meting. */
    private class Accumulator(private val zone: ZoneId) {
        private data class Key(val day: LocalDate, val metric: String, val origin: String)
        private val sums = HashMap<Key, Double>()
        private val avgs = HashMap<Key, Pair<Double, Int>>()
        private val lasts = HashMap<Key, Pair<Instant, Double>>()
        private val sleeps = mutableListOf<Pair<Instant, Instant>>()
        private val hrvs = mutableListOf<Triple<Instant, String, Double>>()

        private fun day(t: Instant) = t.atZone(zone).toLocalDate()
        private fun sum(t: Instant, m: String, o: String, v: Double) {
            val k = Key(day(t), m, o); sums[k] = (sums[k] ?: 0.0) + v
        }
        private fun avg(t: Instant, m: String, o: String, v: Double) {
            val k = Key(day(t), m, o); val p = avgs[k] ?: (0.0 to 0); avgs[k] = (p.first + v) to (p.second + 1)
        }
        private fun last(t: Instant, m: String, o: String, v: Double) {
            val k = Key(day(t), m, o); val p = lasts[k]; if (p == null || t.isAfter(p.first)) lasts[k] = t to v
        }
        private fun minutes(a: Instant, b: Instant) = Duration.between(a, b).toMinutes().toDouble()

        fun add(r: Record) {
            val o = r.metadata.dataOrigin.packageName
            when (r) {
                is StepsRecord -> sum(r.startTime, "steps", o, r.count.toDouble())
                is DistanceRecord -> sum(r.startTime, "dist", o, r.distance.inKilometers)
                is ActiveCaloriesBurnedRecord -> sum(r.startTime, "kcal", o, r.energy.inKilocalories)
                is FloorsClimbedRecord -> sum(r.startTime, "floors", o, r.floors)
                is ExerciseSessionRecord -> sum(r.startTime, "active", o, minutes(r.startTime, r.endTime))
                is SleepSessionRecord -> { sum(r.endTime, "sleep", o, minutes(r.startTime, r.endTime) / 60.0); sleeps += r.startTime to r.endTime }
                is RestingHeartRateRecord -> avg(r.time, "rhr", o, r.beatsPerMinute.toDouble())
                is HeartRateVariabilityRmssdRecord -> hrvs += Triple(r.time, o, r.heartRateVariabilityMillis)
                is OxygenSaturationRecord -> avg(r.time, "spo2", o, r.percentage.value)
                is RespiratoryRateRecord -> avg(r.time, "resp", o, r.rate)
                is WeightRecord -> last(r.time, "weight", o, r.weight.inKilograms)
                is Vo2MaxRecord -> last(r.time, "vo2", o, r.vo2MillilitersPerMinuteKilogram)
            }
        }

        fun rows(): List<Row> =
            sums.map { (k, v) -> Row(k.day, k.metric, k.origin, v) } +
            avgs.map { (k, p) -> Row(k.day, k.metric, k.origin, p.first / p.second) } +
            lasts.map { (k, p) -> Row(k.day, k.metric, k.origin, p.second) } +
            hrvRows()

        /**
         * HRV zoals Garmin, Google en Suunto hem meten: het gemiddelde tijdens de slaap, voor de dag waarop je wakker wordt.
         * Alleen als er die dag geen meting in de slaap is, het gemiddelde van de hele dag.
         */
        private fun hrvRows(): List<Row> {
            val night = HashMap<Key, Pair<Double, Int>>()
            val allDay = HashMap<Key, Pair<Double, Int>>()
            for ((t, o, v) in hrvs) {
                val s = sleeps.firstOrNull { !t.isBefore(it.first) && !t.isAfter(it.second) }
                val map = if (s != null) night else allDay
                val k = Key(day(s?.second ?: t), "hrv", o)
                val p = map[k] ?: (0.0 to 0); map[k] = (p.first + v) to (p.second + 1)
            }
            return (allDay + night).map { (k, p) -> Row(k.day, k.metric, k.origin, p.first / p.second) }
        }
    }

    companion object {
        private const val CHANGES_TOKEN = "changes_token"
        private const val TOKEN_TYPES = "token_types"
        private const val LAST_SYNC = "last_sync"

        val READ_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class,
            FloorsClimbedRecord::class, ExerciseSessionRecord::class, RestingHeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class, OxygenSaturationRecord::class,
            RespiratoryRateRecord::class, SleepSessionRecord::class, WeightRecord::class, Vo2MaxRecord::class,
        )

        val PERMISSIONS: Set<String> =
            READ_TYPES.map { HealthPermission.getReadPermission(it) }.toSet() +
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND +
                HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

        fun sdkStatus(ctx: Context): Int = HealthConnectClient.getSdkStatus(ctx)
    }
}

/** Health Connect-oefentype → vaste sportsleutel (zelfde lijst als de database). */
internal fun hcSport(t: Int): String = when (t) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycling"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "strength"
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> "hiit"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "yoga"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
    ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> "rowing"
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "elliptical"
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL, ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL,
    ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL, ExerciseSessionRecord.EXERCISE_TYPE_RUGBY, ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY,
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN, ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL,
    ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL, ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "team"
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS, ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, ExerciseSessionRecord.EXERCISE_TYPE_SQUASH,
    ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS, ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL -> "racket"
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dance"
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS, ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "martial"
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING, ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING,
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING, ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> "winter"
    else -> "other"
}
