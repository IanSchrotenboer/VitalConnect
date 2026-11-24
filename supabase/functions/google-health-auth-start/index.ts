// POST /google-health-auth-start  (JWT) → { url }
// Start de toestemming voor de Google Health API (los van inloggen met Google:
// inloggen vraagt alleen je identiteit, dit vraagt leesrechten op gezondheidsdata).
import { admin, env, fail, fnUrl, GOOGLE, json, newOAuthState, userFrom } from "../_shared/shared.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();
    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);

    const { state, codeChallenge } = await newOAuthState(sb, user.id, "google_health");
    const url = new URL(GOOGLE.authorize);
    url.searchParams.set("client_id", env("GOOGLE_CLIENT_ID"));
    url.searchParams.set("redirect_uri", fnUrl("google-health-auth-callback"));
    url.searchParams.set("response_type", "code");
    url.searchParams.set("scope", GOOGLE.scopes.join(" "));
    url.searchParams.set("access_type", "offline");          // refresh token voor de uurlijkse sync
    url.searchParams.set("prompt", "consent");
    url.searchParams.set("include_granted_scopes", "true");
    url.searchParams.set("state", state);
    url.searchParams.set("code_challenge", codeChallenge);
    url.searchParams.set("code_challenge_method", "S256");
    if (user.email) url.searchParams.set("login_hint", user.email);
    return json({ url: url.toString() });
  } catch (e) {
    return fail(e);
  }
});
