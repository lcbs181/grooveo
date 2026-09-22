package dev.schlubbe.musicagent.ui.onboarding

/** Chip grid on onboarding's "Was hörst du gern?" step. Plain strings rather than
 * an enum, since these are just a starting-point suggestion list persisted
 * verbatim to [dev.schlubbe.musicagent.data.repository.SettingsRepository.preferredGenres] --
 * anything from here that also matches a [dev.schlubbe.musicagent.ui.home.GenreFilter]
 * label (House, Lo-Fi) reuses that chip on Home's "Trends nach Genre" instead of
 * introducing a second vocabulary; the rest still work there as free-text genre
 * search terms (see SearchRepository.getTrendingByGenre). */
val ONBOARDING_GENRES: List<String> = listOf(
    "Pop", "Hip-Hop", "Rap", "Deutschrap", "R&B", "Rock", "Indie", "Elektronisch",
    "House", "Techno", "Drum & Bass", "Lo-Fi", "Chill", "Jazz", "Klassik", "Metal",
    "Latin", "K-Pop", "Afrobeats", "Schlager",
)
