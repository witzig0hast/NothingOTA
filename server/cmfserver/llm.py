"""AI analysis through an OpenAI-compatible endpoint (LiteLLM proxy, Ollama, OpenAI ...).

Only a compact summary (daily values, nightly aggregates, rule-based findings) is sent - never raw
samples - which keeps requests small and fast.
"""
from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request

from .config import Config

log = logging.getLogger(__name__)

SYSTEM = (
    "Du bist ein vorsichtiger, freundlicher Gesundheits-Coach. Du bekommst zusammengefasste Messwerte einer "
    "Smartwatch (CMF Watch Pro 3): Schlaf, Puls, Ruhepuls, Sauerstoffsättigung (SpO2), Atemfrequenz, Stress, "
    "Schritte und Trainings sowie bereits erkannte Auffälligkeiten. Antworte auf Deutsch, konkret und kurz. "
    "Achte besonders auf Atmung (z. B. Asthma, nächtliche Sauerstoffabfälle, erhöhte Atemfrequenz), Erholung "
    "und Schlaf. Stelle keine Diagnosen, nenne Unsicherheiten (Uhren-Sensoren sind ungenauer als medizinische "
    "Geräte) und rate bei Warnzeichen klar zu ärztlicher Abklärung. Erfinde keine Werte, die nicht in den Daten stehen."
)

REPORT_PROMPT = (
    "Erstelle einen Wochenbericht in Markdown mit diesen Abschnitten:\n"
    "## Kurzfazit (2-3 Sätze)\n## Atmung & Sauerstoff\n## Schlaf & Erholung\n## Herz & Stress\n## Bewegung\n"
    "## 3 konkrete Tipps für die nächsten Tage\n"
    "Nutze Zahlen aus den Daten. Wenn Daten fehlen, sag das kurz und wie man sie bekommt.\n\nDaten (JSON):\n"
)


class LLMError(RuntimeError):
    pass


class LLM:
    def __init__(self, cfg: Config):
        self.cfg = cfg

    @property
    def enabled(self) -> bool:
        return bool(self.cfg.llm_base_url and self.cfg.llm_model)

    def _url(self) -> str:
        base = self.cfg.llm_base_url.rstrip("/")
        return base + ("/chat/completions" if base.endswith("/v1") else "/v1/chat/completions")

    def chat(self, messages: list[dict], max_tokens: int = 900) -> str:
        if not self.enabled:
            raise LLMError("Kein KI-Endpunkt konfiguriert (LLM_BASE_URL und LLM_MODEL setzen).")
        body = json.dumps({"model": self.cfg.llm_model, "messages": messages, "temperature": 0.3,
                           "max_tokens": max_tokens}).encode()
        headers = {"Content-Type": "application/json"}
        if self.cfg.llm_api_key:
            headers["Authorization"] = f"Bearer {self.cfg.llm_api_key}"
        req = urllib.request.Request(self._url(), data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.cfg.llm_timeout) as r:
                data = json.loads(r.read())
        except urllib.error.HTTPError as e:
            raise LLMError(f"KI-Endpunkt antwortet mit HTTP {e.code}: {e.read()[:300].decode(errors='replace')}") from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise LLMError(f"KI-Endpunkt nicht erreichbar: {e}") from e
        try:
            return data["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError, TypeError) as e:
            raise LLMError(f"Unerwartete Antwort vom KI-Endpunkt: {str(data)[:300]}") from e

    def report(self, context: dict) -> str:
        return self.chat([{"role": "system", "content": SYSTEM},
                          {"role": "user", "content": REPORT_PROMPT + json.dumps(context, ensure_ascii=False, default=str)}],
                         max_tokens=1200)

    def ask(self, context: dict, history: list[dict], question: str) -> str:
        msgs = [{"role": "system", "content": SYSTEM + "\n\nAktuelle Daten (JSON):\n" + json.dumps(context, ensure_ascii=False, default=str)}]
        for m in history[-8:]:
            if m.get("role") in ("user", "assistant") and isinstance(m.get("content"), str):
                msgs.append({"role": m["role"], "content": m["content"][:2000]})
        msgs.append({"role": "user", "content": question[:2000]})
        return self.chat(msgs, max_tokens=700)
