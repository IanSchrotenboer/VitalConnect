// POST /garmin-disconnect  (met de JWT van de ingelogde gebruiker)
// Trekt de toestemming bij Garmin in en verwijdert de tokens. Bestaande data blijft staan.
import { accessTokenFor, admin, fail, GARMIN, json, userFrom } from "./shared.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();
    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);

    const token = await accessTokenFor(sb, user.id).catch(() => null);
    if (token) {
      const r = await fetch(GARMIN.registration, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
      if (!r.ok && r.status !== 404) console.error("deregistration", r.status, await r.text());
    }
    await sb.from("garmin_tokens").delete().eq("user_id", user.id);
    await sb.from("connections").update({ status: "revoked" }).eq("user_id", user.id).eq("provider", "garmin");
    return json({ disconnected: true });
  } catch (e) {
    return fail(e);
  }
});
