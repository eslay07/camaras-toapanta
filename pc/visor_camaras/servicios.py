from __future__ import annotations

import builtins
import logging
import shutil
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from urllib.parse import quote, unquote, urlsplit, urlunsplit

import requests
import pyaarlo.cfg as pyaarlo_cfg
from pyaarlo import PyArlo

from .configuracion import ArloConfig, app_data_dir


LOGGER = logging.getLogger(__name__)


class _ArloLoginCapture(logging.Handler):
    """Conserva únicamente mensajes de autenticación para explicar el fallo."""

    def __init__(self) -> None:
        super().__init__(level=logging.WARNING)
        self.messages: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self.messages.append(record.getMessage())
        except Exception:
            pass


def _archive_stale_arlo_auth(storage: Path) -> bool:
    """Aparta solo la confianza local vencida sin cerrar otros dispositivos."""
    stamp = time.strftime("%Y%m%d-%H%M%S")
    moved = False
    for filename in ("session.pickle", "cookies.txt"):
        source = storage / filename
        if not source.exists():
            continue
        candidate = storage / f"{filename}.vencido-{stamp}"
        counter = 1
        while candidate.exists():
            candidate = storage / f"{filename}.vencido-{stamp}-{counter}"
            counter += 1
        try:
            source.replace(candidate)
            moved = True
        except OSError:
            LOGGER.warning("No se pudo apartar la sesión Arlo local vencida.")
    return moved


def _should_renew_arlo_auth(messages: list[str]) -> bool:
    """Renueva solo cuando falló la confianza local, no por red o contraseña."""
    details = " ".join(messages).casefold()
    return any(
        marker in details
        for marker in (
            "quick start failed",
            "pairing: failed",
            "token validation failed",
            "browser not trusted",
        )
    )


def _arlo_failure_message(
    messages: list[str], second_factor_requested: bool, session_renewed: bool
) -> str:
    details = " ".join(messages).casefold()
    if "429" in details:
        return (
            "Arlo limitó temporalmente los intentos de inicio de sesión. "
            "Espere 15 minutos sin volver a intentar y abra nuevamente la aplicación."
        )
    if "dnserror" in details or "name resolution" in details:
        return "La PC no pudo resolver el servidor de Arlo. Revise DNS e Internet."
    if any(
        marker in details
        for marker in ("connecttimeout", "connectionerror", "sslerror", "tlserror")
    ):
        return (
            "La conexión segura con Arlo fue bloqueada o agotó el tiempo. "
            "Revise Internet, fecha y hora de Windows."
        )
    if "no secondary choices" in details:
        return (
            "Arlo no ofreció el método de segundo factor seleccionado. "
            "Abra Configurar y elija email o SMS según su cuenta."
        )
    if second_factor_requested:
        return (
            "Arlo rechazó el código de verificación. Use solamente el código más "
            "reciente enviado para este intento."
        )
    if "403" in details or "cloudflare" in details:
        return (
            "La protección web de Arlo rechazó temporalmente esta PC. "
            "Espere unos minutos y vuelva a intentar sin VPN ni proxy."
        )
    if "401" in details:
        return (
            "Arlo rechazó la autenticación de esta PC. Abra Configurar, vuelva a "
            "guardar la contraseña y use el código más reciente si se solicita."
        )
    if session_renewed:
        return (
            "Arlo no aceptó la nueva sesión local. Espere unos minutos y vuelva a "
            "intentarlo; las sesiones de sus otros dispositivos siguen abiertas."
        )
    return (
        "Arlo rechazó la sesión de esta PC. Las sesiones de los demás dispositivos "
        "no se cerraron."
    )


def add_url_credentials(url: str, username: str, password: str) -> str:
    """Añade credenciales a una URL sin modificar la configuración guardada."""
    url = url.strip()
    if not url or not username:
        return url

    parsed = urlsplit(url)
    if not parsed.scheme or not parsed.hostname:
        return url

    host = parsed.hostname
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    port = f":{parsed.port}" if parsed.port else ""
    auth = quote(username, safe="")
    if password:
        auth += ":" + quote(password, safe="")
    netloc = f"{auth}@{host}{port}"
    return urlunsplit(
        (parsed.scheme, netloc, parsed.path, parsed.query, parsed.fragment)
    )


def strip_url_credentials(url: str) -> tuple[str, str, str]:
    """Quita usuario/clave de una URL para que nunca queden en config.json."""
    parsed = urlsplit(url.strip())
    if not parsed.scheme or not parsed.hostname:
        return url.strip(), "", ""

    host = parsed.hostname
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    port = f":{parsed.port}" if parsed.port else ""
    clean = urlunsplit(
        (parsed.scheme, f"{host}{port}", parsed.path, parsed.query, parsed.fragment)
    )
    return (
        clean,
        unquote(parsed.username or ""),
        unquote(parsed.password or ""),
    )


@dataclass(frozen=True)
class ArloCameraInfo:
    name: str
    model: str
    hardware: str
    battery: str


@dataclass(frozen=True)
class StreamSpec:
    key: str
    title: str
    source_factory: Callable[[], str]
    detail: str = ""


class StreamUnavailableError(ConnectionError):
    """Arlo no pudo entregar una URL utilizable en este intento."""

    retry_after = 10


class ArloStreamSource:
    """Comparte una URL temporal entre video y audio sin reiniciar el stream."""

    def __init__(self, camera: object, lock: threading.Lock) -> None:
        self._camera = camera
        self._lock = lock
        self._url = ""
        self._used_remote_request = False

    def __call__(self) -> str:
        with self._lock:
            if self._url:
                return self._url

            # Una URL devuelta por "get" puede pertenecer a otra sesión o haber
            # vencido aunque Arlo todavía la anuncie como activa. Se solicita
            # primero una URL nueva para esta sesión. Esto no cierra las demás
            # sesiones y, al salir, tampoco se envía activityState=idle.
            get_stream_url = getattr(self._camera, "get_stream_url", None)
            self._detach_local_state()
            url = self._camera.start_stream(user_agent="arlo")
            if not url:
                if callable(get_stream_url):
                    time.sleep(1)
                    url = get_stream_url(user_agent="arlo")
                    self._used_remote_request = bool(url)
            if not url:
                # Arlo puede contestar temporalmente "Unknown" mientras la
                # estación base cambia a transmisión. Se reintenta una vez de
                # forma local, sin mandar activityState=idle.
                time.sleep(1)
                self._detach_local_state()
                url = self._camera.start_stream(user_agent="arlo")
            if not url:
                name = str(getattr(self._camera, "name", "la cámara"))
                raise StreamUnavailableError(
                    f"{name}: Arlo no entregó video en este intento; "
                    "puede estar ocupado por otra sesión"
                )
            self._url = str(url)
            return self._url

    def invalidate(self) -> None:
        with self._lock:
            self._url = ""
            # Se limpia únicamente el estado local. No se envía activityState=idle,
            # porque ese comando cerraría también a los demás usuarios.
            self._detach_local_state()
            self._used_remote_request = False

    def _detach_local_state(self) -> None:
        camera_lock = getattr(self._camera, "_lock", None)
        try:
            if camera_lock is not None:
                with camera_lock:
                    local_users = getattr(self._camera, "_local_users", None)
                    if hasattr(local_users, "clear"):
                        local_users.clear()
                    setattr(self._camera, "_stream_url", None)
            else:
                setattr(self._camera, "_stream_url", None)
        except Exception:
            LOGGER.debug("No se pudo liberar el estado RTSP local.", exc_info=True)


class OnvifPtzController:
    """Control PTZ local para la cámara genérica compatible con ONVIF."""

    SOAP_NS = "http://www.w3.org/2003/05/soap-envelope"
    PTZ_NS = "http://www.onvif.org/ver20/ptz/wsdl"
    SCHEMA_NS = "http://www.onvif.org/ver10/schema"

    def __init__(
        self,
        host: str,
        port: int = 8899,
        profile_token: str = "PROFILE_000",
    ) -> None:
        if not host:
            raise ValueError("Falta la dirección de la cámara genérica.")
        self.endpoint = f"http://{host}:{port}/onvif/ptz_service"
        self.profile_token = profile_token
        self._lock = threading.Lock()

    def _post(self, action: str, body: str) -> None:
        action_url = f"{self.PTZ_NS}/{action}"
        envelope = (
            f'<s:Envelope xmlns:s="{self.SOAP_NS}" '
            f'xmlns:tptz="{self.PTZ_NS}" xmlns:tt="{self.SCHEMA_NS}">'
            f"<s:Body>{body}</s:Body></s:Envelope>"
        )
        with self._lock:
            response = requests.post(
                self.endpoint,
                data=envelope.encode("utf-8"),
                headers={
                    "Content-Type": (
                        'application/soap+xml; charset=utf-8; '
                        f'action="{action_url}"'
                    )
                },
                timeout=5,
            )
            response.raise_for_status()
            if b":Fault" in response.content or b"<Fault" in response.content:
                raise ConnectionError("La cámara rechazó el comando de movimiento.")

    def move(self, x: float, y: float) -> None:
        body = (
            "<tptz:ContinuousMove>"
            f"<tptz:ProfileToken>{self.profile_token}</tptz:ProfileToken>"
            "<tptz:Velocity>"
            f'<tt:PanTilt x="{x:.2f}" y="{y:.2f}"/>'
            "</tptz:Velocity>"
            "<tptz:Timeout>PT2S</tptz:Timeout>"
            "</tptz:ContinuousMove>"
        )
        self._post("ContinuousMove", body)

    def stop(self) -> None:
        body = (
            "<tptz:Stop>"
            f"<tptz:ProfileToken>{self.profile_token}</tptz:ProfileToken>"
            "<tptz:PanTilt>true</tptz:PanTilt>"
            "<tptz:Zoom>true</tptz:Zoom>"
            "</tptz:Stop>"
        )
        self._post("Stop", body)

    def home(self) -> None:
        body = (
            "<tptz:GotoHomePosition>"
            f"<tptz:ProfileToken>{self.profile_token}</tptz:ProfileToken>"
            "</tptz:GotoHomePosition>"
        )
        self._post("GotoHomePosition", body)


def _persistent_arlo_storage() -> Path:
    """Migra la sesión temporal antigua a una carpeta estable por usuario."""
    storage = app_data_dir() / "arlo"
    storage.mkdir(parents=True, exist_ok=True)
    legacy = Path(tempfile.gettempdir()) / ".aarlo"
    if not legacy.exists():
        return storage
    try:
        for source in legacy.rglob("*"):
            relative = source.relative_to(legacy)
            target = storage / relative
            if source.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            elif not target.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(str(source), str(target))
    except OSError:
        LOGGER.debug("No se pudo migrar toda la sesión Arlo anterior.", exc_info=True)
    return storage


class ArloSession:
    """Mantiene una sola sesión Arlo y genera URL nuevas para cada reconexión."""

    def __init__(self) -> None:
        self._arlo: PyArlo | None = None
        self._cameras: list[object] = []
        self._camera_locks: dict[str, threading.Lock] = {}
        self._stop_lock = threading.Lock()

    @property
    def connected(self) -> bool:
        return bool(self._arlo and self._arlo.is_connected)

    def connect(
        self,
        config: ArloConfig,
        password: str,
        ask_second_factor: Callable[[str], str],
        status: Callable[[str], None],
    ) -> list[ArloCameraInfo]:
        if not config.usuario:
            return []
        if not password:
            raise ValueError("Falta la contraseña de Arlo.")

        self.stop()
        status("Iniciando sesión segura en Arlo…")

        original_input = builtins.input
        second_factor_requested = False

        def gui_input(prompt: str = "") -> str:
            nonlocal second_factor_requested
            second_factor_requested = True
            return ask_second_factor(prompt or "Código de verificación de Arlo")

        builtins.input = gui_input
        capture = _ArloLoginCapture()
        pyaarlo_logger = logging.getLogger("pyaarlo")
        pyaarlo_logger.addHandler(capture)
        session_renewed = False
        try:
            storage = _persistent_arlo_storage()
            had_saved_auth = any(
                (storage / filename).exists()
                for filename in ("session.pickle", "cookies.txt")
            )

            try:
                import curl_cffi  # noqa: F401

                http_backend = "curl_cffi"
            except (ImportError, OSError):
                http_backend = "cloudscraper"
                status("Compatibilidad web reducida; usando conexión alternativa…")

            # PyAarlo 0.8 ignora storage_dir en Windows y usa TEMP\.aarlo.
            # Durante la creación se evita esa rama para que token, cookies e ID
            # del dispositivo permanezcan en AppData entre reinicios.
            original_system = pyaarlo_cfg.platform.system
            pyaarlo_cfg.platform.system = lambda: "CamarasToapanta"

            def create_arlo() -> PyArlo:
                return PyArlo(
                    username=config.usuario,
                    password=password,
                    tfa_source="console",
                    tfa_type=config.segundo_factor,
                    tfa_nickname=config.usuario,
                    storage_dir=str(storage),
                    save_session=True,
                    save_state=True,
                    http_backend=http_backend,
                    curl_cffi_impersonate="chrome",
                    user_agent="linux",
                    wait_for_initial_setup=True,
                    library_days=0,
                    no_media_upload=True,
                    refresh_devices_every=0,
                    synchronous_mode=True,
                    request_timeout=20,
                )

            try:
                self._arlo = create_arlo()
                if (
                    (not self._arlo or not self._arlo.is_connected)
                    and had_saved_auth
                    and not second_factor_requested
                    and _should_renew_arlo_auth(capture.messages)
                ):
                    self.stop()
                    if _archive_stale_arlo_auth(storage):
                        session_renewed = True
                        capture.messages.clear()
                        status("La confianza de esta PC venció; renovando sesión Arlo…")
                        self._arlo = create_arlo()
            finally:
                pyaarlo_cfg.platform.system = original_system
        finally:
            pyaarlo_logger.removeHandler(capture)
            builtins.input = original_input

        if not self._arlo or not self._arlo.is_connected:
            self.stop()
            raise ConnectionError(
                _arlo_failure_message(
                    capture.messages, second_factor_requested, session_renewed
                )
            )

        self._cameras = list(self._arlo.cameras)
        if not self._cameras:
            self.stop()
            raise LookupError("La cuenta Arlo no tiene cámaras compartidas o visibles.")

        self._camera_locks = {
            str(getattr(camera, "device_id", index)): threading.Lock()
            for index, camera in enumerate(self._cameras)
        }

        infos: list[ArloCameraInfo] = []
        for camera in self._cameras:
            battery = getattr(camera, "battery_level", None)
            infos.append(
                ArloCameraInfo(
                    name=str(getattr(camera, "name", "Arlo")),
                    model=str(getattr(camera, "model_id", "") or ""),
                    hardware=str(getattr(camera, "hw_version", "") or ""),
                    battery=(
                        f"{battery}%"
                        if isinstance(battery, (int, float)) and battery > 0
                        else ""
                    ),
                )
            )
        status(f"Arlo conectado: {len(infos)} cámara(s).")
        return infos

    def build_streams(self, preferred_names: list[str]) -> list[StreamSpec]:
        ordered: list[object] = []
        names = [name.casefold() for name in preferred_names if name]

        for wanted in names:
            match = next(
                (
                    camera
                    for camera in self._cameras
                    if str(getattr(camera, "name", "")).casefold() == wanted
                ),
                None,
            )
            if match is not None and match not in ordered:
                ordered.append(match)

        ordered.extend(camera for camera in self._cameras if camera not in ordered)
        specs: list[StreamSpec] = []
        for index, camera in enumerate(ordered[:2], start=1):
            name = str(getattr(camera, "name", f"Arlo {index}"))
            model = str(getattr(camera, "model_id", "") or "")
            hardware = str(getattr(camera, "hw_version", "") or "")
            battery = getattr(camera, "battery_level", None)
            parts = [part for part in (model, hardware) if part]
            if isinstance(battery, (int, float)) and battery > 0:
                parts.append(f"batería {battery}%")
            detail = " · ".join(parts)
            specs.append(
                StreamSpec(
                    key=f"arlo{index}",
                    title=name,
                    source_factory=self._factory_for(camera),
                    detail=detail,
                )
            )
        return specs

    def _factory_for(self, camera: object) -> ArloStreamSource:
        device_id = str(getattr(camera, "device_id", id(camera)))
        lock = self._camera_locks.setdefault(device_id, threading.Lock())
        return ArloStreamSource(camera, lock)

    def set_privacy(self, enabled: bool) -> tuple[int, list[str]]:
        """Apaga o reactiva las cámaras Arlo también para sus otras aplicaciones."""
        cameras = list(self._cameras)
        changed = 0
        errors: list[str] = []
        for camera in cameras:
            name = str(getattr(camera, "name", "Arlo"))
            try:
                action = getattr(camera, "turn_off" if enabled else "turn_on")
                action()
                changed += 1
            except Exception as exc:
                LOGGER.warning(
                    "No se pudo cambiar la privacidad de %s: %s",
                    name,
                    exc.__class__.__name__,
                )
                errors.append(name)
        return changed, errors

    def stop(self) -> None:
        with self._stop_lock:
            self._cameras = []
            arlo, self._arlo = self._arlo, None
            if arlo is not None:
                try:
                    arlo.stop(logout=False)
                except Exception:
                    LOGGER.debug("No se pudo cerrar Arlo limpiamente.", exc_info=True)


def configure_logging() -> Path:
    log_path = app_data_dir() / "visor.log"
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[logging.FileHandler(log_path, encoding="utf-8")],
        force=True,
    )
    logging.getLogger("pyaarlo").setLevel(logging.WARNING)
    return log_path
