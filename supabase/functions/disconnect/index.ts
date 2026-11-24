// POST /disconnect  {"provider":"garmin"|"google_health"|"suunto","delete_data":false}  (JWT)
// Trekt de toestemming bij de bron in, verwijdert de tokens en zet de koppeling op 'revoked'.
// Met delete_data=true worden ook alle dagwaarden van die bron verwijderd.
import { revokeAtProvider } from "../_shared/oauth.ts";
import { providerOf } from "../_shared/providers/index.ts";
import { authed, json } from "../_shared/shared.ts";

Deno.serve(authed(async (req, sb, user) => {
  const body = await req.json().catch(() => ({}));
  const p = providerOf(body.provider);
  if (!p) return json({ error: "unknown_provider" }, 400);

  const revoked = await revokeAtProvider(sb, user.id, p);
  await sb.from("oauth_tokens").delete().eq("user_id", user.id).eq("provider", p.id);
  await sb.from("connections").update({ status: "revoked" }).eq("user_id", user.id).eq("provider", p.id);
  if (body.delete_data === true) {
    await sb.from("daily_metrics").delete().eq("user_id", user.id).eq("provider", p.id);
  }
  return json({ disconnected: true, revoked });
}));
