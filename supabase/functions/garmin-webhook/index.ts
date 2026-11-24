// POST /garmin-webhook?key=<GARMIN_WEBHOOK_KEY>
// Ontvangt push-notificaties van de Garmin Health API en zet ze om naar dagwaarden.
// Stel in het Garmin Developer Portal per summary-type deze URL in (push).
// Veldnamen volgen de Garmin Health API-specificatie; controleer ze na goedkeuring tegen de actuele spec.
import { accessTokenFor, admin, env, fail, json } from "./shared.ts";
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";

type Row = { user_id: string; day: string; metric: string; source: "garmin"; provider: "garmin"; value: number; updated_at: string };
type Item = Record<string, any>;

const avg = (m?: Record<string, number>) => {
  const v = Object.values(m ?? {}).filter((x) => typeof x === "number" && x > 0);
  return v.length ? v.reduce((a, b) => a + b, 0) / v.length : undefined;
};
const lastOf = (m?: Record<string, number>) => {
  const k = Object.keys(m ?? {}).map(Number).sort((a, b) => a - b).pop();
  return k === undefined ? undefined : m![String(k)];
};
const dayFromEpoch = (sec?: number, offset = 0) =>
  sec === undefined ? undefined : new Date((sec + offset) * 1000).toISOString().slice(0, 10);

// Per summary-type: welke dag en welke metrics
const MAP: Record<string, (i: Item) => [string | undefined, Record<string, number | undefined>]> = {
  dailies: (i) => [i.calendarDate, {
    steps: i.steps,
    dist: i.distanceInMeters !== undefined ? i.distanceInMeters / 1000 : undefined,
    kcal: i.activeKilocalories,
    floors: i.floorsClimbed,
    rhr: i.restingHeartRateInBeatsPerMinute,
    stress: i.averageStressLevel >= 0 ? i.averageStressLevel : undefined,
    active: i.moderateIntensityDurationInSeconds !== undefined || i.vigorousIntensityDurationInSeconds !== undefined
      ? ((i.moderateIntensityDurationInSeconds ?? 0) + (i.vigorousIntensityDurationInSeconds ?? 0)) / 60
      : undefined,
  }],
  sleeps: (i) => [i.calendarDate, {
    sleep: i.durationInSeconds !== undefined ? i.durationInSeconds / 3600 : undefined,
    sscore: i.overallSleepScore?.value,
  }],
  hrv: (i) => [i.calendarDate, { hrv: i.lastNightAvg }],
  bodyComps: (i) => [dayFromEpoch(i.measurementTimeInSeconds, i.measurementTimeOffsetInSeconds ?? 0), {
    weight: i.weightInGrams !== undefined ? i.weightInGrams / 1000 : undefined,
  }],
  pulseox: (i) => [i.calendarDate, { spo2: avg(i.timeOffsetSpo2Values) }],
  respiration: (i) => [dayFromEpoch(i.startTimeInSeconds, i.startTimeOffsetInSeconds ?? 0), {
    resp: avg(i.timeOffsetEpochToBreaths),
  }],
  userMetrics: (i) => [i.calendarDate, { vo2: i.vo2Max }],
  stressDetails: (i) => [i.calendarDate, { energy: lastOf(i.bodyBatteryValuesMap) }],
};

function safeEqual(a: string, b: string) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

async function handlePush(sb: SupabaseClient, body: Record<string, Item[]>) {
  const { data: cat } = await sb.from("metrics").select("id,min_value,max_value");
  const range = new Map((cat ?? []).map((m) => [m.id, [Number(m.min_value), Number(m.max_value)]]));

  // Garmin userId → Vitaal user_id
  const ids = new Set<string>();
  for (const items of Object.values(body)) if (Array.isArray(items)) items.forEach((i) => i?.userId && ids.add(String(i.userId)));
  const { data: conns } = await sb.from("connections").select("user_id,external_user_id")
    .eq("provider", "garmin").in("external_user_id", [...ids]);
  const userOf = new Map((conns ?? []).map((c) => [c.external_user_id, c.user_id]));

  const rows = new Map<string, Row>();
  const raw: Record<string, unknown>[] = [];
  const now = new Date().toISOString();

  for (const [type, items] of Object.entries(body)) {
    if (!Array.isArray(items)) continue;
    for (let item of items) {
      const uid = userOf.get(String(item?.userId));
      if (!uid) continue;

      if (type === "deregistrations") {
        await sb.from("connections").update({ status: "revoked" }).eq("user_id", uid).eq("provider", "garmin");
        await sb.from("garmin_tokens").delete().eq("user_id", uid);
        continue;
      }
      if (type === "userPermissionsChange") {
        raw.push({ user_id: uid, summary_type: type, payload: item });
        continue;
      }
      const map = MAP[type];
      if (!map) continue;

      // Ping-modus (Enterprise): alleen een callbackURL, data zelf ophalen
      let list: Item[] = [item];
      if (item.callbackURL && Object.keys(item).length <= 3) {
        const token = await accessTokenFor(sb, uid);
        if (!token) continue;
        const r = await fetch(item.callbackURL, { headers: { Authorization: `Bearer ${token}` } });
        if (!r.ok) { console.error("ping fetch", type, r.status); continue; }
        list = await r.json();
      }

      for (item of list) {
        raw.push({ user_id: uid, summary_type: type, summary_id: item.summaryId ?? null, payload: item });
        const [day, values] = map(item);
        if (!day) continue;
        for (const [metric, v] of Object.entries(values)) {
          const lim = range.get(metric);
          if (typeof v !== "number" || !isFinite(v) || !lim || v < lim[0] || v > lim[1]) continue;
          // Garmin stuurt dagtotalen meerdere keren bij; de laatste wint
          rows.set(`${uid}|${day}|${metric}`, {
            user_id: uid, day, metric, source: "garmin", provider: "garmin",
            value: Math.round(v * 100) / 100, updated_at: now,
          });
        }
      }
      await sb.from("connections").update({ last_data_at: now, status: "active" })
        .eq("user_id", uid).eq("provider", "garmin");
    }
  }

  if (raw.length) {
    const { error } = await sb.from("garmin_raw").insert(raw);
    if (error) console.error("raw", error);
  }
  if (rows.size) {
    const { error } = await sb.from("daily_metrics").upsert([...rows.values()], { onConflict: "user_id,day,metric,source" });
    if (error) console.error("upsert", error);
  }
  console.log(`garmin-webhook: ${rows.size} dagwaarden, ${raw.length} summaries`);
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const key = new URL(req.url).searchParams.get("key") ?? "";
    if (!safeEqual(key, env("GARMIN_WEBHOOK_KEY"))) return json({ error: "forbidden" }, 403);

    let body: Record<string, Item[]>;
    try { body = await req.json(); } catch { return json({ error: "bad_json" }, 400); }

    // Garmin wil snel een 200; verwerken gebeurt daarna
    const work = handlePush(admin(), body).catch((e) => console.error(e));
    // @ts-ignore EdgeRuntime bestaat in de Supabase-runtime
    if (typeof EdgeRuntime !== "undefined") EdgeRuntime.waitUntil(work); else await work;
    return json({ ok: true });
  } catch (e) {
    return fail(e);
  }
});
