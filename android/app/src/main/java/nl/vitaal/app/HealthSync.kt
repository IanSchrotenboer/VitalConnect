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
        val typesKey = types.joinToString(",") { it.simpleName.orEmpty() }
        val saved = prefs.getString(CHANGES_TOKEN, null)
        val history = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted

        // Geen token of andere toestemmingen dan vorige keer: alles opnieuw
        if (saved == null || prefs.getString(TOKEN_TYPES, null) != typesKey) {
            return fullSync(types, today.minusDays(if (history) 365 else 29), today, typesKey)
        }

        val days = sortedSetOf<LocalDate>()
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
                    is DeletionChange -> (0L..6L).forEach { days.add(today.minusDays(it)) }
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
        for (type in types) {
            // Slaap die 's avonds begint telt voor de dag waarop je wakker wordt
            val readStart = if (type == SleepSessionRecord::class) start.minus(Duration.ofHours(18)) else start
            readAll(type, readStart, end).forEach(acc::add)
        }
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
                is SleepSessionRecord -> sum(r.endTime, "sleep", o, minutes(r.startTime, r.endTime) / 60.0)
                is RestingHeartRateRecord -> avg(r.time, "rhr", o, r.beatsPerMinute.toDouble())
                is HeartRateVariabilityRmssdRecord -> avg(r.time, "hrv", o, r.heartRateVariabilityMillis)
                is OxygenSaturationRecord -> avg(r.time, "spo2", o, r.percentage.value)
                is RespiratoryRateRecord -> avg(r.time, "resp", o, r.rate)
                is WeightRecord -> last(r.time, "weight", o, r.weight.inKilograms)
                is Vo2MaxRecord -> last(r.time, "vo2", o, r.vo2MillilitersPerMinuteKilogram)
            }
        }

        fun rows(): List<Row> =
            sums.map { (k, v) -> Row(k.day, k.metric, k.origin, v) } +
            avgs.map { (k, p) -> Row(k.day, k.metric, k.origin, p.first / p.second) } +
            lasts.map { (k, p) -> Row(k.day, k.metric, k.origin, p.second) }
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
