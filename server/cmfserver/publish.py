"""Push the current values to Home Assistant (MQTT discovery and/or REST API)."""
from __future__ import annotations

import json
import logging
import re
import threading
import urllib.request
from dataclasses import dataclass

from . import __version__
from .config import Config

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Sensor:
    key: str
    name: str
    unit: str | None = None
    device_class: str | None = None
    state_class: str | None = None
    icon: str | None = None


SENSORS = [
    Sensor("sleep_duration", "Schlaf letzte Nacht", "min", "duration", "measurement", "mdi:sleep"),
    Sensor("sleep_deep", "Tiefschlaf", "min", "duration", "measurement", "mdi:power-sleep"),
    Sensor("sleep_light", "Leichtschlaf", "min", "duration", "measurement", "mdi:weather-night"),
    Sensor("sleep_rem", "REM-Schlaf", "min", "duration", "measurement", "mdi:eye-outline"),
    Sensor("sleep_awake", "Wachphasen", "min", "duration", "measurement", "mdi:eye"),
    Sensor("sleep_in_bed", "Zeit im Bett", "min", "duration", "measurement", "mdi:bed"),
    Sensor("sleep_efficiency", "Schlafeffizienz", "%", None, "measurement", "mdi:percent"),
    Sensor("nap_today", "Nickerchen heute", "min", "duration", "measurement", "mdi:sofa"),
    Sensor("sleep_avg_7d", "Schlaf Ø 7 Tage", "min", "duration", "measurement", "mdi:chart-line"),
    Sensor("sleep_start", "Eingeschlafen", None, "timestamp", None, "mdi:bed-clock"),
    Sensor("sleep_end", "Aufgewacht", None, "timestamp", None, "mdi:alarm"),
    Sensor("steps_today", "Schritte heute", "steps", None, "total_increasing", "mdi:walk"),
    Sensor("steps_yesterday", "Schritte gestern", "steps", None, None, "mdi:walk"),
    Sensor("distance_today", "Distanz heute", "m", "distance", "total_increasing", "mdi:map-marker-distance"),
    Sensor("calories_today", "Kalorien heute", "kcal", None, "total_increasing", "mdi:fire"),
    Sensor("heart_rate", "Puls", "bpm", None, "measurement", "mdi:heart-pulse"),
    Sensor("hr_avg_today", "Puls Ø heute", "bpm", None, "measurement", "mdi:heart"),
    Sensor("hr_min_today", "Puls min heute", "bpm", None, "measurement", "mdi:heart-minus"),
    Sensor("hr_max_today", "Puls max heute", "bpm", None, "measurement", "mdi:heart-plus"),
    Sensor("resting_heart_rate", "Ruhepuls", "bpm", None, "measurement", "mdi:heart-outline"),
    Sensor("hrv", "HRV", "ms", None, "measurement", "mdi:heart-flash"),
    Sensor("spo2", "Blutsauerstoff", "%", None, "measurement", "mdi:water-percent"),
    Sensor("stress", "Stress", None, None, "measurement", "mdi:head-alert"),
    Sensor("respiratory_rate", "Atemfrequenz", "/min", None, "measurement", "mdi:lungs"),
    Sensor("weight", "Gewicht", "kg", "weight", "measurement", "mdi:scale-bathroom"),
    Sensor("watch_battery", "Akku Uhr", "%", "battery", "measurement", None),
    Sensor("last_workout", "Letztes Training", None, None, None, "mdi:run"),
    Sensor("last_workout_distance", "Letztes Training Distanz", "km", "distance", None, "mdi:map-marker-distance"),
    Sensor("last_workout_duration", "Letztes Training Dauer", "min", "duration", None, "mdi:timer-outline"),
    Sensor("last_workout_avg_hr", "Letztes Training Ø Puls", "bpm", None, None, "mdi:heart-pulse"),
    Sensor("last_upload", "Letzte Synchronisierung", None, "timestamp", None, "mdi:cloud-upload"),
    Sensor("steps_goal", "Schrittziel erreicht", "%", None, "measurement", "mdi:flag-checkered"),
    Sensor("recovery", "Erholung", "%", None, "measurement", "mdi:battery-heart-variant"),
    Sensor("health_status", "Gesundheitsstatus", None, None, None, "mdi:shield-alert-outline"),
    Sensor("inhaler_today", "Notfallspray heute", "Hübe", None, "measurement", "mdi:spray"),
    Sensor("asthma_control", "Asthma-Kontrolle", None, None, None, "mdi:lungs"),
    Sensor("peak_flow", "Peak-Flow", "L/min", None, "measurement", "mdi:gauge"),
    Sensor("air_quality", "Luftqualität (EAQI)", None, "aqi", "measurement", None),
    Sensor("pollen", "Pollenbelastung", None, None, None, "mdi:flower-pollen"),
]


@dataclass(frozen=True)
class Target:
    """Where one user's sensors go: the first account keeps the classic ids (cmf_watch, cmfwatch/...)."""
    device_id: str
    name: str
    base: str


def target(cfg: Config, user: dict | None) -> Target:
    if not user or user["id"] == 1:
        return Target(cfg.device_id, cfg.device_name, cfg.base_topic)
    slug = re.sub(r"[^a-z0-9]+", "_", user["username"].lower()).strip("_") or str(user["id"])
    return Target(f"{cfg.device_id}_{slug}", f"{cfg.device_name} ({user['username']})", f"{cfg.base_topic}/{slug}")


class MqttPublisher:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.connected = threading.Event()
        self.announced: set[str] = set()
        self.last: dict[str, tuple[dict, Target]] = {}
        self.error: str | None = None
        self.since: float | None = None
        self.messages = 0
        self.client = make_client(cfg)
        self.avail_topic = f"{cfg.base_topic}/status"
        self.client.will_set(self.avail_topic, "offline", retain=True)
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message
        self.client.reconnect_delay_set(1, 60)
        try:
            self.client.connect_async(cfg.mqtt_host, cfg.mqtt_port, keepalive=60)
        except Exception as e:  # noqa: BLE001 - e.g. bad host name
            self.error = str(e)
        self.client.loop_start()

    def status(self) -> dict:
        return {"type": "mqtt", "host": self.cfg.mqtt_host, "port": self.cfg.mqtt_port,
                "connected": self.connected.is_set(), "error": None if self.connected.is_set() else self.error,
                "since": self.since, "messages": self.messages, "devices": sorted(self.last)}

    def _on_disconnect(self, client, userdata, flags=None, reason_code=None, properties=None):
        self.connected.clear()
        if reason_code is not None and getattr(reason_code, "is_failure", False):
            self.error = f"Verbindung getrennt: {reason_code}"
        elif not self.error:
            self.error = "Verbindung getrennt"

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        if reason_code.is_failure:
            log.error("MQTT-Verbindung abgelehnt: %s", reason_code)
            self.error = f"Broker lehnt ab: {reason_code}"
            return
        import time as _t
        self.error, self.since = None, _t.time()
        log.info("MQTT verbunden mit %s:%s", self.cfg.mqtt_host, self.cfg.mqtt_port)
        self.announced.clear()
        client.publish(self.avail_topic, "online", retain=True)
        client.subscribe(f"{self.cfg.discovery_prefix}/status")  # re-announce when Home Assistant restarts
        self.connected.set()
        for payload, t in list(self.last.values()):
            self._publish(payload, t)

    def _on_message(self, client, userdata, msg):
        if msg.payload == b"online":
            self.announced.clear()
            for payload, t in list(self.last.values()):
                self._publish(payload, t)

    def _device(self, t: Target) -> dict:
        return {"identifiers": [t.device_id], "name": t.name,
                "manufacturer": "Nothing", "model": "CMF Watch", "sw_version": f"cmfserver {__version__}"}

    def _announce(self, t: Target) -> None:
        for s in SENSORS:
            cfg = {
                "name": s.name,
                "unique_id": f"{t.device_id}_{s.key}",
                "state_topic": f"{t.base}/{s.key}",
                "json_attributes_topic": f"{t.base}/{s.key}/attributes",
                "availability_topic": self.avail_topic,
                "device": self._device(t),
            }
            for k, v in (("unit_of_measurement", s.unit), ("device_class", s.device_class),
                         ("state_class", s.state_class), ("icon", s.icon)):
                if v:
                    cfg[k] = v
            self.client.publish(f"{self.cfg.discovery_prefix}/sensor/{t.device_id}/{s.key}/config", json.dumps(cfg), retain=True)
        self.announced.add(t.device_id)

    def publish(self, payload: dict, user: dict | None = None) -> bool:
        t = target(self.cfg, user)
        self.last[t.device_id] = (payload, t)
        return self._publish(payload, t)

    def _publish(self, payload: dict, t: Target) -> bool:
        if not self.connected.is_set():
            return False
        if t.device_id not in self.announced:
            self._announce(t)
        state, attrs = payload["state"], payload["attributes"]
        for s in SENSORS:
            if s.key in state:
                self.client.publish(f"{t.base}/{s.key}", str(state[s.key]), retain=True)
                self.client.publish(f"{t.base}/{s.key}/attributes", json.dumps(attrs.get(s.key, {}), default=str), retain=True)
                self.messages += 2
        self.client.publish(f"{t.base}/state", json.dumps(state, default=str), retain=True)
        self.messages += 1
        return True

    def close(self) -> None:
        try:
            if self.connected.is_set():
                self.client.publish(self.avail_topic, "offline", retain=True).wait_for_publish(2)
            self.client.disconnect()
        except Exception:  # noqa: BLE001
            pass
        self.client.loop_stop()


def make_client(cfg: Config, suffix: str = ""):
    import paho.mqtt.client as mqtt

    cid = (cfg.mqtt_client_id or f"cmfserver-{cfg.device_id}") + suffix
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=cid)
    if cfg.mqtt_user:
        client.username_pw_set(cfg.mqtt_user, cfg.mqtt_password or None)
    if cfg.mqtt_tls:
        import ssl
        client.tls_set(cert_reqs=ssl.CERT_NONE if cfg.mqtt_tls_insecure else ssl.CERT_REQUIRED)
        if cfg.mqtt_tls_insecure:
            client.tls_insecure_set(True)
    return client


def test_mqtt(cfg: Config, timeout: float = 6.0) -> dict:
    """Connect once with these settings and report what the broker says."""
    import time as _t

    if not cfg.mqtt_host:
        return {"ok": False, "error": "Kein Host eingetragen"}
    result: dict = {}
    done = threading.Event()
    client = make_client(cfg, suffix="-test")

    def on_connect(c, u, flags, rc, props=None):
        result.update(ok=not rc.is_failure, error=None if not rc.is_failure else f"Broker lehnt ab: {rc}")
        done.set()

    client.on_connect = on_connect
    t0 = _t.time()
    try:
        client.connect(cfg.mqtt_host, cfg.mqtt_port, keepalive=10)
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": f"Keine Verbindung zu {cfg.mqtt_host}:{cfg.mqtt_port}: {e}"}
    client.loop_start()
    if not done.wait(timeout):
        result = {"ok": False, "error": "Keine Antwort vom Broker (Zeitüberschreitung)"}
    client.loop_stop()
    try:
        client.disconnect()
    except Exception:  # noqa: BLE001
        pass
    result["ms"] = round((_t.time() - t0) * 1000)
    return result


class RestPublisher:
    """Sets sensor.<device_id>_<key> through the Home Assistant REST API."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.error: str | None = None
        self.ok_at: float | None = None

    def status(self) -> dict:
        return {"type": "rest", "host": self.cfg.ha_url, "connected": self.ok_at is not None and not self.error,
                "error": self.error, "since": self.ok_at}

    def publish(self, payload: dict, user: dict | None = None) -> bool:
        import time as _t
        ok = True
        t = target(self.cfg, user)
        state, attrs = payload["state"], payload["attributes"]
        for s in SENSORS:
            if s.key not in state:
                continue
            a = {"friendly_name": f"{t.name} {s.name}", **attrs.get(s.key, {})}
            for k, v in (("unit_of_measurement", s.unit), ("device_class", s.device_class),
                         ("state_class", s.state_class), ("icon", s.icon)):
                if v:
                    a[k] = v
            body = json.dumps({"state": state[s.key], "attributes": a}, default=str).encode()
            req = urllib.request.Request(
                f"{self.cfg.ha_url}/api/states/sensor.{t.device_id}_{s.key}", data=body, method="POST",
                headers={"Authorization": f"Bearer {self.cfg.ha_token}", "Content-Type": "application/json"})
            try:
                urllib.request.urlopen(req, timeout=10).close()
            except Exception as e:  # noqa: BLE001
                log.warning("HA REST %s fehlgeschlagen: %s", s.key, e)
                self.error = str(e)
                ok = False
                break
        if ok:
            self.error, self.ok_at = None, _t.time()
        return ok

    def close(self) -> None:
        pass


def make_publishers(cfg: Config) -> list:
    pubs = []
    if cfg.mqtt_enabled and cfg.mqtt_host:
        pubs.append(MqttPublisher(cfg))
    if cfg.ha_url and cfg.ha_token:
        pubs.append(RestPublisher(cfg))
    if not pubs:
        log.warning("Kein MQTT-Broker und keine HA-REST-Verbindung eingerichtet – der Admin kann das auf der Website unter Konto → Server eintragen.")
    return pubs
