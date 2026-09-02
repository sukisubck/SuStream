package com.sustream.tv.designsystem.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corner radii from the prototype's Tailwind rounding scale:
 * `rounded-md` (6) · `rounded-lg` (8) · `rounded-xl` (12) · `rounded-2xl` (16) · `rounded-3xl` (24).
 *
 * Scaled down slightly for the 960 dp TV canvas so cards do not read as pills at distance.
 */
data class SuStreamShapes(
    /** Badges and rating pills — prototype `rounded-md`. */
    val badge: CornerBasedShape = RoundedCornerShape(6.dp),
    /** Chips, small buttons — prototype `rounded-lg`. */
    val chip: CornerBasedShape = RoundedCornerShape(8.dp),
    /** Buttons and text fields — prototype `rounded-xl`. */
    val button: CornerBasedShape = RoundedCornerShape(10.dp),
    /** Poster and wide cards — prototype `rounded-2xl`. */
    val card: CornerBasedShape = RoundedCornerShape(12.dp),
    /** Panels, hero, section containers — prototype `rounded-3xl`. */
    val panel: CornerBasedShape = RoundedCornerShape(16.dp),
    /** Dialogs and bottom sheets. */
    val dialog: CornerBasedShape = RoundedCornerShape(20.dp),
    /** Fully rounded: avatars, progress tracks, pills. */
    val pill: CornerBasedShape = RoundedCornerShape(percent = 50),
)
