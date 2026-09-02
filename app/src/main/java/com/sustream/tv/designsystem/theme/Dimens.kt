package com.sustream.tv.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing and sizing for a 10-foot UI.
 *
 * Design canvas: **960 x 540 dp**. A 1080p TV panel reports 1920x1080 px at xhdpi (density 2.0),
 * and a 4K panel reports 3840x2160 at density 4.0, so both resolve to the same 960x540 dp canvas.
 * Everything below is expressed against that canvas, which is why the numbers look small next to
 * the prototype's phone pixels.
 *
 * The prototype's *proportions* are preserved (2:3 posters, 16:9 stills, rounded-2xl cards, a
 * hero occupying roughly the top 45% of the viewport) while absolute sizes are scaled up for
 * viewing distance and its type floor is raised — see [Type].
 */
object Dimens {
    // ---- Overscan -----------------------------------------------------------
    /**
     * TVs crop the edges of the picture. Android TV's guidance is a 5% safe-area inset, and Fire
     * TV's is the same, so content never starts closer than this to the panel edge.
     */
    val overscanHorizontal: Dp = 48.dp
    val overscanVertical: Dp = 27.dp

    // ---- Spacing scale (4 dp base) -----------------------------------------
    val space0: Dp = 0.dp
    val space1: Dp = 4.dp
    val space2: Dp = 8.dp
    val space3: Dp = 12.dp
    val space4: Dp = 16.dp
    val space5: Dp = 20.dp
    val space6: Dp = 24.dp
    val space8: Dp = 32.dp
    val space10: Dp = 40.dp
    val space12: Dp = 48.dp
    val space16: Dp = 64.dp

    // ---- Navigation rail ----------------------------------------------------
    /** Collapsed rail shows icons only; it expands on focus, as in the prototype's sidebar. */
    val navRailCollapsedWidth: Dp = 72.dp
    val navRailExpandedWidth: Dp = 216.dp
    val navRailItemHeight: Dp = 48.dp
    val navRailIconSize: Dp = 24.dp

    // ---- Cards --------------------------------------------------------------
    /**
     * Poster cards are 2:3, matching the prototype's `aspect-[2/3]`. 132 dp wide gives six full
     * cards plus a peek on a 960 dp canvas once the rail and overscan are subtracted, which is the
     * density the prototype's grid implies.
     */
    val posterCardWidth: Dp = 132.dp
    val posterCardHeight: Dp = 198.dp

    /** Wide 16:9 cards for episodes, continue-watching and channel tiles. */
    val wideCardWidth: Dp = 224.dp
    val wideCardHeight: Dp = 126.dp

    /** Channel rows in the IPTV list. Tall enough for name plus the current programme. */
    val channelRowHeight: Dp = 72.dp
    val channelLogoSize: Dp = 44.dp

    /** Circular cast portraits. */
    val castPortraitSize: Dp = 88.dp

    /** Season chips and filter chips. */
    val chipHeight: Dp = 36.dp

    // ---- Hero ---------------------------------------------------------------
    /** Featured hero occupies the upper portion of the first screen, as in the prototype. */
    val heroHeight: Dp = 380.dp
    val heroContentMaxWidth: Dp = 520.dp

    // ---- Focus --------------------------------------------------------------
    /**
     * Focus treatment. A 3 dp ring at 1.06x scale is legible from three metres without the
     * layout shifting enough to cause reflow. The prototype's `hover:scale-105` is the same idea.
     */
    val focusBorderWidth: Dp = 3.dp
    val focusBorderWidthSubtle: Dp = 2.dp
    const val FOCUS_SCALE: Float = 1.06f
    const val FOCUS_SCALE_LARGE: Float = 1.03f
    const val FOCUS_ANIMATION_MILLIS: Int = 140

    // ---- Controls -----------------------------------------------------------
    /**
     * Minimum interactive height. Larger than the 48 dp touch minimum because a D-pad user aims
     * with focus rather than a fingertip and needs the target to read clearly at distance.
     */
    val minTouchTarget: Dp = 48.dp
    val buttonHeight: Dp = 48.dp
    val buttonHeightCompact: Dp = 40.dp
    val iconButtonSize: Dp = 48.dp
    val textFieldHeight: Dp = 56.dp

    // ---- Rails --------------------------------------------------------------
    val railSpacing: Dp = 12.dp
    val railHeaderSpacing: Dp = 10.dp
    val railBottomSpacing: Dp = 28.dp
    /** Extra room around a rail so the focus scale is not clipped by the parent. */
    val focusBleed: Dp = 8.dp

    // ---- Player -------------------------------------------------------------
    val playerControlBarHeight: Dp = 120.dp
    val playerSeekBarHeight: Dp = 6.dp
    val playerSeekBarFocusedHeight: Dp = 10.dp
    val playerButtonSize: Dp = 56.dp
    val playerPrimaryButtonSize: Dp = 72.dp

    // ---- EPG grid -----------------------------------------------------------
    val epgChannelColumnWidth: Dp = 180.dp
    val epgRowHeight: Dp = 64.dp
    /** Horizontal dp per minute of programme time in the guide. */
    val epgDpPerMinute: Dp = 4.dp

    // ---- Dialogs and sheets -------------------------------------------------
    val dialogMaxWidth: Dp = 560.dp
    val sheetMaxWidth: Dp = 620.dp

    // ---- Progress -----------------------------------------------------------
    val progressBarHeight: Dp = 4.dp
    val progressBarHeightThick: Dp = 6.dp
}
