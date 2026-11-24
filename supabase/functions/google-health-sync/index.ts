// POST /google-health-sync
// - Vanuit pg_cron (header x-cron-secret), elk kwartier per portie {shard, shards}: gebruikers die 3 uur niet zijn
//   bijgewerkt (meestal doet de telefoon het zelf al), max. 200 per aanroep, 8 tegelijk.
// - Vanuit de app (JWT): alleen de ingelogde gebruiker. Bij vernieuwen hooguit eens per 10 minuten, vanuit de
//   achtergrondtaak ({"background":true}) hooguit eens per 30: zo blijft het aantal aanroepen naar Google beperkt.
import { admin, fail, isCron, json, userFrom } from "../_shared/shared.ts";
import { type SyncState, syncUser } from "../_shared/google-health.ts";

const MIN_INTERVAL_MS = { user: 10 * 60_000, background: 30 * 60_000 };

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();

    if (await isCron(req, sb)) {
      const body = await req.json().catch(() => ({}));
      const shards = Math.max(1, Math.min(1000, Number(body.shards) || 1));
      const shard = Math.max(0, Math.min(shards - 1, Number(body.shard) || 0));
      const { data, error } = await sb.rpc("google_health_due", { p_shard: shard, p_shards: shards, p_limit: 200, p_stale: "3 hours" });
      if (error) throw error;
      const users = (data ?? []) as string[];
      let ok = 0, failed = 0;
      for (let i = 0; i < users.length; i += 8) {
        const res = await Promise.all(users.slice(i, i + 8).map((u) => syncUser(sb, u, "cron")));
        res.forEach((r) => (r.error ? failed++ : ok++));
      }
      return json({ shard, shards, users: users.length, ok, failed });
    }

    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);
    const { data } = await sb.from("connections").select("last_data_at,last_sync_at")
      .eq("user_id", user.id).eq("provider", "google_health").maybeSingle();
    const conn = data as SyncState;
    const background = (await req.json().catch(() => ({}))).background === true;
    const min = background ? MIN_INTERVAL_MS.background : MIN_INTERVAL_MS.user;
    if (conn?.last_sync_at && Date.now() - Date.parse(conn.last_sync_at) < min) {
      return json({ rows: 0, skipped: true });
    }
    const res = await syncUser(sb, user.id, "user", conn);
    return json(res, res.error ? 502 : 200);
  } catch (e) {
    return fail(e);
  }
});
