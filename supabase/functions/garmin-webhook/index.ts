// POST /garmin-webhook?key=<GARMIN_WEBHOOK_KEY>
// Ontvangt push-notificaties van de Garmin Health API en zet ze om naar dagwaarden (omzetting: providers/garmin.ts).
// Stel in het Garmin Developer Portal per summary-type deze URL in (push).
// Veldnamen volgen de Garmin Health API-specificatie; controleer ze na goedkeuring tegen de actuele spec.
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { accessTokenFor } from "../_shared/oauth.ts";
import { garmin, GARMIN_SUMMARIES, type Item } from "../_shared/providers/garmin.ts";
import type { Row } from "../_shared/providers/index.ts";
import { admin, env, fail, finishRun, json, safeEqual, startRun, writeDaily } from "../_shared/shared.ts";

/** Alleen https-adressen van Garmin (apis.garmin.com en subdomeinen van garmin.com). */
export function isGarminUrl(u: string): boolean {
  try {
    const url = new URL(u);
    return url.protocol === "https:" && (url.hostname === "garmin.com" || url.hostname.endsWith(".garmin.com"));
  } catch {
    return false;
  }
}

async function handlePush(sb: SupabaseClient, body: Record<string, Item[]>) {
  // Garmin userId → Vitaal user_id
  const ids = new Set<string>();
  for (const items of Object.values(body)) if (Array.isArray(items)) items.forEach((i) => i?.userId && ids.add(String(i.userId)));
  if (!ids.size) return;
  const { data: conns } = await sb.from("connections").select("user_id,external_user_id")
    .eq("provider", "garmin").in("external_user_id", [...ids]);
  const userOf = new Map<string, string>((conns ?? []).map((c) => [String(c.external_user_id), String(c.user_id)]));

  const rows: Row[] = [];
  const raw: Record<string, unknown>[] = [];
  const touched = new Set<string>();

  for (const [type, items] of Object.entries(body)) {
    if (!Array.isArray(items)) continue;
    for (const item of items) {
      const uid = userOf.get(String(item?.userId));
      if (!uid) continue;

      if (type === "deregistrations") {
        await sb.from("connections").update({ status: "revoked" }).eq("user_id", uid).eq("provider", "garmin");
        await sb.from("oauth_tokens").delete().eq("user_id", uid).eq("provider", "garmin");
        continue;
      }
      if (type === "userPermissionsChange") {
        raw.push({ user_id: uid, provider: "garmin", summary_type: type, payload: item });
        continue;
      }
      const map = GARMIN_SUMMARIES[type];
      if (!map) continue;

      // Ping-modus (Enterprise): alleen een callbackURL, data zelf ophalen
      let list: Item[] = [item];
      const callback = typeof item.callbackURL === "string" ? item.callbackURL : "";
      if (callback && Object.keys(item).length <= 3) {
        // Het toegangstoken gaat alleen mee naar Garmin zelf, nooit naar een ander adres uit het bericht
        if (!isGarminUrl(callback)) { console.error("callbackURL niet van Garmin", type); continue; }
        const token = await accessTokenFor(sb, uid, garmin);
        if (!token) continue;
        const r = await fetch(callback, { headers: { Authorization: `Bearer ${token}` } });
        if (!r.ok) { console.error("ping fetch", type, r.status); continue; }
        list = await r.json();
      }

      for (const it of list) {
        raw.push({ user_id: uid, provider: "garmin", summary_type: type, summary_id: typeof it.summaryId === "string" ? it.summaryId : null, payload: it });
        const [day, values] = map(it);
        if (!day) continue;
        for (const [metric, v] of Object.entries(values)) {
          if (typeof v === "number") rows.push({ user_id: uid, day, metric, value: v });
        }
      }
      touched.add(uid);
    }
  }

  if (raw.length) {
    const { error } = await sb.from("provider_raw").insert(raw);
    if (error) console.error("raw", error);
  }

  // Per gebruiker wegschrijven en loggen; de laatste waarde per dag wint
  const now = new Date().toISOString();
  for (const uid of touched) {
    const run = startRun(uid, "garmin", "webhook");
    try {
      await writeDaily(sb, rows.filter((r) => r.user_id === uid), "garmin");
      await sb.from("connections").update({ last_data_at: now, last_sync_at: now, status: "active", last_error: null })
        .eq("user_id", uid).eq("provider", "garmin");
    } catch (e) {
      await finishRun(sb, run, 0, String(e));
    }
  }
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
