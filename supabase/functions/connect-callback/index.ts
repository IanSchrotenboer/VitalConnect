// GET /connect-callback?provider=<id>&code=...&state=...
// De terugkeerpagina op het eigen domein (app.vital-connect.nl/koppelen/<slug>/) stuurt de browser hierheen.
// Wisselt de code in, legt de gebruiker bij de bron vast, haalt bij een pull-bron direct 30 dagen op
// en stuurt terug naar de app: vitaal://<slug>?status=ok|cancelled|expired|not_linked|error
import { exchangeCode, storeTokens, takeState } from "../_shared/oauth.ts";
import { ConnectError, providerOf } from "../_shared/providers/index.ts";
import { admin, toApp } from "../_shared/shared.ts";
import { syncUser } from "../_shared/sync.ts";

Deno.serve(async (req) => {
  const u = new URL(req.url);
  const p = providerOf(u.searchParams.get("provider"));
  if (!p) return new Response("Onbekende bron", { status: 400 });
  if (u.searchParams.get("error")) return toApp(p.slug, u.searchParams.get("error") === "access_denied" ? "cancelled" : "error");
  const code = u.searchParams.get("code");
  const state = u.searchParams.get("state");
  if (!code || !state) return toApp(p.slug, "cancelled");

  try {
    const sb = admin();
    const st = await takeState(sb, state, p);
    if (!st) return toApp(p.slug, "expired");

    const tokens = await exchangeCode(p, code, st.code_verifier);
    if (!tokens.refresh_token) console.warn(`${p.id}: geen refresh token ontvangen`);
    const externalId = await p.identify(tokens.access_token);

    // Eén account bij de bron hoort bij één Vital Connect-account
    if (externalId) {
      await sb.from("connections").delete().eq("provider", p.id).eq("external_user_id", externalId).neq("user_id", st.user_id);
    }
    await storeTokens(sb, st.user_id, p, tokens);
    const { error } = await sb.from("connections").upsert({
      user_id: st.user_id, provider: p.id, external_user_id: externalId,
      status: "active", connected_at: new Date().toISOString(), last_error: null,
    });
    if (error) throw error;

    if (p.sync === "pull") {
      // Eerste keer 30 dagen ophalen; mag het terugsturen niet ophouden
      const work = syncUser(sb, p, st.user_id, "connect", null).catch((e) => console.error("eerste sync", e));
      // @ts-ignore EdgeRuntime bestaat in de Supabase-runtime
      if (typeof EdgeRuntime !== "undefined") EdgeRuntime.waitUntil(work);
    }
    return toApp(p.slug, "ok");
  } catch (e) {
    console.error(e);
    return toApp(p.slug, e instanceof ConnectError ? e.status : "error");
  }
});
