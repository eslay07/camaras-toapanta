from __future__ import annotations

import argparse
import ctypes
import logging
import os
import queue
import re
import threading
import time
import tkinter as tk
from dataclasses import dataclass
from datetime import datetime
from tkinter import messagebox, simpledialog, ttk
from typing import Any, Callable
from urllib.parse import urlsplit

import numpy as np
from PIL import Image, ImageGrab, ImageOps, ImageTk

from .configuracion import (
    AppConfig,
    ArloConfig,
    GENERIC_LIGHT_URL,
    GENERIC_MAIN_URLS,
    GenericaConfig,
    SecretStore,
    load_config,
    save_config,
)
from .servicios import (
    ArloCameraInfo,
    ArloSession,
    OnvifPtzController,
    StreamSpec,
    add_url_credentials,
    configure_logging,
    strip_url_credentials,
)
from .puente_android import AndroidBridge
from .video import AudioStatus, AudioWorker, DemoWorker, VideoStatus, VideoWorker


BG = "#0A0F16"
SURFACE = "#121A24"
SURFACE_2 = "#182330"
BORDER = "#263445"
TEXT = "#F4F7FA"
MUTED = "#93A4B7"
ACCENT = "#36D399"
WARNING = "#FBBF5A"
DANGER = "#FB7185"
BLUE = "#6EA8FE"
APP_NAME = "Cámaras Toapanta"
AUTHOR = "Jimmy Toapanta"
LOGGER = logging.getLogger(__name__)
CAMERA_KEYS = ("arlo1", "arlo2", "generica")
VIEW_LABELS = {
    "single": "1 cámara",
    "dual": "2 cámaras",
    "three": "3 cámaras",
}


class SingleInstance:
    """Impide dos sesiones Arlo simultáneas del mismo visor en Windows."""

    def __init__(self) -> None:
        self._handle: int | None = None
        self._kernel32: Any = None
        self.already_running = False
        if os.name != "nt":
            return

        from ctypes import wintypes

        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        create_mutex = self._kernel32.CreateMutexW
        create_mutex.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
        create_mutex.restype = wintypes.HANDLE
        handle = create_mutex(
            None,
            False,
            "Local\\CamarasToapanta_JimmyToapanta",
        )
        if not handle:
            return
        self._handle = handle
        self.already_running = ctypes.get_last_error() == 183

    def close(self) -> None:
        if self._handle and self._kernel32 is not None:
            self._kernel32.CloseHandle(self._handle)
            self._handle = None


@dataclass
class LoginValues:
    config: AppConfig
    arlo_password: str
    generic_password: str


@dataclass
class SecondFactorRequest:
    prompt: str
    event: threading.Event
    result: str = ""


class SetupDialog(tk.Toplevel):
    def __init__(
        self,
        parent: tk.Misc,
        config: AppConfig,
        secrets: SecretStore,
    ) -> None:
        super().__init__(parent)
        self.title(f"Configuración · {APP_NAME}")
        self.configure(bg=BG)
        self.resizable(False, False)
        self.transient(parent)
        self.result: LoginValues | None = None

        self._previous_config = config
        self._secrets = secrets
        self._vars: dict[str, tk.Variable] = {
            "arlo_user": tk.StringVar(value=config.arlo.usuario),
            "arlo_password": tk.StringVar(
                value=secrets.get("arlo", config.arlo.usuario)
            ),
            "tfa": tk.StringVar(value=config.arlo.segundo_factor),
            "generic_name": tk.StringVar(value=config.generica.nombre),
            "generic_url": tk.StringVar(
                value=config.generica.url or GENERIC_LIGHT_URL
            ),
            "generic_user": tk.StringVar(value=config.generica.usuario),
            "generic_password": tk.StringVar(
                value=secrets.get("generica", config.generica.url)
            ),
            "save_passwords": tk.BooleanVar(value=config.guardar_claves),
            "fullscreen": tk.BooleanVar(value=config.pantalla_completa),
        }
        self._build()
        self.protocol("WM_DELETE_WINDOW", self._cancel)
        self.bind("<Escape>", lambda _event: self._cancel())
        self.bind("<Return>", lambda _event: self._save())

        self.update_idletasks()
        x = parent.winfo_rootx() + max(20, (parent.winfo_width() - self.winfo_width()) // 2)
        y = parent.winfo_rooty() + max(20, (parent.winfo_height() - self.winfo_height()) // 2)
        self.geometry(f"+{x}+{y}")
        self.grab_set()
        self._entries["arlo_user"].focus_set()

    def _label(
        self,
        parent: tk.Misc,
        text: str,
        row: int,
        column: int = 0,
        columnspan: int = 1,
        color: str = MUTED,
        font: tuple[str, int, str] | tuple[str, int] = ("Segoe UI", 9),
    ) -> tk.Label:
        label = tk.Label(parent, text=text, bg=SURFACE, fg=color, font=font, anchor="w")
        label.grid(
            row=row,
            column=column,
            columnspan=columnspan,
            sticky="ew",
            padx=18,
            pady=(7, 2),
        )
        return label

    def _entry(
        self,
        parent: tk.Misc,
        name: str,
        row: int,
        show: str | None = None,
    ) -> tk.Entry:
        entry = tk.Entry(
            parent,
            textvariable=self._vars[name],
            show=show or "",
            bg="#0E1620",
            fg=TEXT,
            insertbackground=TEXT,
            relief="flat",
            highlightthickness=1,
            highlightbackground=BORDER,
            highlightcolor=ACCENT,
            font=("Segoe UI", 10),
        )
        entry.grid(row=row, column=0, columnspan=2, sticky="ew", padx=18, ipady=8)
        return entry

    def _section_title(self, parent: tk.Misc, text: str, row: int) -> None:
        tk.Label(
            parent,
            text=text,
            bg=SURFACE,
            fg=TEXT,
            font=("Segoe UI Semibold", 13),
            anchor="w",
        ).grid(row=row, column=0, columnspan=2, sticky="ew", padx=18, pady=(16, 4))

    def _build(self) -> None:
        card = tk.Frame(
            self,
            bg=SURFACE,
            highlightthickness=1,
            highlightbackground=BORDER,
        )
        card.pack(padx=18, pady=18, fill="both", expand=True)
        card.grid_columnconfigure(0, weight=1)

        tk.Label(
            card,
            text=f"Configurar {APP_NAME}",
            bg=SURFACE,
            fg=TEXT,
            font=("Segoe UI Semibold", 18),
            anchor="w",
        ).grid(row=0, column=0, sticky="ew", padx=18, pady=(18, 0))
        tk.Label(
            card,
            text=(
                "Arlo se detecta mediante su cuenta; la genérica necesita una dirección "
                "RTSP, RTSPS, HTTP o HTTPS."
            ),
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI", 9),
            anchor="w",
            justify="left",
            wraplength=790,
        ).grid(row=1, column=0, sticky="ew", padx=18, pady=(3, 8))

        columns = tk.Frame(card, bg=SURFACE)
        columns.grid(row=2, column=0, sticky="nsew", padx=9)
        columns.grid_columnconfigure(0, weight=1, uniform="setup", minsize=390)
        columns.grid_columnconfigure(1, weight=1, uniform="setup", minsize=390)
        left = tk.Frame(columns, bg=SURFACE)
        right = tk.Frame(columns, bg=SURFACE)
        left.grid(row=0, column=0, sticky="nsew", padx=9)
        right.grid(row=0, column=1, sticky="nsew", padx=9)
        left.grid_columnconfigure(0, weight=1)
        right.grid_columnconfigure(0, weight=1)

        def heading(parent: tk.Misc, text: str, row: int) -> None:
            tk.Label(
                parent,
                text=text,
                bg=SURFACE,
                fg=TEXT,
                font=("Segoe UI Semibold", 13),
                anchor="w",
            ).grid(row=row, column=0, sticky="ew", pady=(6, 8))

        def label(parent: tk.Misc, text: str, row: int) -> None:
            tk.Label(
                parent,
                text=text,
                bg=SURFACE,
                fg=MUTED,
                font=("Segoe UI", 9),
                anchor="w",
            ).grid(row=row, column=0, sticky="ew", pady=(5, 2))

        def entry(
            parent: tk.Misc,
            name: str,
            row: int,
            show: str = "",
        ) -> tk.Entry:
            widget = tk.Entry(
                parent,
                textvariable=self._vars[name],
                show=show,
                bg="#0E1620",
                fg=TEXT,
                insertbackground=TEXT,
                relief="flat",
                highlightthickness=1,
                highlightbackground=BORDER,
                highlightcolor=ACCENT,
                font=("Segoe UI", 10),
            )
            widget.grid(row=row, column=0, sticky="ew", ipady=7)
            return widget

        self._entries = {}
        heading(left, "Dos cámaras Arlo Pro (H8)", 0)
        label(left, "Correo de la cuenta Arlo", 1)
        self._entries["arlo_user"] = entry(left, "arlo_user", 2)
        label(left, "Contraseña de Arlo", 3)
        self._entries["arlo_password"] = entry(left, "arlo_password", 4, show="●")
        label(left, "El código de verificación llega por", 5)
        tfa = ttk.Combobox(
            left,
            textvariable=self._vars["tfa"],
            values=("email", "sms"),
            state="readonly",
            width=15,
            font=("Segoe UI", 10),
        )
        tfa.grid(row=6, column=0, sticky="ew", ipady=4)
        tk.Label(
            left,
            text="La app pedirá el código en una ventana segura.",
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI", 9),
            anchor="w",
            wraplength=360,
        ).grid(row=7, column=0, sticky="ew", pady=(4, 0))

        heading(right, "Cámara genérica", 0)
        label(right, "Nombre visible", 1)
        self._entries["generic_name"] = entry(right, "generic_name", 2)
        label(right, "Dirección de video local", 3)
        self._entries["generic_url"] = entry(right, "generic_url", 4)
        tk.Label(
            right,
            text=f"Modo ligero detectado: {GENERIC_LIGHT_URL}",
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI", 8),
            anchor="w",
        ).grid(row=5, column=0, sticky="ew", pady=(2, 0))
        label(right, "Usuario de la cámara (opcional)", 6)
        self._entries["generic_user"] = entry(right, "generic_user", 7)
        label(right, "Contraseña de la cámara (opcional)", 8)
        self._entries["generic_password"] = entry(
            right, "generic_password", 9, show="●"
        )

        options = tk.Frame(card, bg=SURFACE)
        options.grid(row=3, column=0, sticky="ew", padx=14, pady=(12, 4))
        tk.Checkbutton(
            options,
            text="Guardar contraseñas en Windows",
            variable=self._vars["save_passwords"],
            bg=SURFACE,
            fg=TEXT,
            activebackground=SURFACE,
            activeforeground=TEXT,
            selectcolor="#0E1620",
            font=("Segoe UI", 9),
        ).pack(side="left")
        tk.Checkbutton(
            options,
            text="Abrir en pantalla completa",
            variable=self._vars["fullscreen"],
            bg=SURFACE,
            fg=TEXT,
            activebackground=SURFACE,
            activeforeground=TEXT,
            selectcolor="#0E1620",
            font=("Segoe UI", 9),
        ).pack(side="right")

        buttons = tk.Frame(card, bg=SURFACE)
        buttons.grid(row=4, column=0, sticky="e", padx=18, pady=(8, 18))
        tk.Button(
            buttons,
            text="Cancelar",
            command=self._cancel,
            bg=SURFACE_2,
            fg=TEXT,
            activebackground=BORDER,
            activeforeground=TEXT,
            relief="flat",
            cursor="hand2",
            font=("Segoe UI Semibold", 10),
            padx=18,
            pady=8,
        ).pack(side="left", padx=(0, 8))
        tk.Button(
            buttons,
            text="Guardar y conectar",
            command=self._save,
            bg=ACCENT,
            fg="#052319",
            activebackground="#59E0AD",
            activeforeground="#052319",
            relief="flat",
            cursor="hand2",
            font=("Segoe UI Semibold", 10),
            padx=18,
            pady=8,
        ).pack(side="left")

    def _save(self) -> None:
        arlo_user = str(self._vars["arlo_user"].get()).strip()
        arlo_password = str(self._vars["arlo_password"].get())
        generic_url_raw = str(self._vars["generic_url"].get()).strip()
        generic_user = str(self._vars["generic_user"].get()).strip()
        generic_password = str(self._vars["generic_password"].get())

        clean_url, pasted_user, pasted_password = strip_url_credentials(generic_url_raw)
        if clean_url in GENERIC_MAIN_URLS:
            clean_url = GENERIC_LIGHT_URL
        generic_user = generic_user or pasted_user
        generic_password = generic_password or pasted_password

        if clean_url:
            parsed = urlsplit(clean_url)
            if parsed.scheme.lower() not in {"rtsp", "rtsps", "http", "https"}:
                messagebox.showerror(
                    "Dirección no válida",
                    "La cámara genérica debe usar RTSP, RTSPS, HTTP o HTTPS.",
                    parent=self,
                )
                return
            if not parsed.hostname:
                messagebox.showerror(
                    "Dirección incompleta",
                    "Escriba una dirección con IP o nombre de red.",
                    parent=self,
                )
                return

        if not arlo_user and not clean_url:
            messagebox.showerror(
                "Falta configuración",
                "Configure la cuenta Arlo, la cámara genérica o ambas.",
                parent=self,
            )
            return
        if arlo_user and not arlo_password:
            messagebox.showerror(
                "Falta contraseña",
                "Escriba la contraseña de la cuenta Arlo.",
                parent=self,
            )
            return

        save_passwords = bool(self._vars["save_passwords"].get())
        config = AppConfig(
            arlo=ArloConfig(
                usuario=arlo_user,
                segundo_factor=str(self._vars["tfa"].get()).lower(),
                nombres_camaras=self._previous_config.arlo.nombres_camaras,
            ),
            generica=GenericaConfig(
                nombre=str(self._vars["generic_name"].get()).strip()
                or "Cámara genérica",
                url=clean_url,
                usuario=generic_user,
            ),
            guardar_claves=save_passwords,
            pantalla_completa=bool(self._vars["fullscreen"].get()),
            vista=self._previous_config.vista,
            camara_principal=self._previous_config.camara_principal,
            camara_secundaria=self._previous_config.camara_secundaria,
            audio_habilitado=self._previous_config.audio_habilitado,
            privacidad=self._previous_config.privacidad,
            android_codigo=self._previous_config.android_codigo,
            android_puerto=self._previous_config.android_puerto,
        )

        old = self._previous_config
        if save_passwords:
            if arlo_user and arlo_password:
                self._secrets.set("arlo", arlo_user, arlo_password)
            if clean_url and generic_password:
                self._secrets.set("generica", clean_url, generic_password)
        else:
            self._secrets.delete("arlo", old.arlo.usuario)
            self._secrets.delete("arlo", arlo_user)
            self._secrets.delete("generica", old.generica.url)
            self._secrets.delete("generica", clean_url)

        save_config(config)
        self.result = LoginValues(config, arlo_password, generic_password)
        self.destroy()

    def _cancel(self) -> None:
        self.result = None
        self.destroy()


class CameraPanel(tk.Frame):
    def __init__(
        self,
        parent: tk.Misc,
        title: str,
        fullscreen_action: Callable[[], None] | None = None,
    ) -> None:
        super().__init__(
            parent,
            bg=SURFACE,
            highlightthickness=1,
            highlightbackground=BORDER,
        )
        self._photo: ImageTk.PhotoImage | None = None
        self._last_frame: np.ndarray | None = None

        self._header = tk.Frame(self, bg=SURFACE_2, height=48)
        self._header.pack(fill="x")
        self._header.pack_propagate(False)
        self._dot = tk.Label(
            self._header,
            text="●",
            bg=SURFACE_2,
            fg=MUTED,
            font=("Segoe UI", 10),
        )
        self._dot.pack(side="left", padx=(14, 7))
        self._title = tk.Label(
            self._header,
            text=title,
            bg=SURFACE_2,
            fg=TEXT,
            font=("Segoe UI Semibold", 11),
            anchor="w",
        )
        self._title.pack(side="left")
        self._detail = tk.Label(
            self._header,
            text="",
            bg=SURFACE_2,
            fg=MUTED,
            font=("Segoe UI", 8),
            anchor="w",
        )
        self._detail.pack(side="left", padx=(10, 0))
        self._fullscreen_button = tk.Button(
            self._header,
            text="⛶",
            command=fullscreen_action,
            bg=SURFACE_2,
            fg=TEXT,
            activebackground=BORDER,
            activeforeground=TEXT,
            relief="flat",
            cursor="hand2",
            font=("Segoe UI Semibold", 13),
            width=3,
        )
        self._fullscreen_button.pack(side="right", padx=(2, 6), pady=5)
        self._status = tk.Label(
            self._header,
            text="SIN CONECTAR",
            bg=SURFACE_2,
            fg=MUTED,
            font=("Segoe UI Semibold", 8),
            anchor="e",
        )
        self._status.pack(side="right", padx=(8, 4))

        self._display = tk.Label(
            self,
            text="Esperando configuración",
            bg="#05080D",
            fg=MUTED,
            font=("Segoe UI", 12),
            anchor="center",
        )
        self._display.pack(fill="both", expand=True)
        self._display.bind("<Configure>", self._on_resize)
        if fullscreen_action is not None:
            self._display.bind("<Double-Button-1>", lambda _event: fullscreen_action())

    def set_focus_mode(self, enabled: bool) -> None:
        if enabled:
            self._header.pack_forget()
        elif not self._header.winfo_manager():
            self._header.pack(fill="x", before=self._display)

    def configure_camera(self, title: str, detail: str = "") -> None:
        self._title.configure(text=title)
        self._detail.configure(text=detail)

    def set_status(self, level: str, message: str) -> None:
        color = {
            "online": ACCENT,
            "waiting": WARNING,
            "error": DANGER,
            "off": MUTED,
        }.get(level, MUTED)
        self._dot.configure(fg=color)
        self._status.configure(text=message.upper(), fg=color)
        if self._last_frame is None:
            self._display.configure(text=message)

    def set_frame(self, frame: np.ndarray) -> None:
        self._last_frame = frame
        self._render(frame)

    def clear(self, text: str = "Esperando conexión") -> None:
        self._last_frame = None
        self._photo = None
        self._display.configure(image="", text=text)

    def _on_resize(self, _event: tk.Event[Any]) -> None:
        if self._last_frame is not None:
            self.after_idle(lambda: self._render(self._last_frame))

    def _render(self, frame: np.ndarray) -> None:
        width = self._display.winfo_width()
        height = self._display.winfo_height()
        if width < 60 or height < 60:
            return
        rgb = frame[:, :, ::-1]
        image = Image.fromarray(rgb)
        image = ImageOps.contain(
            image,
            (width, height),
            method=Image.Resampling.BILINEAR,
        )
        canvas = Image.new("RGB", (width, height), "#05080D")
        x = (width - image.width) // 2
        y = (height - image.height) // 2
        canvas.paste(image, (x, y))
        self._photo = ImageTk.PhotoImage(canvas)
        self._display.configure(image=self._photo, text="")


class SecurityApp:
    def __init__(
        self,
        root: tk.Tk,
        demo: bool = False,
        close_after: float = 0,
        screenshot_path: str = "",
    ) -> None:
        self.root = root
        self.demo = demo
        self.close_after = close_after
        self.config = load_config()
        self.secrets = SecretStore()
        if self.config.generica.url == GENERIC_LIGHT_URL:
            current_secret = self.secrets.get("generica", GENERIC_LIGHT_URL)
            if not current_secret:
                for legacy_url in GENERIC_MAIN_URLS:
                    legacy_secret = self.secrets.get("generica", legacy_url)
                    if legacy_secret:
                        self.secrets.set(
                            "generica",
                            GENERIC_LIGHT_URL,
                            legacy_secret,
                        )
                        break
            save_config(self.config)
        self.arlo = ArloSession()
        self.events: queue.Queue[tuple[str, Any]] = queue.Queue()
        self.workers: dict[str, VideoWorker | DemoWorker] = {}
        self.stream_specs: dict[str, StreamSpec] = {}
        self.demo_titles: dict[str, str] = {}
        self.audio_worker: AudioWorker | None = None
        self.ptz: OnvifPtzController | None = None
        self._privacy_active = bool(self.config.privacidad)
        self._reactivate_arlo_pending = False
        self._camera_fullscreen_key: str | None = None
        self._global_fullscreen = False
        self.android_bridge = AndroidBridge(
            lambda: self.stream_specs,
            self.config.android_codigo,
            self.config.android_puerto,
            privacy=self._privacy_active,
            privacy_callback=lambda enabled: self.events.put(
                ("privacy_request", bool(enabled))
            ),
        )
        self._ptz_queue: queue.Queue[tuple[str, tuple[float, ...]] | None] = queue.Queue()
        self._last_video_status: dict[str, tuple[str, str]] = {}
        self._closing = threading.Event()
        self._connecting = False
        self._arlo_password = ""
        self._generic_password = ""
        self.camera_labels = {
            "arlo1": (
                f"Arlo · {self.config.arlo.nombres_camaras[0]}"
                if len(self.config.arlo.nombres_camaras) > 0
                else "Arlo 1"
            ),
            "arlo2": (
                f"Arlo · {self.config.arlo.nombres_camaras[1]}"
                if len(self.config.arlo.nombres_camaras) > 1
                else "Arlo 2"
            ),
            "generica": f"Genérica · {self.config.generica.nombre}",
        }
        self._view_var = tk.StringVar(
            value=VIEW_LABELS.get(self.config.vista, VIEW_LABELS["three"])
        )
        self._primary_var = tk.StringVar(
            value=self.camera_labels[self.config.camara_principal]
        )
        self._secondary_var = tk.StringVar(
            value=self.camera_labels[self.config.camara_secundaria]
        )
        self._audio_var = tk.BooleanVar(value=self.config.audio_habilitado)

        self.root.title(f"{APP_NAME} · Hecho por {AUTHOR}")
        self.root.configure(bg=BG)
        screen_width = self.root.winfo_screenwidth()
        screen_height = self.root.winfo_screenheight()
        width = min(1360, max(960, int(screen_width * 0.92)))
        height = min(850, max(600, int(screen_height * 0.86)))
        x = max(0, (screen_width - width) // 2)
        y = max(0, (screen_height - height) // 2)
        self.root.minsize(900, 580)
        self.root.geometry(f"{width}x{height}+{x}+{y}")
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.bind("<F11>", lambda _event: self.toggle_fullscreen())
        self.root.bind("<Escape>", self._handle_escape)

        self._build_ui()
        bridge_address = self.android_bridge.start()
        self._android_status_label.configure(
            text=(
                f"ANDROID {bridge_address} · CÓDIGO {self.config.android_codigo}"
                if bridge_address
                else "ANDROID: PUENTE NO DISPONIBLE"
            ),
            fg=ACCENT if bridge_address else DANGER,
        )
        self._apply_privacy_ui()
        if self._privacy_active:
            self._set_global(
                "PRIVACIDAD ACTIVA · TODAS LAS VISTAS DETENIDAS",
                DANGER,
            )
        threading.Thread(
            target=self._ptz_loop,
            name="Control-PTZ",
            daemon=True,
        ).start()
        self.root.after(100, self._poll)
        self.root.after(100, self._clock)

        if self.demo:
            self.root.after(200, self.start_demo)
        elif self.config.completa:
            self.root.after(250, self.connect)
        else:
            self.root.after(350, self.open_settings)

        if self.config.pantalla_completa:
            self.root.after(500, lambda: self.set_fullscreen(True))
        if close_after > 0:
            self.root.after(int(close_after * 1000), self.close)
        if screenshot_path:
            self.root.after(1800, lambda: self._capture(screenshot_path))

    def _build_ui(self) -> None:
        self._top = tk.Frame(self.root, bg=BG, height=72)
        self._top.pack(fill="x", padx=18, pady=(14, 10))
        self._top.pack_propagate(False)

        mark = tk.Canvas(self._top, width=42, height=42, bg=BG, highlightthickness=0)
        mark.pack(side="left", padx=(2, 10), pady=12)
        mark.create_oval(3, 3, 39, 39, fill=ACCENT, outline="")
        mark.create_oval(13, 13, 29, 29, fill=BG, outline="")
        mark.create_oval(18, 18, 24, 24, fill=ACCENT, outline="")

        title_box = tk.Frame(self._top, bg=BG)
        title_box.pack(side="left", pady=7)
        tk.Label(
            title_box,
            text="CÁMARAS TOAPANTA · MODO LIGERO",
            bg=BG,
            fg=TEXT,
            font=("Segoe UI Semibold", 16),
            anchor="w",
        ).pack(anchor="w")
        self._global_status = tk.Label(
            title_box,
            text=f"Hecho por {AUTHOR} · Modo ligero listo",
            bg=BG,
            fg=MUTED,
            font=("Segoe UI", 9),
            anchor="w",
        )
        self._global_status.pack(anchor="w")

        self._clock_label = tk.Label(
            self._top,
            text="",
            bg=BG,
            fg=TEXT,
            font=("Segoe UI Semibold", 13),
        )
        self._clock_label.pack(side="right", padx=(18, 4))
        tk.Button(
            self._top,
            text="Pantalla completa",
            command=self.toggle_fullscreen,
            bg=SURFACE_2,
            fg=TEXT,
            activebackground=BORDER,
            activeforeground=TEXT,
            relief="flat",
            cursor="hand2",
            font=("Segoe UI Semibold", 9),
            padx=13,
            pady=7,
        ).pack(side="right", padx=5)
        tk.Button(
            self._top,
            text="Configurar",
            command=self.open_settings,
            bg=SURFACE_2,
            fg=TEXT,
            activebackground=BORDER,
            activeforeground=TEXT,
            relief="flat",
            cursor="hand2",
            font=("Segoe UI Semibold", 9),
            padx=13,
            pady=7,
        ).pack(side="right", padx=5)
        self._privacy_button = tk.Button(
            self._top,
            text="Detener todo",
            command=self._toggle_privacy,
            bg=DANGER,
            fg="#2A0710",
            activebackground="#FDA4AF",
            activeforeground="#2A0710",
            relief="flat",
            cursor="hand2",
            font=("Segoe UI Semibold", 9),
            padx=13,
            pady=7,
        )
        self._privacy_button.pack(side="right", padx=5)
        tk.Button(
            self._top,
            text="Reconectar",
            command=self.connect,
            bg=ACCENT,
            fg="#052319",
            activebackground="#59E0AD",
            activeforeground="#052319",
            relief="flat",
            cursor="hand2",
            font=("Segoe UI Semibold", 9),
            padx=13,
            pady=7,
        ).pack(side="right", padx=5)

        self._toolbar = tk.Frame(
            self.root,
            bg=SURFACE,
            highlightthickness=1,
            highlightbackground=BORDER,
        )
        self._toolbar.pack(fill="x", padx=18, pady=(0, 10))
        view_controls = tk.Frame(self._toolbar, bg=SURFACE)
        view_controls.pack(fill="x")

        tk.Label(
            view_controls,
            text="Vista",
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI", 9),
        ).pack(side="left", padx=(12, 5), pady=9)
        self._view_combo = ttk.Combobox(
            view_controls,
            textvariable=self._view_var,
            values=tuple(VIEW_LABELS.values()),
            state="readonly",
            width=10,
            font=("Segoe UI", 9),
        )
        self._view_combo.pack(side="left", pady=9)
        self._view_combo.bind("<<ComboboxSelected>>", self._on_view_change)

        tk.Label(
            view_controls,
            text="Principal",
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI", 9),
        ).pack(side="left", padx=(12, 5))
        self._primary_combo = ttk.Combobox(
            view_controls,
            textvariable=self._primary_var,
            values=tuple(self.camera_labels.values()),
            state="readonly",
            width=20,
            font=("Segoe UI", 9),
        )
        self._primary_combo.pack(side="left", pady=9)
        self._primary_combo.bind("<<ComboboxSelected>>", self._on_camera_selection)

        tk.Label(
            view_controls,
            text="Segunda",
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI", 9),
        ).pack(side="left", padx=(12, 5))
        self._secondary_combo = ttk.Combobox(
            view_controls,
            textvariable=self._secondary_var,
            values=tuple(self.camera_labels.values()),
            state="readonly",
            width=20,
            font=("Segoe UI", 9),
        )
        self._secondary_combo.pack(side="left", pady=9)
        self._secondary_combo.bind("<<ComboboxSelected>>", self._on_camera_selection)

        self._audio_check = tk.Checkbutton(
            view_controls,
            text="🔊 Audio principal",
            variable=self._audio_var,
            command=self._on_audio_toggle,
            bg=SURFACE,
            fg=TEXT,
            activebackground=SURFACE,
            activeforeground=TEXT,
            selectcolor="#0E1620",
            font=("Segoe UI", 9),
        )
        self._audio_check.pack(side="left", padx=(12, 4))
        self._audio_status_label = tk.Label(
            view_controls,
            text="APAGADO",
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI Semibold", 8),
        )
        self._audio_status_label.pack(side="left", padx=(0, 10))

        ptz = tk.Frame(self._toolbar, bg=SURFACE)
        ptz.pack(fill="x", padx=10, pady=(0, 7))
        tk.Label(
            ptz,
            text="Mover genérica",
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI", 9),
        ).pack(side="left", padx=(0, 5))

        self._ptz_buttons: list[tk.Button] = []

        def ptz_button(text: str) -> tk.Button:
            button = tk.Button(
                ptz,
                text=text,
                bg=SURFACE_2,
                fg=TEXT,
                activebackground=BORDER,
                activeforeground=TEXT,
                disabledforeground="#536170",
                relief="flat",
                cursor="hand2",
                font=("Segoe UI Semibold", 10),
                width=3,
                pady=2,
                state="disabled",
            )
            button.pack(side="left", padx=2)
            self._ptz_buttons.append(button)
            return button

        directions = (
            ("←", -0.35, 0.0),
            ("↑", 0.0, 0.35),
            ("↓", 0.0, -0.35),
            ("→", 0.35, 0.0),
        )
        for text, x_speed, y_speed in directions:
            button = ptz_button(text)
            button.bind(
                "<ButtonPress-1>",
                lambda _event, x=x_speed, y=y_speed: self._ptz_move(x, y),
            )
            button.bind("<ButtonRelease-1>", lambda _event: self._ptz_stop())
        home_button = ptz_button("⌂")
        home_button.configure(command=self._ptz_home)
        stop_button = ptz_button("■")
        stop_button.configure(command=self._ptz_stop)
        self._ptz_status_label = tk.Label(
            ptz,
            text="NO DISPONIBLE",
            bg=SURFACE,
            fg=MUTED,
            font=("Segoe UI Semibold", 8),
        )
        self._ptz_status_label.pack(side="left", padx=(6, 0))
        self._android_status_label = tk.Label(
            ptz,
            text="ANDROID: INICIANDO…",
            bg=SURFACE,
            fg=WARNING,
            font=("Segoe UI Semibold", 8),
        )
        self._android_status_label.pack(side="right", padx=(14, 2))

        self._content = tk.Frame(self.root, bg=BG)
        self._content.pack(fill="both", expand=True, padx=18, pady=(0, 18))
        self._content.grid_propagate(False)
        self._content.grid_columnconfigure(0, weight=1, uniform="camera")
        self._content.grid_columnconfigure(1, weight=1, uniform="camera")
        self._content.grid_rowconfigure(0, weight=1, uniform="camera")
        self._content.grid_rowconfigure(1, weight=1, uniform="camera")

        self.panels: dict[str, CameraPanel] = {
            "arlo1": CameraPanel(
                self._content,
                "Arlo 1",
                lambda: self.set_camera_fullscreen("arlo1"),
            ),
            "arlo2": CameraPanel(
                self._content,
                "Arlo 2",
                lambda: self.set_camera_fullscreen("arlo2"),
            ),
            "generica": CameraPanel(
                self._content,
                "Cámara genérica",
                lambda: self.set_camera_fullscreen("generica"),
            ),
        }
        self._apply_layout()

    def _key_from_label(self, label: str, fallback: str) -> str:
        for key, current_label in self.camera_labels.items():
            if current_label == label:
                return key
        return fallback

    def _refresh_camera_choices(self) -> None:
        values = tuple(self.camera_labels[key] for key in CAMERA_KEYS)
        self._primary_combo.configure(values=values)
        self._secondary_combo.configure(values=values)
        self._primary_var.set(self.camera_labels[self.config.camara_principal])
        self._secondary_var.set(self.camera_labels[self.config.camara_secundaria])

    def _normalize_selections(self) -> None:
        if (
            self.config.vista == "dual"
            and self.config.camara_principal == self.config.camara_secundaria
        ):
            self.config.camara_secundaria = next(
                key for key in CAMERA_KEYS if key != self.config.camara_principal
            )
            self._secondary_var.set(
                self.camera_labels[self.config.camara_secundaria]
            )

    def _on_view_change(self, _event: tk.Event[Any] | None = None) -> None:
        reverse = {label: key for key, label in VIEW_LABELS.items()}
        self.config.vista = reverse.get(self._view_var.get(), "three")
        self._normalize_selections()
        save_config(self.config)
        self._apply_layout()
        self._sync_visible_workers()
        self._sync_audio()

    def _on_camera_selection(self, _event: tk.Event[Any] | None = None) -> None:
        self.config.camara_principal = self._key_from_label(
            self._primary_var.get(), self.config.camara_principal
        )
        self.config.camara_secundaria = self._key_from_label(
            self._secondary_var.get(), self.config.camara_secundaria
        )
        self._normalize_selections()
        save_config(self.config)
        self._apply_layout()
        self._sync_visible_workers()
        self._sync_audio()

    def _toggle_privacy(self) -> None:
        enabled = not self._privacy_active
        if enabled:
            confirmed = messagebox.askyesno(
                "Detener todas las cámaras",
                (
                    "Se cerrará el video en PC, Android y los enlaces VLC.\n\n"
                    "Las dos cámaras Arlo también se apagarán y dejarán de verse "
                    "desde la aplicación oficial hasta que pulse Reactivar.\n\n"
                    "La cámara genérica quedará bloqueada en Cámaras Toapanta. "
                    "Su aplicación de fabricante solo puede bloquearse si ese "
                    "modelo ofrece apagado remoto.\n\n¿Continuar?"
                ),
                parent=self.root,
            )
            if not confirmed:
                return
        self._set_privacy(enabled)

    def _set_privacy(self, enabled: bool) -> None:
        enabled = bool(enabled)
        self._privacy_active = enabled
        self.config.privacidad = enabled
        self.android_bridge.set_privacy(enabled)
        if not self.demo:
            save_config(self.config)

        if enabled:
            self._reactivate_arlo_pending = False
            self._stop_audio(wait=False)
            self._stop_workers(wait=False)
            for panel in self.panels.values():
                panel.clear("Privacidad activada")
                panel.set_status("off", "DETENIDA")
            self._set_ptz_available(False)
            self._set_global("PRIVACIDAD ACTIVA · TODAS LAS VISTAS DETENIDAS", DANGER)
            if self.arlo.connected:
                self._start_arlo_privacy_change(True)
        else:
            self._set_ptz_available(self.ptz is not None)
            self._set_global("Reactivando cámaras…", WARNING)
            if self.arlo.connected:
                self._reactivate_arlo_pending = False
                self._start_arlo_privacy_change(False)
            else:
                self._reactivate_arlo_pending = True
            if self.stream_specs:
                self._sync_visible_workers()
                self._sync_audio()
            elif self.config.completa and not self._connecting:
                self.connect()
        self._apply_privacy_ui()

    def _apply_privacy_ui(self) -> None:
        if not hasattr(self, "_privacy_button"):
            return
        if self._privacy_active:
            self._privacy_button.configure(
                text="Reactivar cámaras",
                bg=ACCENT,
                fg="#052319",
                activebackground="#59E0AD",
                activeforeground="#052319",
            )
        else:
            self._privacy_button.configure(
                text="Detener todo",
                bg=DANGER,
                fg="#2A0710",
                activebackground="#FDA4AF",
                activeforeground="#2A0710",
            )

    def _start_arlo_privacy_change(self, enabled: bool) -> None:
        def change() -> None:
            changed, errors = self.arlo.set_privacy(enabled)
            self.events.put(
                ("arlo_privacy_result", (enabled, changed, errors))
            )

        threading.Thread(
            target=change,
            name="Privacidad-Arlo",
            daemon=True,
        ).start()

    def _visible_keys(self) -> tuple[str, ...]:
        if self._camera_fullscreen_key is not None:
            return (self._camera_fullscreen_key,)
        if self.config.vista == "single":
            return (self.config.camara_principal,)
        if self.config.vista == "dual":
            self._normalize_selections()
            return (
                self.config.camara_principal,
                self.config.camara_secundaria,
            )
        return CAMERA_KEYS

    def _apply_layout(self) -> None:
        for panel in self.panels.values():
            panel.grid_forget()

        visible = self._visible_keys()
        if self._camera_fullscreen_key is not None:
            self._content.grid_rowconfigure(0, weight=1)
            self._content.grid_rowconfigure(1, weight=0)
            self.panels[visible[0]].grid(
                row=0,
                column=0,
                columnspan=2,
                rowspan=2,
                sticky="nsew",
            )
            self._secondary_combo.configure(state="disabled")
            return
        if self.config.vista == "single":
            self._content.grid_rowconfigure(0, weight=1)
            self._content.grid_rowconfigure(1, weight=0)
            self.panels[visible[0]].grid(
                row=0,
                column=0,
                columnspan=2,
                rowspan=2,
                sticky="nsew",
            )
            self._secondary_combo.configure(state="disabled")
        elif self.config.vista == "dual":
            self._content.grid_rowconfigure(0, weight=1)
            self._content.grid_rowconfigure(1, weight=0)
            self.panels[visible[0]].grid(
                row=0,
                column=0,
                rowspan=2,
                sticky="nsew",
                padx=(0, 6),
            )
            self.panels[visible[1]].grid(
                row=0,
                column=1,
                rowspan=2,
                sticky="nsew",
                padx=(6, 0),
            )
            self._secondary_combo.configure(state="readonly")
        else:
            self._content.grid_rowconfigure(0, weight=1)
            self._content.grid_rowconfigure(1, weight=1)
            self.panels["arlo1"].grid(
                row=0, column=0, sticky="nsew", padx=(0, 6), pady=(0, 6)
            )
            self.panels["arlo2"].grid(
                row=0, column=1, sticky="nsew", padx=(6, 0), pady=(0, 6)
            )
            self.panels["generica"].grid(
                row=1, column=0, columnspan=2, sticky="nsew", pady=(6, 0)
            )
            self._secondary_combo.configure(state="readonly")

    def open_settings(self) -> None:
        if self.demo:
            messagebox.showinfo(
                "Modo demostración",
                "Cierre la demostración y abra CAMARAS_TOAPANTA.bat para configurar cámaras.",
                parent=self.root,
            )
            return
        dialog = SetupDialog(self.root, self.config, self.secrets)
        self.root.wait_window(dialog)
        if dialog.result is None:
            return
        self.config = dialog.result.config
        self._arlo_password = dialog.result.arlo_password
        self._generic_password = dialog.result.generic_password
        self.connect()

    def _passwords_for_session(self) -> bool:
        if self.config.arlo.usuario and not self._arlo_password:
            self._arlo_password = self.secrets.get("arlo", self.config.arlo.usuario)
            if not self._arlo_password:
                value = simpledialog.askstring(
                    "Contraseña de Arlo",
                    f"Contraseña para {self.config.arlo.usuario}:",
                    parent=self.root,
                    show="●",
                )
                if value is None:
                    return False
                self._arlo_password = value

        if (
            self.config.generica.url
            and self.config.generica.usuario
            and not self._generic_password
        ):
            self._generic_password = self.secrets.get(
                "generica", self.config.generica.url
            )
            if not self._generic_password:
                value = simpledialog.askstring(
                    "Contraseña de la cámara",
                    f"Contraseña para {self.config.generica.usuario}:",
                    parent=self.root,
                    show="●",
                )
                if value is None:
                    return False
                self._generic_password = value
        return True

    def connect(self) -> None:
        if self.demo:
            self.start_demo()
            return
        if self._connecting:
            return
        if not self.config.completa:
            self.open_settings()
            return
        if not self._passwords_for_session():
            return

        self._connecting = True
        self._stop_audio()
        self._stop_workers(wait=False)
        self.stream_specs.clear()
        for panel in self.panels.values():
            panel.clear("Preparando conexión…")
            panel.set_status("waiting", "Preparando")
        self._set_global("Conectando cámaras…", WARNING)

        if self.config.generica.url:
            generic_host = urlsplit(self.config.generica.url).hostname or ""
            source = lambda: add_url_credentials(
                self.config.generica.url,
                self.config.generica.usuario,
                self._generic_password,
            )
            spec = StreamSpec(
                key="generica",
                title=self.config.generica.nombre,
                source_factory=source,
                detail=f"{generic_host} · RTSP ligero · PTZ",
            )
            self.stream_specs[spec.key] = spec
            self.camera_labels["generica"] = f"Genérica · {spec.title}"
            self.panels["generica"].configure_camera(spec.title, spec.detail)
            try:
                self.ptz = OnvifPtzController(generic_host)
                self._set_ptz_available(not self._privacy_active)
            except ValueError:
                self.ptz = None
                self._set_ptz_available(False)
        else:
            self.ptz = None
            self._set_ptz_available(False)
            self.panels["generica"].clear("Falta configurar RTSP/ONVIF")
            self.panels["generica"].set_status("off", "Sin configurar")
        self._refresh_camera_choices()
        self._sync_visible_workers()
        self._sync_audio()

        if self.config.arlo.usuario:
            thread = threading.Thread(
                target=self._connect_arlo,
                name="Conexion-Arlo",
                daemon=True,
            )
            thread.start()
        else:
            self.panels["arlo1"].clear("Falta configurar Arlo")
            self.panels["arlo2"].clear("Falta configurar Arlo")
            self.panels["arlo1"].set_status("off", "Sin configurar")
            self.panels["arlo2"].set_status("off", "Sin configurar")
            self._connecting = False
            self._set_global("Cámara genérica iniciada", ACCENT)
            self._sync_audio()

    def _connect_arlo(self) -> None:
        try:
            infos = self.arlo.connect(
                self.config.arlo,
                self._arlo_password,
                self._ask_second_factor_from_worker,
                lambda message: self.events.put(("global", (message, WARNING))),
            )
            specs = self.arlo.build_streams(self.config.arlo.nombres_camaras)
            self.events.put(("arlo_ready", (infos, specs)))
        except Exception as exc:
            message = self._safe_error(exc)
            self.events.put(("arlo_error", message))

    @staticmethod
    def _safe_error(exc: Exception) -> str:
        text = str(exc).strip() or exc.__class__.__name__
        text = re.sub(r"(?i)([a-z][a-z0-9+.-]*://)[^/@\s]+@", r"\1", text)
        return text[:300]

    def _ask_second_factor_from_worker(self, prompt: str) -> str:
        request = SecondFactorRequest(prompt, threading.Event())
        self.events.put(("second_factor", request))
        while not self._closing.is_set():
            if request.event.wait(0.2):
                return request.result
        return ""

    def _start_stream_spec(self, spec: StreamSpec) -> None:
        if spec.key in self.workers:
            return
        panel = self.panels[spec.key]
        panel.configure_camera(spec.title, spec.detail)
        worker = VideoWorker(spec.key, spec.source_factory, self._video_status)
        self.workers[spec.key] = worker
        worker.start()

    def _video_status(self, status: VideoStatus) -> None:
        current = (status.level, status.message)
        if self._last_video_status.get(status.key) != current:
            LOGGER.info("Cámara %s: %s - %s", status.key, status.level, status.message)
            self._last_video_status[status.key] = current
        self.events.put(("video_status", status))

    def _sync_visible_workers(self) -> None:
        if self._closing.is_set():
            return
        if self._privacy_active:
            self._stop_workers(wait=False)
            for panel in self.panels.values():
                panel.clear("Privacidad activada")
                panel.set_status("off", "DETENIDA")
            return
        visible = set(self._visible_keys())
        for key in tuple(self.workers):
            if key not in visible:
                worker = self.workers.pop(key)
                worker.request_stop()
                self.panels[key].clear("Vista pausada para ahorrar recursos")
                self.panels[key].set_status("off", "En pausa")

        for key in visible:
            if key in self.workers:
                continue
            if self.demo and key in self.demo_titles:
                worker = DemoWorker(key, self.demo_titles[key], self._video_status)
                self.workers[key] = worker
                worker.start()
            elif key in self.stream_specs:
                self._start_stream_spec(self.stream_specs[key])

    def _handle_arlo_ready(
        self,
        infos: list[ArloCameraInfo],
        specs: list[StreamSpec],
    ) -> None:
        if specs:
            self.config.arlo.nombres_camaras = [spec.title for spec in specs]
            save_config(self.config)
        for spec in specs:
            self.stream_specs[spec.key] = spec
            self.camera_labels[spec.key] = f"Arlo · {spec.title}"
            self.panels[spec.key].configure_camera(spec.title, spec.detail)
        for index in range(len(specs), 2):
            key = f"arlo{index + 1}"
            self.panels[key].clear("No se encontró otra cámara Arlo")
            self.panels[key].set_status("error", "No encontrada")

        self._refresh_camera_choices()
        self._sync_visible_workers()
        self._sync_audio()
        details = ", ".join(
            f"{info.name} ({' / '.join(x for x in (info.model, info.hardware, info.battery) if x)})"
            for info in infos[:2]
        )
        self._set_global(
            f"Arlo conectado · {details}" if details else "Arlo conectado",
            ACCENT,
        )
        self._connecting = False
        if self._privacy_active:
            self._start_arlo_privacy_change(True)
        elif self._reactivate_arlo_pending:
            self._reactivate_arlo_pending = False
            self._start_arlo_privacy_change(False)

    def _on_audio_toggle(self) -> None:
        self.config.audio_habilitado = bool(self._audio_var.get())
        if not self.demo:
            save_config(self.config)
        self._sync_audio()

    def _audio_status(self, status: AudioStatus) -> None:
        self.events.put(("audio_status", status))

    def _stop_audio(self, wait: bool = False) -> None:
        worker, self.audio_worker = self.audio_worker, None
        if worker is None:
            return
        worker.request_stop()
        if wait:
            worker.stop()

    def _sync_audio(self) -> None:
        self._stop_audio(wait=False)
        if self._privacy_active:
            self._audio_status_label.configure(text="PRIVACIDAD", fg=DANGER)
            return
        if not self._audio_var.get():
            self._audio_status_label.configure(text="APAGADO", fg=MUTED)
            return
        if self.demo:
            self._audio_status_label.configure(text="NO DISP. EN DEMO", fg=WARNING)
            return
        key = self.config.camara_principal
        spec = self.stream_specs.get(key)
        if spec is None:
            self._audio_status_label.configure(text="ESPERANDO CÁMARA", fg=WARNING)
            return
        self._audio_status_label.configure(text="CONECTANDO", fg=WARNING)
        self.audio_worker = AudioWorker(key, spec.source_factory, self._audio_status)
        self.audio_worker.start()

    def _set_ptz_available(self, available: bool) -> None:
        state = "normal" if available else "disabled"
        for button in self._ptz_buttons:
            button.configure(state=state)
        self._ptz_status_label.configure(
            text="LISTO" if available else "NO DISPONIBLE",
            fg=ACCENT if available else MUTED,
        )

    def _ptz_move(self, x_speed: float, y_speed: float) -> None:
        if self.ptz is not None:
            self._ptz_status_label.configure(text="MOVIENDO", fg=WARNING)
            self._ptz_queue.put(("move", (x_speed, y_speed)))

    def _ptz_stop(self) -> None:
        if self.ptz is not None:
            self._ptz_queue.put(("stop", ()))

    def _ptz_home(self) -> None:
        if self.ptz is not None:
            self._ptz_status_label.configure(text="YENDO AL INICIO", fg=WARNING)
            self._ptz_queue.put(("home", ()))

    def _ptz_loop(self) -> None:
        while not self._closing.is_set():
            item = self._ptz_queue.get()
            if item is None:
                return
            action, args = item
            controller = self.ptz
            if controller is None:
                continue
            try:
                if action == "move":
                    controller.move(*args)
                    message = "MOVIENDO"
                elif action == "home":
                    controller.home()
                    message = "EN POSICIÓN INICIAL"
                else:
                    controller.stop()
                    message = "LISTO"
                self.events.put(("ptz_status", ("online", message)))
            except Exception as exc:
                LOGGER.warning("PTZ: %s", self._safe_error(exc))
                self.events.put(
                    ("ptz_status", ("error", f"PTZ: {self._safe_error(exc)}"))
                )

    def _handle_event(self, event_type: str, payload: Any) -> None:
        if event_type == "video_status":
            status: VideoStatus = payload
            panel = self.panels.get(status.key)
            if panel:
                panel.set_status(status.level, status.message)
        elif event_type == "audio_status":
            status: AudioStatus = payload
            current = self.audio_worker
            if current is not None and current.key == status.key:
                color = {
                    "online": ACCENT,
                    "waiting": WARNING,
                    "error": DANGER,
                    "off": MUTED,
                }.get(status.level, MUTED)
                self._audio_status_label.configure(
                    text=status.message.upper(),
                    fg=color,
                )
        elif event_type == "ptz_status":
            level, message = payload
            self._ptz_status_label.configure(
                text=message,
                fg=ACCENT if level == "online" else DANGER,
            )
        elif event_type == "global":
            message, color = payload
            self._set_global(message, color)
        elif event_type == "privacy_request":
            self._set_privacy(bool(payload))
        elif event_type == "arlo_privacy_result":
            enabled, changed, errors = payload
            if enabled != self._privacy_active:
                return
            if errors:
                self._set_global(
                    "Privacidad Arlo incompleta: " + ", ".join(errors),
                    DANGER,
                )
            elif enabled:
                self._set_global(
                    f"PRIVACIDAD ACTIVA · {changed} ARLO APAGADAS",
                    DANGER,
                )
            else:
                self._set_global(
                    f"Cámaras reactivadas · {changed} Arlo encendidas",
                    ACCENT,
                )
                self._sync_visible_workers()
                self._sync_audio()
        elif event_type == "second_factor":
            request: SecondFactorRequest = payload
            code = simpledialog.askstring(
                "Verificación de Arlo",
                "Escriba el código que Arlo acaba de enviar:",
                parent=self.root,
                show="●",
            )
            request.result = (code or "").strip()
            request.event.set()
        elif event_type == "arlo_ready":
            infos, specs = payload
            self._handle_arlo_ready(infos, specs)
        elif event_type == "arlo_error":
            self._connecting = False
            for key in ("arlo1", "arlo2"):
                self.panels[key].clear("No fue posible conectar con Arlo")
                self.panels[key].set_status("error", "Error de Arlo")
            self._set_global(f"Arlo: {payload}", DANGER)
            messagebox.showerror(
                "No se pudo conectar con Arlo",
                f"{payload}\n\nLa cámara genérica y las sesiones abiertas en otros "
                "dispositivos no fueron modificadas.",
                parent=self.root,
            )

    def _poll(self) -> None:
        if self._closing.is_set():
            return
        try:
            while True:
                event_type, payload = self.events.get_nowait()
                self._handle_event(event_type, payload)
        except queue.Empty:
            pass

        for key, worker in list(self.workers.items()):
            frame = worker.frames.get()
            if frame is not None and key in self.panels:
                self.panels[key].set_frame(frame)
        self.root.after(100, self._poll)

    def _clock(self) -> None:
        if self._closing.is_set():
            return
        self._clock_label.configure(
            text=datetime.now().strftime("%H:%M:%S  ·  %d %b %Y").upper()
        )
        self.root.after(1000, self._clock)

    def _set_global(self, message: str, color: str = MUTED) -> None:
        self._global_status.configure(
            text=f"Hecho por {AUTHOR} · {message}",
            fg=color,
        )

    def _capture(self, path: str) -> None:
        self.root.update_idletasks()
        x = self.root.winfo_rootx()
        y = self.root.winfo_rooty()
        width = self.root.winfo_width()
        height = self.root.winfo_height()
        ImageGrab.grab(bbox=(x, y, x + width, y + height)).save(path)

    def start_demo(self) -> None:
        self._stop_audio()
        self._stop_workers(wait=False)
        self.stream_specs.clear()
        self._set_global("Modo demostración · no usa cámaras reales", BLUE)
        definitions = (
            ("arlo1", "Arlo · Entrada", "VMC4030 · H8"),
            ("arlo2", "Arlo · Patio", "VMC4030 · H8"),
            ("generica", "Genérica · Garaje", "RTSP local"),
        )
        for key, title, detail in definitions:
            self.panels[key].configure_camera(title, detail)
            self.demo_titles[key] = title
            self.camera_labels[key] = title
        self.ptz = None
        self._set_ptz_available(False)
        self._refresh_camera_choices()
        self._sync_visible_workers()
        self._sync_audio()

    def _stop_workers(self, wait: bool = False) -> None:
        workers = list(self.workers.values())
        self.workers.clear()
        for worker in workers:
            worker.request_stop()
        if wait:
            for worker in workers:
                worker.stop()

    def set_camera_fullscreen(self, key: str) -> None:
        if key not in self.panels or self._privacy_active:
            return
        if self._camera_fullscreen_key == key and not self._global_fullscreen:
            self.set_fullscreen(False)
            return
        self._global_fullscreen = False
        self._camera_fullscreen_key = key
        self._top.pack_forget()
        self._toolbar.pack_forget()
        self._content.pack_configure(padx=0, pady=0)
        for panel_key, panel in self.panels.items():
            panel.set_focus_mode(panel_key == key)
        self.root.attributes("-fullscreen", True)
        self._apply_layout()
        self._sync_visible_workers()
        self._sync_audio()

    def set_fullscreen(self, value: bool) -> None:
        if value:
            if self._privacy_active:
                return
            self._camera_fullscreen_key = None
            self._global_fullscreen = True
            self._top.pack_forget()
            self._toolbar.pack_forget()
            self._content.pack_configure(padx=0, pady=0)
            visible = set(self._visible_keys())
            for key, panel in self.panels.items():
                panel.set_focus_mode(key in visible)
            self.root.attributes("-fullscreen", True)
            self._apply_layout()
            self._sync_visible_workers()
            self._sync_audio()
            return
        if self._camera_fullscreen_key is None and not self._global_fullscreen:
            self.root.attributes("-fullscreen", False)
            return
        self._camera_fullscreen_key = None
        self._global_fullscreen = False
        self.root.attributes("-fullscreen", False)
        self._top.pack(fill="x", padx=18, pady=(14, 10), before=self._content)
        self._toolbar.pack(fill="x", padx=18, pady=(0, 10), before=self._content)
        self._content.pack_configure(padx=18, pady=(0, 18))
        for panel in self.panels.values():
            panel.set_focus_mode(False)
        self._apply_layout()
        self._sync_visible_workers()
        self._sync_audio()

    def toggle_fullscreen(self) -> None:
        active = self._camera_fullscreen_key is not None or self._global_fullscreen
        self.set_fullscreen(not active)

    def _handle_escape(self, _event: tk.Event[Any] | None = None) -> None:
        self.set_fullscreen(False)

    def close(self) -> None:
        if self._closing.is_set():
            return
        self._closing.set()
        self._stop_audio(wait=False)
        self._stop_workers(wait=False)
        self._ptz_queue.put(None)
        self.android_bridge.stop()
        for event_type, payload in list(self._drain_events()):
            if event_type == "second_factor":
                payload.event.set()
        threading.Thread(
            target=self.arlo.stop,
            name="Cerrar-Arlo",
            daemon=True,
        ).start()
        self.root.destroy()

    def _drain_events(self) -> list[tuple[str, Any]]:
        drained: list[tuple[str, Any]] = []
        try:
            while True:
                drained.append(self.events.get_nowait())
        except queue.Empty:
            return drained


def self_test() -> int:
    clean, user, password = strip_url_credentials(
        "rtsp://usuario:secreto@192.0.2.10:554/stream1"
    )
    assert clean == "rtsp://192.0.2.10:554/stream1"
    assert user == "usuario"
    assert password == "secreto"
    rebuilt = add_url_credentials(clean, user, password)
    assert rebuilt == "rtsp://usuario:secreto@192.0.2.10:554/stream1"
    frame = np.zeros((360, 640, 3), dtype=np.uint8)
    image = Image.fromarray(frame[:, :, ::-1])
    assert image.size == (640, 360)
    print("Autoprueba correcta.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Visor flexible de cámaras de seguridad")
    parser.add_argument("--demo", action="store_true", help="muestra video sintético")
    parser.add_argument(
        "--cerrar-en",
        type=float,
        default=0,
        metavar="SEGUNDOS",
        help="cierra la interfaz automáticamente (pruebas)",
    )
    parser.add_argument("--autoprueba", action="store_true")
    parser.add_argument("--captura", default="", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    configure_logging()
    if args.autoprueba:
        return self_test()

    instance = SingleInstance()
    if instance.already_running:
        instance.close()
        notice = tk.Tk()
        notice.withdraw()
        messagebox.showinfo(
            APP_NAME,
            (
                "Cámaras Toapanta ya está abierto.\n\n"
                "Use la ventana existente para evitar dos sesiones Arlo simultáneas."
            ),
            parent=notice,
        )
        notice.destroy()
        return 0

    try:
        root = tk.Tk()
        SecurityApp(
            root,
            demo=args.demo,
            close_after=args.cerrar_en,
            screenshot_path=args.captura,
        )
        root.mainloop()
    finally:
        instance.close()
    return 0
