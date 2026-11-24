// POST /delete-account  (JWT)
// AVG: trekt toestemmingen in bij Garmin en Google en verwijdert daarna het account met alle data (cascade).
import { authed, json, revokeAtProvider } from "../_shared/shared.ts";

Deno.serve(authed(async (_req, sb, user) => {
  const garmin = await revokeAtProvider(sb, user.id, "garmin");
  const google_health = await revokeAtProvider(sb, user.id, "google_health");

  const { error } = await sb.auth.admin.deleteUser(user.id);
  if (error) throw error;
  return json({ deleted: true, revoked: { garmin, google_health } });
}));
