"""Configuration from environment variables."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env(name: str, default: str | None = None) -> str | None:
    v = os.environ.get(name)
    if v is None or v == "":
        return default
    return v


def _secret(name: str) -> str | None:
    """Value of NAME, or contents of the file in NAME_FILE (docker secrets)."""
    path = _env(name + "_FILE")
    if path:
        return Path(path).read_text().strip()
    return _env(name)


@dataclass
class Config:
    api_token: str | None = field(default_factory=lambda: _secret("API_TOKEN"))
    port: int = field(default_factory=lambda: int(_env("PORT", "8080")))
    data_dir: Path = field(default_factory=lambda: Path(_env("DATA_DIR", "/data")))
    import_dir: Path | None = field(default_factory=lambda: Path(p) if (p := _env("IMPORT_DIR", "/import")) else None)
    watch_interval: int = field(default_factory=lambda: int(_env("WATCH_INTERVAL", "60")))
    publish_interval: int = field(default_factory=lambda: int(_env("PUBLISH_INTERVAL", "300")))
    max_upload_mb: int = field(default_factory=lambda: int(_env("MAX_UPLOAD_MB", "512")))
    device_name: str = field(default_factory=lambda: _env("DEVICE_NAME", "CMF Watch Pro 3"))
    device_id: str = field(default_factory=lambda: _env("DEVICE_ID", "cmf_watch"))
    # MQTT (Home Assistant MQTT discovery)
    mqtt_enabled: bool = field(default_factory=lambda: bool(_env("MQTT_HOST")) and _env("MQTT_ENABLED", "true").lower() in ("1", "true", "yes"))
    mqtt_host: str | None = field(default_factory=lambda: _env("MQTT_HOST"))
    mqtt_port: int = field(default_factory=lambda: int(_env("MQTT_PORT", "1883")))
    mqtt_user: str | None = field(default_factory=lambda: _env("MQTT_USER"))
    mqtt_password: str | None = field(default_factory=lambda: _secret("MQTT_PASSWORD"))
    mqtt_tls: bool = field(default_factory=lambda: _env("MQTT_TLS", "false").lower() in ("1", "true", "yes"))
    mqtt_tls_insecure: bool = field(default_factory=lambda: _env("MQTT_TLS_INSECURE", "false").lower() in ("1", "true", "yes"))
    mqtt_client_id: str | None = field(default_factory=lambda: _env("MQTT_CLIENT_ID"))
    discovery_prefix: str = field(default_factory=lambda: _env("MQTT_DISCOVERY_PREFIX", "homeassistant"))
    base_topic: str = field(default_factory=lambda: _env("MQTT_BASE_TOPIC", "cmfwatch"))
    # Home Assistant REST API (alternative to MQTT)
    ha_url: str | None = field(default_factory=lambda: (_env("HA_URL") or "").rstrip("/") or None)
    ha_token: str | None = field(default_factory=lambda: _secret("HA_TOKEN"))

    # AI analysis: any OpenAI-compatible endpoint (LiteLLM proxy, Ollama /v1, OpenAI ...)
    llm_base_url: str | None = field(default_factory=lambda: _env("LLM_BASE_URL"))
    llm_api_key: str | None = field(default_factory=lambda: _secret("LLM_API_KEY"))
    llm_model: str | None = field(default_factory=lambda: _env("LLM_MODEL"))
    llm_timeout: int = field(default_factory=lambda: int(_env("LLM_TIMEOUT", "120")))
    # Website accounts: "first" = only the first account may register itself, "open" = everyone
    signup: str = field(default_factory=lambda: (_env("SIGNUP", "first") or "first").lower())
    # Session cookie "Secure" flag: auto = when the reverse proxy says https
    cookie_secure: str = field(default_factory=lambda: (_env("COOKIE_SECURE", "auto") or "auto").lower())
    # Public address (https://cmf.example.de) - used for the one-tap app links
    public_url: str | None = field(default_factory=lambda: (_env("PUBLIC_URL") or "").rstrip("/") or None)

    @property
    def db_path(self) -> Path:
        return self.data_dir / "cmfwatch.db"


# Settings the admin can change in the website (Konto -> Server). Values saved there override the
# environment variables above; the environment only provides the defaults.
# key -> (type, secret)
RUNTIME_SETTINGS: dict[str, tuple[type, bool]] = {
    "mqtt_enabled": (bool, False), "mqtt_host": (str, False), "mqtt_port": (int, False),
    "mqtt_user": (str, False), "mqtt_password": (str, True), "mqtt_tls": (bool, False),
    "mqtt_tls_insecure": (bool, False), "mqtt_client_id": (str, False),
    "discovery_prefix": (str, False), "base_topic": (str, False), "device_name": (str, False),
    "ha_url": (str, False), "ha_token": (str, True),
    "llm_base_url": (str, False), "llm_api_key": (str, True), "llm_model": (str, False),
    "signup": (str, False), "public_url": (str, False), "publish_interval": (int, False),
}


def coerce(key: str, value):
    """Validate one admin setting; raises ValueError with a German message."""
    typ, _ = RUNTIME_SETTINGS[key]
    if value is None or value == "":
        if typ is bool:
            return False
        if typ is int:
            raise ValueError(f"{key}: Zahl erwartet")
        return None
    if typ is bool:
        return value if isinstance(value, bool) else str(value).lower() in ("1", "true", "yes", "on")
    if typ is int:
        v = int(value)
        if key == "mqtt_port" and not 0 < v < 65536:
            raise ValueError("MQTT-Port muss zwischen 1 und 65535 liegen")
        if key == "publish_interval" and v < 30:
            raise ValueError("Sende-Intervall: mindestens 30 Sekunden")
        return v
    v = str(value).strip()
    if key == "signup" and v not in ("first", "open"):
        raise ValueError("Registrierung: first oder open")
    if key in ("ha_url", "llm_base_url", "public_url"):
        if not v.startswith(("http://", "https://")):
            raise ValueError(f"{key}: muss mit http:// oder https:// beginnen")
        v = v.rstrip("/")
    if key in ("discovery_prefix", "base_topic") and (not v or any(c in v for c in "#+ ")):
        raise ValueError(f"{key}: keine Leerzeichen, # oder +")
    if key == "mqtt_host" and ("/" in v or " " in v):
        raise ValueError("MQTT-Host: nur Hostname oder IP, ohne mqtt:// und Pfad")
    return v[:500]
