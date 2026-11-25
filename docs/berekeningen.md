# Berekeningen: herstel, fitheid en training

Vital Connect berekent herstel en fitheid zelf, op de server (functie `fitness`, migratie `20261125060900_fitness_scores.sql`).
De uitkomst komt mee in `app_state`, zodat de app nog steeds met één verzoek opent. App en preview rekenen niet zelf;
de preview doet hetzelfde op voorbeelddata.

Waarden die een bron zelf berekent (Body Battery, slaapscore, stress, VO2max van het horloge) tonen we zoals de bron ze levert.

## Herstel vandaag

Methode uit het HRV-onderzoek bij sporters (Plews, Buchheit e.a.; richtlijn HRV van het Australian Institute of Sport).

| Stap | Hoe |
| --- | --- |
| HRV | RMSSD tijdens de slaap, per nacht, logaritmisch omgerekend: ln(RMSSD) |
| 7-daags gemiddelde | Gemiddelde van vandaag en de 6 dagen ervoor; minstens 3 metingen |
| Basislijn | Gemiddelde en standaardafwijking (SD) van de 60 dagen daarvoor (dag −66 t/m −7); minstens 14 dagen |
| Normaal bereik | Basislijn ± kleinste zinvolle verandering (0,5 × SD) |
| Rusthartslag | Zelfde opzet, zonder logaritme; hoger dan normaal telt als minder hersteld |
| Slaap | Afgelopen nacht en het 7-nachtengemiddelde tegen het slaapdoel (standaard 8 uur) |

**Oordeel** (uit de banden):
- **Rustig aan:** HRV én rusthartslag buiten het normale bereik, of één van de twee buiten het bereik en een slaaptekort.
- **Let op:** één signaal wijkt af.
- **Goed hersteld:** de rest.

Een slaaptekort betekent: het 7-nachtengemiddelde is minder dan 90% van het slaapdoel.

**Score 0–100:**
- Per factor: 50 ± 25 × z. Daarin is z de afwijking van het 7-daagse gemiddelde tot de basislijn, gedeeld door de standaardafwijking.
- Bij rusthartslag telt de afwijking omgekeerd.
- Slaap: 100 × (½ nacht + ½ week) ÷ doel, hoogstens 100.
- Weging: HRV 50%, rusthartslag 25%, slaap 25%. **Die weging is onze eigen keuze**; het oordeel hierboven hangt er niet van af.
- Zonder genoeg data voor HRV én rusthartslag is er nog geen score ("nog aan het leren").

**Verloop:** 30 dagen van het 7-daagse HRV-gemiddelde met het normale bereik van elke dag. Dit is de gebruikelijke grafiek uit het onderzoek.

## HRV uit Health Connect

Garmin, Google en Suunto leveren de HRV van de nacht. Health Connect levert losse metingen.

Sinds 1.10 middelt de app alleen de metingen tijdens een slaapsessie, voor de dag waarop je wakker wordt. Alleen als er die dag geen meting in de slaap is, gebruikt hij het gemiddelde van de hele dag. Zo zijn bronnen vergelijkbaar en blijft de basislijn eerlijk.

Bij de eerste synchronisatie na de update berekent de app de geschiedenis één keer opnieuw.

## Fitheid

| Wat | Formule | Bron |
| --- | --- | --- |
| Maximale hartslag (geschat) | 208 − 0,7 × leeftijd | Tanaka e.a. 2001, meta-analyse van 351 studies |
| Hartslagzones | 50–60, 60–70, 70–80, 80–90 en 90–100% van de maximale hartslag | Zones van Edwards (TRIMP) |
| VO2max (schatting) | factor × max. hartslag ÷ rusthartslag; factor 15,3 (man), 14,5 (vrouw), 15 (niet ingevuld) | Uth e.a. 2004 en 2005 |

Rusthartslag in de VO2max-schatting is het 7-daagse gemiddelde.

De schatting is ontwikkeld bij getrainde volwassenen. Een VO2max van het horloge of een inspanningstest is nauwkeuriger; de app toont beide.

Leeftijd en geslacht komen uit het profiel. Zonder geboortedatum vraagt de app die in te vullen.

## Training

De functie `training(p_today)` (migraties `20261125083904_workouts.sql` en `20261125084109_workouts_dedupe.sql`) levert het tabblad Training, en komt mee in `app_state`.

**Welke trainingen meetellen** (Profiel → Koppelingen → Trainingen; migratie `20261125093517_training_filter.sql`).
- `profiles.training_source`:
  - `auto` (standaard): Google Health als dat gekoppeld is, anders Health Connect;
  - `google_health`: alleen trainingen die Google Health van een horloge heeft. Trainingen die Google Health zelf uit Health Connect overneemt (`origin = 'HEALTH_CONNECT'`) tellen niet mee;
  - `health_connect`: alleen trainingen uit Health Connect;
  - `all`: alles.
- `profiles.training_own_only`: trainingen met vastlegmethode `passive` (automatisch herkend) tellen niet mee.
- De vastlegmethode (`workouts.recording`) komt uit Google Health (`dataSource.recordingMethod`: `ACTIVELY_MEASURED`, `PASSIVELY_MEASURED`, `MANUAL`, `DERIVED`, `UNKNOWN`; zonder methode telt een START-gebeurtenis als zelf gestart) en uit Health Connect (`metadata.recordingMethod`).
- Wat je zelf logt (`manual`) of importeert (`file`) telt altijd mee.
- Alles blijft opgeslagen; het filter zit in `training()` en `workouts_before()` (`workout_counts`), dus omschakelen werkt direct. `training()` geeft ook terug hoeveel trainingen de instelling de laatste 90 dagen verbergt.
- Waarom: Google Fit legt elke korte wandeling vast. Die komen via Health Connect binnen én via Google Health, met een andere begintijd, zodat ontdubbelen ze niet vangt.

**Dubbele trainingen.** Dezelfde training komt vaak uit twee bronnen, bijvoorbeeld Health Connect én Google Health.
- Twee trainingen gelden als dezelfde als ze binnen 5 minuten van elkaar beginnen en hun duur hooguit 25% verschilt (minstens 3 minuten).
- Dan blijft er één over, in deze volgorde: bestand, zelf gelogd, Google Health, Health Connect. Een bestand van je horloge is meestal het meest volledig.
- De andere blijft opgeslagen maar telt nergens mee (`workout_hidden`). Het ontdubbelen kijkt alleen naar trainingen die volgens de instelling meetellen: een verborgen training drukt geen zichtbare weg.

**Belasting per training (TRIMP, Banister).**
- `x = (gemiddelde hartslag − rusthartslag) / (maximale hartslag − rusthartslag)`, begrensd tussen 0 en 1.
- `TRIMP = minuten × x × k × e^(b·x)`.
  - Man: k = 0,64; b = 1,92.
  - Vrouw: k = 0,86; b = 1,67.
  - Anders of onbekend: het gemiddelde van beide uitkomsten.
- Rusthartslag: gemiddelde van de laatste 30 dagen (dezelfde bronkeuze als de tegel).
- Maximale hartslag: 208 − 0,7 × leeftijd (Tanaka), dus alleen met een geboortedatum.
- Zonder gemiddelde hartslag, of als maximaal en rust minder dan 20 slagen verschillen, is er geen belasting.
  - Health Connect-trainingen hebben geen hartslag: daarvoor is een extra toestemming nodig (`READ_HEART_RATE`), die de app nu niet vraagt.

**Deze week, gewend en vorm (Banister).**
- Belasting per dag over 180 dagen, als exponentieel voortschrijdend gemiddelde.
  - Deze week (acute belasting, ATL): tijdconstante 7 dagen.
  - Gewend (chronische belasting, CTL): tijdconstante 42 dagen.
  - Vorm = gewend − deze week.
- Oordeel:
  - vorm > 5: uitgerust;
  - −10 tot 5: in balans;
  - −30 tot −10: flink aan het trainen;
  - lager: zwaar belast.
- Met minder dan 42 dagen aan trainingen toont de app dat de waarden nog niet betrouwbaar zijn.

**Week en weken.** Aantal, minuten, afstand, calorieën en belasting van de laatste 7 dagen, en minuten per week voor de laatste 8 weken (maandag tot zondag).

**Bestanden** (alleen op de telefoon gelezen, `WorkoutFiles.kt`; de preview doet hetzelfde in JavaScript).
- FIT: sessieberichten (18) leveren sport, start, duur, afstand, calorieën, hartslag en hoogtemeters.
  - Ontbreekt de hartslag in de sessie, dan berekenen we die uit de meetpunten (20).
  - De lokale dag komt uit het activiteitsbericht (34).
- JSON: de export van Vital Connect, Strava, Garmin Connect (ook de gegevensexport), Google Fit (Takeout) en de Google Health API.
- Een stabiele sleutel per bestand (SHA-1) of per activiteit-id zorgt dat opnieuw importeren bijwerkt in plaats van verdubbelt.

## Bronnen

- Plews DJ e.a. (2012). Heart rate variability in elite triathletes: is variation in variability the key to effective training? *Eur J Appl Physiol*.
- Plews DJ e.a. (2013). Training adaptation and heart rate variability in elite endurance athletes. *Sports Med*.
- Plews DJ e.a. (2014). Monitoring training with heart rate variability: how much compliance is needed for valid assessment? *Int J Sports Physiol Perform*.
- Australian Institute of Sport. Heart rate variability: best practice guidelines.
- Tanaka H, Monahan KD, Seals DR (2001). Age-predicted maximal heart rate revisited. *J Am Coll Cardiol* 37:153–156.
- Uth N e.a. (2004). Estimation of VO2max from the ratio between HRmax and HRrest – the Heart Rate Ratio Method. *Eur J Appl Physiol* 91:111–115.
- Banister EW (1991). Modeling elite athletic performance. In: *Physiological Testing of Elite Athletes*, Human Kinetics.
- Morton RH, Fitz-Clarke JR, Banister EW (1990). Modeling human performance in running. *J Appl Physiol* 69:1171–1177.
- Uth N (2005). Gender difference in the proportionality factor between the mass specific VO2max and the ratio between HRmax and HRrest. *Int J Sports Med*.
