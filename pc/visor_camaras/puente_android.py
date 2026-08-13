from __future__ import annotations

import json
import logging
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable
from urllib.parse import parse_qs, urlsplit

from .servicios import StreamSpec


LOGGER = logging.getLogger(__name__)


def local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("192.0.2.1", 80))
        return str(sock.getsockname()[0])
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"
    finally:
        sock.close()


class AndroidBridge:
    """Entrega URLs temporales Arlo al APK sin exponer claves de la cuenta."""

    def __init__(
        self,
        specs_provider: Callable[[], dict[str, StreamSpec]],
        code: str,
        port: int = 8765,
        privacy: bool = False,
        privacy_callback: Callable[[bool], None] | None = None,
    ) -> None:
        self._specs_provider = specs_provider
        self._code = code
        self._port = port
        self._privacy = privacy
        self._privacy_callback = privacy_callback
        self._privacy_lock = threading.Lock()
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None

    @property
    def privacy(self) -> bool:
        with self._privacy_lock:
            return self._privacy

    def set_privacy(self, enabled: bool) -> None:
        with self._privacy_lock:
            self._privacy = bool(enabled)

    def start(self) -> str:
        bridge = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "CamarasToapanta/1.0"

            def log_message(self, _format: str, *_args: object) -> None:
                return

            def _json(self, status: int, body: dict[str, object]) -> None:
                payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(payload)

            def _playlist(
                self,
                status: int,
                title: str,
                url: str = "",
                filename: str = "camara.m3u",
            ) -> None:
                if status == 200:
                    content = f"#EXTM3U\n#EXTINF:-1,{title}\n{url}\n"
                else:
                    content = f"#EXTM3U\n#EXTINF:-1,{title}\n"
                payload = content.encode("utf-8")
                self.send_response(status)
                self.send_header(
                    "Content-Type",
                    "application/vnd.apple.mpegurl; charset=utf-8",
                )
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Content-Disposition", f'inline; filename="{filename}"')
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(payload)

            def _authorized(self, parsed: object) -> bool:
                header_code = self.headers.get("X-Camaras-Codigo", "")
                query_code = parse_qs(parsed.query).get("codigo", [""])[0]
                return header_code == bridge._code or query_code == bridge._code

            def _prepare_url(
                self,
                key: str,
                renew: bool,
            ) -> tuple[StreamSpec | None, str]:
                if bridge.privacy:
                    return None, ""
                specs = bridge._specs_provider()
                spec = specs.get(key)
                if spec is None:
                    return None, ""
                invalidate = getattr(spec.source_factory, "invalidate", None)
                if renew and callable(invalidate):
                    invalidate()
                return spec, spec.source_factory()

            def do_GET(self) -> None:
                parsed = urlsplit(self.path)
                if not self._authorized(parsed):
                    self._json(403, {"error": "Código Android incorrecto"})
                    return

                if parsed.path == "/api/v1/status":
                    specs = bridge._specs_provider()
                    cameras = {
                        key: {"name": spec.title, "ready": not bridge.privacy}
                        for key, spec in specs.items()
                    }
                    self._json(
                        200,
                        {
                            "app": "Cámaras Toapanta",
                            "privacy": bridge.privacy,
                            "cameras": cameras,
                        },
                    )
                    return

                vlc_prefix = "/vlc/"
                if parsed.path.startswith(vlc_prefix) and parsed.path.endswith(".m3u"):
                    key = parsed.path[len(vlc_prefix) : -len(".m3u")]
                    if bridge.privacy:
                        self._playlist(
                            423,
                            "Privacidad activa en Cámaras Toapanta",
                            filename=f"{key}.m3u",
                        )
                        return
                    try:
                        renew = (
                            parse_qs(parsed.query).get("renovar", ["0"])[0] == "1"
                        )
                        spec, url = self._prepare_url(key, renew)
                        if spec is None:
                            self._playlist(
                                503,
                                "La cámara todavía no está preparada",
                                filename=f"{key}.m3u",
                            )
                            return
                        self._playlist(200, spec.title, url, filename=f"{key}.m3u")
                    except Exception as exc:
                        LOGGER.warning(
                            "Puente VLC: no se pudo preparar %s: %s",
                            key,
                            exc.__class__.__name__,
                        )
                        self._playlist(
                            503,
                            "Arlo no entregó video; cierre y vuelva a abrir el enlace",
                            filename=f"{key}.m3u",
                        )
                    return

                prefix = "/api/v1/stream/"
                if not parsed.path.startswith(prefix):
                    self._json(404, {"error": "Ruta no encontrada"})
                    return

                if bridge.privacy:
                    self._json(
                        423,
                        {
                            "error": "Privacidad activa: las cámaras están detenidas",
                            "privacy": True,
                        },
                    )
                    return

                key = parsed.path[len(prefix) :]
                try:
                    renew = parse_qs(parsed.query).get("renew", ["0"])[0] == "1"
                    spec, url = self._prepare_url(key, renew)
                    if spec is None:
                        self._json(
                            503,
                            {"error": "La cámara todavía no está preparada"},
                        )
                        return
                    self._json(
                        200,
                        {
                            "key": key,
                            "name": spec.title,
                            "url": url,
                        },
                    )
                except Exception as exc:
                    LOGGER.warning(
                        "Puente Android: no se pudo preparar %s: %s",
                        key,
                        exc.__class__.__name__,
                    )
                    self._json(
                        503,
                        {"error": "Arlo no entregó video; reintente en unos segundos"},
                    )

            def do_POST(self) -> None:
                parsed = urlsplit(self.path)
                if not self._authorized(parsed):
                    self._json(403, {"error": "Código Android incorrecto"})
                    return
                if parsed.path != "/api/v1/privacy":
                    self._json(404, {"error": "Ruta no encontrada"})
                    return
                try:
                    content_length = min(
                        1024,
                        max(0, int(self.headers.get("Content-Length", "0"))),
                    )
                    raw = self.rfile.read(content_length)
                    body = json.loads(raw.decode("utf-8")) if raw else {}
                    enabled = body.get("enabled")
                    if not isinstance(enabled, bool):
                        raise ValueError("enabled debe ser booleano")
                except (ValueError, TypeError, json.JSONDecodeError):
                    self._json(400, {"error": "Solicitud de privacidad no válida"})
                    return

                bridge.set_privacy(enabled)
                callback = bridge._privacy_callback
                if callback is not None:
                    try:
                        callback(enabled)
                    except Exception:
                        LOGGER.warning(
                            "No se pudo notificar el cambio de privacidad.",
                            exc_info=True,
                        )
                self._json(
                    200,
                    {
                        "privacy": enabled,
                        "message": (
                            "Todas las cámaras fueron detenidas"
                            if enabled
                            else "Las cámaras fueron reactivadas"
                        ),
                    },
                )

        try:
            self._server = ThreadingHTTPServer(("0.0.0.0", self._port), Handler)
            self._server.daemon_threads = True
        except OSError:
            LOGGER.warning("No se pudo iniciar el puente Android.", exc_info=True)
            self._server = None
            return ""

        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name="Puente-Android",
            daemon=True,
        )
        self._thread.start()
        return f"{local_ip()}:{self._port}"

    def stop(self) -> None:
        server, self._server = self._server, None
        if server is not None:
            threading.Thread(
                target=server.shutdown,
                name="Cerrar-Puente-Android",
                daemon=True,
            ).start()
