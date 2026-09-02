package com.sustream.tv.presentation.addons

import androidx.compose.runtime.Composable
import com.sustream.tv.presentation.navigation.ComingSoonScreen

/**
 * Top-level Addons destination.
 *
 * Stub — keeps the nav graph import compiling while [AddonsViewModel] wiring
 * is completed in the next step. Replace the body with the real UI once the
 * Room-backed [AddonsViewModel] is wired into [androidx.lifecycle.viewmodel.compose.viewModel].
 */
@Composable
fun AddonsScreen(
    onAddAddon: () -> Unit,
    onOpenAddon: (addonId: String) -> Unit,
) {
    ComingSoonScreen(
        title = "Addons",
        description = "Your configured HTML/JSON addons appear here. Press \"Add addon\" to paste a manifest URL.",
        onBack = {},
    )
}
