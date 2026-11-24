// POST /google-health-sync
// - Vanuit pg_cron (header x-cron-secret): alle actieve Google Health-koppelingen, 4 tegelijk.
// - Vanuit de app (JWT): alleen de ingelogde gebruiker, met resultaat.
import { admin, fail, isCron, json, userFrom } from "../_shared/shared.ts";
import { syncUser } from "../_shared/google-health.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();

    if (await isCron(req, sb)) {
      const { data } = await sb.from("connections").select("user_id")
        .eq("provider", "google_health").eq("status", "active")
        .order("last_sync_at", { ascending: true, nullsFirst: true }).limit(200);
      const users = (data ?? []).map((c) => c.user_id as string);
      let ok = 0, failed = 0;
      for (let i = 0; i < users.length; i += 4) {
        const res = await Promise.all(users.slice(i, i + 4).map((u) => syncUser(sb, u, "cron")));
        res.forEach((r) => (r.error ? failed++ : ok++));
      }
      return json({ users: users.length, ok, failed });
    }

    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);
    const res = await syncUser(sb, user.id, "user");
    return json(res, res.error ? 502 : 200);
  } catch (e) {
    return fail(e);
  }
});
