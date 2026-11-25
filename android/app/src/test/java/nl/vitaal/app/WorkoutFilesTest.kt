package nl.vitaal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Trainingen uit FIT- en JSON-bestanden: elke bron die de app ondersteunt, en rommel. */
class WorkoutFilesTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun read(text: String) = WorkoutFiles.read("test.json", text.toByteArray(), zone)

    @Test fun strava() {
        val r = read("""[{"id":1234567890,"name":"Ochtendloop","sport_type":"Run","start_date":"2026-11-20T06:30:00Z",
            "start_date_local":"2026-11-20T07:30:00Z","elapsed_time":2900,"distance":8400.5,"average_heartrate":149.4,
            "max_heartrate":171,"total_elevation_gain":22}]""")
        val w = r.workouts.single()
        assertEquals(0, r.skipped)
        assertEquals("running", w.sport)
        assertEquals("Ochtendloop", w.title)
        assertEquals(LocalDate.of(2026, 11, 20), w.day)
        assertEquals(Instant.parse("2026-11-20T06:30:00Z"), w.startedAt)
        assertEquals(2900, w.durationS)
        assertEquals(8400.5, w.distanceM!!, 0.01)
        assertEquals(149, w.avgHr)
        assertEquals(171, w.maxHr)
        assertEquals("json-1234567890", w.externalId)
    }

    // Twee trainingen met dezelfde naam mogen elkaar niet overschrijven (sleutel = id, niet de titel)
    @Test fun stravaTweeKeerDezelfdeNaam() {
        val r = read("""[{"id":1,"name":"Ochtendloop","sport_type":"Run","start_date":"2026-11-20T06:30:00Z","elapsed_time":1800},
                         {"id":2,"name":"Ochtendloop","sport_type":"Run","start_date":"2026-11-21T06:30:00Z","elapsed_time":1800}]""")
        assertEquals(2, r.workouts.size)
        assertNotEquals(r.workouts[0].externalId, r.workouts[1].externalId)
    }

    @Test fun zonderIdOokUniek() {
        val r = read("""[{"name":"Loop","type":"running","start":"2026-11-20T06:30:00Z","duration_s":1800},
                         {"name":"Loop","type":"running","start":"2026-11-21T06:30:00Z","duration_s":1800}]""")
        assertEquals(2, r.workouts.map { it.externalId }.distinct().size)
    }

    @Test fun garminGegevensexport() {
        val r = read("""[{"summarizedActivitiesExport":[{"activityId":987,"name":"Fietsen","activityType":"road_biking",
            "startTimeGmt":1763620200000,"startTimeLocal":1763623800000,"duration":3600000.0,"distance":3000000.0,
            "avgHr":135,"maxHr":160,"elevationGain":24000.0}]}]""")
        val w = r.workouts.single()
        assertEquals("cycling", w.sport)
        assertEquals(3600, w.durationS)
        assertEquals(30000.0, w.distanceM!!, 0.01)
        assertEquals(240.0, w.ascentM!!, 0.01)
        assertEquals("garmin-987", w.externalId)
    }

    @Test fun googleHealth() {
        val r = read("""{"dataPoints":[{"name":"users/me/dataTypes/exercise/dataPoints/abc123","dataSource":{"recordingMethod":"ACTIVELY_MEASURED"},
            "exercise":{"exerciseType":"RUNNING","displayName":"Run","activeDuration":"1800s",
            "interval":{"startTime":"2026-11-20T06:30:00Z","endTime":"2026-11-20T07:05:00Z"},
            "metricsSummary":{"distanceMillimeters":"5000000","caloriesKcal":350.5,"averageHeartRateBeatsPerMinute":"150"}}}]}""")
        val w = r.workouts.single()
        assertEquals("running", w.sport)
        assertEquals(1800, w.durationS)
        assertEquals(5000.0, w.distanceM!!, 0.01)
        assertEquals(150, w.avgHr)
        assertEquals("json-abc123", w.externalId)
    }

    @Test fun googleFitTakeout() {
        val r = read("""{"session":[{"fitnessActivity":"walking","startTime":"2026-11-20T10:00:00Z","endTime":"2026-11-20T10:45:00Z",
            "aggregate":[{"metricName":"com.google.distance.delta","floatValue":3200.0},{"metricName":"com.google.calories.expended","floatValue":180.0}]}]}""")
        val w = r.workouts.single()
        assertEquals("walking", w.sport)
        assertEquals(2700, w.durationS)
        assertEquals(3200.0, w.distanceM!!, 0.01)
        assertEquals(180.0, w.kcal!!, 0.01)
    }

    @Test fun eigenExportHeenEnTerug() {
        val orig = Workout(null, "manual", "m-abc", "yoga", "Avondyoga", LocalDate.of(2026, 11, 22), Instant.parse("2026-11-22T19:00:00Z"),
            1800, null, 90.0, 95, 110, null, "Rustig", null, null)
        val r = read("[" + orig.toJson() + "]")
        val w = r.workouts.single()
        assertEquals("yoga", w.sport)
        assertEquals("Avondyoga", w.title)
        assertEquals(orig.day, w.day)
        assertEquals(1800, w.durationS)
        assertEquals("Rustig", w.notes)
        assertEquals("json-m-abc", w.externalId)
    }

    @Test fun rommelGeeftGeenFout() {
        for (t in listOf("", "{[", "[" .repeat(5000) + "]".repeat(5000), "null", "42", "\"tekst\"",
            """[{"start_date":"2026-11-20T07:00:00Z","elapsed_time":1e40,"distance":"NaN"},{"start_date":"x","elapsed_time":600}]""")) {
            val r = read(t)
            assertTrue("geen trainingen uit: ${t.take(30)}", r.workouts.isEmpty())
        }
    }

    @Test fun onzinWaardenWordenLeeg() {
        val w = read("""[{"id":"x1","type":"running","start":"2026-11-20T06:30:00Z","duration_s":1800,"avg_hr":400,"distance":-5,"kcal":99999}]""").workouts.single()
        assertNull(w.avgHr); assertNull(w.distanceM); assertNull(w.kcal)
    }

    // ---------- FIT ----------

    /** Bouwt een klein FIT-bestand: één sessiebericht (18) en eventueel hartslagmetingen (20). */
    private fun fit(sessionAvgHr: Int?, records: List<Pair<Long, Int>> = emptyList(), sport: Int = 1, sub: Int = 0): ByteArray {
        val data = ByteArrayOutputStream()
        fun le(v: Long, n: Int) { for (i in 0 until n) data.write(((v shr (8 * i)) and 0xFF).toInt()) }
        val start = Instant.parse("2026-11-20T06:30:00Z").epochSecond - 631065600L
        // definitie lokaal 0 = sessie: start_time(2,u32) sport(5,enum) sub_sport(6,enum) elapsed(7,u32) timer(8,u32)
        // distance(9,u32) calories(11,u16) avg_hr(16,u8) max_hr(17,u8) ascent(22,u16)
        data.write(0x40); data.write(0); data.write(0); le(18, 2)
        val fields = listOf(2 to 4 to 0x86, 5 to 1 to 0x00, 6 to 1 to 0x00, 7 to 4 to 0x86, 8 to 4 to 0x86, 9 to 4 to 0x86,
            11 to 2 to 0x84, 16 to 1 to 0x02, 17 to 1 to 0x02, 22 to 2 to 0x84)
        data.write(fields.size)
        fields.forEach { (a, base) -> data.write(a.first); data.write(a.second); data.write(base) }
        // definitie lokaal 1 = meting: timestamp(253,u32) heart_rate(3,u8)
        data.write(0x41); data.write(0); data.write(0); le(20, 2); data.write(2)
        data.write(253); data.write(4); data.write(0x86); data.write(3); data.write(1); data.write(0x02)
        records.forEach { (dt, hr) -> data.write(0x01); le(start + dt, 4); data.write(hr) }
        data.write(0x00)
        le(start, 4); data.write(sport); data.write(sub); le(3_600_000, 4); le(3_540_000, 4); le(1_000_000, 4)
        le(700, 2); data.write(sessionAvgHr ?: 0xFF); data.write(if (sessionAvgHr == null) 0xFF else 172); le(55, 2)
        val body = data.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(14); out.write(0x10); out.write(0x54); out.write(0x08)
        for (i in 0 until 4) out.write((body.size shr (8 * i)) and 0xFF)
        out.write(".FIT".toByteArray()); out.write(0); out.write(0)
        out.write(body); out.write(0); out.write(0)
        return out.toByteArray()
    }

    @Test fun fitSessie() {
        val r = WorkoutFiles.read("loop.fit", fit(151), zone)
        val w = r.workouts.single()
        assertEquals("running", w.sport)
        assertEquals(Instant.parse("2026-11-20T06:30:00Z"), w.startedAt)
        assertEquals(3540, w.durationS)
        assertEquals(10000.0, w.distanceM!!, 0.01)
        assertEquals(700.0, w.kcal!!, 0.01)
        assertEquals(151, w.avgHr)
        assertEquals(172, w.maxHr)
        assertEquals(55.0, w.ascentM!!, 0.01)
        assertTrue(w.externalId.startsWith("fit-"))
    }

    @Test fun fitHartslagUitMetingen() {
        val w = WorkoutFiles.read("rit.fit", fit(null, listOf(60L to 120, 600L to 140, 1200L to 160), sport = 2), zone).workouts.single()
        assertEquals("cycling", w.sport)
        assertEquals(140, w.avgHr)
        assertEquals(160, w.maxHr)
    }

    @Test fun fitKrachttraining() {
        assertEquals("strength", WorkoutFiles.read("k.fit", fit(120, sport = 10, sub = 20), zone).workouts.single().sport)
        assertEquals("yoga", WorkoutFiles.read("y.fit", fit(90, sport = 10, sub = 43), zone).workouts.single().sport)
    }

    @Test fun fitZelfdeBestandZelfdeSleutel() {
        val a = WorkoutFiles.read("a.fit", fit(150), zone).workouts.single().externalId
        val b = WorkoutFiles.read("b.fit", fit(150), zone).workouts.single().externalId
        val c = WorkoutFiles.read("c.fit", fit(151), zone).workouts.single().externalId
        assertEquals(a, b); assertNotEquals(a, c)
    }

    @Test fun fitAfgekapt() {
        val bytes = fit(150)
        for (n in listOf(12, 14, 20, 40, bytes.size - 10)) {
            val r = WorkoutFiles.read("kapot.fit", bytes.copyOf(n), zone)
            assertTrue(r.workouts.size <= 1)
        }
        assertTrue(WorkoutFiles.read("leeg.fit", ByteArray(0), zone).workouts.isEmpty())
    }
}
