package nl.vitaal.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Beginscherm. Android toont bij het starten eerst alleen de achtergrondkleur van de app (licht of donker,
 * zie values-v31/themes.xml); daarna neemt dit scherm het over met het bewegende logo. De app laadt
 * erachter en verschijnt zodra de gegevens er zijn (minstens SPLASH_MIN_MS, hooguit SPLASH_MAX_MS).
 */
private const val SPLASH_MAX_MS = 6000L

/** Het figuurtje uit het logo, in de coördinaten van brand/logo.svg (108 × 108). */
private object Mark {
    val leftArm = PathParser().parsePathString("M25 41.5 C38 37.5 50 43.5 54 54 C57 62.5 55 72 50.5 80.5 C47.5 70.5 43 60.5 37 52.5 C33 47 29.5 43.5 25 41.5 Z").toPath()
    val rightArm = PathParser().parsePathString("M84.5 31.5 C70.5 33.5 60 43.5 57 56 C55 66 51.5 74.5 43.5 82.5 C58.5 78 66.5 67 71.5 55.5 C75.5 46.5 79 37.5 84.5 31.5 Z").toPath()
    val leaf = PathParser().parsePathString("M83 51.5 C81 66.5 70.5 80 50 82.5 C56.5 76 61 70 65.5 63.5 C70.5 56.5 76 53 83 51.5 Z").toPath()
    val sun = Color(0xFFDDB15A)
    val apricot = Color(0xFFF5935A)
    val seaGreen = Color(0xFF4CC3B3)
    val indigo = Color(0xFF8C97F2)
}

/** Het figuurtje zonder icoonvlak. sep: de achtergrondkleur, als dunne rand tussen de delen (zoals in het logo). */
@Composable
fun LogoMark(modifier: Modifier, sep: Color) {
    Canvas(modifier) {
        val s = size.minDimension / 74f   // uitsnede 18..92 × 16..90 van het logo
        withTransform({ scale(s, s, pivot = Offset.Zero); translate(-18f, -16f) }) {
            drawCircle(Mark.sun, radius = 7.2f, center = Offset(55f, 29.5f))
            val edge = Stroke(width = 2.6f, join = StrokeJoin.Round)
            for ((path, color) in listOf(Mark.leftArm to Mark.apricot, Mark.rightArm to Mark.seaGreen, Mark.leaf to Mark.indigo)) {
                drawPath(path, sep, style = edge)
                drawPath(path, color)
            }
        }
    }
}

/** Legt het beginscherm over de app tot ready (gegevens geladen of niet ingelogd) en de intro klaar is. */
@Composable
fun SplashHost(ready: Boolean, content: @Composable () -> Unit) {
    var show by rememberSaveable { mutableStateOf(true) }
    var introDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(SPLASH_MIN_MS); introDone = true }
    LaunchedEffect(Unit) { delay(SPLASH_MAX_MS); show = false }
    LaunchedEffect(ready, introDone) { if (ready && introDone) show = false }
    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = show,
            enter = EnterTransition.None,
            exit = fadeOut(tween(350)) + scaleOut(tween(350), targetScale = 1.05f),
        ) { SplashScreen() }
    }
}

@Composable
private fun SplashScreen() {
    val p = LocalPalette.current
    val word = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(250); word.animateTo(1f, tween(400)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .pointerInput(Unit) { detectTapGestures { } }   // tikken gaan niet door naar de app eronder
            .semantics { contentDescription = "$APP_NAME wordt geladen" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            SplashLogo(p)
            Text(
                APP_NAME,
                style = MaterialTheme.typography.titleLarge,
                color = p.ink,
                modifier = Modifier.graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 6.dp.toPx() },
            )
        }
    }
}

// ---------- Animatie: Dagringen ----------
// De drie dagringen (abrikoos, zeegroen, indigo, zoals op Vandaag) tekenen zich rond het figuurtje
// en draaien rustig door zolang de app laadt.
private const val SPLASH_MIN_MS = 1200L

@Composable
private fun SplashLogo(p: Palette) {
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(1300, easing = LinearEasing)) }
    val spin = rememberInfiniteTransition(label = "ringen")
    val turn1 by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ring1")
    val turn2 by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(3400, easing = LinearEasing)), label = "ring2")
    val turn3 by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "ring3")
    val track = p.ink.copy(alpha = 0.07f)
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val u = size.minDimension / 200f
            val ms = intro.value * 1300f
            val rings = listOf(Triple(80f, p.activity, turn1), Triple(68f, p.recovery, turn2), Triple(56f, p.sleep, turn3))
            rings.forEachIndexed { i, (r, color, turn) ->
                val radius = r * u
                val stroke = Stroke(width = 7f * u, cap = StrokeCap.Round)
                drawCircle(track, radius = radius, style = stroke)
                val drawn = FastOutSlowInEasing.transform(((ms - 150f * (i + 1)) / 900f).coerceIn(0f, 1f))
                if (drawn > 0f) rotate(-90f + turn) {
                    drawArc(
                        color, startAngle = 0f, sweepAngle = 252f * drawn, useCenter = false,
                        topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2), style = stroke,
                    )
                }
            }
        }
        LogoMark(
            Modifier.size(76.dp).graphicsLayer {
                val e = FastOutSlowInEasing.transform((intro.value * 1300f / 500f).coerceIn(0f, 1f))
                alpha = e; scaleX = 0.85f + 0.15f * e; scaleY = scaleX
            },
            sep = p.bg,
        )
    }
}
