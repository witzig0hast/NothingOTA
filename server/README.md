# CMF Health – Website, KI-Analyse und Home Assistant für die CMF Watch

`cmfserver` ist ein Docker-Container mit diesen Teilen:
- **Website zum Anmelden** auf deiner eigenen Subdomain, mit Übersicht, Schlaf (Hypnogramm), Herz & Atmung,
  Asthma-Tagebuch, Aktivität mit Karten, KI-Coach, druckbarem Arztbericht und Konto. Sie lässt sich wie eine
  App auf den Startbildschirm legen (PWA).
- **Asthma-Tagebuch:** Notfallspray-Hübe, Dauertherapie, Beschwerden, Peak-Flow (mit Zonen grün, gelb, rot) und
  Auslöser. Daraus entsteht eine Einschätzung der Asthma-Kontrolle nach den GINA-Fragen.
- **Luft & Pollen:** Pollenflug (Birke, Erle, Gräser, Beifuß, Ambrosia, Olive), Luftqualitätsindex und Wetter für
  deinen Ort (Open-Meteo, kostenlos, ohne Key). Er erkennt auch, ob deine Beschwerden zu bestimmten Pollen passen.
- **Ziele:** Schritte und Schlaf pro Konto
- **Gesundheits-Hinweise:** Er erkennt Auffälligkeiten bei Atmung (nächtliche Sauerstoffabfälle,
  Atemfrequenz), Ruhepuls, HRV, Stress und Schlaf, immer im Vergleich mit deinen eigenen Werten
- **KI-Analyse** über deinen LiteLLM-, Ollama- oder OpenAI-kompatiblen Endpunkt: Wochenbericht und „Frag deine Daten“
- **Home Assistant:** Die Werte gehen per MQTT Discovery dorthin, ein Gerät pro Konto. CMF Health verbindet
  sich als **Client** mit deinem vorhandenen Broker. Den trägt der Admin auf der Website ein (Seite **Server**).

Die Daten kommen aus den Apps **CMF Export** (Schlaf, Puls, SpO₂, Schritte … alle 15 min) und **CMF Track**
(Trainings mit GPS und Puls). Jedes Konto hat ein eigenes kurzes App-Token (`cmf-xxxxxxxxxxxx`), das auf der
Website unter **Konto** steht. Dort gibt es auch Knöpfe, die die Apps mit einem Tipp verbinden.

```
Uhr ─BT─> CMF-App ─> Health Connect ─> CMF Export ─┐
Handy-GPS + Puls der Uhr ─────────> CMF Track ─────┼─HTTPS─> cmfserver ──> Website (health.example.de)
Gadgetbridge (optional) ─> /import ────────────────┘            └──MQTT──> Home Assistant
```

## Installation auf einer Subdomain

1. **DNS:** Einen Eintrag für die Subdomain (z. B. `health.example.de`) auf deinen Server zeigen lassen.
2. **Stack holen:**
   ```bash
   git clone https://github.com/witzig0hast/NothingOTA.git
   cd NothingOTA/server/stack
   ```
3. **`docker-compose.yml` anpassen:**
   - `PUBLIC_URL: "https://health.example.de"`
   - MQTT und KI musst du hier nicht eintragen, das geht später auf der Website
4. **Starten:**
   - **Ohne eigenen Reverse-Proxy:** In `Caddyfile` die Subdomain eintragen, dann
     `docker compose --profile https up -d`. Caddy holt das HTTPS-Zertifikat selbst; die Ports 80 und 443
     müssen auf den Rechner zeigen.
   - **Mit eigenem Proxy** (Nginx Proxy Manager, Traefik, Caddy …): `docker compose up -d` und die Subdomain auf
     `http://<server>:8080` weiterleiten. Der Proxy muss `X-Forwarded-Proto: https` setzen (machen alle
     gängigen); dann bekommen die Login-Cookies automatisch das `Secure`-Flag.
5. **Konto anlegen:** `https://health.example.de` öffnen und registrieren. Das **erste Konto wird Admin**,
   danach ist die Registrierung geschlossen. Als Admin legst du unter **Server → Benutzer** weitere Konten an,
   setzt Passwörter zurück oder öffnest die Registrierung.
6. **Handy verbinden:** Die Website am Handy öffnen, **Konto → „CMF Export verbinden“** und
   **„CMF Track verbinden“** antippen. Adresse und Token werden automatisch eingetragen.
7. **Home Assistant:** Als Admin auf der Website **Server → MQTT-Broker** öffnen:
   - Host: die IP von Home Assistant (beim Mosquitto-Add-on) oder deines Brokers, Port 1883
   - Benutzer und Passwort: z. B. einen eigenen HA-Benutzer für MQTT
   - **Verbindung testen**, dann **Speichern**

   Die Verbindung wird sofort aufgebaut. Der Status zeigt an, ob sie steht. Kurz darauf erscheint in Home Assistant das Gerät
   „CMF Watch Pro 3“. Ohne Broker geht auch die **Home-Assistant-API** (Adresse und Langzeit-Token). Hast du noch
   gar keinen Broker, startet `docker compose --profile broker up -d` einen; als Host trägst du dann `mosquitto` ein.
8. **Ort festlegen** (Konto → Ziele & Ort) für Pollen und Luftqualität.

**Update:** `docker compose pull && docker compose up -d`. Eine Datenbank aus der Version 1.x wird automatisch
dem ersten Konto zugeordnet.

### KI-Analyse (LiteLLM)

Den Endpunkt trägt der Admin auf der Website unter **Server → KI** ein, mit **Testen**-Knopf. Jeder
OpenAI-kompatible Endpunkt geht, zum Beispiel:
- **LiteLLM-Proxy:** Endpunkt `http://litellm:4000`, Modell = Name in LiteLLM, API-Key = virtual/master key
- **Ollama direkt:** Endpunkt `http://ollama:11434/v1`, Modell `llama3.1:8b`, ohne Key

Gesendet wird nur eine kompakte Zusammenfassung: Tageswerte, Nächte, Sauerstoff- und Atemwerte pro Nacht,
erkannte Hinweise, Trainings, das Asthma-Tagebuch (14 Tage) und die Pollenwerte von heute. Rohdaten wie einzelne Pulsmessungen gehen nicht raus. Das hält
Anfragen klein, schnell und datensparsam. Ohne KI funktionieren alle regelbasierten Hinweise trotzdem.

> **Hinweis:** Die Analysen beruhen auf Uhren-Sensoren und ersetzen keine ärztliche Untersuchung. Bei Atemnot,
> Brustschmerzen oder anhaltenden Beschwerden ärztlich abklären lassen.

### Was die Uhr messen kann

Die CMF Watch Pro 3 liefert über die CMF-App: Schlaf mit Phasen, Puls, SpO₂, Schritte, Kalorien und Trainings.
Ohne Gadgetbridge kommen Stress, HRV und Atemfrequenz nicht an. Für Atmung und Asthma-Verlauf in der CMF-App
diese Messungen einschalten:
- **Sauerstoff (SpO₂):** automatisch beziehungsweise im Schlaf messen
- **Herzfrequenz:** kontinuierlich messen
- **Health Connect:** alle Datentypen freigeben

## Sicherheit

- **Passwörter:** mit scrypt gespeichert. Login-Versuche sind begrenzt (10 Fehlversuche in 10 Minuten).
- **Sitzung:** Cookie mit `HttpOnly`, `SameSite=Lax` und hinter HTTPS `Secure`, dazu CSRF-Schutz und strenge
  Content-Security-Policy. Die Website lädt keine fremden Skripte.
- **App-Token:** dürfen nur Daten senden und lesen; Kontoeinstellungen gehen nur nach Login auf der Website.
  Ein neues Token macht das alte sofort ungültig.
- **Daten:** jedes Konto sieht nur seine eigenen. **Konto → Alle Daten herunterladen** und **Konto löschen**
  sind eingebaut (DSGVO).
- **Karten:** OpenFreeMap (Vektorkarten auf Basis von OpenStreetMap), ohne Konto und ohne Tracking.

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
| Schrittziel erreicht, Erholung | `sensor.cmf_watch_pro_3_schrittziel_erreicht` | % |
| Gesundheitsstatus (`OK`, `Hinweis`, `Warnung`; offene Hinweise als Attribut) | `sensor.cmf_watch_pro_3_gesundheitsstatus` | – |
| Notfallspray heute, Asthma-Kontrolle, Peak-Flow | `sensor.cmf_watch_pro_3_notfallspray_heute` | Hübe, –, L/min |
| Luftqualität (EAQI), Pollenbelastung (`keine` … `hoch`, Werte pro Pollenart als Attribute) | `sensor.cmf_watch_pro_3_pollenbelastung` | – |

Beispiel-Automation: Steht `Gesundheitsstatus` auf `Warnung` oder die Pollenbelastung auf `hoch`, kommt eine
Push-Nachricht aufs Handy.

Jeder Sensor hat Attribute, z. B. `measured_at` beim Puls oder alle Schlafphasen beim Schlafsensor.
Home Assistant speichert den Verlauf selbst, so bekommst du Langzeit-Statistiken und Graphen.
Den Verlauf vor der ersten Synchronisierung kann Home Assistant nicht nachträglich übernehmen.
Er liegt aber im Server und ist über `/api/history` abrufbar.

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

## Konfiguration

Fast alles stellst du als Admin auf der Website unter **Server** ein: MQTT-Broker (Host, Port, Benutzer, Passwort,
TLS, Discovery-Präfix, Topic, Gerätename), Home-Assistant-API, KI-Endpunkt, Registrierung, öffentliche Adresse und
das Sende-Intervall. Diese Werte liegen in der Datenbank und haben Vorrang vor den Umgebungsvariablen. Die
Variablen unten sind nur Vorgaben, etwa für automatisierte Installationen. Passwörter und Keys
zeigt die Website nie an, nur ob einer gespeichert ist.

### Umgebungsvariablen

| Variable | Standard | Bedeutung |
|---|---|---|
| `API_TOKEN` | – | Nur für Installationen der Version 1.x: altes Token gilt weiter für das erste Konto |
| `MQTT_HOST`, `MQTT_PORT` | –, `1883` | MQTT-Broker (aktiviert MQTT Discovery) |
| `MQTT_USER`, `MQTT_PASSWORD` | – | MQTT-Anmeldung (auch `MQTT_PASSWORD_FILE`) |
| `MQTT_TLS`, `MQTT_TLS_INSECURE` | `false` | TLS zum Broker, Zertifikat nicht prüfen (selbstsigniert) |
| `MQTT_ENABLED`, `MQTT_CLIENT_ID` | `true` (wenn Host gesetzt), `cmfserver-cmf_watch` | MQTT an/aus, Client-ID |
| `MQTT_DISCOVERY_PREFIX` | `homeassistant` | Discovery-Präfix von Home Assistant |
| `MQTT_BASE_TOPIC` | `cmfwatch` | Topics: `cmfwatch/<sensor>`, `cmfwatch/state` (alles als JSON) |
| `HA_URL`, `HA_TOKEN` | – | Statt MQTT: Home-Assistant-REST-API mit langlebigem Zugriffstoken |
| `DEVICE_NAME`, `DEVICE_ID` | `CMF Watch Pro 3`, `cmf_watch` | Gerätename und ID-Präfix in Home Assistant |
| `TZ` | UTC | Zeitzone für „heute“ (z. B. `Europe/Berlin`) |
| `PUBLISH_INTERVAL` | `300` | Sekunden zwischen erneutem Senden (Tageswechsel, Reconnect) |
| `WATCH_INTERVAL` | `60` | Sekunden zwischen Prüfungen des Import-Ordners |
| `MAX_UPLOAD_MB` | `512` | Maximale Uploadgröße |
| `PUBLIC_URL` | – | Öffentliche Adresse (`https://health.example.de`), für die Verbindungs-Links der Apps |
| `SIGNUP` | `first` | `first` = nur das erste Konto registriert sich selbst, `open` = jeder |
| `COOKIE_SECURE` | `auto` | `auto` (nach `X-Forwarded-Proto`), `true` oder `false` |
| `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` | – | KI-Endpunkt (LiteLLM/Ollama/OpenAI-kompatibel), Key auch als `LLM_API_KEY_FILE` |
| `LLM_TIMEOUT` | `120` | Sekunden für eine KI-Antwort |

Die REST-Variante legt `sensor.cmf_watch_<name>` direkt an. Diese Zustände überleben aber keinen
Neustart von Home Assistant, bis die nächste Aktualisierung kommt (spätestens nach `PUBLISH_INTERVAL`).
MQTT ist deshalb die bessere Wahl.

## API

Website: Anmeldung per Cookie. Apps und Skripte: `Authorization: Bearer <App-Token>`.

| Methode | Pfad | |
|---|---|---|
| `POST` | `/api/upload` | Datei als Body: Export-ZIP der Apps, Gadgetbridge-DB oder -ZIP |
| `GET` | `/api/latest` | aktuelle Werte (wie in Home Assistant) |
| `GET` | `/api/dashboard` | alles für die Übersicht inkl. Hinweisen und Erholung |
| `GET` | `/api/sleep?days=30` | Nächte und Nickerchen mit Phasen und SpO₂ |
| `GET` | `/api/series?metric=heart_rate&days=7` | Zeitreihe: `heart_rate`, `spo2`, `stress`, `hrv`, `respiratory_rate`, `resting_hr`, `battery`, `weight` |
| `GET` | `/api/daily?days=90` | Tageswerte |
| `GET` | `/api/workouts`, `/api/workouts/<start_ts>` | Trainings, einzeln mit GPS-Strecke und Puls |
| `GET` | `/api/insights` | regelbasierte Hinweise |
| `POST` | `/api/ai/report`, `/api/ai/chat` | KI-Bericht / Frage stellen |
| `GET`/`POST` | `/api/diary` | Asthma-Tagebuch lesen / eintragen: `{"kind":"reliever","value":2}` (`reliever`, `controller`, `symptom`, `peakflow`, `trigger`, `note`) oder `{"entries":[…]}` |
| `DELETE` | `/api/diary/<id>` | Eintrag löschen |
| `GET` | `/api/environment` | Pollen, Luftqualität und Wetter für den eigenen Ort |
| `GET` | `/api/report?days=28` | Daten für den Arztbericht |
| `GET`/`POST` | `/api/admin/settings` | Servereinstellungen (nur Admin, nur mit Login) |
| `POST` | `/api/admin/mqtt/test`, `/api/admin/ai/test` | Verbindung testen (nur Admin) |
| `GET` | `/api/export` | alle eigenen Daten als JSON |
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

## Entwicklung

```bash
pip install pytest paho-mqtt
python3 -m pytest -q          # im Repo-Verzeichnis: testet cmfexport + cmfserver
```
