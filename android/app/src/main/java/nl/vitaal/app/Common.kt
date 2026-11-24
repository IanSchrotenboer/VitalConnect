package nl.vitaal.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

val NL: Locale = Locale.forLanguageTag("nl-NL")
const val APP_NAME = "Vital Connect"

// ---------- Getallen ----------
fun fmt(metric: String, v: Double): String = when (metric) {
    "sleep" -> fmtHours(v)
    "dist", "weight", "resp" -> String.format(NL, "%.1f", v)
    else -> NumberFormat.getIntegerInstance(NL).format(v.roundToLong())
}

/** 7,25 → "7u 15m" */
fun fmtHours(v: Double): String {
    var h = v.toInt()
    var m = ((v - h) * 60).roundToInt()
    if (m == 60) { h++; m = 0 }
    return "${h}u ${m.toString().padStart(2, '0')}m"
}

/** Eenheid zoals hij naast het getal staat; slaap heeft die al in het getal. */
fun unitOf(metric: String, unit: String): String = if (metric == "sleep") "" else unit

/** Grote getallen kort, voor totalen: 1.234.567 → "1,2 mln". */
fun fmtCompact(v: Double): String = when {
    abs(v) >= 1_000_000 -> String.format(NL, "%.1f mln", v / 1_000_000)
    abs(v) >= 10_000 -> String.format(NL, "%.0fk", v / 1_000)
    else -> NumberFormat.getIntegerInstance(NL).format(v.roundToLong())
}

fun fmtDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM yyyy", NL))

fun sourceLabel(source: String): String = when {
    source == "garmin" -> "Garmin Connect"
    source == "google_health" -> "Google Health"
    source.startsWith("health_connect:") -> source.removePrefix("health_connect:").let { APPS[it] ?: "Health Connect" }
    else -> source
}

private val APPS = mapOf(
    "com.garmin.android.apps.connectmobile" to "Garmin via Health Connect",
    "com.google.android.apps.fitness" to "Google Fit",
    "com.sec.android.app.shealth" to "Samsung Health",
    "com.fitbit.FitbitMobile" to "Fitbit",
    "com.stt.android.suunto" to "Suunto",
)

fun providerName(p: String) = when (p) {
    "garmin" -> "Garmin Connect"
    "google_health" -> "Google Health"
    else -> "Health Connect"
}

/** Procentueel verschil met het gemiddelde van de 7 dagen ervoor. */
fun deltaVsWeek(hist: Map<LocalDate, Double>?, today: LocalDate = LocalDate.now()): Double? {
    val v = hist?.get(today) ?: return null
    val prev = (1L..7L).mapNotNull { hist[today.minusDays(it)] }
    if (prev.isEmpty()) return null
    val avg = prev.average()
    return if (avg == 0.0) null else (v - avg) / avg * 100
}

// ---------- Bouwstenen ----------
@Composable
fun VCard(
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Surface(
        color = color,
        shape = shape,
        border = if (!p.dark && color == p.card) BorderStroke(1.dp, p.line) else null,
        modifier = modifier.clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** Dikke voortgangsbalk; boven de 100% wordt hij groen. */
@Composable
fun Progress(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val p = LocalPalette.current
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(color.copy(alpha = 0.18f))) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(height))
            .background(if (fraction >= 1f) p.good else color))
    }
}

/** Keuzeknop (periode, bron, grootte). */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .background(if (selected) p.primary else Color.Transparent)
            .border(1.dp, if (selected) p.primary else p.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) p.onPrimary else p.ink, maxLines = 1)
    }
}

/** Verschil t.o.v. de week ervoor, groen als het beter is. */
@Composable
fun Delta(pc: Double?, lowerIsBetter: Boolean) {
    val p = LocalPalette.current
    if (pc == null) return
    if (abs(pc) < 1) {
        Text("gelijk aan je week", style = MaterialTheme.typography.labelSmall, color = p.muted)
        return
    }
    val better = if (lowerIsBetter) pc < 0 else pc > 0
    Text(
        "${if (pc > 0) "▲" else "▼"} ${abs(pc).roundToInt()}% vs week",
        style = MaterialTheme.typography.labelSmall,
        color = if (better) p.good else p.bad,
        maxLines = 1,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, dot: Color? = null, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) { Dot(dot); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Kleine statistiek: label boven, waarde onder. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LocalPalette.current.ink) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalPalette.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = valueColor, maxLines = 1)
    }
}

@Composable
fun IconLabel(icon: ImageVector, text: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick).defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
