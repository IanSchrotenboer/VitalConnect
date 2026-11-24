// Garmin Connect (Health API): koppelen met OAuth 2.0 + PKCE; Garmin stuurt nieuwe data zelf (push, garmin-webhook).
// Toegang via het Garmin Connect Developer Program (alleen voor bedrijven, na goedkeuring).
// Veldnamen volgen de Garmin Health API-specificatie; controleer ze na goedkeuring tegen de actuele spec.
import type { CloudProvider } from "./types.ts";

const API = "https://apis.garmin.com/wellness-api/rest";

export const garmin: CloudProvider = {
  id: "garmin",
  slug: "garmin",
  name: "Garmin Connect",
  sync: "push",
  secrets: ["GARMIN_CLIENT_ID", "GARMIN_CLIENT_SECRET", "GARMIN_WEBHOOK_KEY"],
  clientIdEnv: "GARMIN_CLIENT_ID",
  clientSecretEnv: "GARMIN_CLIENT_SECRET",
  authorizeUrl: "https://connect.garmin.com/oauth2Confirm",
  tokenUrl: "https://connectapi.garmin.com/di-oauth2-service/oauth/token",
  pkce: true,
  async identify(token) {
    const r = await fetch(`${API}/user/id`, { headers: { Authorization: `Bearer ${token}` } });
    if (!r.ok) throw new Error(`Garmin user id ${r.status}`);
    const { userId } = await r.json();
    return userId ? String(userId) : null;
  },
  async revoke({ access }) {
    if (!access) return "no_token";
    const r = await fetch(`${API}/user/registration`, { method: "DELETE", headers: { Authorization: `Bearer ${access}` } });
    return r.ok || r.status === 404 ? "revoked" : `failed_${r.status}`;
  },
};

// ---------- Push: Garmin-samenvattingen → dagwaarden ----------
export type Item = Record<string, any>;

const avg = (m?: Record<string, number>) => {
  const v = Object.values(m ?? {}).filter((x) => typeof x === "number" && x > 0);
  return v.length ? v.reduce((a, b) => a + b, 0) / v.length : undefined;
};
const lastOf = (m?: Record<string, number>) => {
  const k = Object.keys(m ?? {}).map(Number).sort((a, b) => a - b).pop();
  return k === undefined ? undefined : m![String(k)];
};
const dayFromEpoch = (sec?: number, offset = 0) =>
  sec === undefined ? undefined : new Date((sec + offset) * 1000).toISOString().slice(0, 10);

// Per summary-type: welke dag en welke metrics
export const GARMIN_SUMMARIES: Record<string, (i: Item) => [string | undefined, Record<string, number | undefined>]> = {
  dailies: (i) => [i.calendarDate, {
    steps: i.steps,
    dist: i.distanceInMeters !== undefined ? i.distanceInMeters / 1000 : undefined,
    kcal: i.activeKilocalories,
    floors: i.floorsClimbed,
    rhr: i.restingHeartRateInBeatsPerMinute,
    stress: i.averageStressLevel >= 0 ? i.averageStressLevel : undefined,
    active: i.moderateIntensityDurationInSeconds !== undefined || i.vigorousIntensityDurationInSeconds !== undefined
      ? ((i.moderateIntensityDurationInSeconds ?? 0) + (i.vigorousIntensityDurationInSeconds ?? 0)) / 60
      : undefined,
  }],
  sleeps: (i) => [i.calendarDate, {
    sleep: i.durationInSeconds !== undefined ? i.durationInSeconds / 3600 : undefined,
    sscore: i.overallSleepScore?.value,
  }],
  hrv: (i) => [i.calendarDate, { hrv: i.lastNightAvg }],
  bodyComps: (i) => [dayFromEpoch(i.measurementTimeInSeconds, i.measurementTimeOffsetInSeconds ?? 0), {
    weight: i.weightInGrams !== undefined ? i.weightInGrams / 1000 : undefined,
  }],
  pulseox: (i) => [i.calendarDate, { spo2: avg(i.timeOffsetSpo2Values) }],
  respiration: (i) => [dayFromEpoch(i.startTimeInSeconds, i.startTimeOffsetInSeconds ?? 0), {
    resp: avg(i.timeOffsetEpochToBreaths),
  }],
  userMetrics: (i) => [i.calendarDate, { vo2: i.vo2Max }],
  stressDetails: (i) => [i.calendarDate, { energy: lastOf(i.bodyBatteryValuesMap) }],
};
