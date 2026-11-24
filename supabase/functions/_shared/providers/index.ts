// Register van cloudbronnen. Welke er in de app open staan, staat in de tabel providers (kolom available).
import type { ProviderId } from "../shared.ts";
import { garmin } from "./garmin.ts";
import { google } from "./google.ts";
import { suunto } from "./suunto.ts";
import type { CloudProvider } from "./types.ts";

export const PROVIDERS: Record<ProviderId, CloudProvider> = { garmin, google_health: google, suunto };

export function providerOf(id: unknown): CloudProvider | null {
  return typeof id === "string" && id in PROVIDERS ? PROVIDERS[id as ProviderId] : null;
}

export { ConnectError, configured, type CloudProvider, type Row } from "./types.ts";
