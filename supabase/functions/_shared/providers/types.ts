// Wat elke cloudbron moet kunnen. Nieuwe bron: één bestand in deze map dat CloudProvider invult,
// een rij in de tabel providers, een terugkeerpagina site/koppelen/<slug>/ en de geheimen in Supabase.
import type { ProviderId } from "../shared.ts";

/** Eén dagwaarde, zoals writeDaily hem wegschrijft. */
export type Row = { user_id: string; day: string; metric: string; value: number };

/** Mislukte stap met een status die de app begrijpt (vitaal://<slug>?status=...). */
export class ConnectError extends Error {
  constructor(public status: string, message?: string) { super(message ?? status); }
}

export interface CloudProvider {
  id: ProviderId;
  /** In de terugkeer-URL (app.vital-connect.nl/koppelen/<slug>/) en de deep link naar de app. */
  slug: string;
  name: string;
  /** Hoe de data binnenkomt: de server haalt op (pull) of de bron stuurt zelf (push). Gelijk aan providers.sync. */
  sync: "pull" | "push";
  /** Geheimen die er moeten zijn; ontbreekt er één, dan antwoordt koppelen met not_configured. */
  secrets: string[];
  clientIdEnv: string;
  clientSecretEnv: string;
  authorizeUrl: string;
  tokenUrl: string;
  /** PKCE (code_challenge) meesturen. */
  pkce: boolean;
  /** Client-ID en -secret als Authorization: Basic in plaats van in het formulier. */
  basicAuth?: boolean;
  /** Extra parameters voor het toestemmingsscherm (scopes, offline-toegang, login-hint). */
  authorizeParams?(user: { email?: string }): Record<string, string>;
  /** Wie is deze gebruiker bij de bron (voor de koppeling één account = één Vital Connect-account). */
  identify(accessToken: string): Promise<string | null>;
  /** Dagwaarden van de laatste `days` dagen ophalen (alleen pull-bronnen). */
  fetchDays?(accessToken: string, userId: string, days: number): Promise<Row[]>;
  /** Toestemming intrekken bij de bron. Geeft een korte status terug; fouten blokkeren niets. */
  revoke(tokens: { access: string | null; refresh: string | null }): Promise<string>;
}

export const configured = (p: CloudProvider) => p.secrets.every((s) => !!Deno.env.get(s));
