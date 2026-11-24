// POST /delete-account  (JWT)
// AVG: trekt toestemmingen in bij Garmin en Google en verwijdert daarna het account met alle data (cascade).
import { admin, fail, json, revokeAtProvider, userFrom } from "../_shared/shared.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();
    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);

    const garmin = await revokeAtProvider(sb, user.id, "garmin");
    const google_health = await revokeAtProvider(sb, user.id, "google_health");

    const { error } = await sb.auth.admin.deleteUser(user.id);
    if (error) throw error;
    return json({ deleted: true, revoked: { garmin, google_health } });
  } catch (e) {
    return fail(e);
  }
});
