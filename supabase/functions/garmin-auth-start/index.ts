// POST /garmin-auth-start  (JWT van de ingelogde gebruiker) → { url }
import { admin, env, fail, fnUrl, GARMIN, json, newOAuthState, userFrom } from "../_shared/shared.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const sb = admin();
    const user = await userFrom(req, sb);
    if (!user) return json({ error: "unauthorized" }, 401);

    const { state, codeChallenge } = await newOAuthState(sb, user.id, "garmin");
    const url = new URL(GARMIN.authorize);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("client_id", env("GARMIN_CLIENT_ID"));
    url.searchParams.set("code_challenge", codeChallenge);
    url.searchParams.set("code_challenge_method", "S256");
    url.searchParams.set("redirect_uri", fnUrl("garmin-auth-callback"));
    url.searchParams.set("state", state);
    return json({ url: url.toString() });
  } catch (e) {
    return fail(e);
  }
});
