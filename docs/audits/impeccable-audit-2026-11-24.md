# Impeccable audit · Vital Connect 1.4.0

24 november 2026 · Android-app (Jetpack Compose), plus de preview en de website als bijvangst.

Een technische audit van de broncode, volgens het Impeccable-draaiboek voor native apps (`audit.native.md`) en de Android-richtlijnen van de skill (Material 3). Er is niets aangepast; dit is een lijst om mee aan de slag te gaan.

**Bronnen van bewijs:** de Kotlin-code, contrastberekeningen op de kleuren uit `Theme.kt` en de rule-based detector van Impeccable op de preview en de website. Er is **geen emulator of toestel** gebruikt: TalkBack, lettergrootte 130–200% en het toetsenbord zijn alleen in de code beoordeeld. Test die punten ook op een echt toestel.

## Gezondheidsscore

| # | Onderdeel | Score | Belangrijkste bevinding |
|---|-----------|-------|-------------------------|
| 1 | Toegankelijkheid | 2 | Schakelaars, keuzeknoppen en de grafiek zijn niet goed te gebruiken met TalkBack |
| 2 | Prestaties | 3 | Zes netwerkverzoeken na elkaar bij het opstarten; APK van 27 MB zonder verkleining |
| 3 | Uiterlijk en thema | 3 | Eigen palet voor licht en donker; enkele vaste kleuren buiten het thema |
| 4 | Android-conformiteit | 2 | Eigen knoppen in plaats van Material-chips; tikvlakken van 44 in plaats van 48 dp; geen top app bar |
| 5 | Aanpasbaarheid aan schermen | 3 | Goed op telefoons en foldables; het toetsenbord kan invulvelden bedekken |
| **Totaal** | | **13/20** | **Acceptabel: flink wat werk nodig** |

## Oordeel: voelt het als een echte Android-app?

**Grotendeels wel, met merkbare plekken waar het niet zo is.**

Goed:
- Onderaan een navigatiebalk, op brede schermen een navigatierail.
- De Terug-knop van het systeem werkt overal.
- Edge-to-edge met de juiste ruimte voor de statusbalk.
- Echte Material-schakelaars, -knoppen, -dialogen en -snackbars.

Minder goed:
- **Eigen keuzeknoppen (Pill)** in plaats van Material FilterChip of SegmentedButton. Ze zien eruit als webknoppen, zijn 44 dp hoog in plaats van 48 dp en vertellen TalkBack niet welke optie gekozen is.
- **Eigen schermkoppen** met een losse terugpijl in plaats van een Material top app bar. Daardoor ontbreekt ook het meescrollen van de kop.
- **Inloggen met Google** is een gewone knop met tekst. Google vraagt voor "Sign in with Google" een vaste knopstijl met het G-logo. Dat kan meewegen bij de merkverificatie.

## Samenvatting

- Score **13/20** (Acceptabel)
- **Aantal bevindingen:** 0 × P0, 5 × P1, 7 × P2, 3 × P3
- **Belangrijkste punten:**
  1. Schakelaars in Aanpassen en Meldingen hebben voor TalkBack geen naam.
  2. Keuzeknoppen melden niet welke optie gekozen is.
  3. De grafiek in het detailscherm is alleen met tikken en vegen te bedienen, en geeft geen beschrijving.
  4. Het toetsenbord kan de invulvelden voor lengte en gewicht bedekken.
  5. De tikvlakken zijn kleiner dan de 48 dp die Android vraagt.
- **Volgende stappen:** eerst `/impeccable harden` voor toegankelijkheid en toetsenbord, dan `/impeccable adapt` voor de Material-onderdelen en tikvlakken, en tot slot `/impeccable polish`.

## Bevindingen per ernst

### P1 · Groot (oplossen vóór de Play Store-release)

**[P1] Schakelaars zonder naam voor TalkBack**
- **Waar:** `EditScreen.kt` (`ToggleRow`, `EditRow`), `ProfileScreen.kt` (`SwitchRow`)
- **Soort:** Toegankelijkheid
- **Gevolg:** TalkBack zegt alleen "schakelaar, aan". Wie niet kan zien, weet niet wat hij aanzet (Begroeting, Tot nu toe, Doel gehaald, elke metric in Gezondheid).
- **Richtlijn:** Material/WCAG 4.1.2 (naam, rol, waarde).
- **Aanpak:** maak de hele rij één bedienbaar element met `Modifier.toggleable(value, role = Role.Switch, onValueChange)`, en geef de `Switch` zelf `onCheckedChange = null`. Dan wordt de rij voorgelezen als "Je voornaam, schakelaar, aan", en is meteen de hele rij aantikbaar.
- **Commando:** `/impeccable harden`

**[P1] Keuzeknoppen melden hun keuze niet**
- **Waar:** `Common.kt` → `Pill` (gebruikt voor periode, interval, weergave, slaapdoel, bron, geslacht)
- **Soort:** Toegankelijkheid
- **Gevolg:** TalkBack leest "30 min, knop" zonder te zeggen of die gekozen is.
- **Aanpak:** gebruik Material `FilterChip` of `SegmentedButton`, of voeg in Pill `Modifier.selectable(selected, role = Role.RadioButton)` toe en zet de groep in `Modifier.selectableGroup()`.
- **Commando:** `/impeccable harden`

**[P1] Grafiek niet toegankelijk**
- **Waar:** `Charts.kt` → `TrendChart` (detailscherm), ook de 14-nachtengrafiek op Slaap
- **Soort:** Toegankelijkheid
- **Gevolg:** een dag kies je alleen door te tikken of te vegen op een Canvas. Met TalkBack of een toetsenbord kun je geen dag kiezen en hoor je niets over het verloop.
- **Aanpak:**
  - Geef de grafiek een samenvatting via `semantics { contentDescription = "Stappen, laatste 30 dagen: gemiddeld 8.420, hoogste 14.210 op 12 november" }`.
  - Voeg `customActions` "Vorige dag" en "Volgende dag" toe, of zet onder de grafiek twee kleine knoppen.
- **Commando:** `/impeccable harden`

**[P1] Toetsenbord kan invulvelden bedekken**
- **Waar:** `ProfileScreen.kt` (lengte, gewicht), `OnboardingProfileScreen`, inloggen met e-mail, dagdoel in `EditScreen.kt`
- **Soort:** Aanpasbaarheid
- **Gevolg:** de app draait edge-to-edge, maar er is geen `imePadding()` en geen `windowSoftInputMode`. Op Android 15 en hoger schuift het scherm dan niet mee, waardoor het veld onder het toetsenbord kan verdwijnen.
- **Richtlijn:** Android: pas IME-insets toe.
- **Aanpak:** zet `android:windowSoftInputMode="adjustResize"` op MainActivity en voeg `Modifier.imePadding()` toe aan de lijsten met invulvelden. Compose scrollt het gekozen veld dan zelf in beeld.
- **Commando:** `/impeccable adapt`

**[P1] Tikvlakken kleiner dan 48 dp**
- **Waar:**
  - `Common.kt` → `Pill` (44 dp) en `IconLabel` ("Aanpassen", 44 dp)
  - `ProfileScreen.kt` → "Demo"-link en Privacyverklaring-link: Material-TextButton, dus wel 48 dp
- **Soort:** Platformconformiteit en toegankelijkheid
- **Gevolg:** vaker mis tikken, vooral op het buitenscherm van een klaptelefoon.
- **Richtlijn:** Material: minimaal 48 × 48 dp, met 8 dp ertussen.
- **Aanpak:** voeg `Modifier.minimumInteractiveComponentSize()` toe, of zet de minimale hoogte op 48 dp. Met Material-chips is dit vanzelf goed.
- **Commando:** `/impeccable adapt`

### P2 · Klein (volgende ronde)

**[P2] Eigen keuzeknoppen in plaats van Material-onderdelen**
- **Waar:** `Pill` op ongeveer 12 plekken
- **Soort:** Platformconformiteit
- **Gevolg:** ze voelen als webknoppen. Daarnaast zijn de randen van niet-gekozen knoppen bijna onzichtbaar: `line` op `bg` heeft een contrast van 1,17:1, terwijl WCAG 1.4.11 3:1 vraagt.
- **Aanpak:** gebruik `SingleChoiceSegmentedButtonRow` voor 2–4 opties (Profiel/Koppelingen, weergave, geslacht, grootte) en `FilterChip` voor de rest (interval, periode, slaapdoel, bron, hersteltegels). Dat lost ook de twee P1's hierboven deels op.
- **Commando:** `/impeccable adapt`

**[P2] Geen top app bar**
- **Waar:** Profiel, Aanpassen, het detailscherm
- **Soort:** Platformconformiteit
- **Aanpak:** gebruik een `TopAppBar` met navigatie-icoon en titel, en op lange schermen `enterAlwaysScrollBehavior`. Zet de knop "Klaar" in Aanpassen als actie rechts in de balk.
- **Commando:** `/impeccable adapt`

**[P2] Knop "Doorgaan met Google" volgt de stijl van Google niet**
- **Waar:** `AuthScreens.kt` → `LoginScreen`
- **Soort:** Platformconformiteit
- **Gevolg:** Google stelt eisen aan de vorm van de inlogknop (G-logo, vaste kleuren en tekst). Bij de merkverificatie kan daarom gevraagd worden.
- **Aanpak:** gebruik de officiële knop van Credential Manager / Google Identity, of bouw hem precies na volgens de branding-richtlijnen van Google.
- **Commando:** `/impeccable polish`

**[P2] Tekst kan bij grote letters afgekapt worden**
- **Waar:**
  - `TodayScreen.kt`: waarde in de tegel, met `maxLines = 1` en zonder ellipsis
  - `Common.kt`: `Pill`, met `maxLines = 1` en zonder ellipsis
  - `DialHeader`: legenda naast de ringen
- **Soort:** Toegankelijkheid
- **Gevolg:** bij een lettergrootte van 150–200% vallen getallen als "12.357" of labels als "Handmatig" weg zonder dat je het ziet.
- **Aanpak:**
  - Zet onder de ringen een kolom in plaats van naast de ringen als `fontScale > 1.3`.
  - Laat waarden krimpen met `autoSize` (Compose 1.8) of geef ze een ellipsis.
  - Test met `adb shell settings put system font_scale 2.0`.
- **Commando:** `/impeccable harden`

**[P2] Opstarten wacht op zes verzoeken na elkaar**
- **Waar:** `MainViewModel.loadNow()`: metrics → metric_prefs → profiles → connections → resolved_daily → metric_stats
- **Soort:** Prestaties
- **Gevolg:** op mobiel netwerk is dat 6 × ~150–300 ms voordat Vandaag gevuld is.
- **Aanpak:**
  - Haal ze tegelijk op met `coroutineScope { async { … } }`.
  - Bewaar `metrics`, de vaste lijst, lokaal.
  - Toon Vandaag zodra resolved_daily binnen is en laad metric_stats daarna.
- **Commando:** `/impeccable optimize`

**[P2] APK van 27 MB, R8 staat uit**
- **Waar:** `build.gradle.kts` → `isMinifyEnabled = false`
- **Soort:** Prestaties
- **Gevolg:** grotere download en iets tragere start.
- **Aanpak:** zet `isMinifyEnabled = true` en `isShrinkResources = true` aan, met keep-regels voor kotlinx-serialization en Health Connect. Dat bespaart naar verwachting 50–70%. Test daarna een release-build.
- **Commando:** `/impeccable optimize`

**[P2] Kopjes niet als kop gemarkeerd**
- **Waar:** `SectionTitle`, schermtitels (Gezondheid, Slaap, Aanpassen)
- **Soort:** Toegankelijkheid
- **Gevolg:** TalkBack-gebruikers kunnen niet van kop naar kop springen.
- **Aanpak:** `Modifier.semantics { heading() }` op de titels.
- **Commando:** `/impeccable harden`

### P3 · Afwerking

**[P3] Vaste merkkleuren van de bronnen buiten het thema**
- **Waar:** `ProfileScreen.kt` (Health Connect `#2F7D6E`, Google Health `#3F7F4A`, Garmin `#2B5FA8`)
- **Aanpak:** zet ze als `sourceHealthConnect`, `sourceGoogle` en `sourceGarmin` in `Palette`, met een donkere variant.
- **Commando:** `/impeccable polish`

**[P3] Oranje en oker grafieklijnen net onder 3:1 op wit**
- **Waar:** `Theme.kt`: activiteit `#E4793B` heeft 2,96:1, lichaam `#B9892B` heeft 3,15:1 (licht thema)
- **Gevolg:** minigrafiekjes en voortgangsbalken van Activiteit zijn op wit net minder goed te zien.
- **Aanpak:** maak activiteit iets donkerder (bijvoorbeeld `#D86F31`), of geef de lijn een donkerdere rand. Houd de preview gelijk.
- **Commando:** `/impeccable colorize`

**[P3] Preview gebruikt Inter, de app Roboto**
- **Waar:** `prototype/index.html`, `site/*.html`; de detector meldt "overused font"
- **Gevolg:** de preview lijkt net niet op de app. Inter en Roboto zijn bovendien de standaardkeuzes van veel AI-ontwerpen.
- **Aanpak:** kies één eigen letter met karakter voor koppen en cijfers en zet die in app, preview en website. Een bijpassende keuze: een rustige humanistische sans, zoals Figtree of Atkinson Hyperlegible voor leesbaarheid.
- **Commando:** `/impeccable typeset`

## Terugkerende patronen

- **Eigen onderdelen waar Material er een heeft.** Pill, IconLabel en de schermkoppen veroorzaken samen de meeste conformiteits- en toegankelijkheidsproblemen. Eén vervanging, door FilterChip, SegmentedButton en TopAppBar, lost een groot deel in één keer op.
- **Semantiek ontbreekt op eigen bouwwerk.** Overal waar de app iets zelf tekent (Canvas-grafieken, ringen, eigen knoppen) ontbreekt informatie voor TalkBack. Waar Material-onderdelen gebruikt worden, is het goed.

## Wat goed gaat

- **Navigatie per schermformaat:** onderaan op telefoons, aan de zijkant op foldables en tablets, met 1–4 kolommen tegels en een aparte indeling voor heel smalle schermen.
- **Terug werkt overal** via `BackHandler`. Er wordt geen gebaar gekaapt.
- **Licht en donker zijn allebei echt ontworpen,** met een eigen palet. Alle tekst haalt minimaal 4,5:1:
  - grijze tekst 6,1–8,1:1;
  - groen en rood 4,9–7,8:1;
  - knoppen 9,5:1.
- **Type in sp** met een vaste typeschaal en cijfers met vaste breedte. Tekstgrootte van het systeem wordt gevolgd.
- **LazyColumn** voor alle lange lijsten.
- **Status blijft bewaard** bij draaien en in- en uitvouwen: `configChanges` en `rememberSaveable`.
- **Duidelijke, rustige taal** in de hele app, met uitleg op de plekken waar dat nodig is: toestemming, koppelen, meldingen.

## Aanbevolen volgorde

1. **[P1] `/impeccable harden`**: TalkBack-namen voor schakelaars, de gekozen stand bij keuzeknoppen, een beschrijving en bediening voor de grafiek, kopjes als kop markeren en lange tekst bij grote letters.
2. **[P1] `/impeccable adapt`**: toetsenbord (`imePadding`, `adjustResize`), tikvlakken van 48 dp, Pill vervangen door FilterChip en SegmentedButton, en TopAppBar op Profiel, Aanpassen en het detailscherm.
3. **[P2] `/impeccable optimize`**: verzoeken bij het opstarten tegelijk, R8 en kleiner maken van de resources aanzetten.
4. **[P3] `/impeccable typeset`**: één eigen letter voor app, preview en website.
5. **[P3] `/impeccable colorize`**: oranje van Activiteit iets donkerder en bronkleuren in het thema.
6. **`/impeccable polish`**: de Google-inlogknop volgens de richtlijnen en een laatste ronde.

Draai `/impeccable audit` opnieuw na de aanpassingen om de score te zien stijgen.

---

# Herhaalde audit na versie 1.5.0

Opnieuw beoordeeld vanuit de broncode na alle aanbevelingen hierboven. Ook nu zonder emulator of toestel: test TalkBack, lettergrootte 200% en het toetsenbord nog op een echte telefoon.

| # | Onderdeel | Voor | Na | Wat er veranderde |
|---|-----------|------|----|-------------------|
| 1 | Toegankelijkheid | 2 | 3 | Schakelrijen met naam en rol, chips met geselecteerde stand, grafiek met samenvatting en acties, kopjes, voorgelezen ringen en verschillen; grote letters zonder afkappen |
| 2 | Prestaties | 3 | 4 | Opstartverzoeken tegelijk (samen 182 ms op de database bij 5 jaar data); APK van 26,1 naar 4,0 MB met R8 |
| 3 | Uiterlijk en thema | 3 | 4 | Bronkleuren in het thema met donkere variant; randen van bediening 3:1; abrikoos en oker 3,4 en 3,6:1 op wit |
| 4 | Android-conformiteit | 2 | 3 | FilterChip, AssistChip, SegmentedButton en TopAppBar; tikvlakken 48 dp; Google-knop in de kleuren van Google |
| 5 | Aanpasbaarheid aan schermen | 3 | 3 | Toetsenbord opgelost (adjustResize, imePadding) |
| **Totaal** | | **13** | **17/20** | **Goed** |

## Wat er nog openstaat

- **[P2] G-logo op de Google-knop.** De knop heeft de kleuren en vorm uit de richtlijnen van Google, maar nog geen logo. Download het officiële pakket via developers.google.com/identity/branding-guidelines en zet het G-logo als `res/drawable/google_g.xml` in de app. De knop toont het dan vanzelf.
- **[P2] Testen op een toestel.** Dit zijn de eerste builds met R8. Test daarom inloggen met Google, Health Connect en de achtergrondsynchronisatie op een echte telefoon. Controleer ook TalkBack en een lettergrootte van 200%.
- **[P3] Foldable-houding.** De app reageert nog niet op half opengevouwen ("tafelstand"). Dat kan met Jetpack WindowManager: bijvoorbeeld de grafiek boven de vouw en de cijfers eronder.
