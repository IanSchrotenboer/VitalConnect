// POST /health-check  (header x-cron-secret, alleen voor beheer)
// Controleert de configuratie zonder geheimen te tonen: welke secrets er staan,
// of TOKEN_ENC_KEY werkt en of de Google-clientgegevens door Google worden geaccepteerd.
import { admin, decrypt, encrypt, fnUrl, GOOGLE, isCron, json } from "../_shared/shared.ts";

const SECRETS = [
  "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "TOKEN_ENC_KEY",
  "RESEND_API_KEY", "EMAIL_FROM", "SEND_EMAIL_HOOK_SECRET",
  "GARMIN_CLIENT_ID", "GARMIN_CLIENT_SECRET", "GARMIN_WEBHOOK_KEY",
];

Deno.serve(async (req) => {
  const sb = admin();
  if (!(await isCron(req, sb))) return json({ error: "unauthorized" }, 401);

  const present = Object.fromEntries(SECRETS.map((s) => [s, !!Deno.env.get(s)]));
  const out: Record<string, unknown> = { secrets_present: present };

  // Google client-ID is niet geheim: tonen zodat je hem kunt vergelijken
  out.google_client_id = Deno.env.get("GOOGLE_CLIENT_ID") ?? null;

  // TOKEN_ENC_KEY: 32 bytes en een versleutel/ontsleutel-rondje
  try {
    out.token_enc_key = (await decrypt(await encrypt("vitaal-test"))) === "vitaal-test" ? "ok" : "mismatch";
  } catch (e) {
    out.token_enc_key = `fout: ${String(e instanceof Error ? e.message : e)}`;
  }

  // Google-client: een nep-code inwisselen. invalid_grant = client-ID en secret kloppen.
  if (present.GOOGLE_CLIENT_ID && present.GOOGLE_CLIENT_SECRET) {
    const r = await fetch(GOOGLE.token, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code: "vitaal-health-check",
        client_id: Deno.env.get("GOOGLE_CLIENT_ID")!,
        client_secret: Deno.env.get("GOOGLE_CLIENT_SECRET")!,
        redirect_uri: fnUrl("google-health-auth-callback"),
      }),
    });
    const body = await r.json().catch(() => ({}));
    const err = String(body.error ?? "");
    out.google_client = err === "invalid_grant" ? "ok (client-ID en secret geaccepteerd)"
      : err === "invalid_client" || err === "unauthorized_client" ? "FOUT: client-ID of secret klopt niet"
      : `onverwacht: ${r.status} ${err} ${body.error_description ?? ""}`.trim();
  } else {
    out.google_client = "niet te controleren: secret ontbreekt";
  }

  return json(out);
});
