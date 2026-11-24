package nl.vitaal.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

data class MetricInfo(
    val id: String,
    val name: String,
    val unit: String,
    val lowerIsBetter: Boolean,
    val aggregation: String = "avg",   // sum, avg of last
)

enum class Tab { Today, Health, Sleep }

data class TrendPoint(val day: LocalDate, val value: Double?)

/** Statistieken over de hele geschiedenis (metric_stats). */
data class MetricStats(
    val days: Int,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    val last: Double?,
    val avgAll: Double?,
    val minAll: Double?,
    val maxAll: Double?,
    val totalAll: Double?,
    val avg30: Double?,
)

data class Pref(
    val metric: String,
    val visible: Boolean = false,
    val position: Int = 999,
    val size: String = "s",
    val preferredSource: String? = null,
    val goal: Double? = null,
    val inRings: Boolean = false,
)

data class DayValue(val value: Double, val source: String)

data class Connection(val status: String, val lastSync: String?, val lastError: String?)

/** Persoonlijke gegevens uit het profiel. Alles behalve het e-mailadres is optioneel. */
data class Profile(
    val email: String = "",
    val name: String = "",
    val birthDate: LocalDate? = null,
    val sex: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
)

enum class ProfileTab { Profile, Connections }

enum class HcStatus { UNKNOWN, UNAVAILABLE, UPDATE_REQUIRED, NO_PERMISSION, FOREGROUND_ONLY, OK }

/** Stappen in het inlogscherm. */
sealed interface AuthStep {
    data object Login : AuthStep
    /** type: signup, email (inlogcode) of recovery */
    data class Code(val email: String, val type: String) : AuthStep
    data object NewPassword : AuthStep
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val api = Api(app)
    private val hc = HealthSync(app, api)

    var signedIn by mutableStateOf(api.isSignedIn); private set
    var authStep by mutableStateOf<AuthStep>(AuthStep.Login); private set
    var needsConsent by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var editing by mutableStateOf(false)
    var tab by mutableStateOf(Tab.Today)
    var showSettings by mutableStateOf(false)
    var profileTab by mutableStateOf(ProfileTab.Profile)
    var profile by mutableStateOf(Profile()); private set

    /** Weergave: lokaal bewaard (direct bij het opstarten) en in het profiel (gelijk op al je toestellen). */
    private val ui = app.getSharedPreferences("vitaal_ui", android.content.Context.MODE_PRIVATE)
    var themeMode by mutableStateOf(ThemeMode.of(ui.getString("theme", null))); private set
    /** Metric waarvan het detailscherm (grafiek) open staat. */
    var detailMetric by mutableStateOf<String?>(null); private set
    var stats by mutableStateOf<Map<String, MetricStats>>(emptyMap()); private set

    var metrics by mutableStateOf<Map<String, MetricInfo>>(emptyMap()); private set
    var prefs by mutableStateOf<Map<String, Pref>>(emptyMap()); private set
    var today by mutableStateOf<Map<String, DayValue>>(emptyMap()); private set
    /** Laatste 14 dagen per metric, voor de minigrafiekjes en het verschil met het weekgemiddelde. */
    var history by mutableStateOf<Map<String, Map<LocalDate, Double>>>(emptyMap()); private set
    var lastLoaded by mutableStateOf<Instant?>(null); private set

    // Detailgrafiek
    var trendMetric by mutableStateOf("steps"); private set
    var trendDays by mutableStateOf(30); private set
    /** 2 × trendDays dagen: de tweede helft is de getoonde periode, de eerste de vorige. */
    var trendSeries by mutableStateOf<List<TrendPoint>>(emptyList()); private set
    var trendSource by mutableStateOf<String?>(null); private set
    var connections by mutableStateOf<Map<String, Connection>>(emptyMap()); private set
    var hcStatus by mutableStateOf(HcStatus.UNKNOWN); private set
    var lastSync by mutableStateOf(hc.lastSync); private set

    val visible: List<Pref> get() = prefs.values.filter { it.visible }.sortedBy { it.position }
    val allForEdit: List<Pref>
        get() = visible + metrics.keys.filter { prefs[it]?.visible != true }.map { prefs[it] ?: Pref(it) }

    fun isConnected(provider: String) = connections[provider]?.status == "active"

    init { if (signedIn) load() }

    private fun task(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { block() } catch (e: Exception) { message = e.message ?: "Er ging iets mis" } finally { busy = false }
    }

    // ---------- Inloggen ----------
    private suspend fun afterSignIn() {
        signedIn = true
        authStep = AuthStep.Login
        loadNow()
    }

    fun signIn(email: String, password: String) = task {
        api.signIn(email.trim(), password)
        afterSignIn()
    }

    fun signUp(email: String, password: String) = task {
        val e = email.trim()
        if (api.signUp(e, password)) afterSignIn()
        else { authStep = AuthStep.Code(e, "signup"); message = "We hebben een code naar $e gestuurd." }
    }

    fun requestLoginCode(email: String) = task {
        val e = email.trim()
        api.sendLoginCode(e)
        authStep = AuthStep.Code(e, "email")
        message = "We hebben een inlogcode naar $e gestuurd."
    }

    fun requestPasswordReset(email: String) = task {
        val e = email.trim()
        api.sendRecovery(e)
        authStep = AuthStep.Code(e, "recovery")
        message = "Als dit adres bij ons bekend is, ontvang je een herstelcode."
    }

    fun verifyCode(code: String) = task {
        val step = authStep as? AuthStep.Code ?: return@task
        api.verifyCode(step.email, code.trim(), step.type)
        if (step.type == "recovery") authStep = AuthStep.NewPassword else afterSignIn()
    }

    fun resendCode() = task {
        val step = authStep as? AuthStep.Code ?: return@task
        when (step.type) {
            "signup" -> api.resendSignup(step.email)
            "recovery" -> api.sendRecovery(step.email)
            else -> api.sendLoginCode(step.email)
        }
        message = "Nieuwe code verstuurd."
    }

    fun setNewPassword(password: String) = task {
        api.updatePassword(password)
        message = "Je wachtwoord is gewijzigd."
        afterSignIn()
    }

    fun backToLogin() { authStep = AuthStep.Login }

    fun signInWithGoogle(idToken: String, rawNonce: String) = task {
        api.signInWithGoogle(idToken, rawNonce)
        afterSignIn()
    }

    fun giveConsent() = task {
        api.patch("profiles?id=eq.${api.userId}", buildJsonObject {
            put("consent_health_at", Instant.now().toString())
            put("consent_version", "2026-11")
        })
        needsConsent = false
    }

    fun signOut() {
        api.signOut(); hc.reset()
        signedIn = false; authStep = AuthStep.Login
        tab = Tab.Today; showSettings = false; detailMetric = null; editing = false
        profile = Profile(); profileTab = ProfileTab.Profile
        prefs = emptyMap(); today = emptyMap(); connections = emptyMap()
    }

    fun deleteAccount() = task {
        api.function("delete-account")
        signOut()
        message = "Je account en alle data zijn verwijderd."
    }

    /** AVG-inzage: alle eigen data als JSON. */
    fun exportData(share: (String) -> Unit) = task {
        val data = api.rpc("export_my_data", JsonObject(emptyMap()))
        share(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), data))
    }

    // ---------- Profiel ----------
    fun saveProfile(p: Profile) = task {
        api.patch("profiles?id=eq.${api.userId}", buildJsonObject {
            put("display_name", p.name.trim().ifEmpty { null })
            put("birth_date", p.birthDate?.toString())
            put("sex", p.sex)
            put("height_cm", p.heightCm)
            put("weight_kg", p.weightKg)
        })
        profile = p.copy(name = p.name.trim())
        message = "Je profiel is opgeslagen."
    }

    fun setTheme(mode: ThemeMode) {
        applyTheme(mode)
        if (signedIn) viewModelScope.launch {
            try { api.patch("profiles?id=eq.${api.userId}", buildJsonObject { put("theme", mode.id) }) } catch (_: Exception) {}
        }
    }

    private fun applyTheme(mode: ThemeMode) {
        themeMode = mode
        ui.edit().putString("theme", mode.id).apply()
    }

    fun openProfile(tab: ProfileTab = ProfileTab.Profile) {
        profileTab = tab
        showSettings = true
    }

    // ---------- Data ----------
    fun load() = task { loadNow() }

    private suspend fun loadNow() {
        metrics = api.select("metrics?select=id,name_nl,unit,lower_is_better,aggregation&order=id").associate {
            val o = it.jsonObject
            val id = o.str("id")
            id to MetricInfo(id, o.str("name_nl"), o.str("unit"),
                o["lower_is_better"]?.jsonPrimitive?.booleanOrNull == true, o.str("aggregation").ifEmpty { "avg" })
        }
        prefs = api.select("metric_prefs?select=*").associate {
            val o = it.jsonObject
            val p = Pref(
                metric = o.str("metric"),
                visible = o["visible"]?.jsonPrimitive?.booleanOrNull == true,
                position = o["position"]?.jsonPrimitive?.intOrNull ?: 0,
                size = o.str("size").ifEmpty { "s" },
                preferredSource = o.str("preferred_source").ifEmpty { null },
                goal = o["goal"]?.takeIf { it != JsonNull }?.jsonPrimitive?.doubleOrNull,
                inRings = o["in_rings"]?.jsonPrimitive?.booleanOrNull == true,
            )
            p.metric to p
        }
        val prof = api.select("profiles?select=email,display_name,birth_date,sex,height_cm,weight_kg,theme,consent_health_at")
            .firstOrNull()?.jsonObject
        needsConsent = prof?.get("consent_health_at").let { it == null || it == JsonNull }
        if (prof != null) {
            fun d(k: String) = prof[k]?.takeIf { it != JsonNull }?.jsonPrimitive?.doubleOrNull
            profile = Profile(
                email = prof.str("email"),
                name = prof.str("display_name"),
                birthDate = prof.str("birth_date").ifEmpty { null }?.let(LocalDate::parse),
                sex = prof.str("sex").ifEmpty { null },
                heightCm = d("height_cm"),
                weightKg = d("weight_kg"),
            )
            applyTheme(ThemeMode.of(prof.str("theme")))
        }
        connections = api.select("connections?select=provider,status,last_sync_at,last_error").associate {
            val o = it.jsonObject
            o.str("provider") to Connection(o.str("status"), o.str("last_sync_at").ifEmpty { null }, o.str("last_error").ifEmpty { null })
        }
        val now = LocalDate.now()
        val rows = (api.rpc("resolved_daily", buildJsonObject {
            put("p_from", now.minusDays(13).toString()); put("p_to", now.toString())
        }) as? JsonArray).orEmpty().map { it.jsonObject }
        history = rows.groupBy { it.str("metric") }.mapValues { (_, list) ->
            list.associate { LocalDate.parse(it.str("day")) to (it["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0) }
        }
        today = rows.filter { it.str("day") == now.toString() }.associate {
            it.str("metric") to DayValue(it["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0, it.str("source"))
        }
        stats = (api.rpc("metric_stats", JsonObject(emptyMap())) as? JsonArray).orEmpty().associate {
            val o = it.jsonObject
            fun d(k: String) = o[k]?.takeIf { v -> v != JsonNull }?.jsonPrimitive?.doubleOrNull
            fun day(k: String) = o.str(k).takeIf { v -> v.isNotEmpty() }?.let(LocalDate::parse)
            o.str("metric") to MetricStats(
                days = o["days"]?.jsonPrimitive?.intOrNull ?: 0,
                firstDay = day("first_day"), lastDay = day("last_day"), last = d("last_value"),
                avgAll = d("avg_all"), minAll = d("min_all"), maxAll = d("max_all"),
                totalAll = d("total_all"), avg30 = d("avg_30"),
            )
        }
        lastLoaded = Instant.now()
        refreshHcStatusNow()
    }

    // ---------- Detailgrafiek ----------
    fun openDetail(metric: String) {
        trendMetric = metric
        detailMetric = metric
        trendSeries = emptyList()
        loadTrend()
    }

    fun closeDetail() { detailMetric = null }

    fun setTrendPeriod(days: Int) {
        trendDays = days
        loadTrend()
    }

    fun loadTrend() = task {
        val end = LocalDate.now()
        val start = end.minusDays(2L * trendDays - 1)
        val res = (api.rpc("resolved_series", buildJsonObject {
            put("p_metric", trendMetric); put("p_from", start.toString()); put("p_to", end.toString())
        }) as? JsonArray).orEmpty().map { it.jsonObject }
        val byDay = res.associate { LocalDate.parse(it.str("day")) to it["value"]?.jsonPrimitive?.doubleOrNull }
        trendSource = res.lastOrNull()?.str("source")
        trendSeries = (0 until 2 * trendDays).map { i -> start.plusDays(i.toLong()).let { TrendPoint(it, byDay[it]) } }
    }

    /** Knop in de wijzerplaat: Health Connect bijwerken (als dat kan) en alles opnieuw laden. */
    fun refreshAll() = task {
        refreshHcStatusNow()
        if (hcStatus == HcStatus.OK || hcStatus == HcStatus.FOREGROUND_ONLY) {
            try { hc.sync() } catch (e: Exception) { message = "Health Connect: ${e.message}" }
        }
        loadNow()
        if (detailMetric != null) loadTrend()
    }

    // ---------- Health Connect ----------
    fun refreshHcStatus() = viewModelScope.launch {
        try { refreshHcStatusNow() } catch (_: Exception) { hcStatus = HcStatus.UNAVAILABLE }
    }

    private suspend fun refreshHcStatusNow() {
        hcStatus = when (HealthSync.sdkStatus(getApplication())) {
            HealthConnectClient.SDK_UNAVAILABLE -> HcStatus.UNAVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.UPDATE_REQUIRED
            else -> {
                val g = hc.granted()
                when {
                    HealthSync.READ_TYPES.none { HealthPermission.getReadPermission(it) in g } -> HcStatus.NO_PERMISSION
                    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in g -> HcStatus.FOREGROUND_ONLY
                    else -> HcStatus.OK
                }
            }
        }
        lastSync = hc.lastSync
    }

    fun onHcPermissionsResult(granted: Set<String>) {
        if (granted.isEmpty()) message = "Geen toegang gegeven. Je kunt dit later aanpassen in Health Connect."
        syncNow()
    }

    fun syncNow() = task {
        refreshHcStatusNow()
        if (hcStatus == HcStatus.OK || hcStatus == HcStatus.FOREGROUND_ONLY) {
            val n = hc.sync()
            message = if (n == 0) "Health Connect is bijgewerkt." else "$n dagwaarden bijgewerkt."
        }
        loadNow()
    }

    // ---------- Cloudkoppelingen (Garmin, Google Health) ----------
    fun connect(provider: String, open: (String) -> Unit) = task {
        val fn = if (provider == "garmin") "garmin-auth-start" else "google-health-auth-start"
        val res = api.function(fn)
        val url = res["url"]?.jsonPrimitive?.content
        if (url == null) message = "Deze koppeling is nog niet ingericht op de server." else open(url)
    }

    fun onConnectReturn(provider: String, status: String?) {
        val name = if (provider == "garmin") "Garmin Connect" else "Google Health"
        message = when (status) {
            "ok" -> if (provider == "garmin") "$name is gekoppeld. Nieuwe data komt binnen zodra je horloge synchroniseert."
                    else "$name is gekoppeld. De afgelopen 30 dagen worden opgehaald."
            "cancelled" -> "Koppelen met $name is afgebroken."
            "not_linked" -> "Je Google-account heeft nog geen Google Health-gegevens. Open eerst de Fitbit- of Google Health-app."
            "expired" -> "Het koppelen duurde te lang. Probeer het opnieuw."
            else -> "Koppelen met $name is mislukt."
        }
        load()
    }

    fun googleHealthSyncNow() = task {
        val res = api.function("google-health-sync")
        val rows = res["rows"]?.jsonPrimitive?.intOrNull ?: 0
        message = "Google Health: $rows dagwaarden bijgewerkt."
        loadNow()
    }

    fun disconnect(provider: String, deleteData: Boolean) = task {
        api.function("disconnect", buildJsonObject { put("provider", provider); put("delete_data", deleteData) })
        loadNow()
    }

    // ---------- Dashboard aanpassen ----------
    fun save(p: Pref) = task {
        api.upsert("metric_prefs", prefJson(p))
        prefs = prefs + (p.metric to p)
        loadNow()
    }

    fun setVisible(metric: String, on: Boolean) {
        val cur = prefs[metric] ?: Pref(metric)
        val pos = if (on) (visible.maxOfOrNull { it.position } ?: -1) + 1 else cur.position
        save(cur.copy(visible = on, position = pos, inRings = cur.inRings && on))
    }

    fun move(metric: String, dir: Int) = task {
        val list = visible.toMutableList()
        val i = list.indexOfFirst { it.metric == metric }
        val j = i + dir
        if (i < 0 || j !in list.indices) return@task
        list[i] = list[j].also { list[j] = list[i] }
        val renumbered = list.mapIndexed { idx, p -> p.copy(position = idx) }
        api.upsert("metric_prefs", JsonArray(renumbered.map(::prefJson)))
        prefs = prefs + renumbered.associateBy { it.metric }
    }

    private fun prefJson(p: Pref): JsonObject = buildJsonObject {
        put("user_id", api.userId)
        put("metric", p.metric)
        put("visible", p.visible)
        put("position", p.position)
        put("size", p.size)
        put("preferred_source", p.preferredSource)
        put("goal", p.goal)
        put("in_rings", p.inRings)
    }

    private fun JsonObject.str(k: String) =
        this[k]?.takeIf { it != JsonNull }?.jsonPrimitive?.content.orEmpty()
}
