package com.sustream.tv.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.StremioAddonPreferences
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.provider.stremio.AddonDescriptor
import com.sustream.tv.provider.stremio.AddonManifestProbe
import com.sustream.tv.presentation.common.Loadable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Adding and removing the user's configured addon.
 *
 * ## The two gates, and why they are structural
 *
 * Saving requires **both** a successful probe and an explicit authorisation acknowledgement
 * ([AddonSettingsUiState.canSave]). Neither is a formality:
 *
 *  * **The probe** means an address is never stored until it has answered with a manifest that
 *    actually advertises streams. A typo fails here, on the screen where it can be corrected,
 *    rather than silently as an empty availability panel days later.
 *  * **The acknowledgement** is what `StremioAddonPreferences.authorisedByUser` records, and the
 *    adapter refuses to return anything without it. Making it a precondition of the save button —
 *    rather than a checkbox that defaults on — is the difference between a consent record and a
 *    dark pattern.
 *
 * Editing the URL after a successful probe **clears the probe result**, so a verified address
 * cannot be swapped for an unverified one between testing and saving.
 */
class AddonSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val probe: AddonManifestProbe,
) : ViewModel() {

    private val _state = MutableStateFlow(AddonSettingsUiState())
    val state: StateFlow<AddonSettingsUiState> = _state.asStateFlow()

    private var probeJob: Job? = null

    init {
        observeSaved()
    }

    private fun observeSaved() {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                val saved = settings.stremioAddon
                _state.update { current ->
                    current.copy(
                        saved = saved,
                        // Only seed the form from storage on first load. Overwriting on every
                        // emission would fight the user's typing, because saving emits.
                        urlInput = if (current.formTouched) {
                            current.urlInput
                        } else {
                            saved.baseUrl.orEmpty()
                        },
                        nameInput = if (current.formTouched) {
                            current.nameInput
                        } else {
                            saved.displayName.orEmpty()
                        },
                    )
                }
            }
        }
    }

    fun onUrlChanged(value: String) {
        _state.update {
            it.copy(
                urlInput = value,
                formTouched = true,
                // A changed address invalidates the verification and the acknowledgement: both
                // referred to the previous address.
                probe = Loadable.Idle,
                authorisedConfirmed = false,
                saveError = null,
                justSaved = false,
            )
        }
    }

    fun onNameChanged(value: String) {
        _state.update { it.copy(nameInput = value, formTouched = true, justSaved = false) }
    }

    fun onAuthorisedChanged(confirmed: Boolean) {
        _state.update { it.copy(authorisedConfirmed = confirmed, saveError = null) }
    }

    /** Fetches the manifest and shows the user what they have actually pointed at. */
    fun test() {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            _state.update { it.copy(probe = Loadable.Loading, saveError = null, justSaved = false) }

            when (val result = probe.probe(_state.value.urlInput)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        probe = Loadable.Loaded(result.value),
                        // Offer the addon's own name when the user has not chosen a label, so the
                        // sources sheet shows something recognisable rather than a bare URL.
                        nameInput = it.nameInput.ifBlank { result.value.name },
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(probe = Loadable.Failed(result.error))
                }
            }
        }
    }

    fun save() {
        val current = _state.value
        val descriptor = current.probe.valueOrNull

        if (descriptor == null) {
            _state.update {
                it.copy(saveError = AppError.Unknown("Test the address before saving it."))
            }
            return
        }
        if (!current.authorisedConfirmed) {
            _state.update {
                it.copy(
                    saveError = AppError.Unauthorised(
                        "Confirm you are entitled to use this service before enabling it.",
                        refreshable = false,
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }

            // The normalised form is stored, not what was typed, so the address that was verified
            // is exactly the address that gets requested.
            val result = settingsRepository.updateStremioAddon {
                StremioAddonPreferences(
                    baseUrl = descriptor.normalisedBaseUrl,
                    displayName = current.nameInput.trim().ifBlank { descriptor.name },
                    authorisedByUser = true,
                )
            }

            _state.update {
                when (result) {
                    is AppResult.Success -> it.copy(
                        isSaving = false,
                        justSaved = true,
                        formTouched = false,
                    )

                    is AppResult.Failure -> it.copy(isSaving = false, saveError = result.error)
                }
            }
        }
    }

    /** Clears the addon. The adapter goes inert immediately, because both gates close. */
    fun remove() {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            val result = settingsRepository.updateStremioAddon { StremioAddonPreferences() }

            _state.update {
                when (result) {
                    is AppResult.Success -> AddonSettingsUiState()
                    is AppResult.Failure -> it.copy(isSaving = false, saveError = result.error)
                }
            }
        }
    }
}

data class AddonSettingsUiState(
    val urlInput: String = "",
    val nameInput: String = "",
    val authorisedConfirmed: Boolean = false,
    val probe: Loadable<AddonDescriptor> = Loadable.Idle,
    val saved: StremioAddonPreferences = StremioAddonPreferences(),
    val isSaving: Boolean = false,
    val saveError: AppError? = null,
    val justSaved: Boolean = false,
    /** True once the user edits anything, so storage stops seeding the form underneath them. */
    val formTouched: Boolean = false,
) {
    val isVerified: Boolean get() = probe is Loadable.Loaded

    /** Both gates. See the class comment on [AddonSettingsViewModel]. */
    val canSave: Boolean get() = isVerified && authorisedConfirmed && !isSaving

    val canTest: Boolean get() = urlInput.isNotBlank() && !probe.isLoading && !isSaving

    /** An addon is configured and active only when a URL is stored *and* consent was recorded. */
    val hasActiveAddon: Boolean
        get() = !saved.baseUrl.isNullOrBlank() && saved.authorisedByUser
}
