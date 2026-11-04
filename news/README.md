# Neuigkeiten

`news.json` wird von Grooveo und Dawn Music alle paar Stunden vom `master`-Branch
gelesen (`NewsWorker`). Jeder neue Eintrag erscheint einmal als Benachrichtigung.
Es gibt keinen Server, kein Konto und keinen Google-Dienst: neue News = Commit auf
`master`. Abschalten lässt sich das unter Einstellungen › Neuigkeiten.

```json
{
  "items": [
    {
      "id": "2026-11-05-hallo",
      "date": "2026-11-05",
      "title": "Hallo!",
      "text": "Kurzer Text, bis 500 Zeichen.",
      "url": "https://music.youtube.com/watch?v=…",
      "apps": ["dawn"],
      "minVersionCode": 3000,
      "maxVersionCode": null
    }
  ]
}
```

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `id` | ja | Eindeutig und nie wiederverwenden, sonst wird der Eintrag nicht noch einmal gezeigt. |
| `title` | ja | Titel der Benachrichtigung (max. 80 Zeichen). |
| `text` | ja | Text (max. 500 Zeichen). |
| `date` | nein | `JJJJ-MM-TT`. Einträge, die älter als 3 Tage sind, werden nicht mehr gezeigt. Liegt das Datum in der Zukunft, erscheint der Eintrag erst an diesem Tag. |
| `url` | nein | Nur `https://`. Ein YouTube- oder SoundCloud-Link (Song, Playlist, Künstler) öffnet sich in der App, ein Song wird direkt abgespielt. Andere Links öffnen den Browser. Ohne `url` öffnet ein Tipp die App. |
| `apps` | nein | `"dawn"` und/oder `"grooveo"`; leer oder weggelassen = beide Apps. |
| `minVersionCode` / `maxVersionCode` | nein | Nur an diese App-Versionen senden (versionCode, z. B. 3000 für 0.7.2). |

Die App liest die Datei ab Version 0.7.3. Bis eine neue Benachrichtigung ankommt, können bis zu 4 Stunden vergehen, auf Handys mit Akkuspar-Modus auch länger.
