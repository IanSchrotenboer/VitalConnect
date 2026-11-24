# Vital Connect in de Google Play Store

Alles wat nodig is om Vital Connect in de Play Store te zetten, op dezelfde manier als BOA Paraat.
De app zelf is klaar: ondertekende AAB, targetSdk 36, eigen icoon, privacyverklaring en accountverwijdering in de app.

| Map / bestand | Inhoud |
|---|---|
| `fastlane/metadata/android/nl-NL/` | Titel, korte en volledige beschrijving (in de structuur die fastlane en Play Console gebruiken) |
| `grafisch/icoon-512.png` | App-icoon voor de vermelding (512 × 512) |
| `grafisch/feature-graphic-1024x500.png` | Feature graphic |
| `grafisch/telefoon-*.png` | Vijf telefoon-screenshots (1080 × 2106) |
| `../release-notities/v<versie>.md` | Tekst bij elke release, ook bruikbaar als "Wat is er nieuw" |

## Stap 1. Vaste uploadsleutel (eenmalig, belangrijk)

Zonder vaste sleutel ondertekent GitHub met een tijdelijke sleutel. Die AAB neemt Google Play niet aan als update.

1. Maak op je computer een sleutel (Java of Android Studio nodig):
   ```bash
   keytool -genkeypair -keystore vitalconnect-upload.keystore -storetype PKCS12 \
     -alias vitalconnect -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Vital Connect, O=Ian Schrotenboer, C=NL"
   base64 -w0 vitalconnect-upload.keystore > keystore-base64.txt     # macOS: base64 -i vitalconnect-upload.keystore -o keystore-base64.txt
   ```
2. Zet in GitHub (**Settings → Secrets and variables → Actions → New repository secret**) dezelfde drie geheimen als bij BOA Paraat:

   | Naam | Waarde |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | de hele inhoud van `keystore-base64.txt` |
   | `ANDROID_KEYSTORE_PASSWORD` | het wachtwoord dat je bij stap 1 koos |
   | `ANDROID_KEY_ALIAS` | `vitalconnect` |

3. Start in **Actions → Android-app bouwen** een nieuwe run (**Run workflow**). In de samenvatting staat dan de **SHA-1** van de sleutel.
4. Bewaar het sleutelbestand en het wachtwoord in je wachtwoordkluis. Verwijder `keystore-base64.txt`.

Daarna maakt elke push naar `main` een ondertekende `VitalConnect.apk` en `VitalConnect.aab`. Bij een nieuw versienummer komt er ook een GitHub Release.

## Stap 2. Website aanzetten (eenmalig)

De privacyverklaring en de pagina voor accountverwijdering staan in `site/`. Zet ze online:
**Settings → Pages → Source: GitHub Actions.** De taak **Website publiceren** zet ze dan op:

- https://app.vital-connect.nl/privacy.html
- https://app.vital-connect.nl/verwijderen.html

Eigen domein, net als bij BOA Paraat: bij STRATO het subdomein `app` met een CNAME naar `ianschrotenboer.github.io`,
en in **Settings → Pages → Custom domain** `app.vital-connect.nl` met **Enforce HTTPS**.

Vul de privacy-URL ook in bij Google Cloud: **Google Auth Platform → Branding → Privacy policy link**.

## Stap 2b. Branding in Google Cloud (Google Auth Platform → Branding)

| Veld | Waarde |
|---|---|
| App name | `Vital Connect` |
| User support email | `ian.schrotenboer@proton.me` |
| App logo | `brand/logo-120.png` (120 × 120) |
| Application home page | `https://app.vital-connect.nl/` |
| Application privacy policy link | `https://app.vital-connect.nl/privacy.html` |
| Application terms of service link | `https://app.vital-connect.nl/voorwaarden.html` |
| Authorized domains | `vital-connect.nl` (de site en de doorstuurpagina voor Google Health staan op app.vital-connect.nl) |
| Developer contact information | `ian.schrotenboer@proton.me` |

Daarna **Audience → Publish app**. Met een logo vraagt Google om merkverificatie; de naam "Vital Connect" verschijnt op het toestemmingsscherm zodra die rond is. Daarvoor moet je het domein zelf bezitten: voeg `vital-connect.nl` toe in Google Search Console (DNS-record TXT bij STRATO) met hetzelfde Google-account.

**Redirect-URI (Clients → de webclient → Authorized redirect URIs):** `https://app.vital-connect.nl/koppelen/google/`.
Die pagina (`site/koppelen/google/index.html`) stuurt de code en state door naar de edge function
`google-health-auth-callback`. Het geheim `GOOGLE_REDIRECT_URI` in Supabase staat op dit adres (sinds 24 nov 2026,
getest met een echte koppeling); de functie `health-check` toont welk adres in gebruik is. Het oude adres op
supabase.co en het domein `supabase.co` zijn uit Google Cloud gehaald.

## Stap 3. Inloggen met Google voor de release-versie

De Play Store ondertekent de app opnieuw met een eigen sleutel (Play App Signing). Voeg daarom in Google Cloud
(**Clients → Create client → Android**, package `nl.vitaal.app`) een Android-client toe voor:

1. de SHA-1 van je **uploadsleutel** (stap 1.3), voor de APK uit GitHub;
2. de SHA-1 van de **app-ondertekeningssleutel** uit Play Console (**Test en release → App-integriteit → App-ondertekening**), na de eerste upload.

## Stap 4. App aanmaken in Play Console

1. Ga naar play.google.com/console en kies **App maken**: naam *Vital Connect*, standaardtaal **Nederlands**, type **App**, **Gratis**.
2. **Test en release → Interne test → Nieuwe release:** upload `VitalConnect.aab` uit de laatste GitHub-build. Kies bij de vraag over app-ondertekening voor **Play App Signing** (aanbevolen). Releasenotities: de tekst uit `release-notities/v1.0.0.md`.
3. **Winkelvermelding:** neem titel en beschrijvingen over uit `fastlane/metadata/android/nl-NL/`, upload icoon, feature graphic en screenshots uit `grafisch/`. Categorie: **Gezondheid en fitness**. Contact-e-mail en de website.

Let op: is je ontwikkelaarsaccount een **persoonlijk account** dat na november 2023 is aangemaakt, dan vraagt Google eerst een **gesloten test met minimaal 12 testers gedurende 14 dagen** voordat je naar productie mag.

## Stap 5. Formulieren in Play Console (App-content)

### Privacybeleid
`https://app.vital-connect.nl/privacy.html`

### App-toegang
De app vraagt om in te loggen. Kies **Alle of een deel van de functies is beperkt** en geef een demo-account
(e-mail en wachtwoord) met voorbeelddata, plus deze toelichting:
> Log in met het demo-account. Het dashboard toont voorbeelddata. Koppelingen met Health Connect, Google Health en Garmin zijn optioneel.

### Advertenties
Nee, de app bevat geen advertenties.

### Contentclassificatie (vragenlijst IARC)
Categorie **Hulpprogramma, productiviteit, communicatie of overig**. Op alle vragen over geweld, seksualiteit, taal, drugs, gokken en
interactie tussen gebruikers: **Nee**. Gebruikers kunnen geen content delen met elkaar. Verwacht resultaat: PEGI 3 / Iedereen.

### Doelgroep
**18 jaar en ouder** (de app verwerkt gezondheidsgegevens; zo valt hij niet onder het Families-beleid). Niet aantrekkelijk voor kinderen.

### Gezondheidsapps
Bij **Gezondheidsapps**: kies **Activiteit en fitness** en **Slaap**. De app is **geen medisch hulpmiddel** en geeft geen diagnoses.

### Gegevensveiligheid (Data safety)

| Vraag | Antwoord |
|---|---|
| Verzamelt of deelt de app gebruikersgegevens? | Ja, verzameld; niet gedeeld |
| Versleuteld tijdens overdracht? | Ja (HTTPS) |
| Kunnen gebruikers verwijdering aanvragen? | Ja, in de app en via https://app.vital-connect.nl/verwijderen.html |

| Gegevenstype | Verzameld | Gedeeld | Verplicht | Doel |
|---|---|---|---|---|
| Persoonlijke info → E-mailadres | Ja | Nee | Ja | Accountbeheer |
| Persoonlijke info → Naam | Ja (bij inloggen met Google of zelf ingevuld in het profiel) | Nee | Nee | Accountbeheer, App-functionaliteit |
| Persoonlijke info → Andere info (geboortedatum, geslacht) | Ja, zelf ingevuld | Nee | Nee | App-functionaliteit |
| Gezondheid en fitness → Gezondheidsinformatie (rusthartslag, HRV, SpO2, ademhaling, gewicht, lengte, VO2max, slaap) | Ja | Nee | Nee | App-functionaliteit |
| Gezondheid en fitness → Fitnessinformatie (stappen, afstand, calorieën, verdiepingen, actieve minuten) | Ja | Nee | Nee | App-functionaliteit |

Geen locatie, contacten, foto's, berichten, financiële gegevens, apparaat-ID's of app-activiteit voor analyse.
Bij "Gedeeld": dienstverleners die namens jou verwerken (Supabase, Resend) tellen volgens Google niet als delen.

### Health Connect-rechten (verklaring)
Play Console vraagt per recht waarom de app het nodig heeft. Gebruik:

| Recht | Waarom |
|---|---|
| READ_STEPS, READ_DISTANCE, READ_ACTIVE_CALORIES_BURNED, READ_FLOORS_CLIMBED, READ_EXERCISE | Dagtotalen tonen op het dashboard (tegels, dagringen, grafieken, statistieken) |
| READ_RESTING_HEART_RATE, READ_HEART_RATE_VARIABILITY, READ_OXYGEN_SATURATION, READ_RESPIRATORY_RATE | Hart en herstel tonen, ook in het tabblad Slaap |
| READ_SLEEP | Slaapduur per nacht en het tabblad Slaap |
| READ_WEIGHT, READ_VO2_MAX | Lichaamsmetingen en hun verloop tonen |
| READ_HEALTH_DATA_IN_BACKGROUND | Het dashboard elk uur bijwerken, ook als de app gesloten is; de gebruiker kan dit weigeren |
| READ_HEALTH_DATA_HISTORY | Bij het koppelen tot een jaar terug ophalen, zodat Gezondheid en de grafieken direct gevuld zijn |

De app schrijft niets naar Health Connect. Het scherm met uitleg (vereist door Health Connect) staat in de app en linkt naar de privacyverklaring.

### Google Health API (Google Cloud)
De gezondheidsscopes zijn **beperkt**. Voor meer dan 100 gebruikers vraagt Google een verificatie van het toestemmingsscherm en een
beveiligingsbeoordeling door een externe partij. Tot die tijd: alleen testgebruikers (Google Auth Platform → Audience).

## Stap 6. Elke volgende versie

1. Verhoog `versionName` in `android/app/build.gradle.kts` en schrijf `release-notities/v<versie>.md`.
2. Push naar `main`. GitHub maakt een release met `VitalConnect.aab`.
3. Upload die AAB in Play Console als nieuwe release. Het versionCode telt automatisch op.
4. Werk de preview in Claude bij (zie `CLAUDE.md`).
