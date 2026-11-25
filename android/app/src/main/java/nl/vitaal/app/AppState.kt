package nl.vitaal.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Alles wat de app bij het openen (en de achtergrondtaak) nodig heeft, in één verzoek: RPC app_state.
 * Bij een miljoen gebruikers scheelt dat vijf rondjes naar de server per keer openen.
 */
class AppState(
    val metrics: Map<String, MetricInfo>,
    val prefs: Map<String, Pref>,
    val profile: JsonObject?,
    val connections: Map<String, Connection>,
    /** Bronnenregister (providers); leeg bij een oudere server. */
    val sources: Map<String, SourceInfo>,
    /** Dagwaarden per metric per dag, met de gekozen bron. */
    val daily: Map<String, Map<LocalDate, DayValue>>,
    /** Null als niet gevraagd (achtergrondtaak). */
    val stats: Map<String, MetricStats>?,
    /** Herstel en fitheid, door de server berekend; null bij een oudere server. */
    val fitness: Fitness?,
)

// Verdraagzaam lezen: een veld met een onverwacht type geldt als leeg, de app crasht er niet op.
private fun JsonObject.prim(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
internal fun JsonObject.str(k: String) = prim(k)?.content.orEmpty()
internal fun JsonObject.num(k: String) = prim(k)?.doubleOrNull
internal fun JsonObject.int(k: String) = prim(k)?.intOrNull
internal fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

internal fun day(s: String) = runCatching { LocalDate.parse(s) }.getOrNull()

suspend fun Api.appState(from: LocalDate, to: LocalDate, withStats: Boolean): AppState {
    val o = rpc("app_state", buildJsonObject {
        put("p_from", from.toString()); put("p_to", to.toString()); put("p_stats", withStats)
    }).jsonObject
    return AppState(
        metrics = o.list("metrics").associate { m ->
            val id = m.str("id")
            id to MetricInfo(id, m.str("name_nl"), m.str("unit"), m.bool("lower_is_better") == true, m.str("aggregation").ifEmpty { "avg" })
        },
        prefs = o.list("prefs").associate { p ->
            p.str("metric") to Pref(
                metric = p.str("metric"),
                visible = p.bool("visible") == true,
                position = p.int("position") ?: 0,
                size = p.str("size").ifEmpty { "s" },
                preferredSource = p.str("preferred_source").ifEmpty { null },
                goal = p.num("goal"),
                inRings = p.bool("in_rings") == true,
                ringPosition = p.int("ring_position"),
            )
        },
        profile = o["profile"] as? JsonObject,
        sources = o.list("providers").associate { s ->
            s.str("id") to SourceInfo(s.str("id"), s.str("name"), s.str("sync"), s.bool("available") == true)
        },
        connections = o.list("connections").associate { c ->
            c.str("provider") to Connection(c.str("status"), c.str("last_sync_at").ifEmpty { null }, c.str("last_error").ifEmpty { null })
        },
        daily = o.list("daily").groupBy { it.str("metric") }.mapValues { (_, rows) ->
            rows.mapNotNull { r -> day(r.str("day"))?.let { it to DayValue(r.num("value") ?: return@mapNotNull null, r.str("source")) } }.toMap()
        },
        stats = (o["stats"] as? JsonArray)?.filterIsInstance<JsonObject>()?.associate { s ->
            fun date(k: String) = day(s.str(k))
            s.str("metric") to MetricStats(
                days = s.int("days") ?: 0,
                firstDay = date("first_day"), lastDay = date("last_day"), last = s.num("last_value"),
                avgAll = s.num("avg_all"), minAll = s.num("min_all"), maxAll = s.num("max_all"),
                totalAll = s.num("total_all"), avg30 = s.num("avg_30"),
            )
        },
        fitness = parseFitness(o["fitness"] as? JsonObject),
    )
}
