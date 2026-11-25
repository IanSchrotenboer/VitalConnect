package nl.vitaal.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Vital Connect-palet.
 * Rust: diep dennengroen en zachte mistgrijs-groene ondergrond.
 * Motivatie: warme accentkleuren per categorie en een duidelijk groen voor "doel gehaald".
 * Tekstkleuren halen minimaal WCAG AA (4,5:1) op hun ondergrond.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    /** Randen van bedienbare onderdelen (chips, invulvelden): minimaal 3:1 op de achtergrond. */
    val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val dial: Color,
    val dialInk: Color,
    val dialMuted: Color,
    val activity: Color,
    val heart: Color,
    val sleep: Color,
    val body: Color,
    val recovery: Color,
    val good: Color,
    val bad: Color,
    val warn: Color,
    // Statuskleur voor "Let op" (stip en tekst); warn is alleen een achtergrond
    val caution: Color,
    // Herkenningskleuren van de bronnen (koppelingen)
    val srcHealthConnect: Color,
    val srcGoogle: Color,
    val srcGarmin: Color,
)

private val Light = Palette(
    dark = false,
    bg = Color(0xFFDCE6E1), card = Color(0xFFFFFFFF), ink = Color(0xFF0F1E1B), muted = Color(0xFF52615C),
    line = Color(0xFFCCD7D2), outline = Color(0xFF6E7F79), primary = Color(0xFF1C4D46), onPrimary = Color(0xFFFFFFFF),
    dial = Color(0xFF123430), dialInk = Color(0xFFF3F7F5), dialMuted = Color(0xFFA9BDB7),
    // Abrikoos en oker iets donkerder dan in de wijzerplaat: grafieklijnen halen zo 3:1 op wit
    activity = Color(0xFFD86F31), heart = Color(0xFFD04E6A), sleep = Color(0xFF5865D6),
    body = Color(0xFFAD7E24), recovery = Color(0xFF2F9C8F),
    good = Color(0xFF1E7F5C), bad = Color(0xFFB4462B), warn = Color(0xFFFFF4E0), caution = Color(0xFFB36B00),
    srcHealthConnect = Color(0xFF2F7D6E), srcGoogle = Color(0xFF3F7F4A), srcGarmin = Color(0xFF2B5FA8),
)

private val Dark = Palette(
    dark = true,
    // OLED-zwart: zwarte achtergrond, neutraalgrijze tegels; de wijzerplaat blijft dennengroen en springt eruit
    bg = Color(0xFF000000), card = Color(0xFF1D2321), ink = Color(0xFFE9F1EE), muted = Color(0xFF9DB0AA),
    line = Color(0xFF333B38), outline = Color(0xFF63776F), primary = Color(0xFF7FD1C1), onPrimary = Color(0xFF0C1614),
    dial = Color(0xFF123430), dialInk = Color(0xFFF3F7F5), dialMuted = Color(0xFFA9BDB7),
    activity = Color(0xFFF5935A), heart = Color(0xFFEC7290), sleep = Color(0xFF8C97F2),
    body = Color(0xFFDDB15A), recovery = Color(0xFF4CC3B3),
    good = Color(0xFF4CC79A), bad = Color(0xFFF07D5F), warn = Color(0xFF2E2717), caution = Color(0xFFE8B04A),
    // Bronlogo's: witte letter, ook in donker ≥ 4,5:1 (daarom dezelfde tinten als in licht)
    srcHealthConnect = Color(0xFF2F7D6E), srcGoogle = Color(0xFF3F7F4A), srcGarmin = Color(0xFF2B5FA8),
)

val LocalPalette = staticCompositionLocalOf { Light }

/** De wijzerplaat is altijd donker: daar gebruiken we de heldere kleurvarianten. */
val DialPalette = Dark

/**
 * Wijzerplaat voor het huidige thema: de heldere accentkleuren van Dark, met de achtergrond van het thema
 * (dennengroen #123430, gelijk aan het logo en het app-icoon, in licht en donker).
 */
@Composable
fun dialPalette(): Palette = DialPalette.copy(dial = LocalPalette.current.dial)

/**
 * Dagringen: één kleur per positie (abrikoos, zeegroen, indigo), van buiten naar binnen.
 * Feller dan de categoriekleuren, zodat ze in de tegel bovenaan Vandaag oplichten, in licht en donker.
 * Gelijk aan --ring1..3 in prototype/index.html.
 */
val RING_COLORS = listOf(Color(0xFFFF8438), Color(0xFF26E8C8), Color(0xFF7B83FF))

/** Tekst en pijlen in de ringkleur: in licht een donkerdere tint (contrast ≥ 4,5:1 op wit), in donker gelijk aan de ring. */
private val RING_INK_LIGHT = listOf(Color(0xFFB8500F), Color(0xFF0C7C6A), Color(0xFF4F57E0))
fun Palette.ringInk(i: Int): Color = if (dark) RING_COLORS[i] else RING_INK_LIGHT[i]

/** Neutraal spoor onder de dagringen, op de tegelkleur. Gelijk aan --track in de preview. */
val Palette.ringTrack: Color get() = if (dark) Color(0xFF2A312F) else Color(0xFFE3E9E6)

enum class Category(val label: String) {
    Activity("Activiteit"), Heart("Hart"), Sleep("Slaap"), Recovery("Herstel"), Body("Lichaam")
}

fun categoryOf(metric: String): Category = when (metric) {
    "steps", "active", "kcal", "dist", "floors" -> Category.Activity
    "rhr", "hrv", "spo2" -> Category.Heart
    "sleep", "sscore", "resp" -> Category.Sleep
    "stress", "energy", "recov" -> Category.Recovery
    else -> Category.Body
}

fun Palette.colorOf(metric: String): Color = colorOf(categoryOf(metric))

fun Palette.colorOf(c: Category): Color = when (c) {
    Category.Activity -> activity
    Category.Heart -> heart
    Category.Sleep -> sleep
    Category.Recovery -> recovery
    Category.Body -> body
}

/**
 * Categoriekleur voor tekst (zoals Record op Gezondheid). In donker is dat de categoriekleur zelf (≥ 5,6:1 op de tegel);
 * in licht een donkerdere tint met minstens 4,5:1 op wit, zoals ringInk bij de ringen.
 */
fun Palette.inkOf(metric: String): Color = if (dark) colorOf(metric) else when (categoryOf(metric)) {
    Category.Activity -> Color(0xFFB85A23)
    Category.Heart -> Color(0xFFCD4260)
    Category.Sleep -> sleep
    Category.Recovery -> Color(0xFF278176)
    Category.Body -> Color(0xFF966D1F)
}

/** Cijfers met vaste breedte, zodat waarden onder elkaar netjes uitlijnen. */
private const val TNUM = "tnum"

/**
 * Figtree (SIL Open Font License, zie assets/licenses): rustig, vriendelijk en goed leesbaar,
 * met cijfers van vaste breedte. Dezelfde letter staat in de preview en op de website.
 */
val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

private val Type = Typography(
    displayLarge = TextStyle(fontFamily = Figtree, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),
    displaySmall = TextStyle(fontFamily = Figtree, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),
    headlineLarge = TextStyle(fontFamily = Figtree, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = Figtree, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, fontFeatureSettings = TNUM),
    headlineSmall = TextStyle(fontFamily = Figtree, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
    titleLarge = TextStyle(fontFamily = Figtree, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Figtree, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Figtree, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Figtree, fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = Figtree, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = Figtree, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** Weergavekeuze in het profiel. */
enum class ThemeMode(val id: String, val label: String) {
    System("system", "Systeem"), Light("light", "Licht"), Dark("dark", "Donker");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: System }
}

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun VitaalTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val p = if (mode.isDark()) Dark else Light
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = schemeOf(p), typography = Type) {
            // Standaardletter voor tekst zonder eigen stijl: Figtree, nooit de systeemletter
            ProvideTextStyle(Type.bodyMedium, content)
        }
    }
}

/**
 * Alle Material-kleurrollen uit het palet, zodat geen enkel onderdeel (schakelaar, datumkiezer, snackbar,
 * dialoog, invulveld) terugvalt op de paarse standaardkleuren van Material.
 */
private fun schemeOf(p: Palette) = if (p.dark) darkColorScheme(
    primary = p.primary, onPrimary = p.onPrimary,
    primaryContainer = Color(0xFF1F3A35), onPrimaryContainer = p.ink, inversePrimary = Color(0xFF1C4D46),
    secondary = p.activity, onSecondary = p.bg, secondaryContainer = Color(0xFF2A3230), onSecondaryContainer = p.ink,
    tertiary = p.recovery, onTertiary = p.bg, tertiaryContainer = Color(0xFF173A35), onTertiaryContainer = p.ink,
    background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
    surfaceVariant = p.card, onSurfaceVariant = p.muted, surfaceTint = p.primary,
    inverseSurface = p.ink, inverseOnSurface = p.card,
    error = p.bad, onError = p.bg, errorContainer = Color(0xFF4A2117), onErrorContainer = Color(0xFFFFDAD0),
    outline = p.outline, outlineVariant = p.line, scrim = Color.Black,
    surfaceDim = p.bg, surfaceBright = Color(0xFF2A3230),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF121615), surfaceContainer = p.card,
    surfaceContainerHigh = p.card, surfaceContainerHighest = Color(0xFF2A3230),
) else lightColorScheme(
    primary = p.primary, onPrimary = p.onPrimary,
    primaryContainer = Color(0xFFDCEBE6), onPrimaryContainer = p.ink, inversePrimary = Color(0xFF7FD1C1),
    secondary = p.activity, onSecondary = Color.White, secondaryContainer = Color(0xFFDCEBE6), onSecondaryContainer = p.ink,
    tertiary = p.recovery, onTertiary = Color.White, tertiaryContainer = Color(0xFFD5EFEA), onTertiaryContainer = p.ink,
    background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
    surfaceVariant = p.card, onSurfaceVariant = p.muted, surfaceTint = p.primary,
    inverseSurface = Color(0xFF24332F), inverseOnSurface = Color(0xFFF3F7F5),
    error = p.bad, onError = Color.White, errorContainer = Color(0xFFFBE3DC), onErrorContainer = Color(0xFF5C1D0E),
    outline = p.outline, outlineVariant = p.line, scrim = Color.Black,
    surfaceDim = Color(0xFFE9EEEA), surfaceBright = p.card,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9FBF9), surfaceContainer = p.card,
    surfaceContainerHigh = p.card, surfaceContainerHighest = Color(0xFFE9EEEA),
)
