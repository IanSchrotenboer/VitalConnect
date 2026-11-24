// GET /google-health-auth-callback?code=...&state=...  (Google stuurt de browser hierheen)
// Wisselt de code in, legt de Google Health-gebruiker vast, haalt direct 30 dagen op en stuurt terug naar de app.
import { admin, GOOGLE, googleRedirectUri, storeTokens, takeOAuthState, toApp, tokenRequest } from "../_shared/shared.ts";
import { syncUser } from "../_shared/google-health.ts";

Deno.serve(async (req) => {
  const u = new URL(req.url);
  if (u.searchParams.get("error")) return toApp("google", u.searchParams.get("error") === "access_denied" ? "cancelled" : "error");
  const code = u.searchParams.get("code");
  const state = u.searchParams.get("state");
  if (!code || !state) return toApp("google", "cancelled");

  try {
    const sb = admin();
    const st = await takeOAuthState(sb, state, "google_health");
    if (!st) return toApp("google", "expired");

    const tokens = await tokenRequest("google_health", {
      grant_type: "authorization_code",
      code,
      code_verifier: st.code_verifier,
      redirect_uri: googleRedirectUri(),
    });
    if (!tokens.refresh_token) console.warn("Geen refresh token ontvangen; sync werkt tot het access token verloopt");

    // Het tokenantwoord bevat geen gebruikers-ID: identity direct na de uitwisseling ophalen
    const idRes = await fetch(`${GOOGLE.api}/users/me/identity`, {
      headers: { Authorization: `Bearer ${tokens.access_token}`, Accept: "application/json" },
    });
    if (!idRes.ok) {
      const body = await idRes.text();
      console.error("identity", idRes.status, body);
      return toApp("google", body.includes("ACCOUNT_NOT_LINKED") ? "not_linked" : "error");
    }
    const identity = await idRes.json();
    const externalId = String(identity.healthUserId ?? identity.legacyUserId ?? "");

    if (externalId) {
      await sb.from("connections").delete().eq("provider", "google_health").eq("external_user_id", externalId).neq("user_id", st.user_id);
    }
    await storeTokens(sb, st.user_id, "google_health", tokens);
    const { error } = await sb.from("connections").upsert({
      user_id: st.user_id, provider: "google_health", external_user_id: externalId || null,
      status: "active", connected_at: new Date().toISOString(), last_error: null,
    });
    if (error) throw error;

    // Eerste keer 30 dagen ophalen; mag het terugsturen niet ophouden
    const work = syncUser(sb, st.user_id, "user").catch((e) => console.error("eerste sync", e));
    // @ts-ignore EdgeRuntime bestaat in de Supabase-runtime
    if (typeof EdgeRuntime !== "undefined") EdgeRuntime.waitUntil(work);
    return toApp("google", "ok");
  } catch (e) {
    console.error(e);
    return toApp("google", "error");
  }
});
