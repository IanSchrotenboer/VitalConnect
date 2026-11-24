# Vital Connect

Gezondheidsdata uit **Garmin Connect**, **Google Health** en **Health Connect** in één dashboard dat je zelf indeelt.

```
Garmin-horloge ─▶ Garmin Connect ──push──▶ garmin-webhook ─────────────┐
Fitbit / Pixel ─▶ Google Health  ◀─pull (elk uur)── google-health-sync ─┤
                                                                        ├─▶ daily_metrics ─▶ resolved_daily() ─▶ app
Android: Health Connect ─▶ Vitaal-app ─▶ ingest_health_connect() ──────┘
```

| Map | Inhoud |
|---|---|
| `supabase/migrations` | Volledig datamodel, RLS, functies en geplande taken |
| `supabase/functions` | Edge functions (Deno). Gedeelde code staat in `_shared/` |
| `supabase/config.toml` | Auth (Google, e-mail, Send Email Hook) en functie-instellingen |
| `android/` | Android-app (Kotlin, Jetpack Compose) |
| `prototype/` | Klikbaar HTML-prototype van het dashboard |
| `.github/workflows` | Bouwt bij elke push de APK en controleert de edge functions |

Supabase-project: **Vitaal** (`qtythirnpokfptchavgy`, Frankfurt).

## Wat er staat en werkt

**Database**
- `metrics` (16 metrics met eenheid en geldige bandbreedte) en `providers` (Garmin › Google Health › Health Connect).
- `daily_metrics`: één waarde per gebruiker, dag, metric en bron. `resolved_daily()` kiest per metric de voorkeursbron van de gebruiker, anders de providerprioriteit.
- `metric_prefs`: de dashboardindeling per gebruiker (zichtbaar, volgorde, grootte, bron, doel, maximaal 3 dagringen). Nieuwe gebruikers krijgen een standaardindeling.
- `profiles`: e-mail en naam uit het account (ook bij inloggen met Google) en AVG-toestemming. Gebruikers kunnen alleen naam, tijdzone en toestemming zelf wijzigen.
- `connections` en `sync_runs`: status, laatste sync, laatste fout en een log per synchronisatie.
- `oauth_tokens` (AES-GCM-versleuteld), `oauth_states`, `provider_raw`, `app_secrets`: alleen voor edge functions, niet bereikbaar voor de app.
- `export_my_data()`: alle eigen data als JSON (AVG-inzage). Account verwijderen wist alles (cascade).
- **pg_cron**: elk uur om :17 Google Health ophalen. Elke nacht opschonen: OAuth-pogingen na 1 uur, ruwe Garmin-data na 90 dagen, synclog na 60 dagen.

**Edge functions**

| Functie | Doel |
|---|---|
| `google-health-auth-start` / `-callback` | Toestemming voor de Google Health API (OAuth + PKCE, offline toegang), daarna direct 30 dagen ophalen |
| `google-health-sync` | Dagtotalen (stappen, afstand, calorieën, verdiepingen, actieve minuten, gewicht) en dagwaarden (rusthartslag, HRV, SpO2, VO2max, ademhaling, slaap) |
| `garmin-auth-start` / `-callback` / `garmin-webhook` | Garmin-koppeling en ontvangst van push-data |
| `disconnect` | Toestemming intrekken bij Garmin of Google, optioneel met data wissen |
| `send-email` | Send Email Hook: alle account-mails in het Nederlands via Resend, met een 6-cijferige code |
| `delete-account` | Toestemmingen intrekken en account plus alle data verwijderen |

**Android-app**
- Inloggen met wachtwoord, met een **e-mailcode** of met **Google** (Credential Manager). Wachtwoord herstellen via een code. AVG-toestemming bij de eerste keer.
- Dashboard met dagringen en tegels die je zelf indeelt. Per metric kies je de bron: automatisch, Garmin, Google Health of Health Connect.
- Health Connect: 30 dagen terug (of een jaar met history-toestemming), daarna elk uur op de achtergrond.
- Google Health en Garmin koppelen, nu ophalen, ontkoppelen (met of zonder data wissen).
- Mijn data exporteren (JSON) en account verwijderen.

## Wat jij nog moet instellen

Deze stappen vragen accounts en geheimen die alleen jij kunt aanmaken. Tot ze zijn ingesteld geven de betreffende functies een nette `503 not_configured`.

### 1. Google Cloud (inloggen én Google Health)
1. Maak een project in console.cloud.google.com en zet de **Google Health API** aan.
2. **OAuth-toestemmingsscherm**: extern, app-naam Vitaal, je privacybeleid, en de scopes
   `googlehealth.activity_and_fitness.readonly`, `googlehealth.health_metrics_and_measurements.readonly` en `googlehealth.sleep.readonly`.
   Voeg tijdens het testen jezelf toe als testgebruiker. Voor publieke uitrol moet Google de app verifiëren.
3. **OAuth-client "Webapplicatie"** met deze Authorized redirect URIs:
   - `https://qtythirnpokfptchavgy.supabase.co/auth/v1/callback`
   - `https://qtythirnpokfptchavgy.supabase.co/functions/v1/google-health-auth-callback`
4. **OAuth-client "Android"** met package `nl.vitaal.app` en de SHA-1 van je signing key
   (`./gradlew signingReport` in `android/`).
5. Vul in de app `GOOGLE_WEB_CLIENT_ID` in (`Api.kt`) met de client-ID van de webclient.

### 2. Supabase-dashboard
- **Authentication → Sign In / Providers → Google**: aanzetten, client-ID en secret van de webclient invullen.
- **Edge Functions → Secrets**:

| Naam | Waarde |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Dezelfde webclient als hierboven |
| `TOKEN_ENC_KEY` | `openssl rand -base64 32` (versleutelt OAuth-tokens) |
| `RESEND_API_KEY` | API-sleutel uit resend.com |
| `EMAIL_FROM` | Bijvoorbeeld `Vitaal <noreply@jouwdomein.nl>`, op een domein dat in Resend geverifieerd is |
| `SEND_EMAIL_HOOK_SECRET` | Zie hieronder |
| `GARMIN_CLIENT_ID`, `GARMIN_CLIENT_SECRET`, `GARMIN_WEBHOOK_KEY` | Na goedkeuring door Garmin (zie stap 4) |

- **Authentication → Hooks → Send Email**: kies HTTPS, URL `https://qtythirnpokfptchavgy.supabase.co/functions/v1/send-email`, laat een secret genereren en zet dat als `SEND_EMAIL_HOOK_SECRET`.
  Zet de hook pas aan als `RESEND_API_KEY` en `EMAIL_FROM` staan, anders komen er geen mails aan.
- De functie `garmin-disconnect` is vervangen door `disconnect` en mag verwijderd worden.

In plaats van het dashboard kun je `supabase/config.toml` ook pushen met `supabase config push`.

### 3. Android-app
GitHub Actions bouwt bij elke push naar `main` een debug-APK (`VitalConnect-<versie>-debug.apk`).
Download hem onder **Actions → Android-app bouwen → laatste run → Artifacts**.
Zelf bouwen: open `android/` in Android Studio (compileSdk 36, AGP 8.11) en draai op Android 9 of hoger.

De app heeft drie tabbladen: **Vandaag** (dagringen en je eigen tegels), **Gezondheid** (alle statistieken
over je hele geschiedenis) en **Slaap**. Koppelingen en account zitten achter het persoon-icoon.

### Automatisch uitrollen
De Supabase GitHub-koppeling voert bij een push naar `main` nieuwe migraties uit en rolt de edge functions
uit die in `supabase/config.toml` staan. Zet daarvoor in Supabase bij *Project Settings → Integrations → GitHub*
**Deploy to production** aan, met `main` als productiebranch en `.` als working directory.

### 4. Garmin
Vraag Starter-toegang aan via developer.garmin.com en stel in het portal in:
- Redirect URL `https://qtythirnpokfptchavgy.supabase.co/functions/v1/garmin-auth-callback`
- Push-endpoints `https://qtythirnpokfptchavgy.supabase.co/functions/v1/garmin-webhook?key=<GARMIN_WEBHOOK_KEY>` voor `dailies`, `sleeps`, `hrv`, `bodyComps`, `pulseox`, `respiration`, `userMetrics`, `stressDetails`, `deregistrations` en `userPermissionsChange`.

## Getest en niet getest
- **Getest** tegen het live project: RLS en rechten (gebruikers kunnen geen tokens, geheimen of andermans data lezen), `ingest_health_connect`, de bronkeuze in `resolved_daily`, `export_my_data`, profielvulling vanuit auth, de cron-aanroep van `google-health-sync` en het weigeren van verkeerde sleutels door `google-health-sync`, `send-email` en `garmin-webhook`.
- **Niet getest**: de Android-app is niet gecompileerd (geen build-omgeving beschikbaar). De Google Health-, Garmin- en Resend-aanroepen zijn pas end-to-end te testen met jouw client-ID's en sleutels. De veldnamen volgen de API-referenties; controleer de eerste sync in `sync_runs` en de logs van de edge functions.

## Lokaal ontwikkelen
```bash
supabase link --project-ref qtythirnpokfptchavgy
supabase db pull          # of: supabase db push na nieuwe migraties
supabase functions deploy # alle functies, met config.toml
supabase gen types typescript --project-id qtythirnpokfptchavgy > supabase/database.types.ts
```
