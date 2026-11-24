// GET /garmin-auth-callback?code=...&state=...   (Garmin stuurt de browser hierheen)
// Wisselt de code in voor tokens, legt de Garmin-gebruiker vast en stuurt door naar de app.
import { admin, env, GARMIN, garminToken, storeTokens } from "./shared.ts";

const APP = () => Deno.env.get("APP_REDIRECT") ?? "vitaal://garmin";
const back = (status: string) =>
  new Response(null, { status: 302, headers: { Location: `${APP()}?status=${status}` } });

Deno.serve(async (req) => {
  const u = new URL(req.url);
  const code = u.searchParams.get("code");
  const state = u.searchParams.get("state");
  if (!code || !state) return back("cancelled");

  try {
    const sb = admin();
    const { data: st } = await sb.from("oauth_states").select("*").eq("state", state).maybeSingle();
    if (!st) return back("invalid_state");
    await sb.from("oauth_states").delete().eq("state", state);
    if (Date.now() - new Date(st.created_at).getTime() > 15 * 60_000) return back("expired");

    const tokens = await garminToken({
      grant_type: "authorization_code",
      code,
      code_verifier: st.code_verifier,
      redirect_uri: `${env("SUPABASE_URL")}/functions/v1/garmin-auth-callback`,
    });

    const idRes = await fetch(GARMIN.userId, { headers: { Authorization: `Bearer ${tokens.access_token}` } });
    if (!idRes.ok) throw new Error(`Garmin user id ${idRes.status}`);
    const { userId } = await idRes.json();

    // Hetzelfde Garmin-account kan maar aan één Vitaal-account hangen
    await sb.from("connections").delete().eq("provider", "garmin").eq("external_user_id", userId).neq("user_id", st.user_id);

    await storeTokens(sb, st.user_id, tokens);
    const { error } = await sb.from("connections").upsert({
      user_id: st.user_id,
      provider: "garmin",
      external_user_id: String(userId),
      status: "active",
      connected_at: new Date().toISOString(),
    });
    if (error) throw error;
    return back("ok");
  } catch (e) {
    console.error(e);
    return back("error");
  }
});
