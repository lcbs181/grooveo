package dev.schlubbe.musicagent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Gates the first-run onboarding flow: `null` while the DataStore read is
 * still in flight (nothing decided yet, don't navigate), `true` if this
 * install has never completed onboarding (lastSeenVersionCode == 0). Marking
 * it seen also covers the What's-New banner for the *current* version, per
 * the design's "one hasSeenVersion flag, not two" note -- a fresh install
 * that just finished onboarding shouldn't also see the banner for the same
 * version it was just introduced to.
 *
 * Also owns the three source/data-saver toggles the Canopy onboarding screen
 * puts in front of the user before first launch. These write straight through
 * to [SettingsRepository] as they're flipped rather than being batched on
 * "Los geht's", so backing out of onboarding can't lose the choice.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _shouldShowOnboarding = MutableStateFlow<Boolean?>(null)
    val shouldShowOnboarding: StateFlow<Boolean?> = _shouldShowOnboarding.asStateFlow()

    val soundCloudEnabled: StateFlow<Boolean> = settingsRepository.sourceSoundCloudEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val ytMusicEnabled: StateFlow<Boolean> = settingsRepository.sourceYtMusicEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dataSaverEnabled: StateFlow<Boolean> = settingsRepository.dataSaverMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            _shouldShowOnboarding.value = settingsRepository.lastSeenVersionCode.first() == 0
        }
    }

    fun setSoundCloudEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSourceSoundCloudEnabled(enabled) }
    }

    fun setYtMusicEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSourceYtMusicEnabled(enabled) }
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDataSaverMode(enabled) }
    }

    fun onFinished() {
        viewModelScope.launch {
            settingsRepository.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
        }
    }
}
