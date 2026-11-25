package nl.vitaal.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Lijniconen in dezelfde stijl als het prototype (24×24, lijn 2). */
object VIcons {
    private fun line(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Today = line(
        "today",
        "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
        "M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
    )
    val Health = line("health", "M3 12h4l2.5-6 5 12 2.5-6h4")
    /** Halter: tabblad Training. */
    val Training = line("training", "M6.5 7v10", "M17.5 7v10", "M3.5 9.5v5", "M20.5 9.5v5", "M6.5 12h11")
    val Moon = line("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Person = line("person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M4 20a8 8 0 0 1 16 0")
    val ChevronRight = line("right", "M9 6l6 6-6 6")
    val ArrowRight = line("arrow-right", "M5 12h14", "M13 6l6 6-6 6")
    val Sync = line("sync", "M20 12a8 8 0 1 1-2.3-5.7", "M20 4v5h-5")
    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5")
    val Alert = line("alert", "M12 9v4", "M12 16.5v.01", "M10.3 4.2L2.8 17.3A2 2 0 0 0 4.5 20.3h15a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z")
    val Sliders = line("sliders", "M4 6h9", "M17 6h3", "M15 4v4", "M4 12h3", "M11 12h9", "M9 10v4", "M4 18h11", "M19 18h1", "M17 16v4")
    val ChevronDown = line("down", "M6 9l6 6 6-6")
    val ChevronUp = line("up", "M6 15l6-6 6 6")
    val Back = line("back", "M15 6l-6 6 6 6")

    // Sporten (lijst op Training)
    val SportRun = line("sport-run", "M15.5 4.5a1.5 1.5 0 1 0 0 .01", "M13.5 8.5l-3 3.5 3 2.5-1.5 5", "M13.5 8.5l2.5 2.5 3-.5", "M13.5 8.5l-4.5 .5-2 2.5", "M10.5 12l-2 4H4.5")
    val SportWalk = line("sport-walk", "M13 4.5a1.5 1.5 0 1 0 0 .01", "M12.5 8l-1 6 2.5 2.5V21", "M11.5 14l-2.5 7", "M12.5 8l2.5 3.5 2.5 1", "M12.5 8l-3 2.5-1 3")
    val SportBike = line("sport-bike", "M2 17.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0", "M15 17.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0",
        "M5.5 17.5l3.5-7h6l3.5 7", "M9 10.5l3.5 7h-7", "M15 10.5L13.5 6H11")
    val SportSwim = line("sport-swim", "M3 18c1.5 0 1.5-1.5 3-1.5s1.5 1.5 3 1.5 1.5-1.5 3-1.5 1.5 1.5 3 1.5 1.5-1.5 3-1.5 1.5 1.5 3 1.5",
        "M17 8.5a1.5 1.5 0 1 0 0 .01", "M5 13l5-5 3.5 3 2.5-1.5")
    val SportYoga = line("sport-yoga", "M12 4.5a1.5 1.5 0 1 0 0 .01", "M12 8v6", "M5 9.5l7 1.5 7-1.5", "M7 20l5-6 5 6")
}
