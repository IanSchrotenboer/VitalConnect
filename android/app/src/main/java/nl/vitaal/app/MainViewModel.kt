package nl.vitaal.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
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
    /** Plaats in de dagringen, 1 = buitenste ring. */
    val ringPosition: Int? = null,
)

/** Welk scherm je aanpast. */
enum class EditTarget { Today, Health, Sleep }

/**
 * Indeling van de app (profiles.layout). Los van de tegels op Vandaag:
 * wat je op Vandaag weghaalt of in de ringen zet, blijft gewoon in Gezondheid staan.
 */
data class Layout(
    val greetName: Boolean = true,
    val motivation: Boolean = true,
    val healthTotals: Boolean = true,
    val healthHidden: Set<String> = emptySet(),
    val sleepTiles: List<String> = listOf("hrv", "rhr", "resp"),
    val sleepAdvice: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("greetName", greetName); put("motivation", motivation); put("healthTotals", healthTotals)
        put("healthHidden", JsonArray(healthHidden.sorted().map { JsonPrimitive(it) }))
        put("sleepTiles", JsonArray(sleepTiles.map { JsonPrimitive(it) }))
        put("sleepAdvice", sleepAdvice)
    }

    companion object {
        fun from(o: JsonObject?): Layout {
            if (o == null) return Layout()
            fun b(k: String, d: Boolean) = o.bool(k) ?: d
            fun list(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.distinct()
            return Layout(
                greetName = b("greetName", true), motivation = b("motivation", true), healthTotals = b("healthTotals", true),
                healthHidden = list("healthHidden")?.toSet() ?: emptySet(),
                sleepTiles = list("sleepTiles")?.takeIf { it.isNotEmpty() }?.take(6) ?: Layout().sleepTiles,
                sleepAdvice = b("sleepAdvice", true),
            )
        }
    }
}

/** Startdoel als je een metric zonder doel in de ringen zet. */
val DEFAULT_GOALS = mapOf(
    "steps" to 10000.0, "active" to 30.0, "kcal" to 600.0, "dist" to 5.0, "floors" to 10.0,
    "sleep" to 8.0, "sscore" to 80.0, "energy" to 70.0, "hrv" to 50.0, "spo2" to 96.0, "vo2" to 45.0,
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

/** Kennismaking na de eerste keer inloggen (na de toestemming). */
enum class Onboarding { Import, Profile }

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
    var editing by mutableStateOf<EditTarget?>(null)
    var layout by mutableStateOf(Layout()); private set
    var tab by mutableStateOf(Tab.Today)
    var showSettings by mutableStateOf(false)
    var profileTab by mutableStateOf(ProfileTab.Profile)
    var profile by mutableStateOf(Profile()); private set
    var onboarding by mutableStateOf<Onboarding?>(null)
    /** Ingelogd met Google in deze sessie: dan staat Google Health bovenaan bij importeren. */
    var signedInWithGoogle by mutableStateOf(false); private set

    /** Weergave: lokaal bewaard (direct bij het opstarten) en in het profiel (gelijk op al je toestellen). */
    private val ui = app.getSharedPreferences("vitaal_ui", android.content.Context.MODE_PRIVATE)
    var themeMode by mutableStateOf(ThemeMode.of(ui.getString("theme", null))); private set

    // Synchroniseren en meldingen (instellingen van dit toestel)
    private val device = DeviceSettings(app)
    var syncMinutes by mutableStateOf(device.syncMinutes); private set
    var notifyGoals by mutableStateOf(device.notifyGoals); private set
    var notifyStatus by mutableStateOf(device.notifyStatus); private set
    var notificationsAllowed by mutableStateOf(Notifier.allowed(app)); private set
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
    /** Dagringen (max. 3) op volgorde. */
    val rings: List<Pref>
        get() = prefs.values.filter { it.inRings && it.goal != null && metrics[it.metric]?.lowerIsBetter != true }
            .sortedWith(compareBy({ it.ringPosition ?: 9 }, { it.position })).take(3)
    /** Tegels op Vandaag: zichtbaar en niet al in de ringen. */
    val tiles: List<Pref> get() = visible.filter { !it.inRings }
    val allForEdit: List<Pref>
        get() = tiles + metrics.keys.filter { prefs[it]?.visible != true || prefs[it]?.inRings == true }.map { prefs[it] ?: Pref(it) }

    fun isConnected(provider: String) = connections[provider]?.status == "active"

    /** Eerste keer laden na het openen is klaar (gelukt of niet): dan mag het beginscherm weg. */
    var firstLoadDone by mutableStateOf(!signedIn); private set

    init { if (signedIn) task { try { loadNow() } finally { firstLoadDone = true } } }

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
        signedInWithGoogle = true
        afterSignIn()
    }

    fun giveConsent() = task {
        api.patch("profiles?id=eq.${api.userId}", buildJsonObject {
            put("consent_health_at", Instant.now().toString())
            put("consent_version", "2026-11")
        })
        needsConsent = false
        onboarding = Onboarding.Import
    }

    fun signOut() {
        api.signOut(); hc.reset(); Notifier.clear(getApplication())
        signedIn = false; authStep = AuthStep.Login
        tab = Tab.Today; showSettings = false; detailMetric = null; editing = null; layout = Layout()
        profile = Profile(); profileTab = ProfileTab.Profile; onboarding = null
        // Niets van de vorige gebruiker laten staan op dit toestel
        prefs = emptyMap(); today = emptyMap(); connections = emptyMap(); history = emptyMap(); stats = emptyMap()
        trendSeries = emptyList(); lastLoaded = null; device.googleConnected = false
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
    private fun JsonObjectBuilder.profileFields(p: Profile) {
        put("display_name", p.name.trim().ifEmpty { null })
        put("birth_date", p.birthDate?.toString())
        put("sex", p.sex)
        put("height_cm", p.heightCm)
        put("weight_kg", p.weightKg)
    }

    fun saveProfile(p: Profile) = task {
        api.patch("profiles?id=eq.${api.userId}", buildJsonObject { profileFields(p) })
        profile = p.copy(name = p.name.trim())
        message = "Je profiel is opgeslagen."
    }

    /** Profiel zoals de kennismaking het voorstelt: aangevuld met het laatst gemeten gewicht. */
    val suggestedProfile: Profile
        get() = profile.copy(weightKg = profile.weightKg ?: stats["weight"]?.last?.let { Math.round(it * 10) / 10.0 })

    /** Kennismaking afronden; p = null betekent overslaan. */
    fun finishOnboarding(p: Profile?) = task {
        api.patch("profiles?id=eq.${api.userId}", buildJsonObject {
            put("onboarded_at", Instant.now().toString())
            if (p != null) profileFields(p)
        })
        if (p != null) profile = p.copy(name = p.name.trim())
        onboarding = null
        tab = Tab.Today
    }

    fun setSyncInterval(minutes: Int) {
        device.syncMinutes = minutes
        syncMinutes = minutes
        SyncWorker.schedule(getApplication(), replace = true)
        Notifier.updateStatus(getApplication())
        message = if (minutes == 0) "Synchroniseren op de achtergrond staat uit." else "Je gegevens worden ${syncLabel(minutes)} bijgewerkt."
    }

    /** Zet een melding aan of uit. true = aan, maar eerst toestemming nodig (de UI vraagt die dan). */
    fun setNotify(goals: Boolean? = null, status: Boolean? = null): Boolean {
        goals?.let { device.notifyGoals = it; notifyGoals = it }
        status?.let { device.notifyStatus = it; notifyStatus = it }
        Notifier.updateStatus(getApplication())
        return (goals == true || status == true) && !Notifier.allowed(getApplication())
    }

    fun onNotificationPermission(granted: Boolean) {
        notificationsAllowed = Notifier.allowed(getApplication())
        if (!granted) message = "Zonder toestemming kan $APP_NAME geen meldingen tonen. Je kunt dit aanzetten in de Android-instellingen."
        Notifier.updateStatus(getApplication())
        Notifier.checkGoals(getApplication(), prefs.values, today, metrics)
    }

    fun refreshNotificationPermission() { notificationsAllowed = Notifier.allowed(getApplication()) }

    fun setTheme(mode: ThemeMode) {
        applyTheme(mode)
        if (signedIn) viewModelScope.launch {
            try { api.patch("profiles?id=eq.${api.userId}", buildJsonObject { put("theme", mode.id) }) } catch (_: Exception) {}
        }
    }

    private fun applyTheme(mode: ThemeMode) {
        themeMode = mode
        ui.edit().putString("theme", mode.id).apply()
        // Android 12+: het beginscherm van het systeem volgt dan ook de gekozen weergave (geen lichte flits in donker)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val night = when (mode) {
                ThemeMode.Light -> android.app.UiModeManager.MODE_NIGHT_NO
                ThemeMode.Dark -> android.app.UiModeManager.MODE_NIGHT_YES
                ThemeMode.System -> android.app.UiModeManager.MODE_NIGHT_AUTO
            }
            getApplication<Application>().getSystemService(android.app.UiModeManager::class.java)?.setApplicationNightMode(night)
        }
    }

    fun openProfile(tab: ProfileTab = ProfileTab.Profile) {
        profileTab = tab
        showSettings = true
    }

    // ---------- Data ----------
    fun load() = task { loadNow() }

    /** Bij terugkeren in de app: opnieuw laden als de gegevens ouder zijn dan 15 minuten of er een nieuwe dag is. */
    fun refreshIfStale() {
        val last = lastLoaded ?: return
        val stale = java.time.Duration.between(last, Instant.now()).toMinutes() >= 15 ||
            last.atZone(java.time.ZoneId.systemDefault()).toLocalDate() != LocalDate.now()
        if (signedIn && stale && !busy) load()
    }

    private suspend fun loadNow() {
        // Eén verzoek (app_state) in plaats van zes: profiel, dashboard, koppelingen, 14 dagen en statistieken
        val now = LocalDate.now()
        val st = api.appState(now.minusDays(13), now, withStats = true)
        metrics = st.metrics
        prefs = st.prefs
        val prof = st.profile
        needsConsent = prof?.str("consent_health_at").isNullOrEmpty()
        if (prof != null) {
            profile = Profile(
                email = prof.str("email"),
                name = prof.str("display_name"),
                birthDate = day(prof.str("birth_date")),
                sex = prof.str("sex").ifEmpty { null },
                heightCm = prof.num("height_cm"),
                weightKg = prof.num("weight_kg"),
            )
            applyTheme(ThemeMode.of(prof.str("theme")))
            layout = Layout.from(prof["layout"] as? JsonObject)
            if (!needsConsent && onboarding == null && prof.str("onboarded_at").isEmpty()) onboarding = Onboarding.Import
        }
        connections = st.connections
        device.googleConnected = isConnected("google_health")
        history = st.daily.mapValues { (_, days) -> days.mapValues { it.value.value } }
        today = st.daily.mapNotNull { (metric, days) -> days[now]?.let { metric to it } }.toMap()
        stats = st.stats.orEmpty()
        lastLoaded = Instant.now()
        refreshHcStatusNow()
        Notifier.checkGoals(getApplication(), prefs.values, today, metrics)
        Notifier.updateStatus(getApplication(), justSynced = true)
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
        }) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val byDay = res.mapNotNull { r -> day(r.str("day"))?.let { it to r.num("value") } }.toMap()
        trendSource = res.lastOrNull()?.str("source")
        trendSeries = (0 until 2 * trendDays).map { i -> start.plusDays(i.toLong()).let { TrendPoint(it, byDay[it]) } }
    }

    /** Knop in de wijzerplaat: Health Connect bijwerken (als dat kan) en alles opnieuw laden. */
    fun refreshAll() = task {
        refreshHcStatusNow()
        // Health Connect en Google Health tegelijk bijwerken; de server haalt Google hooguit eens per 10 minuten op
        coroutineScope {
            if (hcStatus == HcStatus.OK || hcStatus == HcStatus.FOREGROUND_ONLY) launch {
                try { hc.sync() } catch (e: Exception) { message = "Health Connect: ${e.message}" }
            }
            if (isConnected("google_health")) launch { runCatching { api.function("google-health-sync") } }
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
        val url = res.str("url").ifEmpty { null }
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
        val rows = res.int("rows") ?: 0
        message = if (res.bool("skipped") == true) "Google Health is net al bijgewerkt."
            else "Google Health: $rows dagwaarden bijgewerkt."
        loadNow()
    }

    fun disconnect(provider: String, deleteData: Boolean) = task {
        api.function("disconnect", buildJsonObject { put("provider", provider); put("delete_data", deleteData) })
        loadNow()
    }

    // ---------- Dashboard aanpassen ----------
    fun save(p: Pref) = task {
        val sourceChanged = prefs[p.metric]?.preferredSource != p.preferredSource
        api.upsert("metric_prefs", prefJson(p))
        prefs = prefs + (p.metric to p)
        // Alleen een andere bron verandert de getallen; tegels, doelen en volgorde zijn lokaal al bijgewerkt
        if (sourceChanged) loadNow() else Notifier.checkGoals(getApplication(), prefs.values, today, metrics)
    }

    fun setVisible(metric: String, on: Boolean) {
        val cur = prefs[metric] ?: Pref(metric)
        val pos = if (on) (visible.maxOfOrNull { it.position } ?: -1) + 1 else cur.position
        save(cur.copy(visible = on, position = pos))
    }

    fun move(metric: String, dir: Int) = task {
        val list = tiles.toMutableList()
        val i = list.indexOfFirst { it.metric == metric }
        val j = i + dir
        if (i < 0 || j !in list.indices) return@task
        list[i] = list[j].also { list[j] = list[i] }
        val renumbered = list.mapIndexed { idx, p -> p.copy(position = idx) }
        api.upsert("metric_prefs", JsonArray(renumbered.map(::prefJson)))
        prefs = prefs + renumbered.associateBy { it.metric }
    }

    // ---------- Dagringen ----------
    /** Zet een metric in de ringen (achteraan). Hij verdwijnt dan uit de tegels, niet uit Gezondheid. */
    fun addRing(metric: String) {
        if (rings.size >= 3) { message = "Er passen drie doelen in de ringen. Haal er eerst één weg."; return }
        val cur = prefs[metric] ?: Pref(metric)
        val goal = cur.goal ?: DEFAULT_GOALS[metric]
        if (goal == null) { message = "Stel eerst een dagdoel in voor ${metrics[metric]?.name ?: metric}."; return }
        saveRings(rings.map { it.metric } + metric, extra = mapOf(metric to cur.copy(goal = goal)))
    }

    /** Haalt een metric uit de ringen; als tegel stond hij nog aan, dus hij komt terug tussen de tegels. */
    fun removeRing(metric: String) = saveRings(rings.map { it.metric } - metric, removed = metric)

    fun moveRing(metric: String, dir: Int) {
        val order = rings.map { it.metric }.toMutableList()
        val i = order.indexOf(metric); val j = i + dir
        if (i < 0 || j !in order.indices) return
        order[i] = order[j].also { order[j] = order[i] }
        saveRings(order)
    }

    private fun saveRings(order: List<String>, extra: Map<String, Pref> = emptyMap(), removed: String? = null) = task {
        val changed = order.mapIndexed { i, id -> (extra[id] ?: prefs[id] ?: Pref(id)).copy(inRings = true, ringPosition = i + 1) } +
            listOfNotNull(removed?.let { (prefs[it] ?: Pref(it)).copy(inRings = false, ringPosition = null) })
        // Eerst de weggehaalde ring opslaan, zodat de controle op maximaal drie ringen klopt
        changed.sortedBy { it.inRings }.forEach { api.upsert("metric_prefs", prefJson(it)) }
        prefs = prefs + changed.associateBy { it.metric }
    }

    // ---------- Indeling ----------
    fun saveLayout(l: Layout) = task {
        layout = l
        api.patch("profiles?id=eq.${api.userId}", buildJsonObject { put("layout", l.toJson()) })
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
        put("ring_position", if (p.inRings) p.ringPosition else null)
    }

}
