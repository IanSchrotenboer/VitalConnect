// POST /delete-account  (met de JWT van de ingelogde gebruiker)
// AVG: ontkoppelt Garmin bij Garmin zelf en verwijdert daarna het account met alle data (cascade).
import { accessTokenFor, admin, fail, GARMIN, json, userFrom } from "./shared.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();
    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);

    let garmin = "not_connected";
    try {
      const token = await accessTokenFor(sb, user.id);
      if (token) {
        const r = await fetch(GARMIN.registration, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
        garmin = r.ok || r.status === 404 ? "deregistered" : `failed_${r.status}`;
      }
    } catch (e) {
      // Ontkoppelen bij Garmin mag het verwijderen niet blokkeren
      console.error("garmin deregistration", e);
      garmin = "failed";
    }

    const { error } = await sb.auth.admin.deleteUser(user.id);
    if (error) throw error;
    return json({ deleted: true, garmin });
  } catch (e) {
    return fail(e);
  }
});
