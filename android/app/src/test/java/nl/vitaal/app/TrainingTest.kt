package nl.vitaal.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Opmaak, het antwoord van training() en de melding over trainingen die niet meetellen. */
class TrainingTest {
    @Test fun duur() {
        assertEquals("30 min", fmtDuration(1800))
        assertEquals("1 min", fmtDuration(60))
        assertEquals("1u 00m", fmtDuration(3600))
        assertEquals("1u 01m", fmtDuration(3660))
        assertEquals("59 min", fmtDuration(3569))
    }

    @Test fun afstandEnTempo() {
        assertEquals("8,4 km", fmtKm(8400.0))
        assertEquals("12 km", fmtKm(12345.0))
        fun w(sport: String, dur: Int, dist: Double?) = Workout(null, "manual", "m", sport, null, LocalDate.of(2026, 1, 1),
            Instant.EPOCH, dur, dist, null, null, null, null, null, null, null)
        assertEquals("5:00 /km", fmtPace(w("running", 1500, 5000.0)))
        assertEquals("30,0 km/u", fmtPace(w("cycling", 3600, 30000.0)))
        assertEquals("2:00 /100 m", fmtPace(w("swimming", 1200, 1000.0)))
        assertNull(fmtPace(w("running", 1500, null)))
        assertNull(fmtPace(w("strength", 1500, 5000.0)))
    }

    @Test fun serverAntwoord() {
        val o = Json.parseToJsonElement("""{"workouts":[{"id":"a1","source":"google_health","external_id":"x","sport":"running",
            "title":"Run","day":"2026-11-20","started_at":"2026-11-20T06:30:00+00:00","duration_s":3600,"distance_m":10000,
            "kcal":700,"avg_hr":150,"max_hr":170,"ascent_m":null,"notes":null,"origin":"FITBIT","recording":"passive","load":95.3}],
            "week":{"count":3,"minutes":160,"distance_km":21.5,"kcal":1500,"load":200},
            "weeks":[{"week":"2026-11-16","count":3,"minutes":160,"load":200}],
            "filter":{"source":"auto","mode":"google_health","walk_min":20,"walk_light":true,"hr_limit":95,"hidden":12,"hidden_source":10,"hidden_walks":2},
            "load":{"ready":true,"days":7,"acute":40.3,"chronic":10.9,"form":-29.4,"hrmax":193,"rest":52.7,"sex":"male"}}""") as JsonObject
        val t = parseTraining(o)!!
        val w = t.workouts.single()
        assertEquals(Instant.parse("2026-11-20T06:30:00Z"), w.startedAt)
        assertEquals("passive", w.recording)
        assertEquals(95.3, w.load!!, 0.001)
        assertEquals(3, t.week.count)
        assertEquals(LocalDate.of(2026, 11, 16), t.weeks.single().week)
        assertTrue(t.load.ready)
        val f = t.filter!!
        assertEquals("google_health", f.mode); assertEquals(20, f.walkMin); assertTrue(f.walkLight)
        assertEquals(95, f.hrLimit); assertEquals(10, f.hiddenSource); assertEquals(2, f.hiddenWalks)
    }

    @Test fun oudeServerZonderFilter() {
        val t = parseTraining(Json.parseToJsonElement("""{"workouts":[],"week":{},"weeks":[],"load":{"ready":false}}""") as JsonObject)!!
        assertNull(t.filter)
        assertEquals(0, t.week.count)
        assertNull(parseTraining(null))
    }

    @Test fun meldingVerborgenTrainingen() {
        fun f(src: Int, walks: Int, mode: String = "google_health", min: Int = 0, light: Boolean = false) =
            filterNoteText(TrainingFilter("auto", mode, min, light, 95, src, walks))
        assertEquals("Verborgen: 244 dubbele of losse registraties uit Health Connect.", f(244, 0))
        assertEquals("Verborgen: 1 dubbele of losse registratie uit Health Connect.", f(1, 0))
        assertEquals("Verborgen: 3 korte wandelingen.", f(0, 3, min = 20))
        assertEquals("Verborgen: 1 lichte wandeling.", f(0, 1, light = true))
        assertEquals("Verborgen: 10 dubbele of losse registraties uit andere bronnen en 2 korte of lichte wandelingen.",
            f(10, 2, mode = "health_connect", min = 20, light = true))
    }

    @Test fun sporten() {
        assertEquals("running", sportFromText("Run"))
        assertEquals("running", sportFromText("TRAIL_RUNNING"))
        assertEquals("cycling", sportFromText("road_biking"))
        assertEquals("hiking", sportFromText("hiking"))
        assertEquals("walking", sportFromText("Walk"))
        assertEquals("other", sportFromText(null))
        assertEquals("other", sportFromText("onbekend"))
        assertTrue(SPORT_KEYS.containsAll(listOf("running", "walking", "other")))
    }
}

/** Slaapduur en andere uren. */
class UrenTest {
    @Test fun uren() {
        assertEquals("7u 15m", fmtHours(7.25))
        assertEquals("8u 00m", fmtHours(7.999))
        assertEquals("56 min", fmtHours(0.93))
        assertEquals("0 min", fmtHours(0.0))
        assertEquals("0 min", fmtHours(-1.0))
        assertEquals("1u 00m", fmtHours(0.9999))
    }
}

/** De zin onder de ringen en het herstel-advies. */
class ZinnenTest {
    private fun r(metric: String, value: Double, goal: Double, name: String = metric, unit: String = "") =
        RingGoal(metric, name, unit, value, goal, (value / goal).toFloat())

    @Test fun motivatie() {
        // Slaap staat het dichtst bij af, maar je kunt overdag alleen nog bewegen
        val a = motivationLine(listOf(r("steps", 6000.0, 10000.0, "Stappen"), r("sleep", 7.07, 8.0, "Slaapduur")))
        assertEquals(0, a.ring); assertEquals("Nog 4.000 stappen tot je doel voor stappen.", a.text)
        // Alleen slaap nog open: vooruitkijken
        val b = motivationLine(listOf(r("steps", 12000.0, 10000.0), r("sleep", 7.07, 8.0)))
        assertEquals(1, b.ring); assertEquals("Vanavond op tijd naar bed voor je 8\u00A0uur slaap.", b.text)
        val c = motivationLine(listOf(r("sscore", 70.0, 80.0)))
        assertEquals("Vanavond op tijd naar bed voor een goede slaapscore.", c.text)
        // Alleen iets open dat je niet kunt sturen
        val d = motivationLine(listOf(r("steps", 12000.0, 10000.0), r("hrv", 40.0, 50.0)))
        assertEquals(null, d.ring); assertEquals("Je bewegingsdoelen voor vandaag zijn gehaald.", d.text)
        assertEquals("Alle dagdoelen gehaald. Mooi werk!", motivationLine(listOf(r("steps", 12000.0, 10000.0))).text)
        assertEquals("Alle dagdoelen gehaald. Mooi werk!", motivationLine(emptyList()).text)
    }

    @Test fun herstelAdvies() {
        fun band(bad: Boolean) = Band(true, 50.0, 45.0, 55.0, 30.0, 70.0, null, bad, 7, 30)
        fun h(hrv: Boolean, rhr: Boolean, sleep: Boolean) =
            Herstel(60, 1, band(hrv), band(rhr), SleepFactor(true, 7.0, 7.0, 8.0, sleep, 7), emptyList())
        assertEquals("Je slaap is deze week korter dan je doel.", deviationText(h(false, false, true)))
        assertEquals("Je HRV ligt onder je normaal en je rusthartslag ligt boven je normaal.", deviationText(h(true, true, false)))
        assertEquals("Je HRV ligt onder je normaal, je rusthartslag ligt boven je normaal en je slaap is deze week korter dan je doel.",
            deviationText(h(true, true, true)))
        assertEquals("Een signaal wijkt af van je normaal.", deviationText(h(false, false, false)))
    }
}

/** Ronde waarden op de grafiekas. */
class AsTest {
    @Test fun rondeStappen() {
        assertEquals(listOf(4.0, 6.0, 8.0, 10.0), niceTicks(4.32, 8.64))
        assertEquals(listOf(50.0, 52.0, 54.0, 56.0, 58.0), niceTicks(51.2, 56.8))
        assertEquals(listOf(0.0, 5000.0, 10000.0, 15000.0), niceTicks(0.0, 12960.0))
        assertEquals(listOf(74.0, 75.0, 76.0), niceTicks(74.2, 75.8))
        assertEquals(listOf(0.0, 1.0), niceTicks(Double.NaN, 3.0))
        assertTrue(niceTicks(5.0, 5.0).size >= 2)
    }
}
