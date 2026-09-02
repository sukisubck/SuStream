package com.sustream.tv.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

/**
 * Typography for a 10-foot UI.
 *
 * The prototype uses Inter (with Manrope for display) at weights 300-900. Those font files are not
 * bundled here, because fetching them at build time would add a network dependency and Google's
 * downloadable-fonts provider is unavailable on Fire TV. [BrandFontFamily] is therefore the single
 * swap point: drop `inter_*.ttf` into `res/font/` and change this one property to
 * `FontFamily(Font(R.font.inter_regular), ...)`. Roboto is a close metric substitute in the
 * meantime, and every size, weight and letter-spacing value below is taken from the prototype.
 *
 * Sizes are raised for viewing distance. The prototype's 10-11 px labels become 12 sp, which is
 * the absolute floor here and is only used for badges and metadata, never for prose. Body copy is
 * 15-16 sp. This follows Android TV's guidance that no text should fall below 12 sp.
 */
val BrandFontFamily: FontFamily = FontFamily.SansSerif

/**
 * Line heights are ~1.4x for prose and ~1.2x for display, matching the prototype's `leading-tight`
 * on headings and default leading on body text.
 */
val SuStreamTypography: Typography = Typography(
    // ---- Display: hero titles ----------------------------------------------
    displayLarge = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),

    // ---- Headline: screen titles -------------------------------------------
    headlineLarge = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),

    // ---- Title: rail headers, card titles, dialog titles -------------------
    titleLarge = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),

    // ---- Body: prose --------------------------------------------------------
    bodyLarge = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),

    // ---- Label: badges, chips, metadata ------------------------------------
    labelLarge = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    /** 12 sp is the floor. Uppercase tracking mirrors the prototype's `tracking-wider` badges. */
    labelSmall = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.8.sp,
    ),
)

/**
 * Line limits used consistently across cards so titles truncate the same way everywhere. Kept
 * here rather than inline so a change is one edit.
 */
object TextLimits {
    const val CARD_TITLE_LINES = 1
    const val CARD_SUBTITLE_LINES = 1
    const val HERO_OVERVIEW_LINES = 3
    const val DETAILS_OVERVIEW_LINES = 6
    const val EPISODE_OVERVIEW_LINES = 2
    val OVERFLOW = TextOverflow.Ellipsis
}
