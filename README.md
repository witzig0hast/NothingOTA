# cmfexport – alle Daten aus der Nothing CMF Watch Pro 3 holen

> **Neu:** [`server/`](server/README.md) ist ein Docker-Backend, das die Daten automatisch an
> **Home Assistant** weitergibt (MQTT Discovery, 29 Sensoren: Schlafphasen, Puls, SpO2, Stress, Schritte, Uhr-Akku, Trainings …).
> Die App lädt dafür alle 15 Minuten (einstellbar) im Hintergrund hoch.

Die offizielle CMF-Watch-App hat **keinen Export-Knopf**. Es gibt aber drei Wege an die
Rohdaten (Schlafphasen, Puls, SpO2, Stress, Schritte, Distanz, Kalorien, Workouts inkl. GPS).
Dieses Repo enthält ein Python-Tool (nur Standardbibliothek, Python ≥ 3.9), das die Dateien
aus Weg A und B in saubere CSV/JSON/GPX-Dateien plus einen HTML-Bericht umwandelt.

| Weg | Was du bekommst | Aufwand | Offizielle App weiter nutzbar? |
|---|---|---|---|
| **A – Gadgetbridge** (empfohlen) | *alles*, was die Uhr speichert, minutengenau: Schlafphasen, Puls, SpO2, Stress, Schritte/Distanz/Kalorien, Workouts + GPS | mittel | nein (eine App pro Uhr) |
| **B – Health Connect** | was die CMF-App nach Health Connect synchronisiert (Schlaf inkl. Phasen, Puls, Schritte, Kalorien, Workouts, je nach App-Version) | gering | ja |
| **C – DSGVO-Auskunft** | komplette Cloud-Historie bei Nothing | Brief/Mail, bis 1 Monat warten | ja |

> Tipp: Erst **B** machen (sichert die Vergangenheit aus der CMF-App), dann auf **A** umsteigen
> (ab dann bekommst du jede Minute Rohdaten). Für die vollständige Historie zusätzlich **C**.

---

## Android-App (APK)

Alles oben gibt es auch als App direkt fürs Handy – kein PC nötig: **[`dist/CMF-Export-1.2.apk`](dist/CMF-Export-1.2.apk)**
(Android 8+, ca. 3 MB). Daten verlassen das Handy nur, wenn du die ZIP teilst oder unter
„Home Assistant“ deinen eigenen [cmfserver](server/README.md) einträgst.

Neu in 1.2: Startseite mit letzter Nacht, Schritten, Puls und Uhr-Akku, helles und dunkles Design,
Senden an Home Assistant alle 15 Minuten (einstellbar bis 6 Stunden).

1. APK aufs Handy laden und öffnen → „Installation aus unbekannten Quellen“ für Browser/Dateimanager erlauben.
2. **Health Connect (Weg B, ohne Export-ZIP):** in der CMF-Watch-App Health-Connect-Sync aktivieren →
   in CMF Export „1. Health Connect Zugriff erlauben“ → alles freigeben (inkl. „Vergangene Daten lesen“,
   sonst gibt Health Connect nur 30 Tage heraus) → „2. Aus Health Connect exportieren“.
3. **Gadgetbridge (Weg A):** in Gadgetbridge die Datenbank exportieren → in CMF Export
   „Gadgetbridge-Datei auswählen …“.
4. Danach: „Bericht anzeigen“ (Schlafbalken + Tageswerte), „ZIP speichern …“ oder „ZIP teilen …“.
   Die ZIP enthält dieselben CSV/GPX/HTML-Dateien wie das Python-Tool, bei Health Connect zusätzlich
   Ruhepuls, HRV, Atemfrequenz, Etagen, Geschwindigkeit, VO2max und Gewicht.

Selbst bauen (JDK 17+, Android SDK 36): `cd android && ./gradlew testReleaseUnitTest assembleRelease`
→ `android/app/build/outputs/apk/release/app-release.apk`. Bei jedem Push baut außerdem GitHub Actions
(`.github/workflows/android.yml`) die APK und hängt sie als Artefakt an. Die APK ist mit einem festen,
nicht geheimen Schlüssel signiert (`android/app/cmfexport.keystore`, Passwort `android`), damit neue
Versionen sich über alte installieren lassen.

## CMF Track – Aktivitäten aufzeichnen (zweite App)

**[`dist/CMF-Track-1.0.apk`](dist/CMF-Track-1.0.apk)** ist ein eigenständiger Aktivitäts-Tracker: Die Strecke
kommt per GPS vom Handy, der Puls von der Uhr.

- **Sportarten:** Laufen, Gehen, Wandern, Radfahren, Inlineskaten, Sonstiges
- **Live-Werte:** Zeit, Distanz, aktuelles und Ø-Tempo (oder km/h), Puls, Höhenmeter, Schrittfrequenz,
  GPS-Genauigkeit, km-Splits, Route; dazu eine Benachrichtigung mit den Werten
- **Genauigkeit:** GPS-Kalman-Filter mit Geschwindigkeitsmodell, Distanz aus der Doppler-Geschwindigkeit des GPS,
  Sprung- und Ungenauigkeitsfilter, Auto-Pause mit Bestätigung in beide Richtungen, Höhenmeter per Barometer
  (sonst geglättete GPS-Höhe). In den Tests mit verrauschtem GPS: 5 km auf 0,07 % genau,
  3-km-Runde auf 0,06 %, Bewegungszeit auf ±1 s, keine Phantomstrecke im Stand.
- **Puls von der Uhr:** live über Bluetooth von jedem Gerät, das den Standard-Herzfrequenzdienst sendet (Brustgurt,
  Uhr im Puls-Übertragungsmodus). Sonst lädt die App nach dem Training automatisch die Pulswerte,
  die die CMF-App in Health Connect gespeichert hat, und rechnet Ø/Max-Puls, Kalorien (Keytel-Formel),
  Pulszonen und Splits neu.
- **Auswertung:** Route nach Tempo eingefärbt, Tempo-, Höhen- und Pulsdiagramm, Pulszonen, km-Splits mit Puls
  und Höhe
- **Export:** TCX (mit Puls, lässt sich in Strava, Garmin Connect und Runalyze hochladen) und GPX.
  Optional geht jedes Training automatisch an den cmfserver; in Home Assistant erscheinen dann
  „Letztes Training“, Distanz, Dauer und Ø-Puls.

Beide Apps sind mit demselben Schlüssel signiert, laufen aber unabhängig voneinander.

## Wichtig: Warum Schlafdaten auf der Uhr „verschwinden“

CMF Export **liest nur**: Es hat ausschließlich Lese-Berechtigungen für Health Connect und öffnet die
Gadgetbridge-Datei schreibgeschützt. Es kann auf der Uhr nichts löschen.

Die Uhr selbst löscht aber Daten, sobald eine App sie abgeholt und den Empfang bestätigt hat. Das tun
**Gadgetbridge und die CMF-App gleichermaßen**. Ist die Uhr abwechselnd mit beiden verbunden, bekommt
jede App nur einen Teil der Nächte. Deshalb:

- **Nur eine App** mit der Uhr verbinden. Entweder die CMF-App (dann holt CMF Export die Daten aus
  Health Connect) oder Gadgetbridge (dann aus der Gadgetbridge-Datei).
- Mit der CMF-App: Prüfe in Health Connect unter *App-Berechtigungen → CMF Watch*, ob „Schlaf“
  **schreiben** erlaubt ist. Fehlt das, landen keine Schlafdaten in Health Connect. Im Protokoll von
  CMF Export siehst du, welche App wie viele Datensätze geliefert hat.

## Weg A – Gadgetbridge (Open Source, direkt per Bluetooth)

[Gadgetbridge](https://gadgetbridge.org/gadgets/wearables/nothing/) unterstützt die CMF Watch Pro,
Pro 2 und **Pro 3** (Unterstützung für die Pro 3 kam laut Wiki über Issue #5596, alle Pro‑2‑Funktionen
gelten auch für die Pro 3). Es speichert alle Messwerte in einer lokalen SQLite-Datenbank.

1. **Optional zuerst Weg B ausführen**, damit die Historie aus der CMF-App gesichert ist.
2. In der CMF-Watch-App die Uhr entkoppeln, danach in Android → Bluetooth die Uhr „Entfernen“.
   Uhr auf Werkseinstellungen zurücksetzen, wenn sie sich nicht neu koppeln lässt.
3. Gadgetbridge aus [F-Droid](https://f-droid.org/packages/nodomain.freeyourgadget.gadgetbridge/)
   installieren (Version ≥ 0.85; je neuer, desto besser für die Pro 3).
4. In Gadgetbridge **„+“ → CMF Watch Pro 3** koppeln. Falls nach einem Auth-Key gefragt wird:
   siehe Gadgetbridge-Wiki-Seite oben.
5. Uhr ein paar Tage/Nächte tragen, in Gadgetbridge regelmäßig nach unten wischen (= Sync).
6. Datenbank exportieren: **Gadgetbridge → ☰ → Datenverwaltung → „Datenbank exportieren“**
   (bzw. „Als ZIP exportieren“). Die Datei `Gadgetbridge.db` / `.zip` auf den PC kopieren.
7. Auswerten:

```bash
python3 -m cmfexport gadgetbridge Gadgetbridge.db -o export --device CMF --raw
```

Ergebnis in `export/`:

| Datei | Inhalt |
|---|---|
| `sleep_sessions.csv` | pro Nacht: eingeschlafen, aufgewacht, Zeit im Bett, Schlaf, Tief/Leicht/REM/Wach (Minuten), Effizienz |
| `sleep_stages.csv` | jede einzelne Schlafphase mit Start, Ende, Dauer |
| `heart_rate.csv` | alle Pulsmessungen |
| `spo2.csv`, `stress.csv` | Blutsauerstoff‑ und Stressmessungen |
| `activity.csv` | Minutenwerte: Schritte, Distanz (m), Kalorien, Puls |
| `workouts.csv` + `gpx/*.gpx` | Trainings (Sportart, aktive Zeit) und GPS-Tracks (z. B. für Strava/Komoot) |
| `daily_summary.csv` | Tageswerte: Schritte, Distanz, Kalorien, Ø/min/max Puls, SpO2, Stress, Schlaf |
| `all_data.json` | alles zusammen maschinenlesbar |
| `raw/*.csv` | (`--raw`) alle CMF-Tabellen 1:1, auch noch unbekannte Felder |
| `report.html` | Übersicht mit Schlafbalken, im Browser öffnen |

`--device` filtert nach Gerätename oder MAC, falls mehrere Uhren in der DB sind.

## Weg B – Health Connect (offizielle App bleibt)

1. **CMF-Watch-App → Profil/Einstellungen → Health Connect** → Synchronisierung aktivieren und alle
   Datentypen erlauben (Schlaf, Herzfrequenz, Schritte, Kalorien, Training …). Einmal syncen.
2. Export (Android 14+): **Einstellungen → Sicherheit & Datenschutz → Datenschutz → Health Connect →
   Sichern und wiederherstellen → Exportieren** (bei manchen Herstellern: Einstellungen → Apps →
   Health Connect). Es entsteht `Health Connect.zip`.
3. Auswerten:

```bash
python3 -m cmfexport healthconnect "Health Connect.zip" -o export_hc --app com.nothing.cmf
```

`--app` behält nur Daten, die die CMF-App geschrieben hat (ohne `--app` kommt alles aus Health Connect).
Das Tool liest das Schema dynamisch aus, weil Google es zwischen Android-Versionen ändert:
jede `*_record_table` landet in `records/`, Zeitreihen (z. B. Pulswerte) in `series/`,
dazu `sleep_sessions.csv`, `sleep_stages.csv`, `summary.json` und `report.html`.
Die Ausgabe zeigt auch, welche Apps nach Health Connect schreiben (CMF wird markiert).

## Weg C – DSGVO-Auskunft (Art. 15 / Art. 20)

Die CMF-App lädt alle Daten in die Nothing-Cloud. Als EU-Nutzer hast du Anspruch auf eine Kopie in
maschinenlesbarer Form. Kurze Mail an den Datenschutzkontakt aus der Datenschutzerklärung der
CMF-Watch-App (in der App unter Einstellungen → Über → Datenschutzrichtlinie), z. B.:

> Betreff: Auskunft und Datenübertragbarkeit nach Art. 15 und 20 DSGVO
>
> Bitte stellen Sie mir alle zu meinem CMF-Watch-Konto (E-Mail: …) gespeicherten personenbezogenen
> Daten, insbesondere Schlaf-, Herzfrequenz-, SpO2-, Stress-, Aktivitäts- und Trainingsdaten, in einem
> strukturierten, gängigen und maschinenlesbaren Format (CSV/JSON) zur Verfügung. Frist: ein Monat.

## Entwicklung

```bash
pip install pytest
python3 -m pytest -q
```

Die Tests bauen synthetische Datenbanken mit exakt dem Gadgetbridge-Schema (aus `GBDaoGenerator.java`)
bzw. einem Health-Connect-Schema und prüfen Schlafphasen-Berechnung, Gerätefilter, Workout-Dekodierung,
GPX-Erzeugung und App-Filter.

### Technische Details (Gadgetbridge-Schema)

| Tabelle | Spalten | Einheit |
|---|---|---|
| `CMF_ACTIVITY_SAMPLE` | `TIMESTAMP, STEPS, DISTANCE, CALORIES, HEART_RATE` | Sekunden, Minutenwerte |
| `CMF_HEART_RATE_SAMPLE` | `TIMESTAMP, HEART_RATE` | ms, bpm |
| `CMF_SPO2_SAMPLE` / `CMF_STRESS_SAMPLE` | `TIMESTAMP, SPO2` / `STRESS` | ms, % / 0–100 |
| `CMF_SLEEP_SESSION_SAMPLE` | `TIMESTAMP, WAKEUP_TIME, METADATA` | ms |
| `CMF_SLEEP_STAGE_SAMPLE` | `TIMESTAMP, DURATION, STAGE` | ms, s, 1=Tief 2=Leicht 3=REM sonst Wach |
| `CMF_WORKOUT_GPS_SAMPLE` | `TIMESTAMP, LATITUDE, LONGITUDE` | ms, Grad × 10⁷ |
| `BASE_ACTIVITY_SUMMARY` | `START_TIME, END_TIME, RAW_SUMMARY_DATA` | ms; Binärformat: `<u32 start><i16 dauer_s><u8 sportart>…<u32 ende @26><u8 gps @30>` |

`cmfexport/workout_types.py` ist aus Gadgetbridges `CmfActivityType.java` (AGPL-3.0) abgeleitet.
