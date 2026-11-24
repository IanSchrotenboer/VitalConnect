// Gedeelde helpers voor de Vitaal edge functions.
import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

export type Provider = "garmin" | "google_health";

export const GARMIN = {
  authorize: "https://connect.garmin.com/oauth2Confirm",
  token: "https://connectapi.garmin.com/di-oauth2-service/oauth/token",
  userId: "https://apis.garmin.com/wellness-api/rest/user/id",
  registration: "https://apis.garmin.com/wellness-api/rest/user/registration",
};

export const GOOGLE = {
  authorize: "https://accounts.google.com/o/oauth2/v2/auth",
  token: "https://oauth2.googleapis.com/token",
  revoke: "https://oauth2.googleapis.com/revoke",
  api: "https://health.googleapis.com/v4",
  scopes: [
    "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
    "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
    "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
  ],
};

// ---------- Basis ----------
export class ConfigError extends Error {
  constructor(public secret: string) { super(`Secret ${secret} ontbreekt`); }
}

export function env(name: string): string {
  const v = Deno.env.get(name);
  if (!v) throw new ConfigError(name);
  return v;
}

export function admin(): SupabaseClient {
  return createClient(env("SUPABASE_URL"), env("SUPABASE_SERVICE_ROLE_KEY"), {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export const fnUrl = (name: string) => `${env("SUPABASE_URL")}/functions/v1/${name}`;

/**
 * Terugkeeradres na de Google-toestemming. Met het geheim GOOGLE_REDIRECT_URI (https://app.vital-connect.nl/koppelen/google/)
 * staat alleen het eigen domein in Google Cloud; die pagina stuurt door naar google-health-auth-callback.
 * Zonder geheim: het oude adres op supabase.co. Het moet exact gelijk zijn in het verzoek en bij het inwisselen.
 */
export const googleRedirectUri = () => Deno.env.get("GOOGLE_REDIRECT_URI") || fnUrl("google-health-auth-callback");

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export function fail(e: unknown): Response {
  if (e instanceof ConfigError) {
    console.error(e.message);
    return json({ error: "not_configured", secret: e.secret }, 503);
  }
  console.error(e);
  return json({ error: "internal" }, 500);
}

/** Terug naar de app via deep link, bijvoorbeeld vitaal://google?status=ok */
export function toApp(host: string, status: string): Response {
  const base = Deno.env.get("APP_REDIRECT_BASE") ?? "vitaal://";
  return new Response(null, { status: 302, headers: { Location: `${base}${host}?status=${encodeURIComponent(status)}` } });
}

/** Ingelogde gebruiker uit de Authorization-header. */
export async function userFrom(req: Request, sb: SupabaseClient) {
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return null;
  const { data, error } = await sb.auth.getUser(token);
  return error ? null : data.user;
}

type User = NonNullable<Awaited<ReturnType<typeof userFrom>>>;

/** POST-endpoint voor een ingelogde gebruiker: methode, login en foutafhandeling op één plek. */
export function authed(handler: (req: Request, sb: SupabaseClient, user: User) => Promise<Response>) {
  return async (req: Request): Promise<Response> => {
    if (req.method !== "POST") return json({ error: "method" }, 405);
    try {
      const sb = admin();
      const user = await userFrom(req, sb);
      if (!user) return json({ error: "unauthorized" }, 401);
      return await handler(req, sb, user);
    } catch (e) {
      return fail(e);
    }
  };
}

export function safeEqual(a: string, b: string): boolean {
  if (!a || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

/** Aanroep vanuit pg_cron: header x-cron-secret moet overeenkomen met app_secrets.cron_secret. */
export async function isCron(req: Request, sb: SupabaseClient): Promise<boolean> {
  const given = req.headers.get("x-cron-secret") ?? "";
  if (!given) return false;
  const { data } = await sb.from("app_secrets").select("value").eq("name", "cron_secret").maybeSingle();
  return !!data && safeEqual(given, data.value);
}

// ---------- AES-GCM voor tokens ----------
const b64 = (u: Uint8Array) => btoa(String.fromCharCode(...u));
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function key(): Promise<CryptoKey> {
  const raw = unb64(env("TOKEN_ENC_KEY"));
  if (raw.length !== 32) throw new Error("TOKEN_ENC_KEY moet 32 bytes (base64) zijn");
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

export async function encrypt(plain: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await key(), new TextEncoder().encode(plain)));
  const out = new Uint8Array(iv.length + ct.length);
  out.set(iv);
  out.set(ct, iv.length);
  return b64(out);
}

export async function decrypt(enc: string): Promise<string> {
  const all = unb64(enc);
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: all.slice(0, 12) }, await key(), all.slice(12));
  return new TextDecoder().decode(pt);
}

// ---------- PKCE ----------
const urlSafe = (s: string) => s.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
export const randomString = (bytes = 48) => urlSafe(b64(crypto.getRandomValues(new Uint8Array(bytes))));
export async function challenge(verifier: string): Promise<string> {
  return urlSafe(b64(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)))));
}

/** Slaat een nieuwe OAuth-poging op en geeft state en code_challenge terug. */
export async function newOAuthState(sb: SupabaseClient, userId: string, provider: Provider) {
  const verifier = randomString(48);
  const state = randomString(24);
  await sb.from("oauth_states").delete().eq("user_id", userId).eq("provider", provider);
  const { error } = await sb.from("oauth_states").insert({ state, user_id: userId, provider, code_verifier: verifier });
  if (error) throw error;
  return { state, codeChallenge: await challenge(verifier) };
}

/** Haalt een OAuth-poging op (eenmalig bruikbaar, maximaal 15 minuten oud). */
export async function takeOAuthState(sb: SupabaseClient, state: string, provider: Provider) {
  const { data } = await sb.from("oauth_states").select("*").eq("state", state).eq("provider", provider).maybeSingle();
  if (!data) return null;
  await sb.from("oauth_states").delete().eq("state", state);
  if (Date.now() - new Date(data.created_at).getTime() > 15 * 60_000) return null;
  return data as { user_id: string; code_verifier: string };
}

// ---------- Tokens ----------
export type TokenResponse = {
  access_token: string;
  refresh_token?: string;
  expires_in: number;
  refresh_token_expires_in?: number;
  scope?: string;
};

export async function tokenRequest(provider: Provider, form: Record<string, string>): Promise<TokenResponse> {
  const cfg = provider === "garmin"
    ? { url: GARMIN.token, id: "GARMIN_CLIENT_ID", secret: "GARMIN_CLIENT_SECRET" }
    : { url: GOOGLE.token, id: "GOOGLE_CLIENT_ID", secret: "GOOGLE_CLIENT_SECRET" };
  const res = await fetch(cfg.url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: env(cfg.id), client_secret: env(cfg.secret), ...form }),
  });
  if (!res.ok) throw new Error(`${provider} token ${res.status}: ${await res.text()}`);
  return await res.json();
}

export async function storeTokens(sb: SupabaseClient, userId: string, provider: Provider, t: TokenResponse) {
  const now = Date.now();
  const row: Record<string, unknown> = {
    user_id: userId,
    provider,
    access_token_enc: await encrypt(t.access_token),
    // 5 minuten marge zodat een token niet halverwege een call verloopt
    access_expires_at: new Date(now + Math.max(t.expires_in - 300, 60) * 1000).toISOString(),
    scope: t.scope ?? null,
    updated_at: new Date(now).toISOString(),
  };
  if (t.refresh_token) {
    row.refresh_token_enc = await encrypt(t.refresh_token);
    row.refresh_expires_at = t.refresh_token_expires_in
      ? new Date(now + t.refresh_token_expires_in * 1000).toISOString()
      : null;
  }
  // Google stuurt bij verversen geen nieuw refresh token: dan het bestaande houden
  const { error } = t.refresh_token
    ? await sb.from("oauth_tokens").upsert(row)
    : await sb.from("oauth_tokens").update(row).eq("user_id", userId).eq("provider", provider);
  if (error) throw error;
}

/** Geldig access token; ververst het zo nodig. Geeft null als er geen (bruikbaar) token is. */
export async function accessTokenFor(sb: SupabaseClient, userId: string, provider: Provider): Promise<string | null> {
  const { data } = await sb.from("oauth_tokens").select("*").eq("user_id", userId).eq("provider", provider).maybeSingle();
  if (!data) return null;
  if (new Date(data.access_expires_at).getTime() > Date.now()) return decrypt(data.access_token_enc);
  if (!data.refresh_token_enc) return null;
  const t = await tokenRequest(provider, { grant_type: "refresh_token", refresh_token: await decrypt(data.refresh_token_enc) });
  await storeTokens(sb, userId, provider, t);
  return t.access_token;
}

/** Trekt de toestemming bij de provider in. Fouten blokkeren niets. */
export async function revokeAtProvider(sb: SupabaseClient, userId: string, provider: Provider): Promise<string> {
  try {
    if (provider === "garmin") {
      const token = await accessTokenFor(sb, userId, "garmin");
      if (!token) return "no_token";
      const r = await fetch(GARMIN.registration, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
      return r.ok || r.status === 404 ? "revoked" : `failed_${r.status}`;
    }
    const { data } = await sb.from("oauth_tokens").select("refresh_token_enc,access_token_enc")
      .eq("user_id", userId).eq("provider", provider).maybeSingle();
    if (!data) return "no_token";
    const token = await decrypt(data.refresh_token_enc ?? data.access_token_enc);
    const r = await fetch(`${GOOGLE.revoke}?token=${encodeURIComponent(token)}`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
    });
    return r.ok ? "revoked" : `failed_${r.status}`;
  } catch (e) {
    console.error("revoke", provider, e);
    return "failed";
  }
}

// ---------- Synchronisatielog ----------
/** Een synchronisatie; alleen bij een fout komt er een regel in sync_runs (geslaagde ronden staan in connections). */
export type Run = { userId: string | null; provider: Provider; trigger: string; started: string };

export function startRun(userId: string | null, provider: Provider, trigger: string): Run {
  return { userId, provider, trigger, started: new Date().toISOString() };
}

export async function finishRun(sb: SupabaseClient, run: Run, rows: number, error?: string) {
  if (!error) return;
  await sb.from("sync_runs").insert({
    user_id: run.userId, provider: run.provider, trigger: run.trigger, started_at: run.started,
    finished_at: new Date().toISOString(), status: "error", rows_written: rows, error: error.slice(0, 500),
  });
}

// Catalogus van metrics met grenzen: verandert zelden, dus per warme functie-instantie 10 minuten bewaard
let catalog: { at: number; lim: Map<string, number[]> } | null = null;
async function limits(sb: SupabaseClient) {
  if (catalog && Date.now() - catalog.at < 600_000) return catalog.lim;
  const { data } = await sb.from("metrics").select("id,min_value,max_value");
  catalog = { at: Date.now(), lim: new Map((data ?? []).map((m) => [m.id, [Number(m.min_value), Number(m.max_value)]])) };
  return catalog.lim;
}

/** Valideert dagwaarden tegen de catalogus en schrijft ze weg. */
export async function writeDaily(
  sb: SupabaseClient,
  rows: { user_id: string; day: string; metric: string; value: number }[],
  provider: Provider,
): Promise<number> {
  if (!rows.length) return 0;
  const lim = await limits(sb);
  const now = new Date().toISOString();
  const dedup = new Map<string, Record<string, unknown>>();
  for (const r of rows) {
    const l = lim.get(r.metric);
    if (!l || !isFinite(r.value) || r.value < l[0] || r.value > l[1] || !/^\d{4}-\d{2}-\d{2}$/.test(r.day)) continue;
    dedup.set(`${r.user_id}|${r.day}|${r.metric}`, {
      user_id: r.user_id, day: r.day, metric: r.metric, source: provider, provider,
      value: Math.round(r.value * 100) / 100, updated_at: now,
    });
  }
  if (!dedup.size) return 0;
  const { error } = await sb.from("daily_metrics").upsert([...dedup.values()], { onConflict: "user_id,day,metric,source" });
  if (error) throw error;
  return dedup.size;
}
