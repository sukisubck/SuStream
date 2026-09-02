package com.sustream.tv.core.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Creates view models from the hand-assembled [AppContainer].
 *
 * Registration is a map of `KClass` to a factory lambda, populated in [register]. That keeps the
 * wiring in one readable place and makes a missing registration a clear, immediate failure with the
 * class name in the message — rather than the reflective `NoSuchMethodException` that the default
 * factory produces when a view model has constructor parameters.
 */
class SuStreamViewModelFactory(
    private val container: AppContainer,
    private val creators: Map<Class<out ViewModel>, () -> ViewModel>,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val creator = creators[modelClass]
            ?: creators.entries.firstOrNull { modelClass.isAssignableFrom(it.key) }?.value
            ?: error(
                "No view model registered for " + modelClass.name +
                    ". Add it to AppContainer.viewModelFactory().",
            )
        return creator() as T
    }
}

/**
 * Provides the container to the composition.
 *
 * A composition local rather than threading the container through every screen's parameters: the
 * alternative is a `container` argument on a dozen composables that only exists to be passed on
 * again, which obscures each screen's real dependencies.
 */
val LocalAppContainer: ProvidableCompositionLocal<AppContainer> =
    staticCompositionLocalOf { error("AppContainer has not been provided") }

/** `val vm: HomeViewModel = suStreamViewModel()` at a screen's top level. */
@Composable
inline fun <reified VM : ViewModel> suStreamViewModel(): VM {
    val container = LocalAppContainer.current
    return viewModel(factory = container.viewModelFactory())
}
