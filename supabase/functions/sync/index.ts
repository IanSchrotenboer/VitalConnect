// POST /sync — ophalen bij de pull-bronnen (Google Health, Suunto). Garmin stuurt zelf (garmin-webhook).
// - Vanuit pg_cron (header x-cron-secret), elk kwartier per portie {shard, shards}: per bron de gebruikers die 3 uur
//   niet zijn bijgewerkt (meestal doet de telefoon het zelf al), max. 200 per bron per aanroep, 8 tegelijk.
// - Vanuit de app (JWT) {provider?, background?}: alleen de ingelogde gebruiker, alle gekoppelde pull-bronnen of één.
//   Bij vernieuwen hooguit eens per 10 minuten per bron, vanuit de achtergrondtaak hooguit eens per 30.
// Antwoord voor de app: { rows, skipped, results: { <bron>: { rows, skipped?, error? } } }
import { configured, PROVIDERS, providerOf } from "../_shared/providers/index.ts";
import { admin, fail, isCron, json, userFrom } from "../_shared/shared.ts";
import { type SyncState, syncUser } from "../_shared/sync.ts";

const MIN_INTERVAL_MS = { user: 10 * 60_000, background: 30 * 60_000 };
const PULL = Object.values(PROVIDERS).filter((p) => p.sync === "pull");

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();
    const body = await req.json().catch(() => ({}));
    const { data: rows } = await sb.from("providers").select("id,available");
    const open = new Set((rows ?? []).filter((r) => r.available).map((r) => r.id));
    const active = PULL.filter((p) => open.has(p.id) && configured(p));

    if (await isCron(req, sb)) {
      const shards = Math.max(1, Math.min(1000, Number(body.shards) || 1));
      const shard = Math.max(0, Math.min(shards - 1, Number(body.shard) || 0));
      const out: Record<string, { users: number; failed: number }> = {};
      for (const p of active) {
        const { data, error } = await sb.rpc("provider_due", { p_provider: p.id, p_shard: shard, p_shards: shards, p_limit: 200, p_stale: "3 hours" });
        if (error) throw error;
        const users = (data ?? []) as string[];
        let failed = 0;
        for (let i = 0; i < users.length; i += 8) {
          const res = await Promise.all(users.slice(i, i + 8).map((u) => syncUser(sb, p, u, "cron")));
          failed += res.filter((r) => r.error).length;
        }
        out[p.id] = { users: users.length, failed };
      }
      return json({ shard, shards, providers: out });
    }

    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);
    const only = body.provider ? providerOf(body.provider) : null;
    if (body.provider && !only) return json({ error: "unknown_provider" }, 400);
    const min = body.background === true ? MIN_INTERVAL_MS.background : MIN_INTERVAL_MS.user;
    const { data: conns } = await sb.from("connections").select("provider,last_data_at,last_sync_at")
      .eq("user_id", user.id).eq("status", "active");
    const results: Record<string, { rows: number; skipped?: boolean; error?: string }> = {};
    await Promise.all(active.filter((p) => !only || p.id === only.id).map(async (p) => {
      const c = (conns ?? []).find((x) => x.provider === p.id);
      if (!c) return;
      if (c.last_sync_at && Date.now() - Date.parse(c.last_sync_at) < min) { results[p.id] = { rows: 0, skipped: true }; return; }
      results[p.id] = await syncUser(sb, p, user.id, "user", c as SyncState);
    }));
    // Altijd 200: per bron staat in results of het lukte, zodat de app per bron kan melden wat er misging
    const all = Object.values(results);
    return json({
      rows: all.reduce((s, r) => s + r.rows, 0),
      skipped: all.length > 0 && all.every((r) => r.skipped),
      results,
    });
  } catch (e) {
    return fail(e);
  }
});
