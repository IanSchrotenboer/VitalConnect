// Basis voor alle edge functions: omgeving, antwoorden, inloggen, versleuteling en wegschrijven van dagwaarden.
// Bron-specifieke code staat in providers/, het koppelen (OAuth) in oauth.ts.
import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

/** Bronnen die via de cloud koppelen. Health Connect staat op de telefoon en loopt via de RPC ingest_health_connect. */
export type ProviderId = "garmin" | "google_health" | "suunto";

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

/** Terug naar de app via deep link, bijvoorbeeld vitaal://google?status=ok (host = slug van de bron) */
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

// ---------- Synchronisatielog ----------
/** Een synchronisatie; alleen bij een fout komt er een regel in sync_runs (geslaagde ronden staan in connections). */
export type Run = { userId: string | null; provider: ProviderId; trigger: string; started: string };

export function startRun(userId: string | null, provider: ProviderId, trigger: string): Run {
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
  provider: ProviderId,
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
