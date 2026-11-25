// Ophalen bij een pull-bron (Google Health, Suunto) voor één gebruiker. Gebruikt door connect-callback (eerste keer)
// en sync (app en servertaak).
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { accessTokenFor } from "./oauth.ts";
import type { CloudProvider } from "./providers/index.ts";
import { finishRun, startRun, writeDaily, writeWorkouts } from "./shared.ts";

export type SyncState = { last_data_at: string | null; last_sync_at: string | null } | null;

/** Eerste keer 30 dagen; daarna 3 dagen als de vorige ronde recent was (late metingen), anders 7. */
export async function syncUser(
  sb: SupabaseClient, p: CloudProvider, uid: string, trigger: "cron" | "user" | "connect", known?: SyncState,
): Promise<{ rows: number; error?: string }> {
  const run = startRun(uid, p.id, trigger);
  const conn = known !== undefined ? known : (await sb.from("connections").select("last_data_at,last_sync_at")
    .eq("user_id", uid).eq("provider", p.id).maybeSingle()).data as SyncState;
  const recent = conn?.last_sync_at && Date.now() - Date.parse(conn.last_sync_at) < 2 * 86_400_000;
  const days = !conn?.last_data_at ? 30 : recent ? 3 : 7;
  try {
    if (!p.fetchDays) throw new Error(`${p.name} stuurt zelf; ophalen is niet nodig`);
    const token = await accessTokenFor(sb, uid, p);
    if (!token) throw new Error(`Geen geldig token; koppel ${p.name} opnieuw`);
    const n = await writeDaily(sb, await p.fetchDays(token, uid, days), p.id);
    if (p.fetchWorkouts) {
      try {
        await writeWorkouts(sb, await p.fetchWorkouts(token, uid, Math.max(days, 7)), p.id);
      } catch (e) {
        console.warn(`trainingen ${p.id}`, String(e));
      }
    }
    const now = new Date().toISOString();
    await sb.from("connections").update({
      last_sync_at: now, ...(n ? { last_data_at: now } : {}), status: "active", last_error: null,
    }).eq("user_id", uid).eq("provider", p.id);
    return { rows: n };
  } catch (e) {
    const msg = String(e instanceof Error ? e.message : e);
    const revoked = msg.includes(" 401") || msg.includes("invalid_grant") || msg.includes("Geen geldig token");
    await sb.from("connections").update({ last_error: msg.slice(0, 500), ...(revoked ? { status: "error" } : {}) })
      .eq("user_id", uid).eq("provider", p.id);
    await finishRun(sb, run, 0, msg.slice(0, 500));
    return { rows: 0, error: msg };
  }
}
