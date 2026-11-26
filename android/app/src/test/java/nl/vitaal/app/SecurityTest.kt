package nl.vitaal.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/** Wachtwoordregels, de datalekcontrole, tweestapsverificatie en het beheeroverzicht. */
class WachtwoordTest {
    @Test fun regels() {
        assertNotNull(passwordProblem("kort1"))
        assertNotNull(passwordProblem("alleenletters"))
        assertNotNull(passwordProblem("12345678"))
        assertNull(passwordProblem("fiets2026"))
    }

    @Test fun sha1() {
        // Bekende waarde: SHA-1 van "password"
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", sha1Hex("password"))
    }

    @Test fun telling() {
        val hash = sha1Hex("password")
        val body = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9659365\r\nFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:0"
        assertEquals(9659365, leakCountIn(body, hash))
        assertEquals(0, leakCountIn(body, sha1Hex("iets-anders-42")))
        assertEquals(0, leakCountIn("", hash))
        // Kleine letters en rommel breken het niet
        assertEquals(3, leakCountIn("1e4c9b93f3f0682250b6cf8331b7ee68fd8:3\nkapot", hash))
    }
}

class TweestapsTest {
    private fun token(claims: String) = "x." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".y"

    @Test fun claimUitToken() {
        assertEquals("aal2", jwtClaim(token("""{"sub":"u","aal":"aal2"}"""), "aal"))
        assertEquals("aal1", jwtClaim(token("""{"aal":"aal1","naam":"Ünïcode é"}"""), "aal"))
        assertNull(jwtClaim(token("""{"sub":"u"}"""), "aal"))
        assertNull(jwtClaim("geen-token", "aal"))
    }

    @Test fun sleutelInGroepjes() {
        assertEquals("JBSW Y3DP EHPK 3PXP", groupSecret("JBSWY3DPEHPK3PXP"))
        assertEquals("ABCD EF", groupSecret("ABCDEF"))
    }

    @Test fun versieZoalsDeDatabase() {
        assertTrue(APP_VERSION.matches("1.19.0"))
        assertFalse(APP_VERSION.matches("1.19.0-debug"))
        assertFalse(APP_VERSION.matches("1.19"))
    }
}

class BeheerTest {
    @Test fun overzichtLezen() {
        val o = Json.parseToJsonElement("""
            {"kpi":{"gebruikers":2,"actief_vandaag":1,"actief_7d":2,"nieuw_7d":2,"tweestaps":0,"fouten_7d":3},
             "versies":[{"versie":"1.19.0","aantal":1},{"versie":null,"aantal":1}],
             "koppelingen":[{"bron":"garmin","status":"active","aantal":1}],
             "fouten":[{"bron":"garmin","aantal":3,"laatste":"2026-11-26T10:00:00+00:00","melding":"401"}],
             "gebruikers":[{"email":"a@b.nl","naam":"Ian","actief":"2026-11-26T14:05:00.123+00:00","versie":"1.19.0","tweestaps":true,
                            "koppelingen":[{"bron":"garmin","status":"active"},{"bron":"google_health","status":"revoked"}],"fouten_7d":3}]}
        """).jsonObject
        val a = parseAdminOverview(o)!!
        assertEquals(2, a.users)
        assertEquals(3, a.errors7)
        assertEquals(listOf("1.19.0" to 1, null to 1), a.versions)
        assertEquals(listOf("garmin"), a.people[0].sources)
        assertTrue(a.people[0].twoStep)
        assertNotNull(a.people[0].active)
        assertNotNull(a.errors[0].last)
        // Onverwachte vorm: geen crash
        assertEquals(0, parseAdminOverview(Json.parseToJsonElement("""{"kpi":"x","gebruikers":5}""").jsonObject)!!.users)
        assertNull(parseAdminOverview(null))
    }

    @Test fun laatstActief() {
        val today = LocalDate.of(2026, 11, 26)
        fun at(d: LocalDate, h: Int = 14) = d.atTime(h, 5).atZone(ZoneId.systemDefault()).toInstant()
        assertEquals("vandaag 14:05", activeText(at(today), today))
        assertEquals("gisteren", activeText(at(today.minusDays(1)), today))
        assertEquals("3 dagen geleden", activeText(at(today.minusDays(3)), today))
        assertEquals("nooit", activeText(null, today))
        assertTrue(activeText(Instant.parse("2026-01-02T12:00:00Z"), today).contains("2026"))
    }
}
