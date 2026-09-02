package com.sustream.tv.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icons drawn in code, in the Lucide style the prototype uses.
 *
 * `material-icons-extended` would supply equivalents, but it adds several thousand vector assets
 * and a few megabytes to the APK for the dozen icons this app actually needs — a poor trade on a
 * Fire TV Stick. `material-icons-core` covers the common glyphs (search, settings, star, play,
 * arrows), and everything below fills the gaps: the prototype's `zap`, `radio`, `film`, `bookmark`
 * and the player transport controls.
 *
 * All are 24x24 stroked paths at 2 dp, matching Lucide's own geometry, so they sit consistently
 * next to the Material icons.
 */
object SuStreamIcons {

    /** Brand mark: the prototype's header `zap` bolt. */
    val Zap: ImageVector by lazy {
        stroked("Zap") {
            moveTo(13f, 2f)
            lineTo(3f, 14f)
            horizontalLineTo(12f)
            lineTo(11f, 22f)
            lineTo(21f, 10f)
            horizontalLineTo(12f)
            close()
        }
    }

    /** Live TV / IPTV. The prototype's amber `radio`. */
    val Radio: ImageVector by lazy {
        stroked("Radio") {
            // Broadcast arcs either side of a central dot.
            moveTo(4.9f, 19.1f)
            curveTo(1f, 15.2f, 1f, 8.8f, 4.9f, 4.9f)
            moveTo(7.8f, 16.2f)
            curveTo(5.8f, 14.2f, 5.8f, 10.9f, 7.8f, 8.8f)
            moveTo(16.2f, 8.8f)
            curveTo(18.2f, 10.8f, 18.2f, 14.1f, 16.2f, 16.2f)
            moveTo(19.1f, 4.9f)
            curveTo(23f, 8.8f, 23f, 15.2f, 19.1f, 19.1f)
            moveTo(13f, 12f)
            curveTo(13f, 12.6f, 12.6f, 13f, 12f, 13f)
            curveTo(11.4f, 13f, 11f, 12.6f, 11f, 12f)
            curveTo(11f, 11.4f, 11.4f, 11f, 12f, 11f)
            curveTo(12.6f, 11f, 13f, 11.4f, 13f, 12f)
            close()
        }
    }

    /** Films. The prototype's `film` strip. */
    val Film: ImageVector by lazy {
        stroked("Film") {
            moveTo(4f, 3f)
            horizontalLineTo(20f)
            verticalLineTo(21f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 7.5f)
            horizontalLineTo(8f)
            moveTo(4f, 12f)
            horizontalLineTo(8f)
            moveTo(4f, 16.5f)
            horizontalLineTo(8f)
            moveTo(16f, 7.5f)
            horizontalLineTo(20f)
            moveTo(16f, 12f)
            horizontalLineTo(20f)
            moveTo(16f, 16.5f)
            horizontalLineTo(20f)
        }
    }

    /** TV shows: a monitor with a stand. */
    val Tv: ImageVector by lazy {
        stroked("Tv") {
            moveTo(3f, 6f)
            horizontalLineTo(21f)
            verticalLineTo(17f)
            horizontalLineTo(3f)
            close()
            moveTo(8f, 21f)
            horizontalLineTo(16f)
            moveTo(12f, 17f)
            verticalLineTo(21f)
        }
    }

    /** Library / saved. */
    val Bookmark: ImageVector by lazy {
        stroked("Bookmark") {
            moveTo(6f, 3f)
            horizontalLineTo(18f)
            verticalLineTo(21f)
            lineTo(12f, 16f)
            lineTo(6f, 21f)
            close()
        }
    }

    /** Filled bookmark, for the "saved" state. */
    val BookmarkFilled: ImageVector by lazy {
        filled("BookmarkFilled") {
            moveTo(6f, 3f)
            horizontalLineTo(18f)
            verticalLineTo(21f)
            lineTo(12f, 16f)
            lineTo(6f, 21f)
            close()
        }
    }

    val Pause: ImageVector by lazy {
        filled("Pause") {
            moveTo(6.5f, 4f)
            horizontalLineTo(10f)
            verticalLineTo(20f)
            horizontalLineTo(6.5f)
            close()
            moveTo(14f, 4f)
            horizontalLineTo(17.5f)
            verticalLineTo(20f)
            horizontalLineTo(14f)
            close()
        }
    }

    val Rewind: ImageVector by lazy {
        filled("Rewind") {
            moveTo(11f, 12f)
            lineTo(21f, 5f)
            verticalLineTo(19f)
            close()
            moveTo(2f, 12f)
            lineTo(12f, 5f)
            verticalLineTo(19f)
            close()
        }
    }

    val FastForward: ImageVector by lazy {
        filled("FastForward") {
            moveTo(13f, 12f)
            lineTo(3f, 5f)
            verticalLineTo(19f)
            close()
            moveTo(22f, 12f)
            lineTo(12f, 5f)
            verticalLineTo(19f)
            close()
        }
    }

    /** Subtitles: a panel with two text lines. */
    val Subtitles: ImageVector by lazy {
        stroked("Subtitles") {
            moveTo(3f, 5f)
            horizontalLineTo(21f)
            verticalLineTo(19f)
            horizontalLineTo(3f)
            close()
            moveTo(6.5f, 14.5f)
            horizontalLineTo(12f)
            moveTo(15f, 14.5f)
            horizontalLineTo(17.5f)
            moveTo(6.5f, 10.5f)
            horizontalLineTo(9f)
            moveTo(12f, 10.5f)
            horizontalLineTo(17.5f)
        }
    }

    /** Audio track selection: a speaker with waves. */
    val AudioTrack: ImageVector by lazy {
        stroked("AudioTrack") {
            moveTo(4f, 9f)
            horizontalLineTo(7f)
            lineTo(11.5f, 5f)
            verticalLineTo(19f)
            lineTo(7f, 15f)
            horizontalLineTo(4f)
            close()
            moveTo(15f, 9.5f)
            curveTo(16.3f, 10.8f, 16.3f, 13.2f, 15f, 14.5f)
            moveTo(18f, 6.5f)
            curveTo(21f, 9.5f, 21f, 14.5f, 18f, 17.5f)
        }
    }

    /** Rising trend, for the "Trending" rail header. */
    val TrendingUp: ImageVector by lazy {
        stroked("TrendingUp") {
            moveTo(3f, 17f)
            lineTo(9f, 11f)
            lineTo(13f, 15f)
            lineTo(21f, 7f)
            moveTo(15f, 7f)
            horizontalLineTo(21f)
            verticalLineTo(13f)
        }
    }

    /** Clock, for the "Continue watching" rail header. */
    val Clock: ImageVector by lazy {
        stroked("Clock") {
            moveTo(21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            close()
            moveTo(12f, 7f)
            verticalLineTo(12.5f)
            lineTo(16f, 14.5f)
        }
    }

    /** Guide grid, for the EPG tab. */
    val Grid: ImageVector by lazy {
        stroked("Grid") {
            moveTo(3f, 4f)
            horizontalLineTo(21f)
            verticalLineTo(20f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 9.3f)
            horizontalLineTo(21f)
            moveTo(3f, 14.6f)
            horizontalLineTo(21f)
            moveTo(9f, 9.3f)
            verticalLineTo(20f)
        }
    }

    /** Shield, used to mark the source-provenance notice. */
    val Shield: ImageVector by lazy {
        stroked("Shield") {
            moveTo(12f, 2.5f)
            lineTo(20f, 5.5f)
            verticalLineTo(12f)
            curveTo(20f, 17f, 16.5f, 20.3f, 12f, 21.5f)
            curveTo(7.5f, 20.3f, 4f, 17f, 4f, 12f)
            verticalLineTo(5.5f)
            close()
        }
    }

    /** Server / provider status. */
    val Server: ImageVector by lazy {
        stroked("Server") {
            moveTo(3f, 4f)
            horizontalLineTo(21f)
            verticalLineTo(10f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 14f)
            horizontalLineTo(21f)
            verticalLineTo(20f)
            horizontalLineTo(3f)
            close()
            moveTo(6.5f, 7f)
            horizontalLineTo(6.6f)
            moveTo(6.5f, 17f)
            horizontalLineTo(6.6f)
        }
    }

    // ---- Builders -----------------------------------------------------------

    private const val VIEWPORT = 24f
    private const val STROKE = 2f

    private fun stroked(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun filled(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            path(fill = SolidColor(Color.White), pathBuilder = block)
        }.build()
}
