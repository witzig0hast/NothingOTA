# cmfexport – alle Daten aus der Nothing CMF Watch Pro 3 holen

> **Neu:** [`server/`](server/README.md) ist **CMF Health**, ein Docker-Container mit Website auf deiner Subdomain:
> - **Website:** Anmeldung, Übersicht, Schlaf, Herz & Atmung, Trainings auf der Karte
> - **Hinweise und KI:** Auffälligkeiten bei Atmung und Erholung, KI-Coach über deinen LiteLLM-Endpunkt
> - **Home Assistant:** Weitergabe aller Werte per MQTT
>
> Die Apps verbinden sich mit einem Tipp und senden alle 15 Minuten.

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

Alles oben gibt es auch als App direkt fürs Handy – kein PC nötig: **[`dist/CMF-Export-1.4.apk`](dist/CMF-Export-1.4.apk)**
(Android 8+, ca. 3 MB). Daten verlassen das Handy nur, wenn du die ZIP teilst oder unter
„Home Assistant“ deinen eigenen [cmfserver](server/README.md) einträgst.

Neu in 1.3:
- **Schlaf pro Tag:** Ein Nickerchen am Nachmittag zählt nicht mehr als „letzte Nacht“. Eine
  unterbrochene Nacht wird zu einer Nacht zusammengefasst.
- **Trends:** Schlaf, Schritte und Ruhepuls der letzten 14 Tage
- **Widget:** zeigt Schlaf, Schritte, Puls und Akku auf dem Startbildschirm
- **Uhr-Akku:** nur noch aus Gadgetbridge. Über Bluetooth meldet die CMF-Uhr einen falschen festen Wert.
- **Home Assistant:** Senden alle 15 Minuten (einstellbar bis 6 Stunden)

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

**[`dist/CMF-Track-3.0.apk`](dist/CMF-Track-3.0.apk)** ist ein eigenständiger Aktivitäts-Tracker im Stil von
Strava/Komoot: Die Strecke kommt per GPS vom Handy, der Puls von der Uhr.

- **Vektorkarte (OpenFreeMap/OpenStreetMap, gestochen scharf, hell und dunkel):** außerdem Ebenen Hell,
  Topo (Wandern) und Satellit. Bei der Aufnahme ist die Karte bildschirmfüllend und folgt deinem Standort.
  Nach dem Training ist die Strecke als Farbverlauf nach Tempo eingefärbt, mit km-Markierungen sowie Start und Ziel.
- **Live-Werte:** Zeit, Distanz, Tempo, Ø-Tempo, Puls (mit Quelle), Höhenmeter, Schrittfrequenz oder kcal,
  km-Splits; Countdown 3-2-1, **Sprachansage** pro Kilometer, **Runden-Taste**, Pause/Runde auch in der
  Benachrichtigung
- **Puls von der Uhr:** Die App liest den Puls, den die CMF-App in Health Connect speichert, schon während
  des Trainings (jede Minute) und danach automatisch im Hintergrund, bis die CMF-App synchronisiert hat.
  Werte, die die Uhr nur alle paar Minuten misst, werden interpoliert. Die App sagt dir klar, warum noch
  kein Puls da ist („noch nicht synchronisiert“, „nicht freigegeben“ …).
  **Tipp:** Starte das Training zusätzlich auf der Uhr, dann misst sie den Puls jede Sekunde.
  Brustgurte (Bluetooth) funktionieren live.
- **Uhr-Trainings übernehmen:** Trainings, die du nur auf der Uhr gestartet hast (mit GPS der Uhr), holt die
  App aus Health Connect und zeigt sie mit Karte, Puls und Splits an.
- **Routen planen wie bei Komoot:**
  - **Planen:** Ziel suchen oder lange auf die Karte drücken; Rundtour mit Wunschdistanz oder „Hin & zurück“,
    zu Fuß oder mit dem Rad.
  - **Navigation:** Die Abbiege-Hinweise kommen als Banner, per Sprache und als Benachrichtigung, die die
    **CMF-Uhr anzeigt**. In der CMF-App muss dafür unter Benachrichtigungen „CMF Track“ an sein.
  - **Abweichung:** Kommst du von der Route ab, warnt die App und berechnet automatisch neu.
  - **Weitere Quellen:** GPX importieren (z. B. aus Komoot), jedes eigene Training als Route übernehmen.
- **Auswertung:** Bestleistungen (400 m bis Marathon) mit 🏆-Bestzeiten, km-Splits, Runden, Pulszonen,
  Tempo-, Höhen-, Puls- und Schrittfrequenz-Diagramm, Schrittlänge, Kalorien (Keytel-Formel)
- **Statistik:** Wochenziel mit Fortschritt, km pro Woche (12 Wochen), Monat, Jahr, gesamt, Höhenmeter,
  Wochen-Serie, persönliche Bestzeiten
- **Teilen und Export:** Bild zum Teilen (Route + Zahlen), TCX mit Puls (Strava, Garmin Connect, Runalyze),
  GPX, in Health Connect speichern, automatisch an Home Assistant senden
- **Genauigkeit:** GPS-Kalman-Filter, Distanz aus der Doppler-Geschwindigkeit, Sprung- und Rauschfilter,
  Auto-Pause mit Bestätigung, Höhenmeter per Barometer; in Tests auf 0,1 % (Distanz) und 1 s (Zeit) genau

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
