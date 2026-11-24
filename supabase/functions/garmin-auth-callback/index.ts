// GET /garmin-auth-callback?code=...&state=...  (Garmin stuurt de browser hierheen)
import { admin, fnUrl, GARMIN, storeTokens, takeOAuthState, toApp, tokenRequest } from "../_shared/shared.ts";

Deno.serve(async (req) => {
  const u = new URL(req.url);
  const code = u.searchParams.get("code");
  const state = u.searchParams.get("state");
  if (!code || !state) return toApp("garmin", "cancelled");

  try {
    const sb = admin();
    const st = await takeOAuthState(sb, state, "garmin");
    if (!st) return toApp("garmin", "expired");

    const tokens = await tokenRequest("garmin", {
      grant_type: "authorization_code",
      code,
      code_verifier: st.code_verifier,
      redirect_uri: fnUrl("garmin-auth-callback"),
    });
    const idRes = await fetch(GARMIN.userId, { headers: { Authorization: `Bearer ${tokens.access_token}` } });
    if (!idRes.ok) throw new Error(`Garmin user id ${idRes.status}`);
    const { userId } = await idRes.json();

    // Eén Garmin-account hoort bij één Vitaal-account
    await sb.from("connections").delete().eq("provider", "garmin").eq("external_user_id", String(userId)).neq("user_id", st.user_id);
    await storeTokens(sb, st.user_id, "garmin", tokens);
    const { error } = await sb.from("connections").upsert({
      user_id: st.user_id, provider: "garmin", external_user_id: String(userId),
      status: "active", connected_at: new Date().toISOString(), last_error: null,
    });
    if (error) throw error;
    return toApp("garmin", "ok");
  } catch (e) {
    console.error(e);
    return toApp("garmin", "error");
  }
});
