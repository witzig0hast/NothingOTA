# cmfserver – CMF Watch → Home Assistant

Kleiner Docker-Container, der die Daten deiner CMF Watch Pro 3 annimmt, in einer SQLite-Datenbank
zusammenführt und an **Home Assistant** weitergibt. Bei MQTT legt Home Assistant über MQTT Discovery
automatisch ein Gerät „CMF Watch Pro 3“ mit 25 Sensoren an. Alternativ geht es über die REST-API.

```
CMF Watch ──BT──> CMF-App ──> Health Connect ─┐
                                              ├─> CMF-Export-App ──HTTP──> cmfserver ──MQTT──> Home Assistant
CMF Watch ──BT──> Gadgetbridge ── Auto-Export ┘                              ▲
                                  └── Syncthing/Nextcloud ──> /import ───────┘
```

## Sensoren in Home Assistant

| Sensor | Entity (Beispiel) | Einheit |
|---|---|---|
| Schlaf letzte Nacht, Tief-, Leicht-, REM-Schlaf, Wachphasen, Zeit im Bett | `sensor.cmf_watch_pro_3_schlaf_letzte_nacht` … | min |
| Schlafeffizienz | `sensor.cmf_watch_pro_3_schlafeffizienz` | % |
| Eingeschlafen / Aufgewacht | `sensor.cmf_watch_pro_3_eingeschlafen` | Zeitstempel |
| Schritte heute / gestern, Distanz heute, Kalorien heute | `sensor.cmf_watch_pro_3_schritte_heute` | steps, m, kcal |
| Puls (letzter Wert), Puls Ø/min/max heute, Ruhepuls, HRV | `sensor.cmf_watch_pro_3_puls` | bpm, ms |
| Blutsauerstoff, Stress, Atemfrequenz, Gewicht | `sensor.cmf_watch_pro_3_blutsauerstoff` | %, –, /min, kg |
| Letztes Training, Letzte Synchronisierung | `sensor.cmf_watch_pro_3_letztes_training` | – |

Jeder Sensor hat Attribute, z. B. `measured_at` beim Puls oder alle Schlafphasen beim Schlafsensor.
Home Assistant speichert den Verlauf selbst, so bekommst du Langzeit-Statistiken und Graphen.
Den Verlauf vor der ersten Synchronisierung kann Home Assistant nicht nachträglich übernehmen.
Er liegt aber im Server und ist über `/api/history` abrufbar.

## Installation

1. **MQTT in Home Assistant** einrichten, falls noch nicht vorhanden: Add-on „Mosquitto broker“
   installieren, die Integration „MQTT“ hinzufügen und einen Benutzer für den Server anlegen
   (Einstellungen → Personen → Benutzer, oder `logins` im Mosquitto-Add-on).
2. Token erzeugen: `openssl rand -hex 24`
3. `server/docker-compose.yml` anpassen (`API_TOKEN`, `MQTT_HOST`, `MQTT_USER`, `MQTT_PASSWORD`, `TZ`)
   und im Repo-Verzeichnis starten:
   ```bash
   docker compose -f server/docker-compose.yml up -d --build
   ```
   Oder ohne Compose:
   ```bash
   docker build -f server/Dockerfile -t cmfserver .
   docker run -d --name cmfserver --restart unless-stopped -p 8080:8080 \
     -e TZ=Europe/Berlin -e API_TOKEN=... -e MQTT_HOST=192.168.1.10 -e MQTT_USER=... -e MQTT_PASSWORD=... \
     -v cmf-data:/data -v cmf-import:/import cmfserver
   ```
4. Im Browser `http://<server>:8080/?token=<API_TOKEN>` öffnen. Dort siehst du die Statusseite und kannst
   Dateien hochladen.
5. **Handy:** In der App „CMF Export“ (`dist/CMF-Export-1.1.apk`) unter **C · Home Assistant** die
   Server-Adresse und das Token eintragen, dann „Verbindung testen“ und
   „Automatisch alle 6 Stunden synchronisieren“ antippen. Dabei auch „Im Hintergrund lesen“ in
   Health Connect erlauben.

Danach erscheint in Home Assistant unter *Einstellungen → Geräte & Dienste → MQTT* das Gerät
**CMF Watch Pro 3**.

## Wege, wie Daten zum Server kommen

| Weg | Wie |
|---|---|
| **App, automatisch** | Die App liest alle 6 h die letzten 3 Tage aus Health Connect und lädt sie hoch. Hast du unter B eine Gadgetbridge-Datei gewählt, schickt sie auch diese mit. |
| **App, manuell** | Nach jedem Export „An Server senden“ oder „Jetzt synchronisieren“ |
| **Import-Ordner** | Jede Datei in `/import` (Gadgetbridge-DB/-ZIP oder Export-ZIP) wird automatisch eingelesen. Ideal mit **Gadgetbridge → Einstellungen → Automatischer Export** in einen Ordner, den **Syncthing** oder Nextcloud auf den Server spiegelt. |
| **HTTP** | `curl -H "Authorization: Bearer $TOKEN" --data-binary @Gadgetbridge.db http://server:8080/api/upload` |

Mehrfache Uploads sind kein Problem: Datensätze werden über ihren Zeitstempel zusammengeführt.
Schritte, Distanz und Kalorien eines Tages werden nie kleiner.

## Konfiguration (Umgebungsvariablen)

| Variable | Standard | Bedeutung |
|---|---|---|
| `API_TOKEN` | – | Token für API, Upload und Statusseite (*bitte setzen*). Alternativ `API_TOKEN_FILE` |
| `MQTT_HOST`, `MQTT_PORT` | –, `1883` | MQTT-Broker (aktiviert MQTT Discovery) |
| `MQTT_USER`, `MQTT_PASSWORD` | – | MQTT-Anmeldung (auch `MQTT_PASSWORD_FILE`) |
| `MQTT_TLS` | `false` | TLS zum Broker |
| `MQTT_DISCOVERY_PREFIX` | `homeassistant` | Discovery-Präfix von Home Assistant |
| `MQTT_BASE_TOPIC` | `cmfwatch` | Topics: `cmfwatch/<sensor>`, `cmfwatch/state` (alles als JSON) |
| `HA_URL`, `HA_TOKEN` | – | Statt MQTT: Home-Assistant-REST-API mit langlebigem Zugriffstoken |
| `DEVICE_NAME`, `DEVICE_ID` | `CMF Watch Pro 3`, `cmf_watch` | Gerätename und ID-Präfix in Home Assistant |
| `TZ` | UTC | Zeitzone für „heute“ (z. B. `Europe/Berlin`) |
| `PUBLISH_INTERVAL` | `300` | Sekunden zwischen erneutem Senden (Tageswechsel, Reconnect) |
| `WATCH_INTERVAL` | `60` | Sekunden zwischen Prüfungen des Import-Ordners |
| `MAX_UPLOAD_MB` | `512` | Maximale Uploadgröße |

Die REST-Variante legt `sensor.cmf_watch_<name>` direkt an. Diese Zustände überleben aber keinen
Neustart von Home Assistant, bis die nächste Aktualisierung kommt (spätestens nach `PUBLISH_INTERVAL`).
MQTT ist deshalb die bessere Wahl.

## API

Alle Endpunkte außer `/healthz` brauchen `Authorization: Bearer <API_TOKEN>` (oder `?token=`).

| Methode | Pfad | |
|---|---|---|
| `POST` | `/api/upload` | Datei als Body (oder Formular-Upload): Export-ZIP der App, Gadgetbridge-DB oder -ZIP |
| `GET` | `/api/latest` | aktuelle Werte + Attribute (JSON) |
| `GET` | `/api/history?metric=heart_rate&days=7` | Verlauf: `heart_rate`, `spo2`, `stress`, `resting_hr`, `hrv`, `weight`, `sleep`, `daily` |
| `GET` | `/api/uploads` | letzte Importe |
| `POST` | `/api/publish` | sofort an Home Assistant senden |
| `GET` | `/` | Statusseite mit Upload-Formular |
| `GET` | `/healthz` | Health-Check |

## Beispiel: Dashboard-Karte

```yaml
type: entities
title: Schlaf
entities:
  - sensor.cmf_watch_pro_3_schlaf_letzte_nacht
  - sensor.cmf_watch_pro_3_tiefschlaf
  - sensor.cmf_watch_pro_3_rem_schlaf
  - sensor.cmf_watch_pro_3_eingeschlafen
  - sensor.cmf_watch_pro_3_aufgewacht
```

## Sicherheit

- Gesundheitsdaten: Den Port nur im Heimnetz freigeben. Von unterwegs per VPN (z. B. WireGuard oder
  Tailscale) oder hinter einem Reverse-Proxy mit HTTPS zugreifen.
- Die App erlaubt unverschlüsseltes HTTP, damit `http://192.168.x.x:8080` im Heimnetz funktioniert.
  Außerhalb des Heimnetzes nur HTTPS verwenden.
- Der Container läuft als unprivilegierter Benutzer (UID 1000). Gemountete Ordner müssen für diese UID
  beschreibbar sein.

## Entwicklung

```bash
pip install pytest paho-mqtt
python3 -m pytest -q          # im Repo-Verzeichnis: testet cmfexport + cmfserver
```
