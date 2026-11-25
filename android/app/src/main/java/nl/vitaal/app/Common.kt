package nl.vitaal.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
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

/** 7,25 → "7u 15m"; onder het uur 0,93 → "56 min" (zoals de duur van trainingen) */
fun fmtHours(v: Double): String {
    val totaal = (v.coerceAtLeast(0.0) * 60).roundToInt()
    val h = totaal / 60
    val m = totaal % 60
    return if (h == 0) "$m min" else "${h}u ${m.toString().padStart(2, '0')}m"
}

/** Eenheid zoals hij naast het getal staat; slaap heeft die al in het getal. */
fun unitOf(metric: String, unit: String): String = if (metric == "sleep") "" else unit

/** Uren in een zin: hele uren als "8 uur", anders als fmtHours ("7u 30m"). */
fun hoursText(v: Double): String = if (abs(v - Math.round(v)) < 1.0 / 120) "${Math.round(v)}\u00A0uur" else fmtHours(v)

/** Grote getallen kort, voor totalen: 1.234.567 → "1,2 mln". */
/** Totalen en aslabels: "18.000", vanaf een miljoen "3,5 mln" (geen Engelse "18k"). */
fun fmtCompact(v: Double): String = when {
    abs(v) >= 1_000_000 -> String.format(NL, "%.1f mln", v / 1_000_000)
    else -> NumberFormat.getIntegerInstance(NL).format(v.roundToLong())
}

fun fmtDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM yyyy", NL))

/** Korte bronnaam voor tegels, zoals "Garmin" in plaats van "Garmin Connect". */
/** Korte bronnaam voor smalle tegels. "Health Connect" blijft heel: "Health" zou ook Google Health kunnen zijn. */
fun sourceShort(source: String): String = when (val l = sourceLabel(source)) {
    "Garmin Connect" -> "Garmin"
    else -> l.removeSuffix(" via Health Connect")
}

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
    // Geen rand: in licht (salie) en donker (OLED-zwart) steken de tegels af door hun kleur
    val shape = RoundedCornerShape(20.dp)
    Surface(
        color = color,
        shape = shape,
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

/**
 * Vanaf hoeveel procent een verandering telt. Hartslag, HRV en slaap schommelen van nature een paar procent;
 * rusthartslag 53 tegen 52,4 is geen nieuws. Beweging en lichaam reageren al op 1%.
 */
fun deltaThreshold(metric: String): Double = when (categoryOf(metric)) {
    Category.Heart, Category.Recovery -> 3.0
    Category.Sleep -> 2.0
    else -> 1.0
}

/**
 * De regel onder een doel. Bij "lager is beter" (gewicht, rusthartslag) zegt een percentage niets
 * (75 ÷ 76,2 = 98% lijkt bijna gehaald): dan de afstand tot je doel.
 */
internal fun goalLine(metric: String, value: Double, goal: Double, lowerIsBetter: Boolean, unit: String): Pair<String, Boolean> {
    if (lowerIsBetter) {
        if (value <= goal) return "Doel gehaald" to true
        return "${fmt(metric, value - goal)}${if (unit.isEmpty()) "" else "\u00A0$unit"} boven je doel" to false
    }
    val f = if (goal > 0) value / goal else 0.0
    return if (f >= 1) "Doel gehaald" to true else "${(f * 100).roundToInt()}% van ${fmt(metric, goal)}" to false
}

/**
 * Verschil t.o.v. de week ervoor, groen als het beter is.
 * short: zonder "vs week", voor smalle tegels; TalkBack hoort altijd de hele zin.
 * threshold: kleinere veranderingen heten "gelijk" (zie deltaThreshold).
 */
@Composable
fun Delta(pc: Double?, lowerIsBetter: Boolean, short: Boolean = false, threshold: Double = 1.0) {
    val p = LocalPalette.current
    if (pc == null) return
    if (abs(pc) < threshold) {
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

/**
 * Uitklapmenu voor lange uitleg: standaard dicht, zodat ieder zelf kiest of hij het leest.
 * De hele kop is één knop (minstens 48 dp); TalkBack leest "uitgeklapt" of "ingeklapt" voor.
 */
@Composable
fun Expandable(title: String, modifier: Modifier = Modifier, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    var open by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "pijl")
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = if (open) "Inklappen" else "Uitklappen") { open = !open }
                .semantics { stateDescription = if (open) "Uitgeklapt" else "Ingeklapt" },
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.primary, modifier = Modifier.weight(1f))
            Icon(VIcons.ChevronDown, contentDescription = null, tint = p.primary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
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
