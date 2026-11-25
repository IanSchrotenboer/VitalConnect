package nl.vitaal.app

/*
 * Uitleg per tegel: wat de meting is, wat hij voor je lichaam betekent en wat gewoon is.
 * Geschreven voor iedereen (taalniveau B1); richtwaarden met bron in docs/uitleg-lichaam.md. Geen medisch advies.
 * Dezelfde teksten staan in de preview (prototype/index.html, BODY).
 */
val BODY_INFO: Map<String, List<String>> = mapOf(
    "steps" to listOf(
        "Stappen telt hoeveel stappen je per dag zet. Je telefoon of horloge meet dat aan de bewegingen van je lichaam.",
        "Lopen is de makkelijkste manier om in beweging te blijven. Het houdt je hart, bloedvaten, spieren en botten sterk, " +
            "helpt je lichaam suiker uit je bloed op te nemen en geeft je hoofd rust.",
        "Elke stap telt. Onderzoek laat zien dat het gezondheidsvoordeel groeit tot ongeveer 8.000 tot 10.000 stappen per dag; " +
            "vanaf 60 jaar al bij 6.000 tot 8.000. Wie nu weinig loopt, heeft het meeste aan een paar duizend stappen extra.",
    ),
    "active" to listOf(
        "Actieve minuten zijn de minuten waarin je zo beweegt dat je hartslag en ademhaling omhoog gaan: stevig wandelen, fietsen, sporten.",
        "Daarvan wordt je hart sterker en je conditie beter. Je bloeddruk daalt, je slaapt beter, je voelt je fitter en je hebt minder kans op hart- en vaatziekten en diabetes type 2.",
        "De Beweegrichtlijn (Gezondheidsraad) is minstens 150 minuten per week matig intensief bewegen, verspreid over meerdere dagen. " +
            "Dat is ruim 20 minuten per dag. Doe daarnaast twee keer per week iets voor je spieren en botten, zoals krachttraining of traplopen.",
    ),
    "dist" to listOf(
        "Afstand is hoeveel kilometer je lopend of rennend aflegt.",
        "Net als stappen zegt het hoeveel je beweegt. Meer afstand op een rustig tempo bouwt je uithoudingsvermogen op: " +
            "je hart, longen en spieren leren zuurstof beter te gebruiken.",
        "Wil je meer gaan lopen of hardlopen? Voer de afstand rustig op, ongeveer 10 procent per week, zodat spieren en pezen kunnen meegroeien.",
    ),
    "kcal" to listOf(
        "Actieve calorieën is de energie die je verbruikt door te bewegen, bovenop wat je lichaam in rust al verbruikt.",
        "Je lichaam haalt die energie uit koolhydraten en vet. Hoe meer en hoe zwaarder je beweegt, hoe meer je verbruikt.",
        "Het is een schatting van je horloge of telefoon en kan flink afwijken. Gebruik het om dagen met elkaar te vergelijken, niet als exact getal.",
    ),
    "floors" to listOf(
        "Verdiepingen telt hoeveel trappen je omhoog loopt. Eén verdieping is ongeveer 3 meter hoogte.",
        "Traplopen is een korte, stevige inspanning. Het traint je beenspieren en je conditie, en telt mee als spier- en botversterkende beweging.",
    ),
    "rhr" to listOf(
        "Je rusthartslag is hoe vaak je hart per minuut klopt als je helemaal stil ligt, meestal gemeten tijdens je slaap.",
        "Een getraind hart pompt per slag meer bloed rond en hoeft daarom minder vaak te kloppen. Een lagere rusthartslag past dus vaak bij een betere conditie.",
        "Bij volwassenen ligt de rusthartslag meestal tussen 60 en 100 slagen per minuut; bij sporters vaak lager, soms rond de 40 tot 60. " +
            "Ligt hij een paar dagen hoger dan je gewend bent, dan kan dat komen door vermoeidheid, stress, alcohol, slecht slapen of een opkomende verkoudheid. " +
            "Heb je klachten zoals duizeligheid of hartkloppingen, ga dan naar je huisarts.",
    ),
    "hrv" to listOf(
        "HRV (hartslagvariabiliteit) is het verschil in tijd tussen twee hartslagen. Je hart klopt niet als een klok: de tijd tussen slagen wisselt steeds een beetje.",
        "Die wisseling komt van je zenuwstelsel. Als je lichaam ontspannen is en herstelt, is de variatie groter. " +
            "Bij stress, ziekte, alcohol, weinig slaap of een zware training wordt hij kleiner.",
        "HRV is heel persoonlijk en daalt met de leeftijd. Vergelijk hem daarom alleen met je eigen normaal, niet met anderen. " +
            "Dat doet de tegel Herstel vandaag voor je.",
    ),
    "spo2" to listOf(
        "Zuurstof (SpO2) laat zien hoeveel procent van je rode bloedcellen zuurstof bij zich heeft.",
        "Rode bloedcellen brengen zuurstof van je longen naar je spieren en organen. Zonder genoeg zuurstof werken ze minder goed.",
        "Bij gezonde mensen ligt het meestal tussen 95 en 100 procent. Horloges meten minder nauwkeurig dan een meter bij de dokter. " +
            "Ben je benauwd of blijft de waarde laag, neem dan contact op met je huisarts.",
    ),
    "resp" to listOf(
        "Ademhaling telt hoe vaak je per minuut in- en uitademt, gemeten tijdens je slaap.",
        "Rustig ademen hoort bij een lichaam dat ontspannen is en herstelt.",
        "Bij volwassenen in rust is 12 tot 20 keer per minuut gewoon. Een plotselinge stijging kan passen bij koorts, ziekte of stress.",
    ),
    "sleep" to listOf(
        "Slaapduur is hoe lang je echt slaapt, zonder de tijd dat je wakker in bed ligt.",
        "Tijdens je slaap herstellen je spieren, slaat je brein herinneringen op, ruimt het afvalstoffen op en regelt je lichaam je hormonen en afweer. " +
            "Wie te kort slaapt, heeft minder energie, meer trek en wordt vaker ziek.",
        "Volwassenen hebben meestal 7 tot 9 uur nodig, vanaf 65 jaar 7 tot 8 uur. Vaste tijden om naar bed te gaan en op te staan helpen het meest.",
    ),
    "sscore" to listOf(
        "De slaapscore is een cijfer van je horloge dat samenvat hoe goed je sliep: hoe lang, hoe diep en hoe onrustig.",
        "Elk merk rekent het op een eigen manier uit. Kijk daarom vooral naar je eigen verloop: gaat het omhoog of omlaag?",
    ),
    "stress" to listOf(
        "Stress is een score van je horloge, berekend uit je hartslag en HRV overdag. Hoe hoger, hoe meer je lichaam 'aan' staat.",
        "Korte stress is normaal en zelfs nuttig, bijvoorbeeld tijdens sporten. Blijft het lang hoog, dan krijgt je lichaam te weinig tijd om te herstellen.",
        "Een korte wandeling, rustig ademen of even niets doen helpt om de score omlaag te krijgen.",
    ),
    "energy" to listOf(
        "Energieniveau (Body Battery van Garmin) schat hoeveel energie je lichaam nog heeft.",
        "Het laadt op als je slaapt en rust, en loopt leeg door beweging, stress en drukte. Zie het als een batterij die je zelf kunt bijladen.",
    ),
    "recov" to listOf(
        "Hersteltijd is het advies van je horloge hoeveel uur je na een training nodig hebt voordat je weer zwaar traint.",
        "Na een zware training heeft je lichaam tijd nodig om spieren te herstellen en sterker terug te komen. " +
            "Rustig bewegen mag in die tijd wel; dat helpt het herstel zelfs.",
    ),
    "vo2" to listOf(
        "VO2max is de hoeveelheid zuurstof die je lichaam per minuut per kilo kan gebruiken als je alles geeft. Het is de beste maat voor je conditie.",
        "Hoe hoger je VO2max, hoe beter je hart, longen en spieren samenwerken. Een goede conditie hangt samen met een langer en gezonder leven.",
        "Je kunt je VO2max verbeteren met duurtraining (lang en rustig) en intervaltraining (kort en zwaar). " +
            "Wat goed is, hangt af van je leeftijd en geslacht; na je 30e daalt hij langzaam, maar trainen remt dat.",
    ),
    "weight" to listOf(
        "Gewicht is je lichaamsgewicht in kilo's.",
        "Je gewicht schommelt per dag makkelijk een tot twee kilo door vocht, eten en wc-bezoek. Kijk daarom naar het verloop over weken, niet naar één dag.",
        "Weeg jezelf voor een eerlijk beeld steeds op hetzelfde moment, bijvoorbeeld 's ochtends na het opstaan.",
    ),
    "herstel" to listOf(
        "Herstel vandaag laat zien hoe goed je lichaam is bijgekomen van inspanning, stress en de dag ervoor.",
        "Als je HRV hoger is en je rusthartslag lager dan je gewend bent, is je lichaam in de herstelstand. Dan kan het een stevige training aan. " +
            "Zijn ze andersom, dan is je lichaam nog bezig met herstellen, of met iets anders zoals een verkoudheid of stress.",
        "Luister naar je lichaam: de score is een hulpmiddel, geen opdracht.",
    ),
    "load" to listOf(
        "Trainingsbelasting zegt hoe zwaar een training voor je lichaam was. Een uur rustig wandelen telt weinig, een uur hard rennen telt veel.",
        "Je lichaam wordt sterker in de rust na een training. Train je een tijd meer dan je gewend bent, dan word je fitter, " +
            "maar heb je ook meer rust nodig. Wordt het te veel ineens, dan neemt de kans op blessures en ziek worden toe.",
        "'Deze week' is je belasting van de laatste dagen, 'gewend' die van de laatste zes weken. " +
            "Vorm is het verschil: positief betekent uitgerust, negatief betekent dat je meer doet dan je gewend bent.",
    ),
)

/** Korte uitleg per sport: wat het met je lichaam doet. */
val SPORT_INFO: Map<String, String> = mapOf(
    "running" to "Hardlopen traint je hart en longen sterk en maakt je botten steviger. Begin rustig en bouw langzaam op, zodat pezen en gewrichten mee kunnen groeien.",
    "walking" to "Wandelen is zacht voor je gewrichten en goed voor je hart, je bloedsuiker en je humeur. Stevig doorwandelen telt als matig intensief bewegen.",
    "hiking" to "Een wandeltocht, zeker met hoogteverschil, traint je benen, je conditie en je balans.",
    "cycling" to "Fietsen traint je hart, longen en beenspieren en is licht voor je gewrichten.",
    "swimming" to "Zwemmen gebruikt bijna al je spieren en is heel licht voor je gewrichten. Goed voor je conditie en je ademhaling.",
    "strength" to "Krachttraining maakt je spieren en botten sterker, helpt je stofwisseling en voorkomt blessures. De Beweegrichtlijn raadt het minstens twee keer per week aan.",
    "hiit" to "Intervaltraining wisselt korte, zware stukken af met rust. In korte tijd verbetert het je conditie en je VO2max flink.",
    "yoga" to "Yoga en pilates maken je soepeler en sterker in je romp, verbeteren je balans en helpen je ontspannen.",
    "rowing" to "Roeien en peddelen trainen je hele lichaam: benen, rug, armen en je conditie tegelijk.",
    "elliptical" to "De crosstrainer en traplopen geven een stevige conditietraining die licht is voor je knieën.",
    "team" to "Teamsport combineert sprinten, draaien en samenspelen: goed voor je conditie, je reactievermogen en je humeur.",
    "racket" to "Racketsporten trainen je conditie, je snelheid en je oog-handcoördinatie.",
    "dance" to "Dansen traint je conditie, je coördinatie en je geheugen, en is vooral leuk.",
    "martial" to "Vechtsporten trainen kracht, conditie, snelheid en concentratie.",
    "winter" to "Wintersport traint je benen, je balans en je conditie.",
    "other" to "Elke beweging telt. Wie regelmatig beweegt, slaapt beter, voelt zich fitter en blijft langer gezond.",
)
