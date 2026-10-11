package dev.schlubbe.musicagent.desktop.ai

import dev.schlubbe.musicagent.desktop.audio.FxUnitSpec
import dev.schlubbe.musicagent.desktop.data.Settings
import dev.schlubbe.musicagent.desktop.mcp.ToolDef
import dev.schlubbe.musicagent.playback.eq.EqProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * When the assistant must wait for the user's OK (settings "Wann die KI fragt"):
 *  - DELETE: before it deletes or clears anything;
 *  - FX: before it switches on an FX chain or turns an effect knob;
 *  - LOUDNESS: when it wants to change how loud it is: volume, loudness normalisation,
 *    the equalizer (gain, bass, bands) and channel volume/EQ. Asked once per new value:
 *    an approved level stays approved, so it is not asked again on every track;
 *  - OTHER: any other change, only if the user wants that.
 * Transition automation (fader blends, bass swaps) is mixing, not a loudness change.
 */
class AiPolicy {
    enum class Kind { DELETE, FX, LOUDNESS, OTHER }

    data class Ask(val kind: Kind, val title: String, val detail: String)

    /** Loudness changes the user already allowed, by tool and value. */
    private val approved = HashSet<String>()

    fun kindOf(def: ToolDef, args: JsonObject, s: Settings? = null): Kind? {
        if (!def.mutates) return null
        val patch = args["settings"] as? JsonObject
        return when {
            def.destructive || def.name in DELETE_TOOLS -> Kind.DELETE
            def.name in FX_TOOLS -> Kind.FX
            def.name in LOUDNESS_TOOLS -> Kind.LOUDNESS
            def.name == "set_deck_fx" -> if (args.str("param") in LOUDNESS_FX) Kind.LOUDNESS else Kind.FX
            def.name == "update_settings" && patch != null && s != null && removesFromList(patch, s) -> Kind.DELETE
            def.name == "update_settings" && patch?.keys?.any { it in LOUDNESS_SETTINGS } == true -> Kind.LOUDNESS
            def.name == "update_settings" && patch?.keys?.any { it in FX_SETTINGS } == true -> Kind.FX
            else -> Kind.OTHER
        }
    }

    /** Whether a settings patch drops entries from a list setting (e.g. preferred artists). */
    private fun removesFromList(patch: JsonObject, s: Settings): Boolean = patch.entries.any { (k, v) ->
        val now = LIST_SETTINGS[k]?.invoke(s) ?: return@any false
        val next = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
        now.any { it !in next }
    }

    /** The question to ask before [def] runs with [args], or null to run it directly. */
    fun check(def: ToolDef, args: JsonObject, s: Settings): Ask? {
        val kind = kindOf(def, args, s) ?: return null
        val on = when (kind) {
            Kind.DELETE -> s.aiAskDelete
            Kind.FX -> s.aiAskFx
            Kind.LOUDNESS -> s.aiAskLoudness && loudnessKey(def, args) !in approved && !unchanged(def, args, s)
            Kind.OTHER -> s.aiAskOther
        }
        if (!on) return null
        return when (kind) {
            Kind.DELETE -> Ask(kind, "Löschen?", describe(def, args))
            Kind.FX -> Ask(kind, if (def.name == "set_deck_fx") "Effekt einschalten?" else "FX-Kette einschalten?", describe(def, args))
            Kind.LOUDNESS -> Ask(kind, "Lautstärke ändern?", describe(def, args) + "\nGilt dann weiter. Ich frage erst wieder, wenn sich die Lautstärke erneut ändern soll.")
            Kind.OTHER -> Ask(kind, "Ausführen?", describe(def, args))
        }
    }

    /** Called after the user allowed [def]: the same loudness change is not asked again. */
    fun allowed(def: ToolDef, args: JsonObject) {
        if (kindOf(def, args) == Kind.LOUDNESS) approved += loudnessKey(def, args)
    }

    fun reset() = approved.clear()

    private fun loudnessKey(def: ToolDef, args: JsonObject) = def.name + ":" + canonical(args)

    /** A request that would not change anything (e.g. the volume it already has) needs no question. */
    private fun unchanged(def: ToolDef, args: JsonObject, s: Settings): Boolean = when (def.name) {
        "set_volume" -> args.num("volume")?.let { kotlin.math.abs(it - s.volume) < 0.005 } == true
        // a saved preset may have a built-in's name with other content: compare what would be set
        "apply_eq_preset" -> args.str("name")?.let { n -> (s.eqUserPresets + EqProfile.PRESETS).firstOrNull { it.name.equals(n, ignoreCase = true) } }
            ?.let { p -> s.eq.enabled && p.copy(enabled = true).normalized() == s.eq.normalized() } == true
        else -> false
    }

    fun describe(def: ToolDef, args: JsonObject): String = when (def.name) {
        "set_volume" -> "Lautstärke auf ${Math.round((args.num("volume") ?: 0.0) * 100)} %"
        "apply_eq_preset" -> "Equalizer-Vorlage „${args.str("name")}“"
        "set_eq" -> "Equalizer: " + ((args["eq"] as? JsonObject)?.entries?.joinToString { (k, v) -> "$k ${short(v)}" } ?: "")
        "set_deck_fx" -> "${args.str("param")} auf ${args.num("value")?.let { Math.round(it * 100) / 100.0 }}"
        "update_settings" -> "Einstellungen: " + ((args["settings"] as? JsonObject)?.entries?.joinToString { (k, v) -> "$k = ${short(v)}" } ?: "")
        "set_fx_chain" -> fxDescription(args["effects"]) + (args.str("name")?.let { "\nName: $it" } ?: "")
        "apply_fx_preset" -> "Gespeicherte Kette „${args.str("name")}“"
        "delete_playlist" -> "Playlist ${args.str("playlist_id")}"
        "clear_history" -> "Den ganzen Verlauf"
        "clear_up_next" -> "Alles nach dem aktuellen Titel aus der Warteschlange"
        "remove_download" -> "Download ${args.str("track_id")}"
        "remove_from_playlist" -> "Titel Nr. ${args.num("index")?.toInt()} aus Playlist ${args.str("playlist_id")}"
        "delete_fx_preset" -> "FX-Kette „${args.str("name")}“"
        else -> "${def.name} ${args.entries.joinToString { (k, v) -> "$k=${short(v)}" }}"
    }

    private fun fxDescription(raw: JsonElement?): String = (raw as? JsonArray)?.joinToString(" › ") { el ->
        val o = el as? JsonObject ?: return@joinToString "?"
        val type = o.str("type") ?: "?"
        val label = FxUnitSpec.of(type)?.label ?: type
        val params = o.entries.filter { it.key != "type" }.joinToString(", ") { (k, v) -> "$k ${short(v)}" }
        if (params.isEmpty()) label else "$label ($params)"
    } ?: "?"

    private fun short(v: JsonElement): String = (v as? JsonPrimitive)?.let { p -> p.doubleOrNull?.let { d -> if (d == Math.floor(d) && kotlin.math.abs(d) < 1e6) d.toLong().toString() else (Math.round(d * 100) / 100.0).toString() } ?: p.contentOrNull } ?: v.toString().take(60)

    private fun canonical(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> "$k:${canonical(v)}" }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        else -> e.toString()
    }

    companion object {
        val DELETE_TOOLS = setOf("remove_from_playlist", "delete_fx_preset")
        val FX_TOOLS = setOf("set_fx_chain", "apply_fx_preset")
        val LOUDNESS_TOOLS = setOf("set_volume", "set_eq", "apply_eq_preset")
        /** Deck parameters that change how loud a deck is. */
        val LOUDNESS_FX = setOf("volume", "eq_low", "eq_mid", "eq_high")
        val LOUDNESS_SETTINGS = setOf("volume", "loudnessNormalization", "eq")
        val FX_SETTINGS = setOf("fxChain", "sound3dPreset")
        /** List settings update_settings may change; dropping an entry counts as deleting. */
        private val LIST_SETTINGS: Map<String, (Settings) -> List<String>> = mapOf(
            "preferredGenres" to { it.preferredGenres },
            "preferredArtists" to { it.preferredArtists },
        )
    }
}

internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
