# Vital Connect bij 1 miljoen gebruikers

Belastingrapport, 24 november 2026 (versie 1.7.0).

**Hoe gemeten:**
- Gemeten op de echte Supabase-database met `tests/belasting.sql`: 500 gewone gebruikers met 60 dagen data, plus één zware gebruiker met 5 jaar uit 2 bronnen.
- Daarnaast `tests/stress-db.sql` en `tests/chaos-db.sql`.
- De getallen voor 1 miljoen zijn doorgerekend vanuit deze metingen. Een echte test met een miljoen accounts is niet gedaan.

## De gemiddelde gebruiker (aanname)

- Achtergrond-sync elk uur (de standaard): 24 keer per dag.
- 5 keer per dag de app openen en 3 keer handmatig vernieuwen.
- Health Connect plus Google Health; een deel van de gebruikers heeft Garmin.
- Ongeveer 12 metrics per dag, uit 1 à 2 bronnen.

## Voor en na

| | 1.6.0 | 1.7.0 |
|---|---|---|
| Verzoeken bij het openen van de app | 6 | 1 |
| Verzoeken per achtergrondronde | 4–6 | 1–3 (geen upload als er niets veranderd is) |
| Verzoeken per gebruiker per dag | ± 190 | ± 70 |
| Gemiddeld bij 1 miljoen gebruikers | ± 2.200 per seconde | ± 800 per seconde |
| Databasetijd, dag van een gemiddelde gebruiker | 927 ms | 49 ms |
| Databasetijd bij 1 miljoen gebruikers (gemiddeld) | ± 11 cores | ± 0,6 core |
| Statistieken (Gezondheid), zware gebruiker | 170 ms bij elk openen | 115 ms eens per dag, daarna 1 ms |
| Logregels `sync_runs` per gebruiker per dag | 24 | 0 (alleen fouten) |
| Servertaak Google Health | 200 gebruikers per uur, één portie | per kwartier, in porties, via een index |
| Google-aanroepen per gebruiker per dag (achtergrond elk kwartier) | tot 1.150 | tot 580 (minimaal 30 minuten tussen achtergrondsyncs) |
| Eén ongeldige regel in een upload | hele upload mislukt | alleen die regel overgeslagen |

Piekuren (ochtend en avond) liggen naar verwachting 3 à 4 keer boven het gemiddelde: ongeveer 3.000 verzoeken per seconde.

## Wat er veranderd is

1. **`app_state`:** één RPC voor het openen van de app, de achtergrondtaak en de doelmeldingen.
2. **`metric_stats_cache`:**
   - De geschiedenis tot gisteren wordt bewaard; vandaag wordt er live bij opgeteld.
   - De cache vervalt alleen als er iets in het verleden verandert of als er een andere bron wordt gekozen.
   - De berekening joint niet per rij met kleine tabellen. Daardoor is ze onafhankelijk van tabelstatistieken (420 → 115 ms).
3. **Uploads schrijven alleen gewijzigde waarden:**
   - `ingest_health_connect` werkt alleen bij wat echt anders is.
   - Health Connect stuurt alleen gewijzigde dagen, in aaneengesloten reeksen.
4. **Google Health:**
   - De servertaak werkt in porties (`google_sync_shards` in `app_secrets`) en kiest alleen gebruikers die 3 uur niet zijn bijgewerkt.
   - De app vraagt een sync hooguit eens per 10 minuten (vernieuwen) of 30 minuten (achtergrond).
   - De 12 aanroepen per sync gaan tegelijk.
5. **Ongewijzigde dagwaarden worden niet herschreven** (trigger `daily_metrics_skip_unchanged`, 24 nov 2026):
   - Garmin en Google sturen bij elke sync de laatste 3 à 7 dagen opnieuw, meestal met dezelfde waarden.
   - Zo'n rij wordt overgeslagen: er wordt niets geschreven en de statistiekencache blijft geldig.
   - Zonder deze trigger verviel de cache bij elke Google-sync (tot elk half uur per gebruiker).
6. **Opruimen:**
   - `provider_raw` wordt 30 dagen bewaard, `sync_runs` 14 dagen, de cache 30 dagen.
   - Geslaagde syncs worden niet meer gelogd.

## Wat nog nodig is vóór 1 miljoen

1. **Opslag:**
   - `daily_metrics` groeit met ongeveer 9.000 rijen per gebruiker per jaar. Bij 1 miljoen gebruikers is dat ongeveer 9 miljard rijen en 1,5–2 TB per jaar, inclusief indexen.
   - **Aanbeveling:**
     - vanaf ongeveer 50.000 gebruikers de tabel partitioneren op `hash(user_id)`, met 32 à 64 partities;
     - een groter Supabase-compute-niveau en een schijf die meegroeit.
2. **Google Health API-quotum:**
   - Bij 1 miljoen Google-gebruikers en een sync per uur zijn dat ongeveer 290 miljoen aanroepen per dag.
   - Vraag tijdig een hoger quotum aan bij Google Cloud.
   - Overweeg de standaard-sync op 3 uur te zetten. Health Connect blijft dan het snelle pad.
3. **Edge function-aanroepen:**
   - Elke achtergrondronde met Google kost één aanroep: ongeveer 24 miljoen per dag bij 1 miljoen gebruikers.
   - Dat valt ruim buiten het gratis deel. Reken op kosten per miljoen aanroepen, of bundel Google naar alleen de servertaak.
4. **Servertaak opschalen:**
   - Zet `google_sync_shards` hoger, bijvoorbeeld 50, en plan één cron-taak per portie.
   - Eén aanroep verwerkt 200 gebruikers, 8 tegelijk.
5. **Verbindingen:**
   - De app praat via PostgREST, dus via de pooler.
   - Houd bij groei het aantal verbindingen van de pooler in de gaten (Supabase-dashboard → Database → Connection pooling).
6. **Wachtwoorden:** zet "Leaked password protection" aan in Supabase Auth. Dat is een instelling in het dashboard en zit niet in de code.

## Controleren

```
node tests/stress-preview.mjs        # apenproef preview
node tests/chaos-preview.mjs         # chaostest preview
python3 tests/opschonen.py           # overbodige en dubbele code
python3 tests/opschonen.py --veiligheid
```

Via Supabase `execute_sql`:
- `tests/stress-db.sql`
- `tests/chaos-db.sql`
- `tests/belasting.sql`
- `tests/veiligheid.sql`

Alle tests draaien in één transactie die wordt teruggedraaid. Het resultaat komt terug als foutmelding `RAPPORT [...]`.
