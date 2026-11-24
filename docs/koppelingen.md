# Koppelingen (bronnen)

Elke bron werkt op dezelfde manier. Wat per bron verschilt, staat in één bestand in
`supabase/functions/_shared/providers/`.

## Overzicht

| Bron | Hoe de data binnenkomt | Status | Code |
|---|---|---|---|
| Health Connect | Telefoon → RPC `ingest_health_connect` | Open | `android/…/HealthSync.kt` |
| Google Health | De server haalt op (pull) | Open | `providers/google.ts` |
| Garmin Connect | Garmin stuurt zelf (push → `garmin-webhook`) | Binnenkort: wacht op toegang van Garmin | `providers/garmin.ts` |
| Suunto | De server haalt op (pull) | Binnenkort: wacht op toegang van Suunto | `providers/suunto.ts` |

De tabel `providers` in de database is het register:
- **`sync`**: `device`, `pull` of `push`.
- **`available`**: staat de koppeling open in de app?
- **`priority`**: welke bron wint als er voor dezelfde dag meer zijn.

De app krijgt dit register mee bij het openen (`app_state` → `providers`). Een bron met `available = false` staat in de app als **Binnenkort beschikbaar**, zonder knop.

## De weg van een koppeling

1. **Koppelen:** de app roept `connect` aan met `{provider}`. Dit antwoordt met een van:
   - het adres van het toestemmingsscherm, met een eenmalige `state` en waar mogelijk PKCE;
   - `409 not_available` als de bron niet open staat;
   - `503 not_configured` als de geheimen ontbreken.
2. **Terugkeer:** de bron stuurt de browser naar `https://app.vital-connect.nl/koppelen/<slug>/`, een pagina in `site/koppelen/<slug>/`. Die pagina stuurt `code` en `state` door naar `connect-callback?provider=<id>`.
3. **`connect-callback`:**
   - wisselt de code in;
   - bewaart de tokens versleuteld (`oauth_tokens`, AES-GCM met `TOKEN_ENC_KEY`);
   - legt de gebruiker bij de bron vast: één account bij de bron hoort bij één Vital Connect-account;
   - haalt bij een pull-bron direct 30 dagen op;
   - stuurt terug naar de app met `vitaal://<slug>?status=ok|cancelled|expired|not_linked|error`.
4. **Bijwerken:**
   - **Pull-bronnen:** de app roept `sync` aan bij vernieuwen (hooguit eens per 10 minuten per bron) en vanuit de achtergrondtaak (hooguit eens per 30 minuten). Daarnaast loopt elk kwartier de servertaak `vitaal-sync` (pg_cron) voor wie 3 uur niet is bijgewerkt, in porties (`sync_shards` in `app_secrets`).
   - **Push-bronnen:** de bron roept zelf een webhook aan.
5. **Ontkoppelen:** `disconnect`:
   - trekt de toestemming bij de bron in (als die bron dat kan);
   - wist de tokens;
   - wist met `delete_data` ook de dagwaarden.

   `delete-account` doet dit voor alle bronnen en verwijdert daarna het account.

Gemeenschappelijke code:
- `_shared/oauth.ts`: koppelen, tokens en intrekken.
- `_shared/sync.ts`: ophalen en vastleggen.
- `_shared/shared.ts`: basis, versleuteling en wegschrijven met controle op de grenzen per metric.

`health-check` toont per bron:
- of hij open staat;
- welke geheimen ontbreken;
- welk terugkeeradres bij de bron geregistreerd moet zijn.

## Een bron aanzetten

1. Zet de geheimen in Supabase (Edge Functions → Secrets).
2. Registreer bij de bron het terugkeeradres `https://app.vital-connect.nl/koppelen/<slug>/`.
3. Controleer met `health-check`.
4. Zet de bron open: `update public.providers set available = true where id = '<id>';`

### Garmin Connect

- **Toegang:** Garmin Connect Developer Program, Health API. Alleen voor bedrijven, na goedkeuring. Garmin heeft nieuwe aanvragen in 2026 een tijd gepauzeerd.
- **Geheimen:** `GARMIN_CLIENT_ID`, `GARMIN_CLIENT_SECRET` en `GARMIN_WEBHOOK_KEY` (zelf te kiezen).
- **Terugkeeradres:** `https://app.vital-connect.nl/koppelen/garmin/`
- **Push-URL** (per samenvattingstype in het Garmin-portaal): `https://qtythirnpokfptchavgy.supabase.co/functions/v1/garmin-webhook?key=<GARMIN_WEBHOOK_KEY>`

### Suunto

- **Toegang:** Suunto partnerprogramma, daarna de Suunto API Zone. Abonneer je daar op het product **Developer API** en, als dat bestaat, op de 24/7-producten (dagactiviteit, slaap).
- **Geheimen:**
  - `SUUNTO_CLIENT_ID` en `SUUNTO_CLIENT_SECRET` (OAuth-instellingen in je profiel in de API Zone);
  - `SUUNTO_SUBSCRIPTION_KEY` (de *Primary key* van je abonnement).
- **Terugkeeradres:** `https://app.vital-connect.nl/koppelen/suunto/`
- **Wat er wordt opgehaald:**
  - stappen per dag;
  - slaap per nacht: duur, slaapscore, laagste hartslag (als rusthartslag) en HRV;
  - actieve minuten uit de workouts.
- **Te controleren zodra er toegang is:**
  - de eenheid van de slaapduur;
  - `since` bij workouts;
  - of energie per dag totaal of actief is, en in kcal of joule. Energie wordt daarom nog niet opgeslagen.
  - Suunto-webhooks (WORKOUT_CREATED). Tot die tijd haalt de server de data op.

## Opruimen na de overstap (november 2026)

De oude functies staan nog in Supabase, zodat testers met een oudere app kunnen blijven koppelen:
`google-health-auth-start`, `google-health-auth-callback`, `google-health-sync`, `garmin-auth-start`, `garmin-auth-callback` en `garmin-disconnect`.

Verwijder ze in het Supabase-dashboard (Edge Functions) zodra alle testers op versie 1.9.0 of hoger zitten. Verwijder dan ook het geheim `GOOGLE_REDIRECT_URI`: alleen de oude functies gebruiken het nog. De nieuwe functies halen het terugkeeradres uit `REDIRECT_BASE`, met als standaard `https://app.vital-connect.nl/koppelen/`.
