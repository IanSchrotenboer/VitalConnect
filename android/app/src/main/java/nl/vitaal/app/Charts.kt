package nl.vitaal.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition

/** Klein lijntje van de afgelopen dagen, met een stip op de laatste waarde. */
@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pts = values.withIndex().filter { it.value != null }
        if (pts.size < 2) return@Canvas
        val lo = pts.minOf { it.value!! }
        val hi = pts.maxOf { it.value!! }
        val range = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = 3.dp.toPx()
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        fun y(v: Double) = size.height - pad - ((v - lo) / range).toFloat() * (size.height - 2 * pad)
        val path = Path()
        pts.forEachIndexed { i, iv ->
            val x = iv.index * stepX
            if (i == 0) path.moveTo(x, y(iv.value!!)) else path.lineTo(x, y(iv.value!!))
        }
        drawPath(path, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = pts.last()
        drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(last.index * stepX, y(last.value!!)))
    }
}

/** Dagringen: één ring per doel, van buiten naar binnen. Groeit bij het openen. */
@Composable
fun Rings(
    rings: List<Pair<Float, Color>>,
    center: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    refreshing: Boolean = false,
) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val t by animateFloatAsState(if (started) 1f else 0f, tween(900), label = "rings")
    // Na vernieuwen groeien de ringen naar de nieuwe waarde in plaats van te verspringen
    val shown = rings.mapIndexed { i, (f, _) -> animateFloatAsState(f.coerceIn(0f, 1f), tween(700), label = "ring$i").value }
    val pulse = if (refreshing) {
        rememberInfiniteTransition(label = "vernieuwen")
            .animateFloat(1f, 0.45f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "puls").value
    } else 1f
    Box(modifier.then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val d = size.minDimension
            val w = d * 0.085f
            val gap = w * 0.42f
            rings.forEachIndexed { i, (_, c) ->
                val inset = w / 2 + i * (w + gap)
                val sz = Size(d - 2 * inset, d - 2 * inset)
                val tl = Offset((size.width - sz.width) / 2, (size.height - sz.height) / 2)
                drawArc(c.copy(alpha = 0.22f), 0f, 360f, false, tl, sz, style = Stroke(w))
                val sweep = 360f * shown[i] * t
                if (sweep > 0.5f) drawArc(c.copy(alpha = pulse), -90f, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Text(center, style = MaterialTheme.typography.titleLarge, color = DialPalette.dialInk)
    }
}

/**
 * Trendgrafiek: staven voor dagtotalen, een lijn voor gemiddelden en metingen.
 * Tik of veeg om een dag te kiezen; de gekozen dag is vol gekleurd.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    bars: Boolean,
    goal: Double?,
    color: Color,
    selected: Int,
    onSelect: (Int) -> Unit,
    axis: (Double) -> String,
    modifier: Modifier = Modifier,
    format: (Double) -> String = axis,
    name: String = "",
) {
    val p = LocalPalette.current
    val tm = rememberTextMeasurer()
    val label = TextStyle(fontFamily = Figtree, fontSize = 12.sp, color = p.muted)
    val n = points.size
    val values = points.mapNotNull { it.value }
    if (n == 0 || values.isEmpty()) {
        Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nog geen data in deze periode", color = p.muted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val leftDp = 46.dp
    val rightDp = 8.dp

    // Voor TalkBack: een samenvatting, de gekozen dag als stand, en Vorige/Volgende dag als acties
    val withVal = points.withIndex().filter { it.value.value != null }
    val hi = withVal.maxByOrNull { it.value.value!! }
    val lo = withVal.minByOrNull { it.value.value!! }
    val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", NL)
    val summary = "Grafiek $name over $n dagen: gemiddeld ${format(values.average())}" +
        (hi?.let { ", hoogste ${format(it.value.value!!)} op ${it.value.day.format(dayFmt)}" } ?: "") +
        (lo?.let { ", laagste ${format(it.value.value!!)} op ${it.value.day.format(dayFmt)}" } ?: "")
    val sel = points.getOrNull(selected)
    val state = sel?.let { "${it.day.format(dayFmt)}: ${it.value?.let(format) ?: "geen data"}" } ?: ""
    Canvas(
        modifier
            .semantics {
                contentDescription = summary
                stateDescription = state
                customActions = listOf(
                    CustomAccessibilityAction("Vorige dag") { if (selected > 0) { onSelect(selected - 1); true } else false },
                    CustomAccessibilityAction("Volgende dag") { if (selected < n - 1) { onSelect(selected + 1); true } else false },
                )
            }
            .pointerInput(n) {
                detectTapGestures { o ->
                    val l = leftDp.toPx(); val bw = (size.width - l - rightDp.toPx()) / n
                    onSelect(((o.x - l) / bw).toInt().coerceIn(0, n - 1))
                }
            }
            .pointerInput(n) {
                detectHorizontalDragGestures { change, _ ->
                    val l = leftDp.toPx(); val bw = (size.width - l - rightDp.toPx()) / n
                    onSelect(((change.position.x - l) / bw).toInt().coerceIn(0, n - 1))
                }
            }
    ) {
        val l = leftDp.toPx()
        val r = rightDp.toPx()
        val top = 10.dp.toPx()
        val bottom = 24.dp.toPx()
        val w = size.width - l - r
        val h = size.height - top - bottom
        val bw = w / n

        var lo = values.min()
        var hi = values.max()
        if (goal != null) { lo = minOf(lo, goal); hi = maxOf(hi, goal) }
        if (bars) lo = 0.0 else { val pad = ((hi - lo) * 0.15).takeIf { it > 0 } ?: 1.0; lo -= pad; hi += pad }
        if (bars) hi *= 1.08
        if (hi <= lo) hi = lo + 1
        fun y(v: Double) = top + h - ((v - lo) / (hi - lo)).toFloat() * h
        fun x(i: Int) = l + bw * (i + 0.5f)

        // Hulplijnen en waarden op de y-as
        for (k in 0..4) {
            val v = lo + (hi - lo) * k / 4
            val yy = y(v)
            drawLine(p.line, Offset(l, yy), Offset(size.width - r, yy), strokeWidth = 1.dp.toPx())
            if (k % 2 == 0) {
                val m = tm.measure(axis(v), label)
                drawText(m, topLeft = Offset(l - 6.dp.toPx() - m.size.width, yy - m.size.height / 2f))
            }
        }

        // Datums op de x-as, gerekend vanaf vandaag terug
        val step = when { n <= 7 -> 1; n <= 31 -> 7; n <= 92 -> 30; else -> 91 }
        val fmt = if (n <= 7) DateTimeFormatter.ofPattern("EEE", NL) else DateTimeFormatter.ofPattern("d MMM", NL)
        for (i in n - 1 downTo 0 step step) {
            val m = tm.measure(points[i].day.format(fmt), label)
            val cx = (x(i) - m.size.width / 2f).coerceIn(l, size.width - r - m.size.width)
            drawText(m, topLeft = Offset(cx, size.height - m.size.height))
        }

        if (bars) {
            val bwBar = (bw * 0.62f).coerceAtLeast(1.5f)
            points.forEachIndexed { i, pt ->
                val v = pt.value ?: return@forEachIndexed
                val yy = y(v)
                drawRoundRect(
                    color = if (i == selected) color else color.copy(alpha = 0.42f),
                    topLeft = Offset(x(i) - bwBar / 2, yy),
                    size = Size(bwBar, (y(lo) - yy).coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(minOf(bwBar / 2, 6.dp.toPx())),
                )
            }
        } else {
            val line = Path()
            val area = Path()
            var first = -1
            var lastI = -1
            points.forEachIndexed { i, pt ->
                val v = pt.value ?: return@forEachIndexed
                if (first < 0) { first = i; line.moveTo(x(i), y(v)); area.moveTo(x(i), y(lo)); area.lineTo(x(i), y(v)) }
                else { line.lineTo(x(i), y(v)); area.lineTo(x(i), y(v)) }
                lastI = i
            }
            if (lastI >= 0) {
                area.lineTo(x(lastI), y(lo)); area.close()
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), startY = top, endY = top + h))
                drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            points.getOrNull(selected)?.value?.let { v ->
                drawLine(p.muted, Offset(x(selected), top), Offset(x(selected), top + h), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawCircle(p.card, 7.dp.toPx(), Offset(x(selected), y(v)))
                drawCircle(color, 5.dp.toPx(), Offset(x(selected), y(v)))
            }
        }

        // Doellijn
        if (goal != null && goal in lo..hi) {
            val gy = y(goal)
            drawLine(p.ink, Offset(l, gy), Offset(size.width - r, gy), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            val m = tm.measure("Doel", label.copy(color = p.ink))
            drawText(m, topLeft = Offset(size.width - r - m.size.width, gy - m.size.height - 2.dp.toPx()))
        }
    }
}
