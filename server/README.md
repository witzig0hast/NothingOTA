# cmfserver – CMF Watch → Home Assistant

Kleiner Docker-Container, der die Daten deiner CMF Watch Pro 3 annimmt, in einer SQLite-Datenbank
zusammenführt und an **Home Assistant** weitergibt. Bei MQTT legt Home Assistant über MQTT Discovery
automatisch ein Gerät „CMF Watch Pro 3“ mit 31 Sensoren an. Alternativ geht es über die REST-API.

```
CMF Watch ──BT──> CMF-App ──> Health Connect ─┐
                                              ├─> CMF-Export-App ──HTTP──> cmfserver ──MQTT──> Home Assistant
CMF Watch ──BT──> Gadgetbridge ── Auto-Export ┘                              ▲
                                  └── Syncthing/Nextcloud ──> /import ───────┘
```

## Sensoren in Home Assistant

| Sensor | Entity (Beispiel) | Einheit |
|---|---|---|
| Schlaf letzte Nacht, Tief-, Leicht-, REM-Schlaf, Wachphasen, Zeit im Bett (Nickerchen zählen nicht) | `sensor.cmf_watch_pro_3_schlaf_letzte_nacht` … | min |
| Nickerchen heute, Schlaf Ø 7 Tage | `sensor.cmf_watch_pro_3_nickerchen_heute` | min |
| Schlafeffizienz | `sensor.cmf_watch_pro_3_schlafeffizienz` | % |
| Eingeschlafen / Aufgewacht | `sensor.cmf_watch_pro_3_eingeschlafen` | Zeitstempel |
| Schritte heute / gestern, Distanz heute, Kalorien heute | `sensor.cmf_watch_pro_3_schritte_heute` | steps, m, kcal |
| Puls (letzter Wert), Puls Ø/min/max heute, Ruhepuls, HRV | `sensor.cmf_watch_pro_3_puls` | bpm, ms |
| Blutsauerstoff, Stress, Atemfrequenz, Gewicht | `sensor.cmf_watch_pro_3_blutsauerstoff` | %, –, /min, kg |
| Akku Uhr (nur mit Gadgetbridge; Werte älter als 2 Tage werden nicht angezeigt) | `sensor.cmf_watch_pro_3_akku_uhr` | % |
| Letztes Training (+ Distanz, Dauer, Ø Puls), Letzte Synchronisierung | `sensor.cmf_watch_pro_3_letztes_training` | km, min, bpm |

Jeder Sensor hat Attribute, z. B. `measured_at` beim Puls oder alle Schlafphasen beim Schlafsensor.
Home Assistant speichert den Verlauf selbst, so bekommst du Langzeit-Statistiken und Graphen.
Den Verlauf vor der ersten Synchronisierung kann Home Assistant nicht nachträglich übernehmen.
Er liegt aber im Server und ist über `/api/history` abrufbar.

## Installation (Home Assistant im Docker-Container)

Du brauchst nur Docker auf dem Rechner, auf dem auch Home Assistant läuft (oder einem anderen im Heimnetz).
Das fertige Image `ghcr.io/witzig0hast/cmfserver:latest` gibt es für PC/NAS (amd64) und Raspberry Pi (arm64/armv7).

1. **Ordner holen:** Ordner `server/stack/` aus diesem Repo auf den Docker-Rechner kopieren, z. B.
   ```bash
   git clone https://github.com/witzig0hast/NothingOTA.git
   cd NothingOTA/server/stack
   ```
2. **Token setzen:** In `docker-compose.yml` `API_TOKEN` auf ein eigenes Passwort setzen
   (z. B. Ausgabe von `openssl rand -hex 24`).
3. **Starten:**
   ```bash
   docker compose up -d
   ```
   Das startet **Mosquitto** (MQTT-Broker, Port 1883) und **cmfserver** (Port 8080).
   Hast du schon einen MQTT-Broker, lösche den `mosquitto`-Block und trage bei `MQTT_HOST` dessen IP ein
   (plus `MQTT_USER`/`MQTT_PASSWORD`).
4. **Home Assistant mit MQTT verbinden:** *Einstellungen → Geräte & Dienste → Integration hinzufügen →
   MQTT* → Broker = IP des Docker-Rechners (z. B. `192.168.1.10`), Port `1883`, Benutzer leer lassen.
   Läuft Home Assistant mit `network_mode: host` auf demselben Rechner, geht auch `127.0.0.1`.
5. **Prüfen:** `http://<IP>:8080/?token=<API_TOKEN>` im Browser öffnen. Dort steht „Weiterleitung an: Mqtt“.
6. **Handy einrichten:**
   - **CMF Export:** Auf der Karte „Home Assistant“ Adresse `http://<IP>:8080` und Token eintragen,
     „Testen“ antippen, „Automatisch senden“ einschalten.
   - **CMF Track:** Unter *Profil → Home Assistant* dieselben Daten eintragen.

Nach dem ersten Senden erscheint unter *Einstellungen → Geräte & Dienste → MQTT* das Gerät
**CMF Watch Pro 3** mit allen Sensoren.

**Update:** `docker compose pull && docker compose up -d`

**MQTT mit Passwort (optional):** In `mosquitto/mosquitto.conf` `allow_anonymous false` und
`password_file /mosquitto/config/passwd` setzen, dann
`docker compose exec mosquitto mosquitto_passwd -c /mosquitto/config/passwd cmf`.
Damit das funktioniert, die Konfiguration ohne `:ro` einbinden. In cmfserver `MQTT_USER`/`MQTT_PASSWORD` und
in Home Assistant dieselben Zugangsdaten eintragen.

### Selbst bauen statt fertigem Image

```bash
docker compose -f server/docker-compose.yml up -d --build   # im Repo-Verzeichnis, nutzt server/Dockerfile
```

## Wege, wie Daten zum Server kommen

| Weg | Wie |
|---|---|
| **App, automatisch** | CMF Export liest alle 15 min (einstellbar) den Uhr-Akku per Bluetooth und die letzten 2 Tage aus Health Connect und lädt sie hoch. Hast du eine Gadgetbridge-Datei gewählt, wird sie mitgeschickt, aber nur wenn sie sich geändert hat. |
| **CMF Track** | Nach jedem Training automatisch: Sportart, Distanz, Dauer, Puls, Höhenmeter, Kalorien |
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
