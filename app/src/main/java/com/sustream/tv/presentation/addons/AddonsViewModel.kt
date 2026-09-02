package com.sustream.tv.presentation.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.AddonConfiguration
import com.sustream.tv.domain.model.AddonTestResult
import com.sustream.tv.domain.repository.AddonRepository
import com.sustream.tv.presentation.common.Loadable
import com.sustream.tv.provider.htmljson.AddonManifestProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddonsViewModel(
    private val addonRepository: AddonRepository,
    private val probe: AddonManifestProbe,
) : ViewModel() {

    private val _state = MutableStateFlow(AddonsUiState())
    val state: StateFlow<AddonsUiState> = _state.asStateFlow()

    private var probeJob: Job? = null

    init {
        viewModelScope.launch {
            addonRepository.observeAddons().collect { addons ->
                _state.update { it.copy(addons = addons) }
            }
        }
    }

    fun openAddSheet() {
        _state.update { it.copy(addSheet = AddSheetState()) }
    }

    fun closeAddSheet() {
        probeJob?.cancel()
        _state.update { it.copy(addSheet = null) }
    }

    fun onUrlChanged(value: String) {
        _state.update {
            it.copy(
                addSheet = it.addSheet?.copy(
                    urlInput = value,
                    probe = Loadable.Idle,
                    authorisedConfirmed = false,
                    saveError = null,
                ),
            )
        }
    }

    fun onNameChanged(value: String) {
        _state.update {
            it.copy(addSheet = it.addSheet?.copy(nameInput = value))
        }
    }

    fun onAuthorisedChanged(confirmed: Boolean) {
        _state.update {
            it.copy(addSheet = it.addSheet?.copy(authorisedConfirmed = confirmed, saveError = null))
        }
    }

    /**
     * Probes the URL. A changed URL invalidates any prior probe result so a verified address
     * cannot be swapped for an unverified one between testing and saving.
     */
    fun test() {
        val sheet = _state.value.addSheet ?: return
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            _state.update { it.copy(addSheet = it.addSheet?.copy(probe = Loadable.Loading, saveError = null)) }

            when (val result = probe.probe(sheet.urlInput)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        addSheet = it.addSheet?.copy(
                            probe = Loadable.Loaded(result.value),
                            nameInput = it.addSheet.nameInput.ifBlank { result.value.addonName },
                        ),
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(addSheet = it.addSheet?.copy(probe = Loadable.Failed(result.error)))
                }
            }
        }
    }

    fun save() {
        val sheet = _state.value.addSheet ?: return
        val descriptor = sheet.probe.valueOrNull

        if (descriptor == null) {
            _state.update {
                it.copy(
                    addSheet = it.addSheet?.copy(
                        saveError = AppError.Unknown("Test the address before saving it."),
                    ),
                )
            }
            return
        }
        if (!sheet.authorisedConfirmed) {
            _state.update {
                it.copy(
                    addSheet = it.addSheet?.copy(
                        saveError = AppError.Unauthorised(
                            "Confirm you are entitled to use this service before enabling it.",
                            refreshable = false,
                        ),
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(addSheet = it.addSheet?.copy(isSaving = true, saveError = null)) }

            when (val result = addonRepository.add(descriptor, sheet.nameInput)) {
                is AppResult.Success -> closeAddSheet()
                is AppResult.Failure -> _state.update {
                    it.copy(addSheet = it.addSheet?.copy(isSaving = false, saveError = result.error))
                }
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            addonRepository.remove(id)
        }
    }
}

data class AddonsUiState(
    val addons: List<AddonConfiguration> = emptyList(),
    /** Non-null when the add sheet is open. */
    val addSheet: AddSheetState? = null,
)

data class AddSheetState(
    val urlInput: String = "",
    val nameInput: String = "",
    val authorisedConfirmed: Boolean = false,
    val probe: Loadable<AddonTestResult> = Loadable.Idle,
    val isSaving: Boolean = false,
    val saveError: AppError? = null,
) {
    val isVerified: Boolean get() = probe is Loadable.Loaded
    val canTest: Boolean get() = urlInput.isNotBlank() && !probe.isLoading && !isSaving
    val canSave: Boolean get() = isVerified && authorisedConfirmed && !isSaving
}