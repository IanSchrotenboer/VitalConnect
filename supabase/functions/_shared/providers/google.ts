// Google Health (Fitbit, Pixel Watch, Google Health-app): koppelen met OAuth + PKCE, ophalen (pull).
// Veldnamen volgen de Google Health API v4-referentie (dailyRollUp en list).
import { ConnectError, type CloudProvider, type Row, type WorkoutRow } from "./types.ts";

const API = "https://health.googleapis.com/v4";
type GDate = { year: number; month: number; day: number };

const num = (v: unknown): number | undefined => {
  const n = typeof v === "string" ? Number(v) : typeof v === "number" ? v : NaN;
  return isFinite(n) ? n : undefined;
};
const iso = (d?: GDate) =>
  d?.year ? `${d.year}-${String(d.month).padStart(2, "0")}-${String(d.day).padStart(2, "0")}` : undefined;
const gdate = (d: Date): GDate => ({ year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() });
const daysAgo = (n: number) => { const d = new Date(); d.setUTCHours(0, 0, 0, 0); d.setUTCDate(d.getUTCDate() - n); return d; };

/** Eerste getal in een object waarvan de sleutel op het patroon past (voor velden die per versie kunnen verschillen). */
function firstNum(o: Record<string, unknown> | undefined, re: RegExp): number | undefined {
  for (const [k, v] of Object.entries(o ?? {})) if (re.test(k)) { const n = num(v); if (n !== undefined) return n; }
  return undefined;
}

// Aggregaten per dag via dailyRollUp
const ROLLUPS: { type: string; field: string; metric: string; maxDays?: number; get: (v: any) => number | undefined }[] = [
  { type: "steps", field: "steps", metric: "steps", get: (v) => num(v?.countSum) },
  { type: "distance", field: "distance", metric: "dist", get: (v) => { const mm = num(v?.millimetersSum); return mm === undefined ? undefined : mm / 1e6; } },
  { type: "active-energy-burned", field: "activeEnergyBurned", metric: "kcal", get: (v) => num(v?.kcalSum) },
  { type: "floors", field: "floors", metric: "floors", get: (v) => num(v?.countSum) },
  {
    type: "active-minutes", field: "activeMinutes", metric: "active", maxDays: 14,
    get: (v) => {
      const levels = v?.activeMinutesRollupByActivityLevel as { activeMinutesSum?: string }[] | undefined;
      return levels?.length ? levels.reduce((s, l) => s + (num(l.activeMinutesSum) ?? 0), 0) : undefined;
    },
  },
  { type: "weight", field: "weight", metric: "weight", get: (v) => { const g = firstNum(v, /^weightGrams/); return g === undefined ? undefined : g / 1000; } },
];

// Dagelijkse samenvattingen via list
const LISTS: { type: string; field: string; metric: string; filter: (from: string) => string; day: (p: any) => string | undefined; get: (p: any) => number | undefined; sum?: boolean }[] = [
  { type: "daily-resting-heart-rate", field: "dailyRestingHeartRate", metric: "rhr",
    filter: (f) => `daily_resting_heart_rate.date >= "${f}"`, day: (p) => iso(p.date), get: (p) => num(p.beatsPerMinute) },
  { type: "daily-heart-rate-variability", field: "dailyHeartRateVariability", metric: "hrv",
    filter: (f) => `daily_heart_rate_variability.date >= "${f}"`, day: (p) => iso(p.date), get: (p) => num(p.averageHeartRateVariabilityMilliseconds) },
  { type: "daily-oxygen-saturation", field: "dailyOxygenSaturation", metric: "spo2",
    filter: (f) => `daily_oxygen_saturation.date >= "${f}"`, day: (p) => iso(p.date), get: (p) => num(p.averagePercentage) },
  { type: "daily-vo2-max", field: "dailyVo2Max", metric: "vo2",
    filter: (f) => `daily_vo2_max.date >= "${f}"`, day: (p) => iso(p.date), get: (p) => num(p.vo2Max) },
  { type: "daily-respiratory-rate", field: "dailyRespiratoryRate", metric: "resp",
    filter: (f) => `daily_respiratory_rate.date >= "${f}"`, day: (p) => iso(p.date), get: (p) => firstNum(p, /breath/i) },
  { type: "sleep", field: "sleep", metric: "sleep", sum: true,
    filter: (f) => `sleep.interval.civil_end_time >= "${f}T00:00:00"`,
    day: (p) => iso(p.interval?.civilEndTime?.date),
    // Dutjes tellen niet mee; minuten slaap → uren
    get: (p) => p.metadata?.nap ? undefined : (() => { const m = num(p.summary?.minutesAsleep); return m === undefined ? undefined : m / 60; })() },
];

class ApiError extends Error {
  constructor(public status: number, body: string) { super(`Google Health ${status}: ${body.slice(0, 300)}`); }
}

async function call(token: string, path: string, init: RequestInit = {}) {
  // Google Health geeft soms even 503 (tijdelijk niet beschikbaar) of 429 (te veel verzoeken): één keer opnieuw proberen
  for (let poging = 1; ; poging++) {
    const r = await fetch(`${API}${path}`, {
      ...init,
      headers: { Authorization: `Bearer ${token}`, Accept: "application/json", "Content-Type": "application/json", ...(init.headers ?? {}) },
    });
    if (r.ok) return await r.json();
    const tijdelijk = r.status === 429 || r.status === 502 || r.status === 503 || r.status === 504;
    if (!tijdelijk || poging >= 2) throw new ApiError(r.status, await r.text());
    await r.body?.cancel();
    const wacht = Math.min(3000, Math.max(800, Number(r.headers.get("retry-after") ?? 0) * 1000));
    await new Promise((ok) => setTimeout(ok, wacht));
  }
}

async function rollups(token: string, uid: string, days: number): Promise<Row[]> {
  const out: Row[] = [];
  const end = gdate(daysAgo(-1)); // morgen 00:00, exclusief
  // Alle datatypes tegelijk: een sync duurt zo één rondje naar Google in plaats van zes
  await Promise.all(ROLLUPS.map(async (r) => {
    const span = Math.min(days, r.maxDays ?? 90);
    try {
      let pageToken: string | undefined;
      do {
        const res = await call(token, `/users/me/dataTypes/${r.type}/dataPoints:dailyRollUp`, {
          method: "POST",
          body: JSON.stringify({
            range: { start: { date: gdate(daysAgo(span - 1)) }, end: { date: end } },
            windowSizeDays: 1,
            pageSize: Math.min(span, r.maxDays ?? 90),
            ...(pageToken ? { pageToken } : {}),
          }),
        });
        for (const p of res.rollupDataPoints ?? []) {
          const day = iso(p.civilStartTime?.date);
          const v = r.get(p[r.field]);
          if (day && v !== undefined) out.push({ user_id: uid, day, metric: r.metric, value: v });
        }
        pageToken = res.nextPageToken;
      } while (pageToken);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) throw e;
      // 403 = scope niet gegeven, 404/400 = type niet beschikbaar: de rest gaat gewoon door
      console.warn(`rollup ${r.type}`, String(e));
    }
  }));
  return out;
}

async function lists(token: string, uid: string, days: number): Promise<Row[]> {
  const out: Row[] = [];
  const from = iso(gdate(daysAgo(days - 1)))!;
  await Promise.all(LISTS.map(async (l) => {
    const acc = new Map<string, number>();
    try {
      let pageToken: string | undefined;
      let useFilter = true;
      for (let page = 0; page < 5; page++) {
        const q = new URLSearchParams({ pageSize: "100" });
        if (useFilter) q.set("filter", l.filter(from));
        if (pageToken) q.set("pageToken", pageToken);
        let res;
        try {
          res = await call(token, `/users/me/dataTypes/${l.type}/dataPoints?${q}`);
        } catch (e) {
          // Onbekende filtersyntax: één keer zonder filter, en zelf op datum filteren
          if (e instanceof ApiError && e.status === 400 && useFilter) { useFilter = false; page--; continue; }
          throw e;
        }
        for (const dp of res.dataPoints ?? []) {
          const p = dp[l.field];
          const day = p && l.day(p);
          const v = p && l.get(p);
          if (!day || v === undefined || day < from) continue;
          acc.set(day, l.sum ? (acc.get(day) ?? 0) + v : v);
        }
        pageToken = res.nextPageToken;
        if (!pageToken) break;
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) throw e;
      console.warn(`list ${l.type}`, String(e));
    }
    for (const [day, value] of acc) out.push({ user_id: uid, day, metric: l.metric, value });
  }));
  return out;
}

// Trainingen: datatype exercise (exerciseType, interval, activeDuration, metricsSummary)
const SPORT_OF: [RegExp, string][] = [
  [/RUN/, "running"], [/HIK/, "hiking"], [/WALK/, "walking"], [/BIK|CYCL|SPINNING/, "cycling"], [/SWIM/, "swimming"],
  [/STRENGTH|WEIGHT|CALISTHENIC|CROSSFIT|CIRCUIT/, "strength"], [/INTERVAL|HIIT|BOOT_CAMP/, "hiit"],
  [/YOGA|PILATES|STRETCH/, "yoga"], [/ROWING/, "rowing"], [/ELLIPTICAL|STAIR/, "elliptical"],
  [/SOCCER|FOOTBALL|BASKETBALL|VOLLEYBALL|HOCKEY|HANDBALL|RUGBY|CRICKET|BASEBALL|SOFTBALL/, "team"],
  [/TENNIS|BADMINTON|SQUASH|PADEL|PICKLEBALL|RACQUET/, "racket"], [/DANC|ZUMBA|BARRE/, "dance"],
  [/BOX|KARATE|JIU|JUDO|MARTIAL|TAEKWONDO|KICKBOX/, "martial"], [/SKI|SNOWBOARD|SKAT/, "winter"],
];
/** Hoe Google Health de training vastlegde: zelf gestart, automatisch herkend, ingevoerd of afgeleid */
const RECORDING: Record<string, WorkoutRow["recording"]> = {
  ACTIVELY_MEASURED: "active", PASSIVELY_MEASURED: "passive", MANUAL: "manual", MANUALLY_ENTERED: "manual",
  DERIVED: "derived", UNKNOWN: "unknown",
};
const recordingOf = (dp: { dataSource?: { recordingMethod?: string }; exercise?: { exerciseEvents?: { exerciseEventType?: string }[] } }) =>
  RECORDING[dp.dataSource?.recordingMethod ?? ""] ??
  // Zonder vastlegmethode: een START-gebeurtenis betekent dat iemand de training zelf startte
  (dp.exercise?.exerciseEvents?.some((e) => e.exerciseEventType === "START") ? "active" : null);
const sportOf = (t?: string) => SPORT_OF.find(([re]) => re.test(t ?? ""))?.[1] ?? "other";
/** "1800s" of "1800.5s" → seconden */
const secs = (d?: string) => { const n = num(d?.replace(/s$/, "")); return n; };

async function exercises(token: string, uid: string, days: number): Promise<WorkoutRow[]> {
  const out: WorkoutRow[] = [];
  const from = daysAgo(days - 1).toISOString();
  let pageToken: string | undefined;
  let useFilter = true;
  for (let page = 0; page < 10; page++) {
    const q = new URLSearchParams({ pageSize: "100" });
    if (useFilter) q.set("filter", `exercise.interval.start_time >= "${from}"`);
    if (pageToken) q.set("pageToken", pageToken);
    let res;
    try {
      res = await call(token, `/users/me/dataTypes/exercise/dataPoints?${q}`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 400 && useFilter) { useFilter = false; page--; continue; }
      if (e instanceof ApiError && e.status === 401) throw e;
      console.warn("exercise", String(e));
      break;
    }
    for (const dp of res.dataPoints ?? []) {
      const ex = dp.exercise;
      const start = ex?.interval?.startTime as string | undefined;
      if (!start || start < from) continue;
      const end = ex.interval.endTime as string | undefined;
      const dur = secs(ex.activeDuration) ?? (end ? (Date.parse(end) - Date.parse(start)) / 1000 : undefined);
      if (!dur) continue;
      // Lokale dag van de start: tijd + UTC-verschuiving
      const off = (secs(ex.interval.startUtcOffset) ?? 0) * 1000;
      const day = new Date(Date.parse(start) + off).toISOString().slice(0, 10);
      const m = ex.metricsSummary ?? {};
      const mm = (v: unknown) => { const n = num(v); return n === undefined ? null : n / 1000; };
      out.push({
        user_id: uid, external_id: String(dp.name ?? "").split("/").pop() || `${start}`, sport: sportOf(ex.exerciseType),
        title: ex.displayName ?? null, day, started_at: start, duration_s: dur,
        distance_m: mm(m.distanceMillimeters), kcal: num(m.caloriesKcal) ?? null, avg_hr: num(m.averageHeartRateBeatsPerMinute) ?? null,
        ascent_m: mm(m.elevationGainMillimeters), origin: dp.dataSource?.platform ?? null, recording: recordingOf(dp),
      });
    }
    pageToken = res.nextPageToken;
    if (!pageToken) break;
  }
  return out;
}

export const google: CloudProvider = {
  id: "google_health",
  slug: "google",
  name: "Google Health",
  sync: "pull",
  secrets: ["GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET"],
  clientIdEnv: "GOOGLE_CLIENT_ID",
  clientSecretEnv: "GOOGLE_CLIENT_SECRET",
  authorizeUrl: "https://accounts.google.com/o/oauth2/v2/auth",
  tokenUrl: "https://oauth2.googleapis.com/token",
  pkce: true,
  authorizeParams: (user) => ({
    scope: [
      "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
      "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
      "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
    ].join(" "),
    access_type: "offline", // refresh token voor ophalen op de achtergrond
    prompt: "consent",
    include_granted_scopes: "true",
    ...(user.email ? { login_hint: user.email } : {}),
  }),
  async identify(token) {
    // Het tokenantwoord bevat geen gebruikers-ID: de identiteit direct na het inwisselen ophalen
    const r = await fetch(`${API}/users/me/identity`, { headers: { Authorization: `Bearer ${token}`, Accept: "application/json" } });
    if (!r.ok) {
      const body = await r.text();
      console.error("identity", r.status, body);
      throw new ConnectError(body.includes("ACCOUNT_NOT_LINKED") ? "not_linked" : "error");
    }
    const id = await r.json();
    return String(id.healthUserId ?? id.legacyUserId ?? "") || null;
  },
  async fetchDays(token, uid, days) {
    const [a, b] = await Promise.all([rollups(token, uid, days), lists(token, uid, days)]);
    return [...a, ...b];
  },
  fetchWorkouts: exercises,
  async revoke({ access, refresh }) {
    const token = refresh ?? access;
    if (!token) return "no_token";
    const r = await fetch(`https://oauth2.googleapis.com/revoke?token=${encodeURIComponent(token)}`, {
      method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    });
    return r.ok ? "revoked" : `failed_${r.status}`;
  },
};
