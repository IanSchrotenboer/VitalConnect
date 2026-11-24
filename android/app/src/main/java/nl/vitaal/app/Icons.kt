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
    val Moon = line("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Person = line("person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M4 20a8 8 0 0 1 16 0")
    val ChevronRight = line("right", "M9 6l6 6-6 6")
    val Sync = line("sync", "M20 12a8 8 0 1 1-2.3-5.7", "M20 4v5h-5")
    val Sliders = line("sliders", "M4 6h9", "M17 6h3", "M15 4v4", "M4 12h3", "M11 12h9", "M9 10v4", "M4 18h11", "M19 18h1", "M17 16v4")
    val ChevronDown = line("down", "M6 9l6 6 6-6")
    val ChevronUp = line("up", "M6 15l6-6 6 6")
    val Back = line("back", "M15 6l-6 6 6 6")
}
