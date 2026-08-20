package dev.schlubbe.musicagent.ui.theme

import androidx.compose.ui.graphics.Color

// Nocturne design tokens (see the design_handoff bundle's nocturne-tokens.css) —
// values copied verbatim from :root, not re-derived, so this file stays the
// single source of truth to diff against if the tokens change.
object Nocturne {
    val bg = Color(0xFF161826)
    val surface = Color(0xFF232532)
    val text = Color(0xFFE9E9ED)
    val accent = Color(0xFF9184D9)
    val accent2 = Color(0xFFA7A1DB)
    // --color-divider: color-mix(in srgb, #e9e9ed 16%, transparent) -- expressed
    // directly as an ARGB alpha (16% of 255 ≈ 0x29) rather than a runtime color-mix,
    // since Compose Color already carries alpha natively.
    val divider = Color(0x29E9E9ED)

    val neutral100 = Color(0xFFF3F5FE)
    val neutral200 = Color(0xFFE4E7F5)
    val neutral300 = Color(0xFFCFD3E5)
    val neutral400 = Color(0xFFB2B6CA)
    val neutral500 = Color(0xFF9397AB)
    val neutral600 = Color(0xFF75798C)
    val neutral700 = Color(0xFF595D6C)
    val neutral800 = Color(0xFF3F424D)
    val neutral900 = Color(0xFF292B31)

    val accent100 = Color(0xFFF5F4FF)
    val accent200 = Color(0xFFE7E5FE)
    val accent300 = Color(0xFFD2CEFD)
    val accent400 = Color(0xFFB5ABFC)
    val accent500 = Color(0xFF968AE0)
    val accent600 = Color(0xFF796CBF)
    val accent700 = Color(0xFF5D5294)
    val accent800 = Color(0xFF423A6A)
    val accent900 = Color(0xFF2B2741)

    val accent2_100 = Color(0xFFF5F4FF)
    val accent2_800 = Color(0xFF423E5D)

    val section = Color(0xFF262A60)
    val sectionGlow = Color(0xFF353B80)
    val sectionGhost = Color(0xFF4C5397)
}

/** The 4 playlist accent swatches from the edit sheet: "Auto" (a deterministic
 * per-id hash color, same placeholder-until-set approach the design's README
 * prescribes for covers before real data exists), then the 3 explicit choices.
 * [colorKey] is the persisted [dev.schlubbe.musicagent.data.local.entity.PlaylistEntity.accentColorKey]
 * ("accent"/"accent2"/"neutral", or null for Auto) -- shared by Home's shelf
 * swatch, Library's playlist cards, the detail screen's cover slot, and the
 * edit sheet's own swatch preview, so all four always agree on the same color. */
fun accentColorFor(colorKey: String?, autoSeed: String): Color = when (colorKey) {
    "accent" -> Nocturne.accent
    "accent2" -> Nocturne.accent2
    "neutral" -> Nocturne.neutral600
    else -> {
        val autoPalette = listOf(Nocturne.accent500, Nocturne.accent700, Nocturne.section, Nocturne.sectionGlow)
        autoPalette[kotlin.math.abs(autoSeed.hashCode()) % autoPalette.size]
    }
}
