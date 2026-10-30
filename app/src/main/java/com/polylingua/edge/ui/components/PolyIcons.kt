package com.polylingua.edge.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn 24 dp outline icon set (stroke 2, round caps). Keeps the APK free of the 30 MB
 * material-icons-extended artifact while giving the UI a consistent technical look.
 */
object PolyIcons {
    private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                path(
                    fill = if (filled) SolidColor(Color.White) else null,
                    stroke = if (filled) null else SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    pathBuilder = block,
                )
            }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        arcTo(rad, rad, 0f, false, true, r, t + rad)
        lineTo(r, b - rad)
        arcTo(rad, rad, 0f, false, true, r - rad, b)
        lineTo(l + rad, b)
        arcTo(rad, rad, 0f, false, true, l, b - rad)
        lineTo(l, t + rad)
        arcTo(rad, rad, 0f, false, true, l + rad, t)
        close()
    }

    val Mic: ImageVector by lazy {
        icon("Mic") {
            roundRect(9f, 2f, 15f, 14f, 3f)
            moveTo(5f, 11f); arcTo(7f, 7f, 0f, false, false, 19f, 11f)
            moveTo(12f, 18f); lineTo(12f, 22f)
            moveTo(8f, 22f); lineTo(16f, 22f)
        }
    }
    val MicOff: ImageVector by lazy {
        icon("MicOff") {
            roundRect(9f, 2f, 15f, 14f, 3f)
            moveTo(5f, 11f); arcTo(7f, 7f, 0f, false, false, 19f, 11f)
            moveTo(12f, 18f); lineTo(12f, 22f)
            moveTo(3f, 3f); lineTo(21f, 21f)
        }
    }
    val Stop: ImageVector by lazy { icon("Stop", filled = true) { roundRect(6f, 6f, 18f, 18f, 2.5f) } }
    val Play: ImageVector by lazy { icon("Play", filled = true) { moveTo(7f, 4.5f); lineTo(19.5f, 12f); lineTo(7f, 19.5f); close() } }
    val Pause: ImageVector by lazy {
        icon("Pause", filled = true) { roundRect(6f, 5f, 10f, 19f, 1.2f); roundRect(14f, 5f, 18f, 19f, 1.2f) }
    }
    val Replay: ImageVector by lazy {
        icon("Replay") {
            moveTo(3f, 12f); arcTo(9f, 9f, 0f, true, true, 6f, 18.7f)
            moveTo(3f, 7f); lineTo(3f, 12f); lineTo(8f, 12f)
        }
    }
    val Headphones: ImageVector by lazy {
        icon("Headphones") {
            moveTo(4f, 17f); lineTo(4f, 12f); arcTo(8f, 8f, 0f, false, true, 20f, 12f); lineTo(20f, 17f)
            roundRect(3f, 14f, 8f, 21f, 2f)
            roundRect(16f, 14f, 21f, 21f, 2f)
        }
    }
    val Speaker: ImageVector by lazy {
        icon("Speaker") {
            moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close()
            moveTo(16.5f, 9f); arcTo(4f, 4f, 0f, false, true, 16.5f, 15f)
            moveTo(19f, 6.5f); arcTo(8f, 8f, 0f, false, true, 19f, 17.5f)
        }
    }
    val Lock: ImageVector by lazy {
        icon("Lock") {
            roundRect(5f, 11f, 19f, 21f, 2f)
            moveTo(8f, 11f); lineTo(8f, 7.5f); arcTo(4f, 4f, 0f, false, true, 16f, 7.5f); lineTo(16f, 11f)
            moveTo(12f, 15f); lineTo(12f, 17f)
        }
    }
    val Wifi: ImageVector by lazy {
        icon("Wifi") {
            moveTo(2.5f, 8.5f); arcTo(13.5f, 13.5f, 0f, false, true, 21.5f, 8.5f)
            moveTo(5.5f, 12f); arcTo(9f, 9f, 0f, false, true, 18.5f, 12f)
            moveTo(8.5f, 15.5f); arcTo(5f, 5f, 0f, false, true, 15.5f, 15.5f)
            moveTo(12f, 19.5f); lineTo(12f, 19.6f)
        }
    }
    val Globe: ImageVector by lazy {
        icon("Globe") {
            circle(12f, 12f, 9.5f)
            moveTo(2.5f, 12f); lineTo(21.5f, 12f)
            moveTo(12f, 2.5f); arcTo(14f, 14f, 0f, false, true, 12f, 21.5f)
            moveTo(12f, 2.5f); arcTo(14f, 14f, 0f, false, false, 12f, 21.5f)
        }
    }
    val Users: ImageVector by lazy {
        icon("Users") {
            circle(9f, 8f, 3.5f)
            moveTo(2.5f, 20f); arcTo(6.5f, 6.5f, 0f, false, true, 15.5f, 20f)
            moveTo(16f, 4.7f); arcTo(3.5f, 3.5f, 0f, false, true, 16f, 11.3f)
            moveTo(18f, 14.3f); arcTo(6.5f, 6.5f, 0f, false, true, 21.5f, 20f)
        }
    }
    val Bot: ImageVector by lazy {
        icon("Bot") {
            roundRect(4f, 8f, 20f, 20f, 3f)
            moveTo(12f, 8f); lineTo(12f, 4f)
            circle(12f, 3.2f, 0.8f)
            moveTo(9f, 13f); lineTo(9f, 14.5f)
            moveTo(15f, 13f); lineTo(15f, 14.5f)
            moveTo(9.5f, 17f); lineTo(14.5f, 17f)
            moveTo(2f, 13f); lineTo(2f, 16f)
            moveTo(22f, 13f); lineTo(22f, 16f)
        }
    }
    val Chat: ImageVector by lazy {
        icon("Chat") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(10f, 16f); lineTo(5f, 20f); lineTo(5f, 16f); lineTo(4f, 16f); close()
            moveTo(8f, 9.5f); lineTo(16f, 9.5f)
            moveTo(8f, 12.5f); lineTo(13f, 12.5f)
        }
    }
    val Video: ImageVector by lazy {
        icon("Video") {
            roundRect(2.5f, 6f, 15.5f, 18f, 2f)
            moveTo(15.5f, 10f); lineTo(21.5f, 6.5f); lineTo(21.5f, 17.5f); lineTo(15.5f, 14f)
        }
    }
    val VideoOff: ImageVector by lazy {
        icon("VideoOff") {
            roundRect(2.5f, 6f, 15.5f, 18f, 2f)
            moveTo(15.5f, 10f); lineTo(21.5f, 6.5f); lineTo(21.5f, 17.5f); lineTo(15.5f, 14f)
            moveTo(2f, 3f); lineTo(21f, 21f)
        }
    }
    val CameraSwitch: ImageVector by lazy {
        icon("CameraSwitch") {
            roundRect(2.5f, 6f, 21.5f, 19f, 2.5f)
            moveTo(8f, 6f); lineTo(9.5f, 3.5f); lineTo(14.5f, 3.5f); lineTo(16f, 6f)
            moveTo(8.5f, 12.5f); arcTo(3.5f, 3.5f, 0f, false, true, 15f, 11f)
            moveTo(15.5f, 13.5f); arcTo(3.5f, 3.5f, 0f, false, true, 9f, 15f)
            moveTo(15f, 9f); lineTo(15f, 11f); lineTo(13f, 11f)
        }
    }
    val HangUp: ImageVector by lazy {
        icon("HangUp") {
            moveTo(2.5f, 14.5f); arcTo(14f, 14f, 0f, false, true, 21.5f, 14.5f)
            lineTo(19.5f, 17f); lineTo(16f, 15.5f); lineTo(16f, 13f)
            arcTo(10f, 10f, 0f, false, false, 8f, 13f); lineTo(8f, 15.5f); lineTo(4.5f, 17f); close()
        }
    }
    val Send: ImageVector by lazy {
        icon("Send") {
            moveTo(3f, 11f); lineTo(21f, 3f); lineTo(13f, 21f); lineTo(11f, 13f); close()
            moveTo(11f, 13f); lineTo(21f, 3f)
        }
    }
    val FileAdd: ImageVector by lazy {
        icon("FileAdd") {
            moveTo(14f, 2.5f); lineTo(6f, 2.5f); lineTo(6f, 21.5f); lineTo(18f, 21.5f); lineTo(18f, 6.5f); close()
            moveTo(14f, 2.5f); lineTo(14f, 6.5f); lineTo(18f, 6.5f)
            moveTo(12f, 11f); lineTo(12f, 17f)
            moveTo(9f, 14f); lineTo(15f, 14f)
        }
    }
    val Sheet: ImageVector by lazy {
        icon("Sheet") {
            roundRect(3.5f, 3.5f, 20.5f, 20.5f, 2f)
            moveTo(3.5f, 9f); lineTo(20.5f, 9f)
            moveTo(3.5f, 14.5f); lineTo(20.5f, 14.5f)
            moveTo(9.5f, 3.5f); lineTo(9.5f, 20.5f)
        }
    }
    val Download: ImageVector by lazy {
        icon("Download") {
            moveTo(12f, 3f); lineTo(12f, 15f)
            moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
            moveTo(4f, 17f); lineTo(4f, 20.5f); lineTo(20f, 20.5f); lineTo(20f, 17f)
        }
    }
    val Upload: ImageVector by lazy {
        icon("Upload") {
            moveTo(12f, 15f); lineTo(12f, 3f)
            moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
            moveTo(4f, 17f); lineTo(4f, 20.5f); lineTo(20f, 20.5f); lineTo(20f, 17f)
        }
    }
    val Check: ImageVector by lazy { icon("Check") { moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f) } }
    val Close: ImageVector by lazy { icon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val Back: ImageVector by lazy { icon("Back") { moveTo(20f, 12f); lineTo(4.5f, 12f); moveTo(10.5f, 6f); lineTo(4.5f, 12f); lineTo(10.5f, 18f) } }
    val Forward: ImageVector by lazy { icon("Forward") { moveTo(4f, 12f); lineTo(19.5f, 12f); moveTo(13.5f, 6f); lineTo(19.5f, 12f); lineTo(13.5f, 18f) } }
    val ChevronRight: ImageVector by lazy { icon("ChevronRight") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) } }
    val ChevronDown: ImageVector by lazy { icon("ChevronDown") { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) } }
    val Flag: ImageVector by lazy {
        icon("Flag") {
            moveTo(5f, 21.5f); lineTo(5f, 3f)
            moveTo(5f, 4f); lineTo(18.5f, 4f); lineTo(15.5f, 8.5f); lineTo(18.5f, 13f); lineTo(5f, 13f)
        }
    }
    val Shuffle: ImageVector by lazy {
        icon("Shuffle") {
            moveTo(3f, 7f); lineTo(7f, 7f); lineTo(15f, 17f); lineTo(21f, 17f)
            moveTo(3f, 17f); lineTo(7f, 17f); lineTo(15f, 7f); lineTo(21f, 7f)
            moveTo(18f, 4f); lineTo(21f, 7f); lineTo(18f, 10f)
            moveTo(18f, 14f); lineTo(21f, 17f); lineTo(18f, 20f)
        }
    }
    val Timer: ImageVector by lazy {
        icon("Timer") {
            circle(12f, 13.5f, 8f)
            moveTo(12f, 13.5f); lineTo(12f, 9f)
            moveTo(9.5f, 2.5f); lineTo(14.5f, 2.5f)
            moveTo(18.5f, 6f); lineTo(20f, 4.5f)
        }
    }
    val Book: ImageVector by lazy {
        icon("Book") {
            moveTo(12f, 6.5f); arcTo(9f, 9f, 0f, false, false, 3f, 4.5f); lineTo(3f, 18.5f)
            arcTo(9f, 9f, 0f, false, true, 12f, 20.5f)
            arcTo(9f, 9f, 0f, false, true, 21f, 18.5f); lineTo(21f, 4.5f)
            arcTo(9f, 9f, 0f, false, false, 12f, 6.5f); lineTo(12f, 20.5f)
        }
    }
    val Chart: ImageVector by lazy {
        icon("Chart") {
            moveTo(3.5f, 20.5f); lineTo(20.5f, 20.5f)
            moveTo(7f, 17f); lineTo(7f, 11f)
            moveTo(12f, 17f); lineTo(12f, 6f)
            moveTo(17f, 17f); lineTo(17f, 13f)
        }
    }
    val Waveform: ImageVector by lazy {
        icon("Waveform") {
            moveTo(3f, 12f); lineTo(3f, 12.1f)
            moveTo(6.5f, 9f); lineTo(6.5f, 15f)
            moveTo(10f, 5f); lineTo(10f, 19f)
            moveTo(13.5f, 8f); lineTo(13.5f, 16f)
            moveTo(17f, 10f); lineTo(17f, 14f)
            moveTo(20.5f, 11f); lineTo(20.5f, 13f)
        }
    }
    val Sliders: ImageVector by lazy {
        icon("Sliders") {
            moveTo(4f, 6f); lineTo(20f, 6f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 18f); lineTo(20f, 18f)
            circle(9f, 6f, 2f)
            circle(15f, 12f, 2f)
            circle(7f, 18f, 2f)
        }
    }
    val Trash: ImageVector by lazy {
        icon("Trash") {
            moveTo(3.5f, 6f); lineTo(20.5f, 6f)
            moveTo(9f, 6f); lineTo(9f, 3.5f); lineTo(15f, 3.5f); lineTo(15f, 6f)
            moveTo(5.5f, 6f); lineTo(6.5f, 21f); lineTo(17.5f, 21f); lineTo(18.5f, 6f)
            moveTo(10f, 10.5f); lineTo(10f, 16.5f)
            moveTo(14f, 10.5f); lineTo(14f, 16.5f)
        }
    }
    val Share: ImageVector by lazy {
        icon("Share") {
            circle(18f, 5f, 2.5f)
            circle(6f, 12f, 2.5f)
            circle(18f, 19f, 2.5f)
            moveTo(8.2f, 10.8f); lineTo(15.8f, 6.2f)
            moveTo(8.2f, 13.2f); lineTo(15.8f, 17.8f)
        }
    }
    val Sparkle: ImageVector by lazy {
        icon("Sparkle") {
            moveTo(12f, 3f); lineTo(13.8f, 10.2f); lineTo(21f, 12f); lineTo(13.8f, 13.8f); lineTo(12f, 21f)
            lineTo(10.2f, 13.8f); lineTo(3f, 12f); lineTo(10.2f, 10.2f); close()
        }
    }
    val Loop: ImageVector by lazy {
        icon("Loop") {
            moveTo(17f, 3f); lineTo(20.5f, 6.5f); lineTo(17f, 10f)
            moveTo(3.5f, 11f); lineTo(3.5f, 9.5f); arcTo(3f, 3f, 0f, false, true, 6.5f, 6.5f); lineTo(20.5f, 6.5f)
            moveTo(7f, 21f); lineTo(3.5f, 17.5f); lineTo(7f, 14f)
            moveTo(20.5f, 13f); lineTo(20.5f, 14.5f); arcTo(3f, 3f, 0f, false, true, 17.5f, 17.5f); lineTo(3.5f, 17.5f)
        }
    }
    val Speed: ImageVector by lazy {
        icon("Speed") {
            moveTo(3.5f, 17f); arcTo(9f, 9f, 0f, true, true, 20.5f, 17f)
            moveTo(12f, 14f); lineTo(16.5f, 8.5f)
            circle(12f, 14f, 1.2f)
        }
    }
    val Eye: ImageVector by lazy {
        icon("Eye") {
            moveTo(2f, 12f); arcTo(11f, 11f, 0f, false, true, 22f, 12f); arcTo(11f, 11f, 0f, false, true, 2f, 12f); close()
            circle(12f, 12f, 3f)
        }
    }
    val Info: ImageVector by lazy {
        icon("Info") {
            circle(12f, 12f, 9.5f)
            moveTo(12f, 11f); lineTo(12f, 16.5f)
            moveTo(12f, 7.5f); lineTo(12f, 7.6f)
        }
    }
    val Warning: ImageVector by lazy {
        icon("Warning") {
            moveTo(12f, 3f); lineTo(22f, 20.5f); lineTo(2f, 20.5f); close()
            moveTo(12f, 9.5f); lineTo(12f, 14f)
            moveTo(12f, 17.2f); lineTo(12f, 17.3f)
        }
    }
    val Trophy: ImageVector by lazy {
        icon("Trophy") {
            moveTo(7f, 3.5f); lineTo(17f, 3.5f); lineTo(17f, 9f); arcTo(5f, 5f, 0f, false, true, 7f, 9f); close()
            moveTo(7f, 5.5f); lineTo(3.5f, 5.5f); lineTo(3.5f, 7f); arcTo(3.5f, 3.5f, 0f, false, false, 7.3f, 10.5f)
            moveTo(17f, 5.5f); lineTo(20.5f, 5.5f); lineTo(20.5f, 7f); arcTo(3.5f, 3.5f, 0f, false, true, 16.7f, 10.5f)
            moveTo(12f, 14f); lineTo(12f, 18f)
            moveTo(8f, 21f); lineTo(16f, 21f); lineTo(15f, 18f); lineTo(9f, 18f); close()
        }
    }
    val Target: ImageVector by lazy {
        icon("Target") {
            circle(12f, 12f, 9.5f)
            circle(12f, 12f, 5.5f)
            circle(12f, 12f, 1.5f)
        }
    }
    val Home: ImageVector by lazy {
        icon("Home") {
            moveTo(3f, 11f); lineTo(12f, 3.5f); lineTo(21f, 11f)
            moveTo(5.5f, 9.5f); lineTo(5.5f, 20.5f); lineTo(18.5f, 20.5f); lineTo(18.5f, 9.5f)
            moveTo(10f, 20.5f); lineTo(10f, 14.5f); lineTo(14f, 14.5f); lineTo(14f, 20.5f)
        }
    }
    val History: ImageVector by lazy {
        icon("History") {
            moveTo(3.5f, 12f); arcTo(8.5f, 8.5f, 0f, true, false, 6f, 6f)
            moveTo(3f, 3.5f); lineTo(3f, 7.5f); lineTo(7f, 7.5f)
            moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
        }
    }
    val SdCard: ImageVector by lazy {
        icon("SdCard") {
            moveTo(9f, 2.5f); lineTo(18f, 2.5f); arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 4f); lineTo(19.5f, 20f)
            arcTo(1.5f, 1.5f, 0f, false, true, 18f, 21.5f); lineTo(6f, 21.5f); arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 20f)
            lineTo(4.5f, 7f); close()
            moveTo(9f, 6f); lineTo(9f, 9f)
            moveTo(12f, 6f); lineTo(12f, 9f)
            moveTo(15f, 6f); lineTo(15f, 9f)
        }
    }
    val Cpu: ImageVector by lazy {
        icon("Cpu") {
            roundRect(6f, 6f, 18f, 18f, 1.5f)
            roundRect(9.5f, 9.5f, 14.5f, 14.5f, 0.5f)
            moveTo(9f, 2.5f); lineTo(9f, 6f); moveTo(15f, 2.5f); lineTo(15f, 6f)
            moveTo(9f, 18f); lineTo(9f, 21.5f); moveTo(15f, 18f); lineTo(15f, 21.5f)
            moveTo(2.5f, 9f); lineTo(6f, 9f); moveTo(2.5f, 15f); lineTo(6f, 15f)
            moveTo(18f, 9f); lineTo(21.5f, 9f); moveTo(18f, 15f); lineTo(21.5f, 15f)
        }
    }
    val Film: ImageVector by lazy {
        icon("Film") {
            roundRect(3f, 3.5f, 21f, 20.5f, 2f)
            moveTo(7.5f, 3.5f); lineTo(7.5f, 20.5f)
            moveTo(16.5f, 3.5f); lineTo(16.5f, 20.5f)
            moveTo(3f, 12f); lineTo(21f, 12f)
            moveTo(3f, 8f); lineTo(7.5f, 8f); moveTo(3f, 16f); lineTo(7.5f, 16f)
            moveTo(16.5f, 8f); lineTo(21f, 8f); moveTo(16.5f, 16f); lineTo(21f, 16f)
        }
    }
    val Cards: ImageVector by lazy {
        icon("Cards") {
            roundRect(7f, 3f, 20f, 17f, 2f)
            moveTo(4f, 7f); lineTo(4f, 19f); arcTo(2f, 2f, 0f, false, false, 6f, 21f); lineTo(16f, 21f)
        }
    }
    val Quiz: ImageVector by lazy {
        icon("Quiz") {
            roundRect(3.5f, 3.5f, 20.5f, 20.5f, 3f)
            moveTo(9.2f, 9.5f); arcTo(2.8f, 2.8f, 0f, true, true, 12f, 12.3f); lineTo(12f, 14f)
            moveTo(12f, 17f); lineTo(12f, 17.1f)
        }
    }
    val Bolt: ImageVector by lazy {
        icon("Bolt") { moveTo(13f, 2.5f); lineTo(5f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 21.5f); lineTo(19f, 10f); lineTo(12.5f, 10f); close() }
    }
    val Translate: ImageVector by lazy {
        icon("Translate") {
            moveTo(3f, 5f); lineTo(12f, 5f)
            moveTo(7.5f, 3f); lineTo(7.5f, 5f)
            moveTo(10f, 5f); arcTo(10f, 10f, 0f, false, true, 4f, 14f)
            moveTo(5.5f, 8.5f); arcTo(8f, 8f, 0f, false, false, 11f, 13f)
            moveTo(12.5f, 21f); lineTo(16.5f, 11f); lineTo(20.5f, 21f)
            moveTo(14f, 17.5f); lineTo(19f, 17.5f)
        }
    }
    val Edit: ImageVector by lazy {
        icon("Edit") {
            moveTo(16.5f, 3.5f); lineTo(20.5f, 7.5f); lineTo(8f, 20f); lineTo(3.5f, 20.5f); lineTo(4f, 16f); close()
            moveTo(14f, 6f); lineTo(18f, 10f)
        }
    }
}
