package dev.schlubbe.musicagent.ui.advent

/** What is behind a door of the Adventskalender: big, well-known productions only.
 * ARD ids were picked from the ARD Audiothek, YouTube videos only from the publishers'
 * own channels (Maritim, WinterZeit, Holysoft). */
sealed class AdventContent {
    /** Where "Mehr davon" leads: the show or channel ("ard:…", "yt:…"). */
    abstract val showId: String?

    /** One ARD Audiothek episode (item id), played straight away; when it has been taken
     * down, its show opens instead. */
    data class ArdEpisode(val itemId: String, override val showId: String) : AdventContent()

    /** A whole show, opened on its page. */
    data class Show(override val showId: String) : AdventContent()

    /** A full Hörspiel on its publisher's YouTube channel, played as audio. */
    data class YouTube(val videoId: String, val channelId: String, val channel: String, val durationSec: Int) : AdventContent() {
        override val showId: String get() = "yt:$channelId"
        val url: String get() = "https://www.youtube.com/watch?v=$videoId"
        val cover: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    /** A music playlist: the first playlist the search finds for [query]. */
    data class Playlist(val query: String) : AdventContent() {
        override val showId: String? get() = null
    }
}

data class AdventDoor(
    val day: Int,
    val label: String,
    val title: String,
    val teaser: String,
    val content: AdventContent,
)

private fun ard(item: String, set: String) = AdventContent.ArdEpisode(item, "ard:$set")
private fun show(set: String) = AdventContent.Show("ard:$set")

private const val HOLY = "UC_df2qgwDbfuubYMaJ03UVQ"
private const val WINTERZEIT = "UC706WYJusWohe_AE_-f1FVg"
private const val MARITIM = "UCjMzlcG7THh8QEsP5pWFr2w"
private fun yt(id: String, channel: String, name: String, seconds: Int) = AdventContent.YouTube(id, channel, name, seconds)

// for grown-ups: no children's series
val ADVENT_DOORS = listOf(
    AdventDoor(1, "Hörspiel", "Sherlock Holmes: Der diebische Weihnachtsmann", "X-Mas Special 1 der Sherlock Holmes Chronicles", yt("felcDRajEKg", WINTERZEIT, "WinterZeit", 2281)),
    AdventDoor(2, "Musik", "Lametta fürs Ohr", "Die Weihnachts-Playlist der ARD", show("12105819")),
    AdventDoor(3, "Hörspiel", "Nussknacker und Mausekönig", "E.T.A. Hoffmanns Weihnachtsmärchen als großes Hörspiel (Holy Klassiker)", yt("t4Y-M28LMMI", HOLY, "Holy Klassiker", 4710)),
    AdventDoor(4, "Hörspiel", "Stille Nacht II", "Familien-Weihnacht im Videocall, aus dem ARD Hörspiel-Speicher", ard("15577013", "31649612")),
    AdventDoor(5, "Musik", "Christmas Hits", "Die großen Weihnachtssongs", AdventContent.Playlist("Christmas Hits")),
    AdventDoor(6, "Hörspiel", "Sherlock Holmes: Der blaue Karfunkel", "Holmes' Weihnachtsfall, Maritim-Hörspiel", yt("-rVt3R4Ta6E", MARITIM, "Maritim Verlag", 3272)),
    AdventDoor(7, "Hörspiel", "Dreamland Grusel: Mörderische Weihnachten", "Grusel-Hörspiel von Maritim", yt("IK-4pDWUfyI", MARITIM, "Maritim Verlag", 2699)),
    AdventDoor(8, "Musik", "Der Nussknacker", "Tschaikowskys Blumenwalzer, erklärt und gespielt", ard("15700715", "51600256")),
    AdventDoor(9, "Hörspiel", "Der kleine Lord", "Der Weihnachtsklassiker als Hörspiel (Holy Klassiker)", yt("CCWBQpElVAg", HOLY, "Holy Klassiker", 4897)),
    AdventDoor(10, "Hörspiel", "Hotel 4 Xmas", "Charmant-schräges Comedy-Hörspiel der ARD", ard("15577497", "46941692")),
    AdventDoor(11, "Musik", "Deutsche Weihnachtslieder", "Zum Mitsingen", AdventContent.Playlist("Deutsche Weihnachtslieder")),
    AdventDoor(12, "Hörspiel", "Sherlock Holmes: Tödliche Weihnachten", "X-Mas Special 2 der Sherlock Holmes Chronicles, 100 Minuten", yt("6PBkZ5Dw6gw", WINTERZEIT, "WinterZeit", 6064)),
    AdventDoor(13, "Krimi", "Kein Mucks!: Unterm Mistelzweig", "Das Christmas Special mit Bastian Pastewka", ard("15649225", "77021218")),
    AdventDoor(14, "Hörspiel", "Sherlock Holmes: Die andere Frau", "X-Mas Special 3 der Sherlock Holmes Chronicles", yt("J8IU5_AowzU", WINTERZEIT, "WinterZeit", 4130)),
    AdventDoor(15, "Hörspiel", "Das letzte Schaf", "Die Weihnachtsgeschichte, neu erzählt (MDR)", ard("15586427", "31649612")),
    AdventDoor(16, "Hörbuch", "Charles Dickens: Weihnachtsgeschichten", "Gelesen für WDR 5", show("12180991")),
    AdventDoor(17, "Musik", "Weihnachtsjazz", "Mit der NDR Bigband", ard("15654345", "95237386")),
    AdventDoor(18, "Hörspiel", "Sherlock Holmes: Das Rätsel der grauen Katze", "X-Mas Special 4 der Sherlock Holmes Chronicles", yt("iiMQV-iJFYs", WINTERZEIT, "WinterZeit", 3834)),
    AdventDoor(19, "Podcast", "Loriot 100 – mehr Lametta", "Mit Ariana Baborie", show("12866833")),
    AdventDoor(20, "Hörspiel", "D Nudelsupp", "Eine schwäbische Weihnachtsgeschichte (SWR)", ard("15567687", "14172839")),
    AdventDoor(21, "Musik", "Weihnachtsklassik", "Bach, Händel, Tschaikowsky", AdventContent.Playlist("Weihnachtsklassik")),
    AdventDoor(22, "Podcast", "Wir knacken Weihnachten", "Klassik für Klugscheisser über den Nussknacker", ard("13988019", "72079106")),
    AdventDoor(23, "Musik", "Lieblingsmusik zu Weihnachten", "Lieblingsstücke, vorgestellt von der ARD", show("13983985")),
    AdventDoor(24, "Hörspiel", "Die hr3 Weihnachtsgeschichte", "Das große Hörspiel für Heiligabend", ard("15979231", "83904916")),
)
