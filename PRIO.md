# Prio-Wiedergabe (Fork-Erweiterung)

Kanäle in eine Prioritätsreihenfolge bringen. Als nächstes läuft immer das älteste ungesehene Video des Kanals mit der höchsten Priorität – wie bei Podcastli. Funktioniert ohne Login, Zustand nur lokal in der App.

Status: **umgesetzt** (Bausteine 0, 1, 3, 4, Wiedergabe-Logik, 6).

## Bedienung

- **Kanal hinzufügen:** Kontextmenü (OK lange drücken) auf einem Video oder Kanal → „Zu Prio-Kanälen hinzufügen“. Neue Kanäle kommen ans Ende der Liste. Die beim Hinzufügen vorhandenen Videos gelten als gesehen, nur neue erscheinen.
- **Bereich „Prio“** (ganz oben in der linken Leiste): offene Videos in Abspielreihenfolge. Wird beim Öffnen aktualisiert (höchstens alle 10 Minuten).
- **Wiedergabe:** Nach dem Ende eines Prio-Videos startet automatisch das nächste (unabhängig vom Wiedergabemodus, die normale Warteschlange hat Vorrang). „Weiter“ überspringt das Video (gilt als gesehen).
- **Gesehen:** ab 90 % angeschaut, am Ende oder beim Überspringen. Manuell über das Kontextmenü („Prio: als gesehen/ungesehen markieren“).
- **Reihenfolge ändern / entfernen:** Kontextmenü auf einem Prio-Video → „Prio-Kanäle verwalten“, oder Einstellungen → „Prio-Kanäle verwalten“. Kanal wählen → Ganz nach oben / Nach oben / Nach unten / Ganz nach unten / Entfernen.

## Umsetzung

Neuer Code in `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prio/`:

| Datei | Inhalt |
|---|---|
| `PrioData` | Speicherung (AppPrefs, lokal): Kanäle in Reihenfolge, bekannte Videos mit Status |
| `PrioService` | Abgleich der Kanal-Videos, Warteschlange, `getNext()`, Gesehen-Status |
| `PrioController` | Player-Controller: markiert Videos als gesehen |
| `PrioMenu` | Kontextmenü-Einträge und Verwalten-Dialog |

Texte: `common/src/main/res/values{,-de}/strings_prio.xml`.

Kleine Eingriffe in bestehenden Code (jeweils mit `// Fork` markiert, für einfache Merges):

- `PlaybackPresenter` – `PrioController` registriert (vor `VideoLoaderController`)
- `SuggestionsController.getNext()` – nächstes Prio-Video (nach der Warteschlange)
- `VideoLoaderController.applyPlaybackMode()` – bei Prio-Videos immer weiterspielen
- `BrowsePresenter`, `SidebarService` – Bereich „Prio“
- `VideoMenuPresenter` – Kontextmenü-Einträge
- `AppDataSourceManager` – Einstellungs-Eintrag
- `Utils.KNOWN_PACKAGES` – Paket-ID (volle „Über“-Seite mit Update-Prüfung)

**Neue Videos:** über die Video-Liste des Kanals der normalen YouTube-Schnittstelle (`ContentService.getGroupObserve`, wie angepinnte Kanäle). Die YouTube-RSS-Feeds liefern aktuell 404 und werden nicht verwendet. Da die Liste keine Upload-Daten enthält, wird die Reihenfolge innerhalb eines Kanals aus der Position in der Liste (neueste zuerst) und dem Zeitpunkt der ersten Sichtung abgeleitet. Gesehene Videos, die seit 30 Tagen nicht mehr in der Liste auftauchen, werden gelöscht.

### 0. Build-Variante `stprio`

- Flavor in `smarttubetv/build.gradle`: `applicationId "org.smarttube.prio"`, `matchingFallbacks = ['ststable']` (Bibliotheken haben keinen eigenen Flavor).
- `smarttubetv/src/stprio/res/`: Name „SmartTube Prio“, Icon/Banner (vorerst Kopie von stable), `searchable.xml` und `search_authority` auf die eigene Paket-ID, `update_urls.xml` auf die eigenen Releases.
- `versionCode` = offizieller versionCode × 10000 + Build-Nummer (`-PprioBuild=N`), `versionName` = `32.67-prio.N`.
- Läuft neben der offiziellen SmartTube. Daten sind getrennt; Übernahme der Einstellungen über SmartTubes Backup möglich.

Lokal bauen:
```bash
./gradlew assembleStprioDebug
```

### 6. Updates aus eigenem Fork

Workflow `.github/workflows/prio-release.yml`:

- **Push auf `master`:** baut `stprio` (Release, signiert) und veröffentlicht Release `latest` mit APKs und `smarttube_prio.json`.
- **Täglich (03:00 UTC) / manuell:** merged `yuliskov/SmartTube` in den Fork, pusht und veröffentlicht, falls sich etwas geändert hat. Bei Merge-Konflikten schlägt der Lauf fehl (Mail von GitHub) → lokal mergen und pushen.
- Die App prüft `https://github.com/wima75/SmartTube/releases/download/latest/smarttube_prio.json` und bietet das Update an.

Einrichtung (einmalig):

1. Keystore erzeugen und **sicher aufbewahren** (ohne ihn keine Updates mehr, nur Neuinstallation):
   ```bash
   keytool -genkeypair -v -keystore smarttube-prio.jks -alias prio -keyalg RSA -keysize 2048 -validity 10000
   ```
2. GitHub → Fork → Settings → Secrets and variables → Actions:
   - `SIGNING_KEY` = Inhalt von `base64 -w0 smarttube-prio.jks`
   - `KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `ALIAS` (`prio`)
   - optional `SYNC_TOKEN`: Personal Access Token mit `repo` + `workflow`. Nötig, sobald ein Upstream-Merge Dateien unter `.github/workflows` ändert (mit dem Standard-Token lehnt GitHub den Push ab).
3. Actions → den Upstream-Workflow „Build Debug APK“ deaktivieren (baut sonst bei jedem Push die Beta-Variante).
4. Erste Installation auf dem Shield: `smarttube_prio_arm64-v8a.apk` aus dem Release `latest` (adb oder Downloader). Danach kommen Updates über die App.

## Nicht enthalten

- ~~2. Eigener RSS-Abruf~~
- ~~5. Unterbrechen~~
- Sync über Google Drive

## Offen / Ideen

- Eigenes Icon/Banner für die Variante (aktuell identisch mit SmartTube).
- Hinweis im leeren Prio-Bereich, solange keine Kanäle hinzugefügt sind.
