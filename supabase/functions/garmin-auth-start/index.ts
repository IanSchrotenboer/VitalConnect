// POST /garmin-auth-start  (met de JWT van de ingelogde gebruiker)
// Antwoord: { url } — open die in de browser; Garmin stuurt daarna door naar garmin-auth-callback.
import { admin, challenge, env, fail, GARMIN, json, randomString, userFrom } from "./shared.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();
    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);

    const clientId = env("GARMIN_CLIENT_ID");
    const verifier = randomString(48); // 64 tekens, binnen de PKCE-grens van 43–128
    const state = randomString(24);

    // Oude, onafgemaakte pogingen van deze gebruiker opruimen
    await sb.from("oauth_states").delete().eq("user_id", user.id);
    const { error } = await sb.from("oauth_states").insert({ state, user_id: user.id, code_verifier: verifier });
    if (error) throw error;

    const url = new URL(GARMIN.authorize);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("client_id", clientId);
    url.searchParams.set("code_challenge", await challenge(verifier));
    url.searchParams.set("code_challenge_method", "S256");
    url.searchParams.set("redirect_uri", `${env("SUPABASE_URL")}/functions/v1/garmin-auth-callback`);
    url.searchParams.set("state", state);
    return json({ url: url.toString() });
  } catch (e) {
    return fail(e);
  }
});
