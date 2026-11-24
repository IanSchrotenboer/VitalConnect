// Koppelen met OAuth 2.0, voor elke cloudbron gelijk: toestemming vragen, code inwisselen, tokens versleuteld
// bewaren en verversen, toestemming intrekken.
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import type { CloudProvider } from "./providers/index.ts";
import { challenge, decrypt, encrypt, env, randomString } from "./shared.ts";

/**
 * Terugkeeradres na de toestemming: een pagina op het eigen domein die doorstuurt naar connect-callback
 * (site/koppelen/<slug>/). Precies dit adres staat bij de bron geregistreerd.
 */
export const redirectUri = (p: CloudProvider) =>
  `${Deno.env.get("REDIRECT_BASE") ?? "https://app.vital-connect.nl/koppelen/"}${p.slug}/`;

/** Adres van het toestemmingsscherm, met een eenmalige state (en PKCE als de bron dat ondersteunt). */
export async function authorizeUrl(sb: SupabaseClient, p: CloudProvider, user: { id: string; email?: string }) {
  const verifier = randomString(48);
  const state = randomString(24);
  await sb.from("oauth_states").delete().eq("user_id", user.id).eq("provider", p.id);
  const { error } = await sb.from("oauth_states").insert({ state, user_id: user.id, provider: p.id, code_verifier: verifier });
  if (error) throw error;
  const url = new URL(p.authorizeUrl);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", env(p.clientIdEnv));
  url.searchParams.set("redirect_uri", redirectUri(p));
  url.searchParams.set("state", state);
  if (p.pkce) {
    url.searchParams.set("code_challenge", await challenge(verifier));
    url.searchParams.set("code_challenge_method", "S256");
  }
  for (const [k, v] of Object.entries(p.authorizeParams?.(user) ?? {})) url.searchParams.set(k, v);
  return url.toString();
}

/** Haalt een OAuth-poging op: eenmalig bruikbaar en maximaal 15 minuten oud. */
export async function takeState(sb: SupabaseClient, state: string, p: CloudProvider) {
  const { data } = await sb.from("oauth_states").select("*").eq("state", state).eq("provider", p.id).maybeSingle();
  if (!data) return null;
  await sb.from("oauth_states").delete().eq("state", state);
  if (Date.now() - new Date(data.created_at).getTime() > 15 * 60_000) return null;
  return data as { user_id: string; code_verifier: string };
}

export type TokenResponse = {
  access_token: string;
  refresh_token?: string;
  expires_in: number;
  refresh_token_expires_in?: number;
  scope?: string;
};

export async function tokenRequest(p: CloudProvider, form: Record<string, string>): Promise<TokenResponse> {
  const id = env(p.clientIdEnv), secret = env(p.clientSecretEnv);
  const headers: Record<string, string> = { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" };
  const body = new URLSearchParams(form);
  if (p.basicAuth) headers.Authorization = `Basic ${btoa(`${id}:${secret}`)}`;
  else { body.set("client_id", id); body.set("client_secret", secret); }
  const res = await fetch(p.tokenUrl, { method: "POST", headers, body });
  if (!res.ok) throw new Error(`${p.id} token ${res.status}: ${await res.text()}`);
  return await res.json();
}

/** Code van de terugkeer inwisselen voor tokens. */
export const exchangeCode = (p: CloudProvider, code: string, verifier: string) =>
  tokenRequest(p, {
    grant_type: "authorization_code", code, redirect_uri: redirectUri(p),
    ...(p.pkce ? { code_verifier: verifier } : {}),
  });

export async function storeTokens(sb: SupabaseClient, userId: string, p: CloudProvider, t: TokenResponse) {
  const now = Date.now();
  const row: Record<string, unknown> = {
    user_id: userId,
    provider: p.id,
    access_token_enc: await encrypt(t.access_token),
    // 5 minuten marge zodat een token niet halverwege een aanroep verloopt
    access_expires_at: new Date(now + Math.max((t.expires_in ?? 3600) - 300, 60) * 1000).toISOString(),
    scope: t.scope ?? null,
    updated_at: new Date(now).toISOString(),
  };
  if (t.refresh_token) {
    row.refresh_token_enc = await encrypt(t.refresh_token);
    row.refresh_expires_at = t.refresh_token_expires_in ? new Date(now + t.refresh_token_expires_in * 1000).toISOString() : null;
  }
  // Google stuurt bij verversen geen nieuw refresh token: dan het bestaande houden
  const { error } = t.refresh_token
    ? await sb.from("oauth_tokens").upsert(row)
    : await sb.from("oauth_tokens").update(row).eq("user_id", userId).eq("provider", p.id);
  if (error) throw error;
}

/** Geldig access token; ververst het zo nodig. Null als er geen (bruikbaar) token is. */
export async function accessTokenFor(sb: SupabaseClient, userId: string, p: CloudProvider): Promise<string | null> {
  const { data } = await sb.from("oauth_tokens").select("*").eq("user_id", userId).eq("provider", p.id).maybeSingle();
  if (!data) return null;
  if (new Date(data.access_expires_at).getTime() > Date.now()) return decrypt(data.access_token_enc);
  if (!data.refresh_token_enc) return null;
  const t = await tokenRequest(p, { grant_type: "refresh_token", refresh_token: await decrypt(data.refresh_token_enc) });
  await storeTokens(sb, userId, p, t);
  return t.access_token;
}

/** Trekt de toestemming bij de bron in. Fouten blokkeren niets: ontkoppelen en account verwijderen gaan altijd door. */
export async function revokeAtProvider(sb: SupabaseClient, userId: string, p: CloudProvider): Promise<string> {
  try {
    const { data } = await sb.from("oauth_tokens").select("refresh_token_enc").eq("user_id", userId).eq("provider", p.id).maybeSingle();
    if (!data) return "no_token";
    const access = await accessTokenFor(sb, userId, p).catch(() => null);
    const refresh = data.refresh_token_enc ? await decrypt(data.refresh_token_enc) : null;
    return await p.revoke({ access, refresh });
  } catch (e) {
    console.error("revoke", p.id, e);
    return "failed";
  }
}
