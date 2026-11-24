// Google Health API → dagwaarden in daily_metrics.
// Pull-based: pg_cron roept google-health-sync elk uur aan; de app kan ook "nu ophalen".
// Veldnamen volgen de Google Health API v4-referentie (dailyRollUp en list).
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { accessTokenFor, finishRun, GOOGLE, startRun, writeDaily } from "./shared.ts";

type Row = { user_id: string; day: string; metric: string; value: number };
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
  const r = await fetch(`${GOOGLE.api}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json", "Content-Type": "application/json", ...(init.headers ?? {}) },
  });
  if (!r.ok) throw new ApiError(r.status, await r.text());
  return await r.json();
}

async function rollups(token: string, uid: string, days: number): Promise<Row[]> {
  const out: Row[] = [];
  const end = gdate(daysAgo(-1)); // morgen 00:00, exclusief
  for (const r of ROLLUPS) {
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
  }
  return out;
}

async function lists(token: string, uid: string, days: number): Promise<Row[]> {
  const out: Row[] = [];
  const from = iso(gdate(daysAgo(days - 1)))!;
  for (const l of LISTS) {
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
  }
  return out;
}

/** Synchroniseert één gebruiker. Eerste keer 30 dagen, daarna de laatste 7 (Google werkt data soms later bij). */
export async function syncUser(sb: SupabaseClient, uid: string, trigger: "cron" | "user"): Promise<{ rows: number; error?: string }> {
  const run = await startRun(sb, uid, "google_health", trigger);
  const { data: conn } = await sb.from("connections").select("last_data_at")
    .eq("user_id", uid).eq("provider", "google_health").maybeSingle();
  const days = conn?.last_data_at ? 7 : 30;
  try {
    const token = await accessTokenFor(sb, uid, "google_health");
    if (!token) throw new Error("Geen geldig token; koppel Google Health opnieuw");
    const rows = [...(await rollups(token, uid, days)), ...(await lists(token, uid, days))];
    const n = await writeDaily(sb, rows, "google_health");
    const now = new Date().toISOString();
    await sb.from("connections").update({
      last_sync_at: now, ...(n ? { last_data_at: now } : {}), status: "active", last_error: null,
    }).eq("user_id", uid).eq("provider", "google_health");
    await finishRun(sb, run, n);
    return { rows: n };
  } catch (e) {
    const msg = String(e instanceof Error ? e.message : e);
    const revoked = msg.includes(" 401") || msg.includes("invalid_grant") || msg.includes("Geen geldig token");
    await sb.from("connections").update({ last_error: msg.slice(0, 500), ...(revoked ? { status: "error" } : {}) })
      .eq("user_id", uid).eq("provider", "google_health");
    await finishRun(sb, run, 0, msg.slice(0, 500));
    return { rows: 0, error: msg };
  }
}
