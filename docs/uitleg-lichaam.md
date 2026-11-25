# Uitleg per tegel: wat zegt het over je lichaam?

Bij elke metric, bij Herstel vandaag, bij Trainingsbelasting en bij elke sport staat een uitklapbare uitleg in gewone taal (taalniveau B1).
De teksten staan op één plek per platform en zijn gelijk:
- app: `android/.../BodyInfo.kt` (`BODY_INFO`, `SPORT_INFO`);
- preview: `BODY` en `SPORT_INFO` in `prototype/index.html`.

Pas je een tekst aan, doe het dan op beide plekken.

## Uitgangspunten

- Kort en concreet: wat het is, wat het met je lichaam doet, wat normaal is en wat je er zelf aan kunt doen.
- Geen diagnoses. Onder elke uitleg staat: "Geen medisch advies. Twijfel je, vraag het je huisarts."
- Standaard ingeklapt, zodat wie het niet wil lezen er geen last van heeft.
- Normaalwaarden zijn richtwaarden voor gezonde volwassenen; de app oordeelt verder steeds tegen je eigen gemiddelde.

## Richtwaarden en bronnen

| Metric | Richtwaarde in de tekst | Bron |
|---|---|---|
| Actieve minuten | 150 minuten per week matig intensief, verspreid over de week | Gezondheidsraad, Beweegrichtlijnen 2017 |
| Stappen | 8.000–10.000 per dag; vanaf 60 jaar 6.000–8.000 | Paluch AE e.a. (2022), *Lancet Public Health* 7:e219–e228 |
| Rusthartslag | 60–100 slagen per minuut; sporters vaak lager | American Heart Association |
| Zuurstofsaturatie | 95–100% | Thuisarts / NHG |
| Ademhaling in rust | 12–20 per minuut | Thuisarts / NHG |
| Slaap | 7–9 uur; vanaf 65 jaar 7–8 uur | Hirshkowitz M e.a. (2015), *Sleep Health* 1:40–43 (National Sleep Foundation) |
| HRV, herstel | Alleen tegen je eigen normale bereik, geen vaste waarde | Plews e.a.; zie `docs/berekeningen.md` |
| VO2max | Uitleg zonder normtabel: hoe hoger, hoe fitter, en het daalt met de leeftijd | Uth e.a. (2004) |
| Trainingsbelasting | Deze week tegenover gewend; geen vaste grens | Banister (1991); zie `docs/berekeningen.md` |

Waarden die een bron zelf berekent (slaapscore, stress, energieniveau, hersteltijd) leggen we uit als "zo rekent je horloge het uit", zonder eigen normen.
