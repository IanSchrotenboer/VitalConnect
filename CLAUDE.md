# Werkafspraken Vital Connect

## Bij elke update
1. Commit en push naar `main`. GitHub Actions bouwt dan een nieuwe APK (artefact `VitalConnect-<versie>-build<n>-<commit>`).
2. Werk de **app-preview in Claude** bij, zodat die de app volgt:
   - Pas `prototype/index.html` aan naar de nieuwe staat van de app (schermen, kleuren, teksten, versienummer).
   - Publiceer een nieuwe versie van het Claude-artifact **Vital Connect**
     (slug `e697eca5-9da6-4bd3-9333-549e7e9dbe05`, https://claude.ai/artifact/VUXmRLgxCuWe5GRVnUCQKn)
     met het label van de app-versie, bijvoorbeeld `v0.2.0`.
3. Verhoog `versionName` in `android/app/build.gradle.kts` bij een zichtbare wijziging.

## Vormgeving
- Kleuren staan in `android/.../Theme.kt` en als CSS-variabelen bovenaan `prototype/index.html`; houd ze gelijk.
- Tabbladen: Vandaag, Gezondheid (statistieken over de hele geschiedenis), Slaap. Koppelingen en account achter het persoon-icoon.
- De dagringen kleuren per positie (abrikoos, zeegroen, indigo), niet per categorie.

## Backend
- Supabase-project `qtythirnpokfptchavgy`. Nieuwe migraties krijgen als bestandsnaam de versie die Supabase toekent.
- Elke edge function moet in `supabase/config.toml` staan, anders rolt de GitHub-koppeling hem niet uit.
