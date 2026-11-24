// POST /disconnect  {"provider":"garmin"|"google_health","delete_data":false}  (JWT)
// Trekt de toestemming bij de provider in, verwijdert tokens en zet de koppeling op 'revoked'.
// Met delete_data=true worden ook alle dagwaarden van die bron verwijderd.
import { authed, json, Provider, revokeAtProvider } from "../_shared/shared.ts";

const PROVIDERS: Provider[] = ["garmin", "google_health"];

Deno.serve(authed(async (req, sb, user) => {
  const body = await req.json().catch(() => ({}));
  const provider = body.provider as Provider;
  if (!PROVIDERS.includes(provider)) return json({ error: "unknown_provider" }, 400);

  const revoked = await revokeAtProvider(sb, user.id, provider);
  await sb.from("oauth_tokens").delete().eq("user_id", user.id).eq("provider", provider);
  await sb.from("connections").update({ status: "revoked" }).eq("user_id", user.id).eq("provider", provider);
  if (body.delete_data === true) {
    await sb.from("daily_metrics").delete().eq("user_id", user.id).eq("provider", provider);
  }
  return json({ disconnected: true, revoked });
}));
