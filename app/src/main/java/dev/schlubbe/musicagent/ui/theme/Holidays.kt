package dev.schlubbe.musicagent.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.schlubbe.musicagent.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** How a holiday restyles Dawn Music: its accent colours, the dawn sky
 * behind every screen and a watercolour motif for Home and Search. Colours are
 * picked like [OceanDawn]'s: white on [accent] at least 5:1, [accentDark] at
 * least 7:1 on the glass, [accent2] at least 4.5:1 on the page background. */
enum class HolidayLook(
    @DrawableRes val art: Int,
    val accent: Color,
    val accentDark: Color,
    val accent2: Color,
    val sky: List<Color>,
) {
    NEW_YEAR(R.drawable.dawn_event_newyear, Color(0xFF2F3E9E), Color(0xFF1F2A6E), Color(0xFF7A5E00), listOf(Color(0xFFF6E3B4), Color(0xFFC9D3F2), Color(0xFFEEF1FB))),
    VALENTINE(R.drawable.dawn_event_valentine, Color(0xFFC2185B), Color(0xFF8E1244), Color(0xFFB23A3A), listOf(Color(0xFFF9C6D3), Color(0xFFF4D2E2), Color(0xFFFDF0F4))),
    CARNIVAL(R.drawable.dawn_event_carnival, Color(0xFF7B1FA2), Color(0xFF5A1678), Color(0xFFB0420A), listOf(Color(0xFFFFE08A), Color(0xFFCDE6FF), Color(0xFFF3E9FF))),
    EASTER(R.drawable.dawn_event_easter, Color(0xFF2E7D32), Color(0xFF1F5A23), Color(0xFFB0306A), listOf(Color(0xFFFFF0B3), Color(0xFFD3EDC4), Color(0xFFF3FAEE))),
    HALLOWEEN(R.drawable.dawn_event_halloween, Color(0xFFB5400B), Color(0xFF7A2C08), Color(0xFF6B3FA0), listOf(Color(0xFFFFC58A), Color(0xFFE2C8F0), Color(0xFFF6EEFB))),
    CHRISTMAS(R.drawable.dawn_event_christmas, Color(0xFFB71C1C), Color(0xFF7F1414), Color(0xFF2E7D32), listOf(Color(0xFFF6D0C8), Color(0xFFD3E8DA), Color(0xFFF4F8F5))),
}

/** A day worth marking: the Home greeting for it, optionally a [look] and a
 * Search tile ([searchLabel] runs [searchQuery]). */
data class Holiday(
    val greeting: String,
    val look: HolidayLook? = null,
    val searchLabel: String? = null,
    val searchQuery: String? = null,
)

/** Today's date for holiday purposes. [override] lets a test build pretend
 * another day (`adb shell am start ... -e dawn_date 2026-12-24`). */
object HolidayClock {
    @Volatile var override: LocalDate? = null
    fun today(): LocalDate = override ?: LocalDate.now()
    fun now(): LocalDateTime = override?.let { LocalDateTime.of(it, LocalDateTime.now().toLocalTime()) } ?: LocalDateTime.now()
}

/** Holiday of the day in Germany, or null. Fixed dates plus the ones anchored
 * on Easter (Karneval, Ostern) and Christmas (Advent). */
fun holidayFor(date: LocalDate): Holiday? {
    val year = date.year
    val md = date.monthValue * 100 + date.dayOfMonth
    val easter = easterSunday(year)
    val christmasEve = LocalDate.of(year, 12, 24)
    val fourthAdvent = christmasEve.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val firstAdvent = fourthAdvent.minusWeeks(3)
    val newYear = Holiday("Frohes neues Jahr!", HolidayLook.NEW_YEAR, "Silvester", "Silvester Party Hits")
    val christmasSearch = "Weihnachtslieder"
    return when {
        md == 101 -> newYear
        md == 1231 -> newYear.copy(greeting = "Guten Rutsch!")
        md == 214 -> Holiday("Schönen Valentinstag!", HolidayLook.VALENTINE, "Love Songs", "Love Songs")
        date == easter.minusDays(52) -> Holiday("Helau und Alaaf!", HolidayLook.CARNIVAL, "Karneval", "Karneval Hits")
        date == easter.minusDays(48) -> Holiday("Schönen Rosenmontag!", HolidayLook.CARNIVAL, "Karneval", "Karneval Hits")
        date > easter.minusDays(52) && date <= easter.minusDays(47) ->
            Holiday("Helau und Alaaf!", HolidayLook.CARNIVAL, "Karneval", "Karneval Hits")
        date == easter.minusDays(2) -> Holiday("Schönen Karfreitag", HolidayLook.EASTER, "Ostern", "Frühlingshits")
        date == easter -> Holiday("Frohe Ostern!", HolidayLook.EASTER, "Ostern", "Frühlingshits")
        date == easter.plusDays(1) -> Holiday("Schönen Ostermontag!", HolidayLook.EASTER, "Ostern", "Frühlingshits")
        date >= easter.minusDays(7) && date < easter -> Holiday("Schöne Osterzeit!", HolidayLook.EASTER, "Ostern", "Frühlingshits")
        date == LocalDate.of(year, 5, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY)) ->
            Holiday("Schönen Muttertag!")
        md == 1003 -> Holiday("Schönen Tag der Deutschen Einheit!")
        md == 1031 -> Holiday("Happy Halloween!", HolidayLook.HALLOWEEN, "Halloween", "Halloween Party")
        md in 1024..1030 -> Holiday("Bald ist Halloween!", HolidayLook.HALLOWEEN, "Halloween", "Halloween Party")
        md == 1111 -> Holiday("Schönen Martinstag!")
        md in 1224..1226 -> Holiday("Frohe Weihnachten!", HolidayLook.CHRISTMAS, "Weihnachten", christmasSearch)
        md in 1227..1230 -> Holiday("Schöne Feiertage!", HolidayLook.CHRISTMAS, "Weihnachten", christmasSearch)
        md == 1206 -> Holiday("Schönen Nikolaustag!", HolidayLook.CHRISTMAS, "Weihnachten", christmasSearch)
        date >= firstAdvent && date < christmasEve -> {
            val greeting = if (date.dayOfWeek == DayOfWeek.SUNDAY) {
                val n = 1 + (date.toEpochDay() - firstAdvent.toEpochDay()).toInt() / 7
                "Schönen $n. Advent!"
            } else {
                "Schöne Adventszeit!"
            }
            Holiday(greeting, HolidayLook.CHRISTMAS, "Weihnachten", christmasSearch)
        }
        else -> null
    }
}

/** Gregorian Easter Sunday (Meeus/Jones/Butcher): 22 March to 25 April. */
internal fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate.of(year, month, day)
}

/** [base] restyled for [look]: accent ramp, second accent and page background. */
fun CanopyColors.withHoliday(look: HolidayLook?): CanopyColors {
    if (look == null) return this
    val a = look.accent
    val dark = look.accentDark
    val light = lerp(Color.White, a, 0.1f)
    return copy(
        bg = look.sky.last(),
        accent = a,
        accent2 = look.accent2,
        accent100 = light,
        accent200 = lerp(light, a, 0.25f),
        accent300 = lerp(light, a, 0.5f),
        accent400 = lerp(light, a, 0.75f),
        accent500 = a,
        accent600 = lerp(a, Color.Black, 0.12f),
        accent700 = dark,
        accent800 = lerp(dark, Color.Black, 0.2f),
        accent900 = lerp(dark, Color.Black, 0.5f),
        accent2_100 = lerp(Color.White, look.accent2, 0.12f),
        accent2_500 = look.accent2,
        accent2_800 = lerp(look.accent2, Color.Black, 0.35f),
    )
}

/** The holiday Dawn Music is dressed for right now (null: none, or the
 * full app). */
val LocalHoliday = staticCompositionLocalOf<Holiday?> { null }
