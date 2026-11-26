# Supabase-instellingen die niet in de code staan

Project `qtythirnpokfptchavgy` (Frankfurt). Migraties en edge functions rollen uit via de GitHub-koppeling;
de instellingen hieronder staan in het **dashboard** en gaan niet mee met een push. `supabase/config.toml`
beschrijft dezelfde stand voor lokaal gebruik. Let op: `supabase config push` overschrijft het dashboard met dat bestand.

Controleer deze lijst na elke wijziging in het dashboard en houd hem bij.

## Authentication → URL Configuration
- **Site URL**: `https://app.vital-connect.nl` (in `config.toml` staat nog `localhost`; dat is alleen voor lokaal).
- **Redirect URLs**: `vitaal://login` en `https://app.vital-connect.nl/**` (terugkeer na het koppelen van een bron loopt via `app.vital-connect.nl/koppelen/<slug>/`, zie `docs/koppelingen.md`).

## Authentication → Sign In / Providers
- **Email**: aan; **Confirm email** aan; **Secure email change** aan; **Email OTP Length** `6`; **Email OTP Expiration** `3600` (de mail zegt "1 uur geldig").
- **Minimum password length** `8`, **Password requirements** "Letters and digits". De app eist hetzelfde (`passwordProblem` in `Api.kt`).
- **Google**: aan, Client ID = Web-client uit Google Cloud (staat ook in `Config.GOOGLE_WEB_CLIENT_ID`), secret alleen in het dashboard.

## Mails: alleen een code, nooit een link
- **Authentication → Hooks → Send Email**: HTTPS naar `https://qtythirnpokfptchavgy.supabase.co/functions/v1/send-email`,
  secret als `SEND_EMAIL_HOOK_SECRET` in de edge-functiegeheimen (`v1,whsec_…`). De functie stuurt via Resend.
- De mails bevatten **alleen de code van 6 cijfers**. Beveiligingsscanners van werk- en schoolmail (Microsoft Defender) openen
  links in binnenkomende mail automatisch; dan is de eenmalige code verbruikt voordat de gebruiker hem intypt (geleerd bij BOA Paraat).
  Staat de hook uit, controleer dan dat de sjablonen onder Authentication → Emails → Templates ook alleen `{{ .Token }}` tonen.
- **Resend**: domein `vital-connect.nl` op Verified (SPF, DKIM). Voeg bij de DNS ook DMARC toe: `_dmarc` TXT `v=DMARC1; p=none;`.
  Zonder DMARC belandt mail bij Outlook en Microsoft 365 vaker in ongewenst. Aflevering per mail: Resend → Emails.
- **Authentication → Rate Limits → Rate limit for sending emails**: minimaal 30 per uur; niet hoger dan het Resend-abonnement
  toelaat (gratis: 100 per dag).

## Tweestapsverificatie (vrijwillig)
- **Authentication → Multi-Factor → TOTP (App Authenticator)**: aan (standaard aan). Sms is niet gebouwd (betaald).
- In de app: Profiel → Account → Tweestapsverificatie. Na elke manier van inloggen vraagt de app de code als het account een
  bevestigde factor heeft; pas daarna laadt het dashboard. Bij aanzetten worden andere toestellen uitgelogd (`logout?scope=others`).
- Afgedwongen in de database (migratie `20261126143946_mfa_beheer.sql`): `public.mfa_ok()` en een restrictive policy
  "tweestapsverificatie" op `profiles`, `metric_prefs`, `daily_metrics`, `connections`, `sync_runs` en `workouts`.
  `app_state` en `ingest_health_connect` geven dan de fout "Tweede stap nodig", `metric_stats` geeft niets terug.
  De edge functions (`userFrom` in `_shared/shared.ts`) weigeren een sessie zonder tweede stap.
- **Telefoon kwijt: de gebruiker regelt het zelf** met een herstelcode (migratie `20261126162809_mfa_herstelcodes.sql`).
  Bij het aanzetten krijgt hij 10 eenmalige codes (`create_recovery_codes()`, alleen met tweede stap), die hij zelf bewaart.
  Op het scherm "Tweede stap" → "Telefoon kwijt? Gebruik een herstelcode" (`use_recovery_code()`, werkt met een gewone sessie):
  juiste code = alle factoren en codes weg, tweestapsverificatie uit. Codes staan alleen als bcrypt-hash in `mfa_herstelcodes`;
  na 5 foute pogingen per uur volgt een blokkade (`mfa_herstel_pogingen`). In Profiel zie je hoeveel codes je nog hebt.
- **Noodgreep beheerder** (niet in de app genoemd; de gebruiker is zelf verantwoordelijk): Authentication → Users → gebruiker →
  Remove MFA factor, of `delete from auth.mfa_factors where user_id = (select id from auth.users where email = '…');`

## Herstel via sms (onderzocht 26 november 2026, niet gebouwd)
- **Via Supabase** (Advanced MFA Phone): $75 per maand voor het eerste project, alleen op Pro (nog eens $25 per maand),
  plus kosten per sms bij Twilio, MessageBird of Vonage. Valt niet onder de spend cap.
- **Zelf gebouwd** (edge function + sms-dienst, zoals de mails via Resend): geen vaste kosten, alleen per sms (in Nederland
  ongeveer € 0,07 tot € 0,10). Vraagt een account bij een sms-dienst, het telefoonnummer opslaan (nieuw persoonsgegeven:
  privacyverklaring en Play Store aanpassen) en een eigen code-, verloop- en limietlogica.
- **Nadeel van beide:** sms is kwetsbaar voor SIM-swapping (iemand laat je nummer overzetten); herstel via sms is daardoor
  zwakker dan de authenticator-app zelf. Herstelcodes hebben dat probleem niet.

## Databasetests en de logs
- `tests/chaos-db.sql`, `stress-db.sql` en `belasting.sql` draaien in een subtransactie die aan het eind wordt teruggedraaid en
  geven het rapport terug als tabel. Tot 26 november 2026 eindigden ze met een bewuste fout ("RAPPORT …") om alles terug te draaien;
  dat stond in Supabase als "Execute SQL FAILED". Dat waren dus geen echte fouten.

## Gelekte wachtwoorden
- **Leaked password protection** (Authentication → Sign In / Providers → Email) kan alleen op het Pro-abonnement.
  Zolang het project gratis is, staat hij uit en meldt de security advisor dat; dat is bekend.
- De app controleert zelf bij een nieuw account en een nieuw wachtwoord via Have I Been Pwned (k-anonimiteit: alleen de eerste
  5 tekens van de SHA-1-hash gaan de deur uit; `leakCount` in `Api.kt`). Geen netwerk = niet tegenhouden.
- Gaat het project naar Pro: zet de schakelaar aan; de controle in de app mag blijven (dubbel is niet erg).

## Beheer
- **Beheerders** staan in `public.beheerders` (nu Ians account). Toevoegen:
  `insert into public.beheerders (user_id) select id from auth.users where email = '…';`
- `admin_overview()` geeft kerncijfers, app-versies, koppelingen, fouten van 7 dagen en alle gebruikers (e-mail, naam, laatst actief,
  versie, tweestaps, koppelingen). Geen gezondheidswaarden. Weigert wie geen beheerder is of de tweede stap mist; `anon` mag niet.
- In de app: Profiel → tabblad **Beheer** (alleen zichtbaar als `app_state.is_admin`).
- De app meldt zijn versie in `profiles.app_version` en `profiles.app_seen_at`, alleen bij een nieuwe versie of een nieuwe dag.

## Rechten
- `anon` en `authenticated` hebben geen TRUNCATE, REFERENCES of TRIGGER op tabellen (TRUNCATE gaat langs RLS heen);
  ook niet op nieuwe tabellen (default privileges, migratie `20261126143612_no_truncate.sql`). `tests/veiligheid.sql` controleert het.
