# Werkafspraken Vital Connect

## Bij elke update
1. Commit en push naar `main`. GitHub Actions bouwt dan een nieuwe APK (artefact `VitalConnect-<versie>-build<n>-<commit>`).
2. Werk de **app-preview in Claude** bij, zodat die de app volgt:
   - Pas `prototype/index.html` aan naar de nieuwe staat van de app (schermen, kleuren, teksten, versienummer).
   - Publiceer een nieuwe versie van het Claude-artifact **Vital Connect**
     (slug `e697eca5-9da6-4bd3-9333-549e7e9dbe05`, https://claude.ai/artifact/VUXmRLgxCuWe5GRVnUCQKn)
     met het label van de app-versie, bijvoorbeeld `v0.2.0`.
3. Verhoog `versionName` in `android/app/build.gradle.kts` bij een zichtbare wijziging en schrijf `release-notities/v<versie>.md` (Nieuw / Verbeterd / Installeren). Met de vaste sleutel maakt GitHub dan een release met APK en AAB.
4. Raakt de wijziging de Play Store (rechten, gegevens, schermen), werk dan `play-store/` en `site/privacy.html` bij.

## Vaste controles bij elke update (verplicht, vóór de push)
1. **Stresstest:** `node tests/stress-preview.mjs` (apenproef op de preview) en `tests/stress-db.sql` via Supabase
   (`execute_sql`; maakt een tijdelijke testgebruiker met jaren aan data, meet de RPC's en ruimt alles op).
   Grenzen: elke RPC onder 300 ms bij 5 jaar data, geen JavaScript-fouten in de preview.
2. **Opschonen:** `python3 tests/opschonen.py` zoekt ongebruikte imports, functies, CSS-klassen en bestanden.
   Verwijder wat echt overbodig is; functies van de app blijven behouden.
3. **Veiligheidscheck:** `tests/veiligheid.sql` (RLS op elke tabel, geen rechten voor anon, isolatie tussen gebruikers)
   plus `get_advisors` (security en performance) in Supabase, en `python3 tests/opschonen.py --veiligheid`
   (back-up uit, geen cleartext, geen geheimen in de code).
4. Meld de uitkomst kort in de commit of release-notities. Een rode controle wordt eerst opgelost.

## Vormgeving
- Kleuren staan in `android/.../Theme.kt` en als CSS-variabelen bovenaan `prototype/index.html`; houd ze gelijk.
- Tabbladen: Vandaag, Gezondheid (statistieken over de hele geschiedenis), Slaap. Koppelingen en account achter het persoon-icoon.
- De dagringen kleuren per positie (abrikoos, zeegroen, indigo), niet per categorie.

## Backend
- Supabase-project `qtythirnpokfptchavgy`. Nieuwe migraties krijgen als bestandsnaam de versie die Supabase toekent.
- Elke edge function moet in `supabase/config.toml` staan, anders rolt de GitHub-koppeling hem niet uit.
