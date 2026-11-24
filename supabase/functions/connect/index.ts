// POST /connect  {"provider":"google_health"|"garmin"|"suunto"}  (JWT) → { url } van het toestemmingsscherm
// 409 not_available: de bron staat (nog) niet open (tabel providers, kolom available).
// 503 not_configured: de geheimen van de bron ontbreken in Supabase.
import { authorizeUrl } from "../_shared/oauth.ts";
import { configured, providerOf } from "../_shared/providers/index.ts";
import { authed, json } from "../_shared/shared.ts";

Deno.serve(authed(async (req, sb, user) => {
  const body = await req.json().catch(() => ({}));
  const p = providerOf(body.provider);
  if (!p) return json({ error: "unknown_provider" }, 400);
  const { data: row } = await sb.from("providers").select("available").eq("id", p.id).maybeSingle();
  if (!row?.available) return json({ error: "not_available", provider: p.id }, 409);
  if (!configured(p)) return json({ error: "not_configured", provider: p.id }, 503);
  return json({ url: await authorizeUrl(sb, p, { id: user.id, email: user.email }) });
}));
