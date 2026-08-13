from __future__ import annotations

import math
import os
import queue
import subprocess
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Callable

os.environ.setdefault(
    "OPENCV_FFMPEG_CAPTURE_OPTIONS",
    (
        "rtsp_transport;tcp|stimeout;10000000|rw_timeout;10000000|"
        "threads;1|skip_frame;nonref"
    ),
)

import cv2
import imageio_ffmpeg
import numpy as np
import sounddevice as sd


DISPLAY_FPS = 6
MAX_FRAME_EDGE = 960
cv2.setNumThreads(1)
cv2.ocl.setUseOpenCL(False)


@dataclass(frozen=True)
class VideoStatus:
    key: str
    level: str
    message: str


@dataclass(frozen=True)
class AudioStatus:
    key: str
    level: str
    message: str


class LatestFrame:
    def __init__(self) -> None:
        self._queue: queue.Queue[np.ndarray] = queue.Queue(maxsize=1)

    def put(self, frame: np.ndarray) -> None:
        try:
            self._queue.get_nowait()
        except queue.Empty:
            pass
        try:
            self._queue.put_nowait(frame)
        except queue.Full:
            pass

    def get(self) -> np.ndarray | None:
        try:
            return self._queue.get_nowait()
        except queue.Empty:
            return None


class VideoWorker:
    """Lee un stream en segundo plano y conserva solamente el cuadro más nuevo."""

    def __init__(
        self,
        key: str,
        source_factory: Callable[[], str],
        status_callback: Callable[[VideoStatus], None],
    ) -> None:
        self.key = key
        self.frames = LatestFrame()
        self._source_factory = source_factory
        self._status_callback = status_callback
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name=f"Video-{key}",
            daemon=True,
        )

    def start(self) -> None:
        self._thread.start()

    def request_stop(self) -> None:
        self._stop.set()

    def stop(self) -> None:
        self.request_stop()
        if self._thread.is_alive():
            self._thread.join(timeout=3)

    def _status(self, level: str, message: str) -> None:
        self._status_callback(VideoStatus(self.key, level, message))

    def _wait(self, seconds: float) -> bool:
        return self._stop.wait(seconds)

    def _open(self, source: str) -> cv2.VideoCapture:
        base_params = [
            cv2.CAP_PROP_OPEN_TIMEOUT_MSEC,
            15_000,
            cv2.CAP_PROP_READ_TIMEOUT_MSEC,
            12_000,
        ]
        try:
            return cv2.VideoCapture(source, cv2.CAP_FFMPEG, base_params)
        except TypeError:
            fallback = cv2.VideoCapture(source, cv2.CAP_FFMPEG)
            fallback.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            return fallback

    def _run(self) -> None:
        retry = 0
        while not self._stop.is_set():
            capture: cv2.VideoCapture | None = None
            try:
                self._status("waiting", "Solicitando video…")
                source = self._source_factory()
                if not source:
                    raise ValueError("No hay una dirección de video configurada.")

                capture = self._open(source)
                if not capture.isOpened():
                    raise ConnectionError("No se pudo abrir el stream.")

                retry = 0
                failures = 0
                last_publish = 0.0
                self._status("online", "EN VIVO")

                while not self._stop.is_set():
                    ok, frame = capture.read()
                    if not ok or frame is None:
                        failures += 1
                        if failures >= 4:
                            raise ConnectionError("El stream dejó de responder.")
                        if self._wait(0.15):
                            break
                        continue

                    failures = 0
                    now = time.monotonic()
                    if now - last_publish >= 1 / DISPLAY_FPS:
                        height, width = frame.shape[:2]
                        longest = max(width, height)
                        if longest > MAX_FRAME_EDGE:
                            scale = MAX_FRAME_EDGE / longest
                            frame = cv2.resize(
                                frame,
                                (max(1, int(width * scale)), max(1, int(height * scale))),
                                interpolation=cv2.INTER_AREA,
                            )
                        self.frames.put(frame)
                        last_publish = now
            except Exception as exc:
                retry += 1
                delay = int(getattr(exc, "retry_after", min(4 + retry * 2, 20)))
                message = str(exc).strip() or "Conexión interrumpida."
                self._status("error", f"{message} Reintento en {delay} s.")
                invalidate = getattr(self._source_factory, "invalidate", None)
                if callable(invalidate):
                    try:
                        invalidate()
                    except Exception:
                        pass
                if self._wait(delay):
                    break
            finally:
                if capture is not None:
                    capture.release()

        self._status("off", "Detenida")


class AudioWorker:
    """Reproduce únicamente el audio de la cámara principal."""

    SAMPLE_RATE = 16_000
    CHUNK_BYTES = 3_200

    def __init__(
        self,
        key: str,
        source_factory: Callable[[], str],
        status_callback: Callable[[AudioStatus], None],
    ) -> None:
        self.key = key
        self._source_factory = source_factory
        self._status_callback = status_callback
        self._stop = threading.Event()
        self._process: subprocess.Popen[bytes] | None = None
        self._thread = threading.Thread(
            target=self._run,
            name=f"Audio-{key}",
            daemon=True,
        )

    def start(self) -> None:
        self._thread.start()

    def request_stop(self) -> None:
        self._stop.set()
        process = self._process
        if process is not None and process.poll() is None:
            try:
                process.terminate()
            except OSError:
                pass

    def stop(self) -> None:
        self.request_stop()
        if self._thread.is_alive():
            self._thread.join(timeout=2)

    def _status(self, level: str, message: str) -> None:
        self._status_callback(AudioStatus(self.key, level, message))

    @staticmethod
    def _hidden_startup() -> subprocess.STARTUPINFO | None:
        if os.name != "nt":
            return None
        startup = subprocess.STARTUPINFO()
        startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        return startup

    def _command(self, source: str) -> list[str]:
        command = [
            imageio_ffmpeg.get_ffmpeg_exe(),
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-fflags",
            "nobuffer",
            "-flags",
            "low_delay",
        ]
        if source.lower().startswith(("rtsp://", "rtsps://")):
            command.extend(["-rtsp_transport", "tcp"])
        command.extend(
            [
                "-i",
                source,
                "-vn",
                "-acodec",
                "pcm_s16le",
                "-ac",
                "1",
                "-ar",
                str(self.SAMPLE_RATE),
                "-f",
                "s16le",
                "pipe:1",
            ]
        )
        return command

    def _run(self) -> None:
        retry = 0
        while not self._stop.is_set():
            process: subprocess.Popen[bytes] | None = None
            try:
                self._status("waiting", "Conectando audio…")
                source = self._source_factory()
                if not source:
                    raise ValueError("La cámara no entregó audio.")
                creationflags = (
                    subprocess.CREATE_NO_WINDOW
                    if os.name == "nt" and hasattr(subprocess, "CREATE_NO_WINDOW")
                    else 0
                )
                process = subprocess.Popen(
                    self._command(source),
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    startupinfo=self._hidden_startup(),
                    creationflags=creationflags,
                )
                self._process = process
                if process.stdout is None:
                    raise RuntimeError("No se pudo abrir la salida de audio.")

                with sd.RawOutputStream(
                    samplerate=self.SAMPLE_RATE,
                    channels=1,
                    dtype="int16",
                    blocksize=self.CHUNK_BYTES // 2,
                    latency="low",
                ) as output:
                    retry = 0
                    self._status("online", "Audio activo")
                    while not self._stop.is_set():
                        chunk = process.stdout.read(self.CHUNK_BYTES)
                        if not chunk:
                            raise ConnectionError(
                                "La cámara no tiene audio disponible."
                            )
                        output.write(chunk)
            except Exception as exc:
                if self._stop.is_set():
                    break
                retry += 1
                delay = int(getattr(exc, "retry_after", min(5 + retry * 3, 30)))
                message = str(exc).strip() or "Audio interrumpido."
                self._status("error", f"{message} Reintento en {delay} s.")
                if self._stop.wait(delay):
                    break
            finally:
                self._process = None
                if process is not None and process.poll() is None:
                    try:
                        process.terminate()
                        process.wait(timeout=1)
                    except (OSError, subprocess.TimeoutExpired):
                        try:
                            process.kill()
                        except OSError:
                            pass

        self._status("off", "Audio apagado")


class DemoWorker:
    """Genera video sintético para comprobar la interfaz sin usar cámaras."""

    COLORS = {
        "arlo1": (50, 117, 92),
        "arlo2": (94, 86, 45),
        "generica": (80, 62, 119),
    }

    def __init__(
        self,
        key: str,
        title: str,
        status_callback: Callable[[VideoStatus], None],
    ) -> None:
        self.key = key
        self.title = title
        self.frames = LatestFrame()
        self._status_callback = status_callback
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name=f"Demo-{key}",
            daemon=True,
        )

    def start(self) -> None:
        self._thread.start()

    def request_stop(self) -> None:
        self._stop.set()

    def stop(self) -> None:
        self.request_stop()
        if self._thread.is_alive():
            self._thread.join(timeout=2)

    def _run(self) -> None:
        self._status_callback(VideoStatus(self.key, "online", "DEMO EN VIVO"))
        width, height = 960, 540
        tick = 0
        base = np.array(self.COLORS.get(self.key, (65, 65, 65)), dtype=np.uint8)
        safe_title = self.title.replace(" · ", " - ")
        while not self._stop.wait(1 / DISPLAY_FPS):
            y = np.linspace(0, 1, height, dtype=np.float32)[:, None, None]
            shade = 0.55 + 0.45 * y
            frame = np.broadcast_to(base, (height, width, 3)).copy()
            frame = np.clip(frame.astype(np.float32) * shade, 0, 255).astype(np.uint8)

            x = int((math.sin(tick / 22) + 1) * (width - 120) / 2)
            cv2.rectangle(frame, (x, 260), (x + 120, 380), (219, 225, 230), -1)
            cv2.circle(frame, (x + 60, 245), 42, (219, 225, 230), -1)
            cv2.putText(
                frame,
                safe_title,
                (44, 78),
                cv2.FONT_HERSHEY_SIMPLEX,
                1.25,
                (245, 247, 250),
                2,
                cv2.LINE_AA,
            )
            cv2.putText(
                frame,
                datetime.now().strftime("%d/%m/%Y  %H:%M:%S"),
                (44, height - 38),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.75,
                (245, 247, 250),
                2,
                cv2.LINE_AA,
            )
            self.frames.put(frame)
            tick += 1

        self._status_callback(VideoStatus(self.key, "off", "Detenida"))
