package dev.schlubbe.musicagent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Gates the first-run onboarding flow: `null` while the DataStore read is
 * still in flight (nothing decided yet, don't navigate), `true` if this
 * install has never completed onboarding (lastSeenVersionCode == 0). Marking
 * it seen also covers the What's-New banner for the *current* version, per
 * the design's "one hasSeenVersion flag, not two" note -- a fresh install
 * that just finished onboarding shouldn't also see the banner for the same
 * version it was just introduced to. */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _shouldShowOnboarding = MutableStateFlow<Boolean?>(null)
    val shouldShowOnboarding: StateFlow<Boolean?> = _shouldShowOnboarding.asStateFlow()

    init {
        viewModelScope.launch {
            _shouldShowOnboarding.value = settingsRepository.lastSeenVersionCode.first() == 0
        }
    }

    fun onFinished() {
        viewModelScope.launch {
            settingsRepository.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
        }
    }
}
