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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

data class MetricInfo(val id: String, val name: String, val unit: String, val lowerIsBetter: Boolean)

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

enum class HcStatus { UNKNOWN, UNAVAILABLE, UPDATE_REQUIRED, NO_PERMISSION, FOREGROUND_ONLY, OK }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val api = Api(app)
    private val hc = HealthSync(app, api)

    var signedIn by mutableStateOf(api.isSignedIn); private set
    var needsConsent by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var editing by mutableStateOf(false)

    var metrics by mutableStateOf<Map<String, MetricInfo>>(emptyMap()); private set
    var prefs by mutableStateOf<Map<String, Pref>>(emptyMap()); private set
    var today by mutableStateOf<Map<String, DayValue>>(emptyMap()); private set
    var hcStatus by mutableStateOf(HcStatus.UNKNOWN); private set
    var garminConnected by mutableStateOf(false); private set
    var lastSync by mutableStateOf(hc.lastSync); private set

    val visible: List<Pref> get() = prefs.values.filter { it.visible }.sortedBy { it.position }

    /** Alle metrics voor het aanpasscherm: zichtbare op volgorde, daarna de rest. */
    val allForEdit: List<Pref>
        get() = visible + metrics.keys.filter { prefs[it]?.visible != true }.map { prefs[it] ?: Pref(it) }

    init { if (signedIn) load() }

    private fun task(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { block() } catch (e: Exception) { message = e.message ?: "Er ging iets mis" } finally { busy = false }
    }

    // ---------- Account ----------
    fun signIn(email: String, password: String) = task {
        api.signIn(email.trim(), password)
        signedIn = true
        loadNow()
    }

    fun signUp(email: String, password: String) = task {
        if (api.signUp(email.trim(), password)) { signedIn = true; loadNow() }
        else message = "Bevestig je e-mailadres via de link in je mail en log daarna in."
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
        signedIn = false; prefs = emptyMap(); today = emptyMap()
    }

    fun deleteAccount() = task {
        api.function("delete-account")
        signOut()
        message = "Je account en alle data zijn verwijderd."
    }

    // ---------- Data ----------
    fun load() = task { loadNow() }

    private suspend fun loadNow() {
        metrics = api.select("metrics?select=id,name_nl,unit,lower_is_better").associate {
            val o = it.jsonObject
            val id = o.str("id")
            id to MetricInfo(id, o.str("name_nl"), o.str("unit"), o["lower_is_better"]?.jsonPrimitive?.booleanOrNull == true)
        }
        prefs = api.select("metric_prefs?select=*").associate {
            val o = it.jsonObject
            val p = Pref(
                metric = o.str("metric"),
                visible = o["visible"]?.jsonPrimitive?.booleanOrNull == true,
                position = o["position"]?.jsonPrimitive?.intOrNull ?: 0,
                size = o.str("size").ifEmpty { "s" },
                preferredSource = o["preferred_source"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                goal = o["goal"]?.takeIf { it != JsonNull }?.jsonPrimitive?.doubleOrNull,
                inRings = o["in_rings"]?.jsonPrimitive?.booleanOrNull == true,
            )
            p.metric to p
        }
        needsConsent = api.select("profiles?select=consent_health_at")
            .firstOrNull()?.jsonObject?.get("consent_health_at").let { it == null || it == JsonNull }
        garminConnected = api.select("connections?select=provider,status&provider=eq.garmin")
            .any { it.jsonObject.str("status") == "active" }
        val d = LocalDate.now().toString()
        today = (api.rpc("resolved_daily", buildJsonObject { put("p_from", d); put("p_to", d) }) as? JsonArray)
            .orEmpty().associate {
                val o = it.jsonObject
                o.str("metric") to DayValue(o["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0, o.str("source"))
            }
        refreshHcStatusNow()
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

    // ---------- Garmin ----------
    fun garminConnect(open: (String) -> Unit) = task {
        val url = api.function("garmin-auth-start")["url"]?.jsonPrimitive?.content
        if (url == null) message = "Garmin-koppeling is nog niet ingericht op de server." else open(url)
    }

    fun onGarminReturn(status: String?) {
        message = when (status) {
            "ok" -> "Garmin Connect is gekoppeld. Nieuwe data komt binnen zodra je horloge synchroniseert."
            "cancelled" -> "Koppelen met Garmin is afgebroken."
            else -> "Koppelen met Garmin is mislukt ($status)."
        }
        load()
    }

    fun garminDisconnect() = task {
        api.function("garmin-disconnect")
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
