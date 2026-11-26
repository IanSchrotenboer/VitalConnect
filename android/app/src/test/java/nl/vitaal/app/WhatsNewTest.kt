package nl.vitaal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Nieuw in Vital Connect": punten uit de release-notities en wanneer het venster komt. */
class WhatsNewTest {
    private val md = """
        # Vital Connect 1.16.0

        ## Nieuw

        - **Een zin onder de ringen die je verder helpt.**
          - De zin noemt alleen nog doelen die je vandaag kunt halen.
          - Is alleen je slaap nog niet gehaald, dan kijkt hij vooruit.
        - **Herstel zegt wat er afwijkt.** Bij "Let op" staat nu welk signaal afwijkt.
        - **Knop:** opent je profiel bij `Geboortedatum`.

        ## Verbeterd

        - **Iets anders.** Hoort niet bij Nieuw.
    """.trimIndent()

    @Test fun leestPunten() {
        val items = parseNew(md)
        assertEquals(3, items.size)
        assertEquals("Een zin onder de ringen die je verder helpt", items[0].title)
        assertEquals("De zin noemt alleen nog doelen die je vandaag kunt halen. Is alleen je slaap nog niet gehaald, dan kijkt hij vooruit.", items[0].text)
        assertEquals("Herstel zegt wat er afwijkt", items[1].title)
        assertEquals("Bij \"Let op\" staat nu welk signaal afwijkt.", items[1].text)
        assertEquals("Knop", items[2].title)
        assertEquals("Opent je profiel bij Geboortedatum.", items[2].text)
    }

    @Test fun zonderNieuwGeenPunten() {
        assertTrue(parseNew("# 1.17.1\n\n## Verbeterd\n\n- **Weken.** Werken weer.\n").isEmpty())
        assertTrue(parseNew("").isEmpty())
        assertTrue(parseNew("## Nieuw\n\nGeen opsomming.\n").isEmpty())
    }

    @Test fun wanneerHetVensterKomt() {
        assertTrue(shouldShowNew(null, "1.18.0", 3, firstUse = false))      // bestaande gebruiker, eerste versie met dit venster
        assertTrue(shouldShowNew("1.17.1", "1.18.0", 3, firstUse = false))  // gewone update
        assertFalse(shouldShowNew("1.18.0", "1.18.0", 3, firstUse = false)) // al gezien
        assertFalse(shouldShowNew(null, "1.18.0", 3, firstUse = true))      // nieuwe gebruiker
        assertFalse(shouldShowNew("1.17.1", "1.18.0", 0, firstUse = false)) // onderhoudsversie zonder Nieuw
        assertEquals("1.18", shortVersion("1.18.0"))
        assertEquals("1.17.1", shortVersion("1.17.1"))
    }

    /** De notities van deze versie leveren punten met titel; zo merkt de bugtest een kapotte opmaak. */
    @Test fun notitiesVanDezeVersie() {
        val f = File("../../release-notities/v${BuildConfig.VERSION_NAME}.md")
        if (!f.exists()) return
        val tekst = f.readText()
        val items = parseNew(tekst)
        val verwacht = tekst.substringAfter("## Nieuw", "").substringBefore("\n## ").lines().count { it.startsWith("- **") }
        assertEquals(verwacht, items.size)
        assertTrue(items.all { it.title.isNotBlank() && it.title.length <= 80 })
    }
}
