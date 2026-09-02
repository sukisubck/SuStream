package com.sustream.tv.presentation.addons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sustream.tv.presentation.navigation.ComingSoonScreen

/**
 * Top-level Addons screen — wired to [AddonsViewModel].
 *
 * Currently renders a [ComingSoonScreen] stub while the full list/detail UI
 * is being built. The [onAddAddon] and [onOpenAddon] callbacks are passed
 * through so [SuStreamNavGraph] can compile without any changes once the
 * real implementation lands.
 */
@Composable
fun AddonsScreen(
    onAddAddon: () -> Unit,
    onOpenAddon: (addonId: String) -> Unit,
) {
    ComingSoonScreen(
        title = "Addons",
        description = "Your configured HtmlJson addons will appear here. Press the add button to connect one.",
        onBack = {},
    )
}
