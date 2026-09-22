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
 * Also owns the two source toggles step 1 puts in front of the user before first
 * launch, and step 2's genre/artist taste picks. Every one of these writes
 * straight through to [SettingsRepository] as it's flipped/picked rather than
 * being batched on "Los geht's", so backing out of onboarding can't lose the
 * choice. Datensparmodus used to live here too; it's a Settings/Downloads-only
 * toggle now (SettingsViewModel/DownloadsViewModel), meaningless to a user who
 * has nothing downloaded yet.
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

    /** Step 2's picks -- also reused verbatim by Settings' "Musikgeschmack
     * anpassen" entry, since it's the same picker reopened, not a separate flow.
     * Written straight through as they're picked, same as the source toggles
     * above, not batched behind "Los geht's". */
    val preferredGenres: StateFlow<Set<String>> = settingsRepository.preferredGenres
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val preferredArtists: StateFlow<List<String>> = settingsRepository.preferredArtists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    fun toggleGenre(genre: String) {
        viewModelScope.launch {
            val current = preferredGenres.value
            settingsRepository.setPreferredGenres(if (genre in current) current - genre else current + genre)
        }
    }

    /** "Lieblingskünstler" field's submit action -- splits on commas so pasting
     * "Drake, SZA, Metro Boomin" in one go yields three chips, not one. A single
     * launch (rather than one per name) so a fast paste can't race itself against
     * [preferredArtists]'s own stale value between two near-simultaneous writes. */
    fun addArtists(raw: String) {
        val names = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return
        viewModelScope.launch {
            val updated = preferredArtists.value.toMutableList()
            names.forEach { name -> if (updated.none { it.equals(name, ignoreCase = true) }) updated += name }
            settingsRepository.setPreferredArtists(updated)
        }
    }

    fun removeArtist(name: String) {
        viewModelScope.launch { settingsRepository.setPreferredArtists(preferredArtists.value - name) }
    }

    fun onFinished() {
        viewModelScope.launch {
            settingsRepository.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
        }
    }
}
