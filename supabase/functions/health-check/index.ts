// POST /health-check  (header x-cron-secret, alleen voor beheer)
// Controleert de configuratie zonder geheimen te tonen: per bron of hij open staat, welke geheimen ontbreken en welk
// terugkeeradres bij de bron geregistreerd moet zijn; of TOKEN_ENC_KEY werkt en of Google de clientgegevens accepteert.
import { redirectUri, tokenRequest } from "../_shared/oauth.ts";
import { configured, PROVIDERS } from "../_shared/providers/index.ts";
import { admin, decrypt, encrypt, isCron, json } from "../_shared/shared.ts";

const GENERAL = ["TOKEN_ENC_KEY", "RESEND_API_KEY", "EMAIL_FROM", "SEND_EMAIL_HOOK_SECRET"];

Deno.serve(async (req) => {
  const sb = admin();
  if (!(await isCron(req, sb))) return json({ error: "unauthorized" }, 401);

  const { data: rows } = await sb.from("providers").select("id,available");
  const open = new Map((rows ?? []).map((r) => [r.id, r.available]));
  const providers = Object.fromEntries(Object.values(PROVIDERS).map((p) => [p.id, {
    available: open.get(p.id) ?? false,
    configured: configured(p),
    missing_secrets: p.secrets.filter((s) => !Deno.env.get(s)),
    redirect_uri: redirectUri(p),
    // Client-ID is niet geheim: tonen zodat je hem kunt vergelijken met het portaal van de bron
    client_id: Deno.env.get(p.clientIdEnv) ?? null,
  }]));
  const out: Record<string, unknown> = {
    providers,
    secrets_present: Object.fromEntries(GENERAL.map((s) => [s, !!Deno.env.get(s)])),
  };

  // TOKEN_ENC_KEY: 32 bytes en een versleutel/ontsleutel-rondje
  try {
    out.token_enc_key = (await decrypt(await encrypt("vitaal-test"))) === "vitaal-test" ? "ok" : "mismatch";
  } catch (e) {
    out.token_enc_key = `fout: ${String(e instanceof Error ? e.message : e)}`;
  }

  // Google-client: een nep-code inwisselen. invalid_grant = client-ID en secret kloppen.
  const google = PROVIDERS.google_health;
  if (configured(google)) {
    try {
      await tokenRequest(google, { grant_type: "authorization_code", code: "vitaal-health-check", redirect_uri: redirectUri(google) });
      out.google_client = "onverwacht: nep-code geaccepteerd";
    } catch (e) {
      const msg = String(e instanceof Error ? e.message : e);
      out.google_client = msg.includes("invalid_grant") ? "ok (client-ID en secret geaccepteerd)"
        : msg.includes("invalid_client") || msg.includes("unauthorized_client") ? "FOUT: client-ID of secret klopt niet"
        : `onverwacht: ${msg.slice(0, 200)}`;
    }
  } else {
    out.google_client = "niet te controleren: geheim ontbreekt";
  }

  return json(out);
});
