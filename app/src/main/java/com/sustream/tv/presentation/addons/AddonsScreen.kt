package com.sustream.tv.presentation.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.core.di.LocalAppContainer
import com.sustream.tv.domain.model.AddonConfiguration
import com.sustream.tv.domain.model.AddonHealthState
import com.sustream.tv.presentation.common.Loadable

@Composable
fun AddonsScreen(
    onAddAddon: () -> Unit,
    onOpenAddon: (addonId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: AddonsViewModel = viewModel(factory = container.viewModelFactory())
    val state by vm.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.nav_addons),
                style = MaterialTheme.typography.headlineMedium,
            )
            Button(onClick = { vm.openAddSheet() }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.addons_add_button),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (state.addons.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = stringResource(R.string.addons_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.addons_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.addons, key = { it.id }) { addon ->
                    AddonRow(
                        addon = addon,
                        onClick = { onOpenAddon(addon.id) },
                        onRemove = { vm.remove(addon.id) },
                    )
                }
            }
        }
    }

    if (state.addSheet != null) {
        AddAddonSheet(
            state = state.addSheet!!,
            onUrlChanged = vm::onUrlChanged,
            onNameChanged = vm::onNameChanged,
            onAuthorisedChanged = vm::onAuthorisedChanged,
            onTest = vm::test,
            onSave = vm::save,
            onDismiss = vm::closeAddSheet,
        )
    }
}

@Composable
private fun AddonRow(
    addon: AddonConfiguration,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = addon.normalisedBaseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val healthLabel = when (addon.lastHealthState) {
                    AddonHealthState.OK -> "Active"
                    AddonHealthState.FAILING -> "Error"
                    AddonHealthState.DISABLED -> "Disabled"
                    else -> if (addon.authorisedByUser) "Configured" else "Inactive"
                }
                Text(
                    text = healthLabel,
                    style = MaterialTheme.typography.labelSmall,
                )
                Button(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.addons_remove))
                }
            }
        }
    }
}

@Composable
private fun AddAddonSheet(
    state: AddSheetState,
    onUrlChanged: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onAuthorisedChanged: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Full add-sheet implementation belongs in a follow-up. For now this is a placeholder
    // that renders the two-gate flow (test then save) so the screen is functional.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(modifier = Modifier.padding(48.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.addons_add_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                // TODO: replace with real TextField, Checkbox, and error display
                Text("URL: ${state.urlInput}")
                Text("Name: ${state.nameInput}")
                when (val p = state.probe) {
                    is Loadable.Loading -> Text("Checking…")
                    is Loadable.Loaded  -> Text("✓ ${p.value.addonName} (${p.value.types.joinToString()})")
                    is Loadable.Failed  -> Text("✗ ${p.error.detail}")
                    else -> Unit
                }
                state.saveError?.let { Text("Error: ${it.detail}") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(onClick = onTest, enabled = state.canTest) { Text(stringResource(R.string.addons_test_button)) }
                    Button(onClick = onSave, enabled = state.canSave) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
}