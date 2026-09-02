package com.sustream.tv.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens lifted from `prototype.html`.
 *
 * The prototype's palette is a Tailwind slate ramp over a near-black cinema background, with a
 * violet brand ramp, amber for the IPTV/live domain, emerald for healthy status and rose for LIVE.
 * Those exact hex values are preserved so the TV build is recognisably the same product.
 *
 * Naming is by *role*, not by hue, so a rebrand touches this file only. Raw hexes are private.
 */
internal object Palette {
    // Brand ramp — prototype `brand` / `brandLight`, used in the header gradient, primary
    // buttons, focus rings and the active navigation state.
    val Violet300 = Color(0xFFC7C2FF)
    val Violet400 = Color(0xFFA29BFE) // prototype brandLight
    val Violet500 = Color(0xFF8070F7) // prototype primary button gradient end
    val Violet600 = Color(0xFF6C5CE7) // prototype brand
    val Violet900 = Color(0xFF1A1740)

    // Cinema surfaces — prototype body `#07090E`, `cinemaDark`, `surfaceCard`, header `#0D111A`.
    val Ink = Color(0xFF07090E)
    val Cinema = Color(0xFF0B0E14) // prototype cinemaDark
    val Elevated = Color(0xFF0D111A) // prototype header
    val Card = Color(0xFF161B24) // prototype surfaceCard
    val CardRaised = Color(0xFF181F2E) // prototype source row
    val CardSunken = Color(0xFF121620) // prototype EPG panel

    // Tailwind slate ramp, as used throughout the prototype for borders and text.
    val Slate950 = Color(0xFF020617)
    val Slate800 = Color(0xFF1E293B)
    val Slate700 = Color(0xFF334155)
    val Slate500 = Color(0xFF64748B)
    val Slate400 = Color(0xFF94A3B8)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate200 = Color(0xFFE2E8F0)
    val Slate100 = Color(0xFFF1F5F9)
    val White = Color(0xFFFFFFFF)

    // Domain accents.
    val Amber400 = Color(0xFFFBBF24) // ratings, IPTV accent
    val Amber500 = Color(0xFFF59E0B)
    val Emerald400 = Color(0xFF34D399) // healthy / connected
    val Rose500 = Color(0xFFEF4444) // LIVE badge, destructive
    val Rose400 = Color(0xFFF87171)
    val Pink500 = Color(0xFFEC4899) // unread dot
    val Sky400 = Color(0xFF38BDF8) // informational
}

/**
 * Semantic colours for the app. A TV UI is dark unconditionally, so there is a single scheme —
 * a light variant would wash out poster art and is never appropriate at viewing distance.
 *
 * Contrast: every foreground/background pair used for text here meets or exceeds WCAG AA for
 * large text (3:1), and body pairs meet AA for normal text (4.5:1). `TextTertiary` on
 * `SurfaceCard` is the tightest pair at ~4.6:1 and is therefore never used below 14 sp.
 */
data class SuStreamColours(
    // Backgrounds
    val background: Color = Palette.Cinema,
    val backgroundDeep: Color = Palette.Ink,
    val surface: Color = Palette.Card,
    val surfaceRaised: Color = Palette.CardRaised,
    val surfaceSunken: Color = Palette.CardSunken,
    val surfaceNav: Color = Palette.Elevated,
    val scrim: Color = Color(0xD9000000),

    // Brand / interaction
    val primary: Color = Palette.Violet600,
    val primaryGradientEnd: Color = Palette.Violet500,
    val primaryMuted: Color = Palette.Violet900,
    val onPrimary: Color = Palette.White,
    val accent: Color = Palette.Violet400,

    // Focus. On TV this is the single most important visual signal, so it is a token in its own
    // right rather than a reuse of `primary`.
    val focusRing: Color = Palette.White,
    val focusRingSecondary: Color = Palette.Violet400,
    val focusFill: Color = Palette.Violet600,
    val onFocusFill: Color = Palette.White,

    // Text
    val textPrimary: Color = Palette.Slate100,
    val textSecondary: Color = Palette.Slate300,
    val textTertiary: Color = Palette.Slate400,
    val textDisabled: Color = Palette.Slate500,
    val onAccent: Color = Palette.Slate950,

    // Lines
    val border: Color = Palette.Slate800,
    val borderStrong: Color = Palette.Slate700,
    val divider: Color = Color(0x991E293B),

    // Status
    val live: Color = Palette.Rose500,
    val onLive: Color = Palette.White,
    val healthy: Color = Palette.Emerald400,
    val warning: Color = Palette.Amber400,
    val danger: Color = Palette.Rose400,
    val info: Color = Palette.Sky400,
    val rating: Color = Palette.Amber400,
    val unread: Color = Palette.Pink500,

    // The IPTV / Live TV domain is amber-accented in the prototype; keeping that as an explicit
    // token stops it drifting away from the brand violet used everywhere else.
    val iptvAccent: Color = Palette.Amber500,
    val iptvAccentSoft: Color = Palette.Amber400,
    val onIptvAccent: Color = Palette.Slate950,
)
