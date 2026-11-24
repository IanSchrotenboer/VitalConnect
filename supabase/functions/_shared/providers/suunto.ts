// Suunto (Suunto-app, Suunto-horloges): koppelen met OAuth 2.0 (zonder PKCE, client via Basic), ophalen (pull).
// Toegang via het Suunto partnerprogramma en de Suunto API Zone (Developer API + 24/7-producten).
//
// TE CONTROLEREN zodra er toegang is (de openbare documentatie zegt hier niets over):
//   - eenheid van Duration bij slaap (seconden of milliseconden: beide worden herkend);
//   - of `since` bij workouts milliseconden of seconden zijn (hier: milliseconden, zoals in /v2);
//   - energyconsumption per dag: totaal of actief, kcal of joule. Daarom nog niet als "actieve calorieën" opgeslagen.
//   - webhooks (WORKOUT_CREATED): formaat en handtekening; tot die tijd haalt de server op (pull) zoals bij Google.
import { ConnectError, type CloudProvider, type Row } from "./types.ts";

const API = "https://cloudapi.suunto.com";
const DAY = 86_400_000;

const num = (v: unknown): number | undefined => {
  const n = typeof v === "string" ? Number(v) : typeof v === "number" ? v : NaN;
  return isFinite(n) ? n : undefined;
};
const ymd = (ms: number) => new Date(ms).toISOString().slice(0, 10);
/** Dag van een tijdstip: ISO-tekst (de lokale datum staat vooraan) of epoch in ms. */
const dayOf = (v: unknown): string | undefined => {
  if (typeof v === "string" && /^\d{4}-\d{2}-\d{2}/.test(v)) return v.slice(0, 10);
  const n = num(v);
  return n === undefined ? undefined : ymd(n > 1e11 ? n : n * 1000);
};
const list = (j: any): any[] => Array.isArray(j) ? j : Array.isArray(j?.payload) ? j.payload : [];

class ApiError extends Error {
  constructor(public status: number, body: string) { super(`Suunto ${status}: ${body.slice(0, 300)}`); }
}

async function call(token: string, path: string) {
  const r = await fetch(`${API}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      "Ocp-Apim-Subscription-Key": Deno.env.get("SUUNTO_SUBSCRIPTION_KEY") ?? "",
      Accept: "application/json",
    },
  });
  if (!r.ok) throw new ApiError(r.status, await r.text());
  const j = await r.json();
  // Fout soms als 200 met een code in de body
  if (j && typeof j === "object" && !Array.isArray(j) && j.error) throw new ApiError(Number(j.code) || 500, String(j.error));
  return j;
}

/** Eén onderdeel ophalen; een 401 stopt alles (koppeling verlopen), andere fouten slaan alleen dit onderdeel over. */
async function part(name: string, fn: () => Promise<Row[]>): Promise<Row[]> {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) throw e;
    console.warn(`suunto ${name}`, String(e));
    return [];
  }
}

/** Stappen per dag (/247/daily-activity-statistics, vensters van 14 dagen). Bij meer bronnen telt de hoogste. */
async function steps(token: string, uid: string, from: number, to: number): Promise<Row[]> {
  const best = new Map<string, number>();
  for (let s = from; s <= to; s += 14 * DAY) {
    const e = Math.min(s + 13 * DAY, to);
    const q = `startdate=${ymd(s)}T00:00:00&enddate=${ymd(e)}T23:59:59`;
    for (const stat of list(await call(token, `/247/daily-activity-statistics?${q}`))) {
      if (String(stat?.Name).toLowerCase() !== "stepcount") continue;
      for (const src of stat.Sources ?? []) {
        for (const smp of src.Samples ?? []) {
          const day = dayOf(smp.TimeISO8601), v = num(smp.Value);
          if (day && v !== undefined) best.set(day, Math.max(best.get(day) ?? 0, v));
        }
      }
    }
  }
  return [...best].map(([day, value]) => ({ user_id: uid, day, metric: "steps", value }));
}

/** Slaap per nacht (/247samples/sleep, vensters van 20 dagen): duur, slaapscore, laagste hartslag, HRV. Dutjes tellen niet mee. */
async function sleep(token: string, uid: string, from: number, to: number): Promise<Row[]> {
  const nights = new Map<string, { h: number; score?: number; hr?: number; hrv?: number }>();
  for (let s = from; s <= to; s += 20 * DAY) {
    const e = Math.min(s + 20 * DAY, to);
    for (const it of list(await call(token, `/247samples/sleep?from=${s}&to=${e}`))) {
      const d = it?.entryData ?? {};
      if (d.IsNap === true) continue;
      const day = dayOf(d.BedtimeEnd ?? it.timestamp);
      const dur = num(d.Duration);
      if (!day || dur === undefined) continue;
      const n = nights.get(day) ?? { h: 0 };
      n.h += dur > 100_000 ? dur / 3_600_000 : dur / 3600;   // ms of seconden
      n.score = num(d.SleepQualityScore) ?? n.score;
      n.hr = num(d.HRMin) ?? n.hr;
      n.hrv = num(d.AvgHRV) ?? n.hrv;
      nights.set(day, n);
    }
  }
  const out: Row[] = [];
  for (const [day, n] of nights) {
    out.push({ user_id: uid, day, metric: "sleep", value: n.h });
    if (n.score !== undefined) out.push({ user_id: uid, day, metric: "sscore", value: n.score });
    if (n.hr !== undefined) out.push({ user_id: uid, day, metric: "rhr", value: n.hr });
    if (n.hrv !== undefined) out.push({ user_id: uid, day, metric: "hrv", value: n.hrv });
  }
  return out;
}

/** Actieve minuten per dag: de duur van de workouts (/v2/workouts), op de lokale startdag. */
async function workouts(token: string, uid: string, from: number): Promise<Row[]> {
  const mins = new Map<string, number>();
  const j = await call(token, `/v2/workouts?since=${from}&limit=100`);
  for (const w of list(j)) {
    const start = num(w.startTime), secs = num(w.totalTime);
    if (start === undefined || secs === undefined) continue;
    const day = ymd(start + (num(w.timeOffsetInMinutes) ?? 0) * 60_000);
    mins.set(day, (mins.get(day) ?? 0) + secs / 60);
  }
  return [...mins].map(([day, value]) => ({ user_id: uid, day, metric: "active", value }));
}

export const suunto: CloudProvider = {
  id: "suunto",
  slug: "suunto",
  name: "Suunto",
  sync: "pull",
  secrets: ["SUUNTO_CLIENT_ID", "SUUNTO_CLIENT_SECRET", "SUUNTO_SUBSCRIPTION_KEY"],
  clientIdEnv: "SUUNTO_CLIENT_ID",
  clientSecretEnv: "SUUNTO_CLIENT_SECRET",
  authorizeUrl: "https://cloudapi-oauth.suunto.com/oauth/authorize",
  tokenUrl: "https://cloudapi-oauth.suunto.com/oauth/token",
  pkce: false,
  basicAuth: true,
  identify(token) {
    // Het access token is een JWT met de Suunto-gebruiker (sub of user)
    try {
      const b64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
      const claims = JSON.parse(atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4)));
      return Promise.resolve(String(claims.sub ?? claims.user ?? "") || null);
    } catch {
      return Promise.reject(new ConnectError("error", "Suunto-token zonder gebruiker"));
    }
  },
  async fetchDays(token, uid, days) {
    const to = Date.now();
    const from = to - (days - 1) * DAY;
    const parts = await Promise.all([
      part("stappen", () => steps(token, uid, from, to)),
      part("slaap", () => sleep(token, uid, from, to)),
      part("workouts", () => workouts(token, uid, from)),
    ]);
    return parts.flat();
  },
  // Suunto heeft geen gedocumenteerde manier om toestemming in te trekken: de tokens worden bij ons gewist.
  revoke: () => Promise.resolve("local_only"),
};
