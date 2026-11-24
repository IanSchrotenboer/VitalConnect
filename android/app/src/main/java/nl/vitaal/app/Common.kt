package nl.vitaal.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
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
const val PRIVACY_URL = "https://app.vital-connect.nl/privacy.html"

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

/** Korte bronnaam voor tegels, zoals "Garmin" in plaats van "Garmin Connect". */
fun sourceShort(source: String): String = sourceLabel(source).removeSuffix(" Connect").removeSuffix(" via Health Connect")

fun sourceLabel(source: String): String = when {
    source == "garmin" -> "Garmin Connect"
    source == "google_health" -> "Google Health"
    source == "suunto" -> "Suunto"
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
    "suunto" -> "Suunto"
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

/**
 * Keuze uit een reeks (periode, interval, bron, slaapdoel): een Material FilterChip.
 * TalkBack leest "30 min, geselecteerd"; het tikvlak is minimaal 48 dp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            labelColor = p.ink, selectedContainerColor = p.primary, selectedLabelColor = p.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected, borderColor = p.outline, selectedBorderColor = p.primary,
        ),
    )
}

/** Een handeling (omhoog, ring toevoegen): een Material AssistChip, geen keuze. */
@Composable
fun ActionChip(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = AssistChipDefaults.assistChipColors(labelColor = p.ink),
        border = AssistChipDefaults.assistChipBorder(enabled = enabled, borderColor = p.outline),
    )
}

/** Eén keuze uit twee tot vier opties (weergave, geslacht, grootte, tabbladen): Material-segmentknop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T?, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = p.primary, activeContentColor = p.onPrimary,
                    inactiveContainerColor = Color.Transparent, inactiveContentColor = p.ink,
                    activeBorderColor = p.primary, inactiveBorderColor = p.outline,
                ),
                icon = {},
                label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Verschil t.o.v. de week ervoor, groen als het beter is. */
@Composable
/** short: zonder "vs week", voor smalle tegels; TalkBack hoort altijd de hele zin. */
fun Delta(pc: Double?, lowerIsBetter: Boolean, short: Boolean = false) {
    val p = LocalPalette.current
    if (pc == null) return
    if (abs(pc) < 1) {
        Text(if (short) "gelijk" else "gelijk aan je week", style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1,
            modifier = Modifier.semantics { contentDescription = "gelijk aan vorige week" })
        return
    }
    val better = if (lowerIsBetter) pc < 0 else pc > 0
    val pct = abs(pc).roundToInt()
    Text(
        "${if (pc > 0) "▲" else "▼"} $pct%" + if (short) "" else " vs week",
        style = MaterialTheme.typography.labelSmall,
        color = if (better) p.good else p.bad,
        maxLines = 1,
        modifier = Modifier.semantics {
            contentDescription = "$pct procent ${if (pc > 0) "hoger" else "lager"} dan vorige week, ${if (better) "beter" else "minder goed"}"
        },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, dot: Color? = null, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) { Dot(dot); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.titleLarge, color = LocalPalette.current.ink, modifier = Modifier.weight(1f).semantics { heading() })
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

/** Tekstknop met icoon, zoals "Aanpassen": Material TextButton, tikvlak minimaal 48 dp. */
@Composable
fun IconLabel(icon: ImageVector, text: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Schermtitel die TalkBack als kop herkent. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineLarge, color = LocalPalette.current.ink, modifier = modifier.semantics { heading() })
}

/**
 * Kop van een scherm met terugknop (Profiel, Aanpassen, detail): Material TopAppBar.
 * De Scaffold eromheen regelt de ruimte voor de statusbalk al, dus de balk zelf krijgt geen insets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VTopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, subtitleColor: Color? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = subtitleColor ?: p.muted, maxLines = 1) }
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(VIcons.Back, contentDescription = "Terug", tint = p.ink) }
        },
        actions = actions,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg, scrolledContainerColor = p.card),
    )
}
