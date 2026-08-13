from __future__ import annotations

import json
import os
import shutil
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import keyring


APP_ID = "CamarasToapanta"
LEGACY_APP_ID = "VisorCamarasCasa"
SECRET_SERVICE = "Camaras Toapanta - Jimmy Toapanta"
LEGACY_SECRET_SERVICE = "Visor de camaras de casa"
GENERIC_MAIN_URLS = {
    "rtsp://192.0.2.10:554",
    "rtsp://192.0.2.10:554/",
}
GENERIC_LIGHT_URL = "rtsp://192.0.2.10:554/live/ch00_1"


def app_data_dir() -> Path:
    base = Path(os.environ.get("APPDATA", Path.home()))
    path = base / APP_ID
    path.mkdir(parents=True, exist_ok=True)
    legacy = base / LEGACY_APP_ID
    legacy_config = legacy / "config.json"
    current_config = path / "config.json"
    if not current_config.exists() and legacy_config.exists():
        try:
            shutil.copy2(legacy_config, current_config)
        except OSError:
            pass
    return path


@dataclass
class ArloConfig:
    usuario: str = ""
    segundo_factor: str = "email"
    nombres_camaras: list[str] = field(default_factory=list)


@dataclass
class GenericaConfig:
    nombre: str = "Cámara genérica"
    url: str = ""
    usuario: str = ""


@dataclass
class AppConfig:
    arlo: ArloConfig = field(default_factory=ArloConfig)
    generica: GenericaConfig = field(default_factory=GenericaConfig)
    guardar_claves: bool = True
    pantalla_completa: bool = False
    vista: str = "three"
    camara_principal: str = "arlo1"
    camara_secundaria: str = "arlo2"
    audio_habilitado: bool = False
    privacidad: bool = False
    android_codigo: str = "871066"
    android_puerto: int = 8765

    @property
    def completa(self) -> bool:
        return bool(self.arlo.usuario or self.generica.url)


def _read_dict(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError, TypeError):
        return {}


def load_config() -> AppConfig:
    raw = _read_dict(app_data_dir() / "config.json")
    arlo = raw.get("arlo") if isinstance(raw.get("arlo"), dict) else {}
    generic = raw.get("generica") if isinstance(raw.get("generica"), dict) else {}

    camera_names = arlo.get("nombres_camaras", [])
    if not isinstance(camera_names, list):
        camera_names = []

    generic_url = str(generic.get("url", ""))
    if generic_url in GENERIC_MAIN_URLS:
        generic_url = GENERIC_LIGHT_URL

    vista = str(raw.get("vista", "three")).lower()
    if vista not in {"single", "dual", "three"}:
        vista = "three"
    principal = str(raw.get("camara_principal", "arlo1"))
    secundaria = str(raw.get("camara_secundaria", "arlo2"))
    valid_keys = {"arlo1", "arlo2", "generica"}
    if principal not in valid_keys:
        principal = "arlo1"
    if secundaria not in valid_keys:
        secundaria = "arlo2"

    return AppConfig(
        arlo=ArloConfig(
            usuario=str(arlo.get("usuario", "")),
            segundo_factor=str(arlo.get("segundo_factor", "email")).lower(),
            nombres_camaras=[str(name) for name in camera_names[:2]],
        ),
        generica=GenericaConfig(
            nombre=str(generic.get("nombre", "Cámara genérica")),
            url=generic_url,
            usuario=str(generic.get("usuario", "")),
        ),
        guardar_claves=bool(raw.get("guardar_claves", True)),
        pantalla_completa=bool(raw.get("pantalla_completa", False)),
        vista=vista,
        camara_principal=principal,
        camara_secundaria=secundaria,
        audio_habilitado=bool(raw.get("audio_habilitado", False)),
        privacidad=bool(raw.get("privacidad", False)),
        android_codigo=str(raw.get("android_codigo", "871066"))[:12] or "871066",
        android_puerto=int(raw.get("android_puerto", 8765)),
    )


def save_config(config: AppConfig) -> None:
    path = app_data_dir() / "config.json"
    temporary = path.with_suffix(".tmp")
    temporary.write_text(
        json.dumps(asdict(config), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary.replace(path)


class SecretStore:
    """Guarda contraseñas en el almacén seguro de Windows mediante keyring."""

    @staticmethod
    def _key(kind: str, identity: str) -> str:
        return f"{kind}:{identity.strip().lower()}"

    def get(self, kind: str, identity: str) -> str:
        if not identity:
            return ""
        try:
            key = self._key(kind, identity)
            secret = keyring.get_password(SECRET_SERVICE, key) or ""
            if not secret:
                secret = keyring.get_password(LEGACY_SECRET_SERVICE, key) or ""
                if secret:
                    keyring.set_password(SECRET_SERVICE, key, secret)
            return secret
        except Exception:
            return ""

    def set(self, kind: str, identity: str, secret: str) -> bool:
        if not identity or not secret:
            return False
        try:
            keyring.set_password(SECRET_SERVICE, self._key(kind, identity), secret)
            return True
        except Exception:
            return False

    def delete(self, kind: str, identity: str) -> None:
        if not identity:
            return
        key = self._key(kind, identity)
        for service in (SECRET_SERVICE, LEGACY_SECRET_SERVICE):
            try:
                keyring.delete_password(service, key)
            except Exception:
                pass
