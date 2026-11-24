package nl.vitaal.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
)

private val Light = Palette(
    dark = false,
    bg = Color(0xFFF5F7F4), card = Color(0xFFFFFFFF), ink = Color(0xFF0F1E1B), muted = Color(0xFF52615C),
    line = Color(0xFFE1E6E2), primary = Color(0xFF1C4D46), onPrimary = Color(0xFFFFFFFF),
    dial = Color(0xFF123430), dialInk = Color(0xFFF3F7F5), dialMuted = Color(0xFFA9BDB7),
    activity = Color(0xFFE4793B), heart = Color(0xFFD04E6A), sleep = Color(0xFF5865D6),
    body = Color(0xFFB9892B), recovery = Color(0xFF2F9C8F),
    good = Color(0xFF1E7F5C), bad = Color(0xFFB4462B), warn = Color(0xFFFFF4E0),
)

private val Dark = Palette(
    dark = true,
    bg = Color(0xFF0C1614), card = Color(0xFF15221F), ink = Color(0xFFE9F1EE), muted = Color(0xFF9DB0AA),
    line = Color(0xFF24332F), primary = Color(0xFF7FD1C1), onPrimary = Color(0xFF0C1614),
    dial = Color(0xFF0F2A26), dialInk = Color(0xFFF3F7F5), dialMuted = Color(0xFFA9BDB7),
    activity = Color(0xFFF5935A), heart = Color(0xFFEC7290), sleep = Color(0xFF8C97F2),
    body = Color(0xFFDDB15A), recovery = Color(0xFF4CC3B3),
    good = Color(0xFF4CC79A), bad = Color(0xFFF07D5F), warn = Color(0xFF2E2717),
)

val LocalPalette = staticCompositionLocalOf { Light }

/** De wijzerplaat is altijd donker: daar gebruiken we de heldere kleurvarianten. */
val DialPalette = Dark

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

/** Cijfers met vaste breedte, zodat waarden onder elkaar netjes uitlijnen. */
private const val TNUM = "tnum"

private val Type = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, fontFeatureSettings = TNUM),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun VitaalTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (p.dark) darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, secondary = p.activity, onSecondary = p.bg,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
        surfaceVariant = p.card, onSurfaceVariant = p.muted, outline = p.line, outlineVariant = p.line,
        error = p.bad, surfaceContainer = p.bg, surfaceContainerHigh = p.card,
        secondaryContainer = p.line, onSecondaryContainer = p.ink,
    ) else lightColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, secondary = p.activity, onSecondary = Color.White,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
        surfaceVariant = p.card, onSurfaceVariant = p.muted, outline = p.line, outlineVariant = p.line,
        error = p.bad, surfaceContainer = p.card, surfaceContainerHigh = p.card,
        secondaryContainer = Color(0xFFDCEBE6), onSecondaryContainer = p.ink,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = Type, content = content)
    }
}
