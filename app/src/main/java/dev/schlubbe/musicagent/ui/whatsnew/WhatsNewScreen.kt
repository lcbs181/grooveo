package dev.schlubbe.musicagent.ui.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy

private data class WhatsNewItem(val iconName: String, val title: String, val description: String)

private val ITEMS = listOf(
    WhatsNewItem(
        "queue",
        "\"Zur Warteschlange hinzufügen\" spielt jetzt wirklich als nächstes",
        "Hinzugefügte Titel landeten bisher am Ende der Warteschlange statt direkt danach dran zu sein. Jetzt spielt ein hinzugefügter Song wirklich als nächstes, wie der Name es verspricht.",
    ),
    WhatsNewItem(
        "circles-three",
        "Visualizer: spürbar schneller und treffen jetzt den Beat",
        "Die Visualizer liefen bisher mit gut 8 statt 60 Bildern pro Sekunde und reagierten kaum auf einzelne Kicks - das ist behoben. Alle fünf Visualizer laufen jetzt flüssig, die Partikel-Kugel hat viele mehr, kleinere Punkte mit je eigener Frequenz, und der Wellen-Visualizer ist einem kreisförmigen Spektrum gewichen. Bei Pulse spritzt das Konfetti jetzt spürbar auf den Beat statt gleichmäßig zu sprühen.",
    ),
    WhatsNewItem(
        "cloud",
        "Weniger Aussetzer beim Streamen",
        "Der Wiedergabe-Puffer war zu knapp bemessen und riss bei kurzen Netzaussetzern ab. Jetzt wird mehr im Voraus geladen, und bei einem Abbruch versucht die App es automatisch erneut.",
    ),
    WhatsNewItem(
        "stack",
        "Bibliothek merkt sich die Ansicht",
        "Playlists, Likes, Verlauf oder Künstler - die zuletzt gewählte Kachel bleibt erhalten, auch wenn du zwischendurch in einen anderen Tab wechselst.",
    ),
    WhatsNewItem(
        "waveform",
        "Neuer Player",
        "Der Player ist komplett neu: großes Cover, das im Takt atmet, fünf umschaltbare Visualizer, eine Wellenform zum Spulen und ein eigener Bereich für Offline-Wiedergabe.",
    ),
    WhatsNewItem(
        "sliders-horizontal",
        "Equalizer als eigener Bildschirm",
        "Fünf Bänder, Vorverstärker und eine live gezeichnete Frequenzkurve. Die Voreinstellungen wirken sofort; die einzelnen Schieber sind noch Vorschau und werden nicht gespeichert.",
    ),
    WhatsNewItem(
        "download-simple",
        "Downloads als eigener Tab",
        "Downloads sind aus der Bibliothek heraus in die untere Leiste gewandert - mit Warteschlange, Fortschritt pro Titel und Speicheranzeige. Der Konto-Tab ist dafür entfallen.",
    ),
    WhatsNewItem(
        "desktop",
        "Mit PC verbinden",
        "Neuer Bildschirm, um Grooveo auf eine Adresse im eigenen Netz zu zeigen. Die Verbindung wird wirklich geprüft - Bibliothek und Streaming vom PC folgen später.",
    ),
    WhatsNewItem(
        "cloud",
        "Quellen selbst wählen",
        "Der neue Startbildschirm fragt beim ersten Öffnen, ob du SoundCloud, YouTube Music oder beides nutzen willst - und die Suche hält sich daran. Jederzeit änderbar.",
    ),
    WhatsNewItem(
        "sparkle",
        "Neuer Look",
        "Grooveo bekommt ein völlig neues Aussehen: Waldgrün statt Violett, neue Schrift, und erstmals auch ein helles Design. Die Umstellung läuft Bildschirm für Bildschirm.",
    ),
    WhatsNewItem(
        "sparkle",
        "Neuer Name: Grooveo",
        "Music Agent heißt jetzt Grooveo - gleiche App, gleiche Bibliothek, nur ein Name, der sich bei Google nicht mehr mit tausend anderen Ergebnissen teilt.",
    ),
    WhatsNewItem(
        "waveform",
        "Player-Fehler behoben",
        "Der Visualizer stand auf dem Kopf, SoundCloud-Titel brachen vorzeitig ab, und DRM-geschützte Titel wurden stumm übersprungen - alles behoben. DRM-Titel zeigen jetzt \"Song nicht verfügbar\" direkt im Player.",
    ),
    WhatsNewItem(
        "clock-counter-clockwise",
        "Suchverlauf",
        "Die Suche merkt sich zuletzt gesuchte Begriffe und zeigt sie beim Öffnen an - einzeln löschbar per Wisch.",
    ),
    WhatsNewItem(
        "house",
        "Startseite überarbeitet",
        "Neue Grußformel je Tageszeit und Jahreszeit, personalisierte Mixes unter \"Im Fokus\", und \"Neu von Künstlern\" funktioniert jetzt inklusive Vorschaubildern.",
    ),
    WhatsNewItem(
        "stack",
        "Bibliothek neu gestaltet",
        "Komplett neue Übersicht mit Downloads, Likes und Playlists als eigene Unterseiten - näher am SoundCloud-Vorbild.",
    ),
    WhatsNewItem(
        "cloud-arrow-up",
        "Eigene Sicherung",
        "Unter Einstellungen lässt sich der eigene Bestand jetzt als Datei sichern und teilen oder wiederherstellen.",
    ),
    WhatsNewItem(
        "list-plus",
        "Fremde Playlists & Alben",
        "Gespeicherte Playlists und Alben von SoundCloud und YT Music zeigen jetzt Beschreibung und Tags und bekommen dieselben Aktionen wie eigene Playlists.",
    ),
    WhatsNewItem(
        "download-simple",
        "Downloadgröße sichtbar",
        "Beim Herunterladen wird jetzt die Dateigröße jedes Titels angezeigt.",
    ),
    WhatsNewItem(
        "arrow-clockwise",
        "Update-Check im Hintergrund",
        "Music Agent prüft jetzt automatisch auf neue Versionen und meldet sich per Benachrichtigung - dazu eine überarbeitete Wiedergabe-Benachrichtigung mit mehr Reglern.",
    ),
    WhatsNewItem(
        "sparkle",
        "Neues Logo & Feinschliff",
        "Abgerundetes Logo, funktionierende Homescreen-Widgets, eine sanftere Navigationsleiste und ein Layout, das sich an die Größe deines Handys anpasst.",
    ),
)

/** Shown after installing an update, or reachable any time via Einstellungen >
 * Updates & Sicherungen > "Was ist neu". Bottom nav is hidden by the caller
 * (see NavGraph's BOTTOM_BAR_HIDDEN_ROUTES) - immersive/onboarding-style,
 * matching the design handoff. */
@Composable
fun WhatsNewScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canopy.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Canopy.accent800),
                contentAlignment = Alignment.Center,
            ) {
                Icon(phosphorIcon("sparkle"), contentDescription = null, tint = Canopy.accent300, modifier = Modifier.size(26.dp))
            }
            Spacer(modifier = Modifier.padding(top = 16.dp))
            Text(
                "Neu in ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "Music Agent wurde aktualisiert",
                style = MaterialTheme.typography.labelMedium,
                color = Canopy.neutral500,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ITEMS.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Canopy.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(phosphorIcon(item.iconName), contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(item.title, style = MaterialTheme.typography.labelLarge)
                        Text(
                            item.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = Canopy.neutral500,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.padding(top = 30.dp))
        CanopyButton(text = "Los geht's", onClick = onDone, block = true)
        Spacer(modifier = Modifier.padding(bottom = 30.dp))
    }
}
