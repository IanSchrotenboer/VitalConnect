# Berekeningen: herstel en fitheid

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

## Nog niet

**Trainingsbelasting** (TRIMP; vermoeidheid, fitheid en vorm volgens Banister) vraagt de hartslag per minuut tijdens trainingen.
- Dat is een nieuwe toestemming in Health Connect (`READ_HEART_RATE`) met een aanpassing in de Play Store.
- Of het komt via de trainingen van Garmin of Suunto.

## Bronnen

- Plews DJ e.a. (2012). Heart rate variability in elite triathletes: is variation in variability the key to effective training? *Eur J Appl Physiol*.
- Plews DJ e.a. (2013). Training adaptation and heart rate variability in elite endurance athletes. *Sports Med*.
- Plews DJ e.a. (2014). Monitoring training with heart rate variability: how much compliance is needed for valid assessment? *Int J Sports Physiol Perform*.
- Australian Institute of Sport. Heart rate variability: best practice guidelines.
- Tanaka H, Monahan KD, Seals DR (2001). Age-predicted maximal heart rate revisited. *J Am Coll Cardiol* 37:153–156.
- Uth N e.a. (2004). Estimation of VO2max from the ratio between HRmax and HRrest – the Heart Rate Ratio Method. *Eur J Appl Physiol* 91:111–115.
- Uth N (2005). Gender difference in the proportionality factor between the mass specific VO2max and the ratio between HRmax and HRrest. *Int J Sports Med*.
