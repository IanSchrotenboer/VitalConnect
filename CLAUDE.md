# Werkafspraken Vital Connect

## Bij elke update
1. Commit en push naar `main`. GitHub Actions bouwt dan een nieuwe APK (artefact `VitalConnect-<versie>-build<n>-<commit>`).
2. Werk de **app-preview in Claude** bij, zodat die de app volgt:
   - Pas `prototype/index.html` aan naar de nieuwe staat van de app (schermen, kleuren, teksten, versienummer).
   - Publiceer een nieuwe versie van het Claude-artifact **Vital Connect**
     (slug `e697eca5-9da6-4bd3-9333-549e7e9dbe05`, https://claude.ai/artifact/VUXmRLgxCuWe5GRVnUCQKn)
     met het label van de app-versie, bijvoorbeeld `v0.2.0`.
3. Verhoog `versionName` in `android/app/build.gradle.kts` bij een zichtbare wijziging en schrijf `release-notities/v<versie>.md` (Nieuw / Verbeterd / Installeren). Met de vaste sleutel maakt GitHub dan een release met APK en AAB.
   Een bestaande versie opnieuw uitbrengen: pas `release-notities/v<versie>.md` aan in dezelfde push; dan vervangt GitHub APK, AAB en tekst van die release.
4. Raakt de wijziging de Play Store (rechten, gegevens, schermen), werk dan `play-store/` en `site/privacy.html` bij.

## Vaste controles bij elke update (verplicht, vóór de push)
1. **Stresstest:** `node tests/stress-preview.mjs` (apenproef op de preview) en `tests/stress-db.sql` via Supabase
   (`execute_sql`; maakt een tijdelijke testgebruiker met jaren aan data, meet de RPC's en ruimt alles op).
   Grenzen: elke RPC onder 300 ms bij 5 jaar data, geen JavaScript-fouten in de preview.
2. **Chaostest:** `node tests/chaos-preview.mjs` (kapotte opslag, snel tikken en vegen, herladen, draaien, extreme invoer)
   en `tests/chaos-db.sql` via `execute_sql` (onzin en randgevallen op elke RPC die de app gebruikt). Alles moet ok zijn.
3. **Belastingtest:** `tests/belasting.sql` via `execute_sql` (500 gebruikers + 1 zware, cron-selectie op de index,
   app openen < 300 ms, dag van een gemiddelde gebruiker < 1 s, cache gelijk aan volledige berekening, 0 logregels bij succes).
   Capaciteit en aannames: `docs/belasting-1-miljoen.md`.
4. **Opschonen:** `python3 tests/opschonen.py` zoekt ongebruikte imports, functies, CSS-klassen, bestanden en databasefuncties,
   dubbele code en uitgeschakelde code. Verwijder wat echt overbodig is; functies van de app blijven behouden.
5. **Veiligheidscheck:** `tests/veiligheid.sql` (RLS op elke tabel, geen rechten voor anon, isolatie tussen gebruikers)
   plus `get_advisors` (security en performance) in Supabase, en `python3 tests/opschonen.py --veiligheid`
   (back-up uit, geen cleartext, geen geheimen in de code).
6. **Opruimen na de databasetests:** `vacuum full analyze public.daily_metrics;` via `execute_sql`.
   De tests schrijven honderdduizenden rijen en draaien ze terug; de tabel ruimt Postgres zelf op, de index niet
   (25 november 2026: 2.493 rijen in 58 MB tabel en 246 MB index, na opbouwen 312 kB en 352 kB).
   Controleer daarna met `pg_relation_size` en `pg_indexes_size` dat de tabel weer klein is.
7. Meld de uitkomst kort in de commit of release-notities. Een rode controle wordt eerst opgelost.

## Schaal (afspraken die bij elke wijziging blijven gelden)
- De app opent met één verzoek (`app_state`); nieuwe schermgegevens komen daar bij, niet in een extra verzoek.
- Statistieken over de hele geschiedenis komen uit `metric_stats_cache`; grote queries joinen niet per rij met kleine tabellen.
- Uploads schrijven alleen gewijzigde waarden; `sync_runs` krijgt alleen fouten.
- Ophaal-bronnen (Google Health, Suunto): hooguit eens per 10 minuten (vernieuwen) of 30 minuten (achtergrond) per gebruiker en bron; servertaak `vitaal-sync` in porties (`sync_shards` in `app_secrets`).

## Vormgeving
- Ontwerpskill: `.claude/skills/impeccable` (Impeccable 4.10, Apache 2.0; zonder de hooks). Gebruik `/impeccable critique`, `audit`, `polish` enz. voor ontwerpwerk aan de app en de preview.
- Kleuren staan in `android/.../Theme.kt`, `res/values(-night)/colors.xml` (opstartachtergrond), als CSS-variabelen bovenaan `prototype/index.html` en in `site/style.css`; houd ze gelijk. Licht = salie: achtergrond #DCE6E1, tegels #FFFFFF zonder rand, lijnen #CCD7D2. Donker = OLED-zwart: achtergrond #000000, tegels #1D2321, lijnen #333B38.
- Tabbladen: Vandaag, Gezondheid (statistieken over de hele geschiedenis), Slaap. Koppelingen en account achter het persoon-icoon.
- De dagringen kleuren per positie (abrikoos, zeegroen, indigo), niet per categorie.

## Backend
- Koppelingen: zie `docs/koppelingen.md`. Eén bestand per bron in `supabase/functions/_shared/providers/`; generieke functies
  `connect`, `connect-callback`, `sync`, `disconnect`; terugkeer via `app.vital-connect.nl/koppelen/<slug>/`; register in de tabel
  `providers` (`sync`, `available`). Nieuwe bron = providerbestand + rij in `providers` + terugkeerpagina + geheimen.
- Supabase-project `qtythirnpokfptchavgy`. Nieuwe migraties krijgen als bestandsnaam de versie die Supabase toekent.
- Elke edge function moet in `supabase/config.toml` staan, anders rolt de GitHub-koppeling hem niet uit.
