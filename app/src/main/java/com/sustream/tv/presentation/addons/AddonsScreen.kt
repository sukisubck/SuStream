package com.sustream.tv.presentation.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sustream.tv.R
import com.sustream.tv.core.di.suStreamViewModel
import com.sustream.tv.designsystem.component.ConfirmDialog
import com.sustream.tv.designsystem.component.EmptyState
import com.sustream.tv.designsystem.component.InlineError
import com.sustream.tv.designsystem.component.PrimaryButton
import com.sustream.tv.designsystem.component.SecondaryButton
import com.sustream.tv.designsystem.component.SectionHeader
import com.sustream.tv.designsystem.component.TvActionRow
import com.sustream.tv.designsystem.component.TvCheckbox
import com.sustream.tv.designsystem.component.TvDialog
import com.sustream.tv.designsystem.component.TvTextField
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.domain.model.AddonConfiguration
import com.sustream.tv.presentation.common.Loadable

/**
 * Top-level Addons destination — lists configured HTML/JSON addons and
 * exposes the add-addon flow (URL probe → authorisation → save).
 *
 * Nav callbacks [onAddAddon] and [onOpenAddon] are kept for future
 * deep-link use; the add flow is presented as an inline sheet rather
 * than a separate route, matching the Live TV playlist pattern.
 */
@Composable
fun AddonsScreen(
    onAddAddon: () -> Unit,
    onOpenAddon: (addonId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AddonsViewModel = suStreamViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var addonIdToRemove by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        modifier = modifier
            .fillMaxSize()
            .background(SuStreamTheme.colours.background)
            .padding(horizontal = Dimens.overscanHorizontal, vertical = Dimens.overscanVertical),
    ) {
        SectionHeader(
            title = stringResource(R.string.nav_addons),
            subtitle = stringResource(R.string.addons_subtitle),
            trailing = {
                PrimaryButton(
                    text = stringResource(R.string.addons_add),
                    onClick = viewModel::openAddSheet,
                )
            },
        )

        if (state.addons.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.addons_none_configured),
                body = stringResource(R.string.addons_empty_body),
                actionLabel = stringResource(R.string.addons_add),
                onAction = viewModel::openAddSheet,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Dimens.space3),
                contentPadding = PaddingValues(bottom = Dimens.overscanVertical),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.addons, key = { it.id }) { addon ->
                    AddonRow(
                        addon = addon,
                        onRemove = { addonIdToRemove = addon.id },
                    )
                }
            }
        }
    }

    // ---- Add-addon sheet ----------------------------------------------------

    state.addSheet?.let { sheet ->
        AddAddonDialog(
            sheet = sheet,
            onDismiss = viewModel::closeAddSheet,
            onUrlChanged = viewModel::onUrlChanged,
            onNameChanged = viewModel::onNameChanged,
            onAuthorisedChanged = viewModel::onAuthorisedChanged,
            onTest = viewModel::test,
            onSave = viewModel::save,
        )
    }

    // ---- Remove confirmation -------------------------------------------------

    state.addons.firstOrNull { it.id == addonIdToRemove }?.let { addon ->
        ConfirmDialog(
            title = stringResource(R.string.addons_remove_confirm_title, addon.name),
            body = stringResource(R.string.addons_remove_confirm_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.remove(addon.id)
                addonIdToRemove = null
            },
            onDismiss = { addonIdToRemove = null },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun AddonRow(
    addon: AddonConfiguration,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        TvActionRow(
            title = addon.name,
            subtitle = addon.manifestUrl,
            onClick = {},
        )
        if (addon.supportedTypes.isNotEmpty()) {
            androidx.tv.material3.Text(
                text = stringResource(
                    R.string.addons_supported_types,
                    addon.supportedTypes.joinToString(" · ") { it.name.lowercase() },
                ),
                style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                color = SuStreamTheme.colours.textTertiary,
            )
        }
        SecondaryButton(
            text = stringResource(R.string.action_delete),
            onClick = onRemove,
            destructive = true,
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun AddAddonDialog(
    sheet: AddSheetState,
    onDismiss: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onAuthorisedChanged: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    TvDialog(
        title = stringResource(R.string.addons_add),
        subtitle = stringResource(R.string.addons_add_subtitle),
        onDismiss = onDismiss,
    ) {
        TvTextField(
            value = sheet.urlInput,
            onValueChange = onUrlChanged,
            label = stringResource(R.string.addons_field_url),
            enabled = !sheet.isSaving,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SecondaryButton(
                text = stringResource(R.string.addons_action_test),
                enabled = sheet.canTest,
                onClick = onTest,
            )
            if (sheet.isVerified) {
                TvTextField(
                    value = sheet.nameInput,
                    onValueChange = onNameChanged,
                    label = stringResource(R.string.addons_field_name),
                    enabled = !sheet.isSaving,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Probe result feedback
        when (val probe = sheet.probe) {
            is Loadable.Loading -> androidx.tv.material3.Text(
                text = stringResource(R.string.addons_probing),
                style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                color = SuStreamTheme.colours.textMuted,
            )
            is Loadable.Loaded -> {
                val descriptor = probe.value
                androidx.tv.material3.Text(
                    text = stringResource(
                        R.string.addons_probe_ok,
                        descriptor.addonName,
                        descriptor.supportedTypes.joinToString(", ") { it.name.lowercase() },
                    ),
                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                    color = SuStreamTheme.colours.healthy,
                )
            }
            is Loadable.Failed -> InlineError(
                error = probe.error,
                onRetry = onTest,
            )
            else -> Unit
        }

        if (sheet.isVerified) {
            Spacer(Modifier.height(Dimens.space2))

            TvCheckbox(
                checked = sheet.authorisedConfirmed,
                onCheckedChange = onAuthorisedChanged,
                label = stringResource(R.string.addons_authorised_label),
                enabled = !sheet.isSaving,
            )

            androidx.tv.material3.Text(
                text = stringResource(R.string.addons_authorised_note),
                style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                color = SuStreamTheme.colours.textMuted,
            )
        }

        sheet.saveError?.let { error ->
            InlineError(error = error, onRetry = null)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(
                text = stringResource(R.string.action_save),
                enabled = sheet.canSave,
                onClick = onSave,
            )
            SecondaryButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        }
    }
}
