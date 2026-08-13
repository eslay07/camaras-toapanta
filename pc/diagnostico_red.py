from __future__ import annotations

import concurrent.futures
import ipaddress
import re
import socket
import subprocess
import sys
from dataclasses import dataclass


PORTS = (80, 81, 443, 554, 8000, 8080, 8443, 8554, 34567, 37777)
CAMERA_PORTS = {554, 8554, 8000, 34567, 37777}
ARLO_MAC = "9C-3D-CF-72-B5-B3"
GENERIC_MAC = "10-E9-06-D9-E3-6E"


@dataclass
class Device:
    ip: str
    mac: str = ""
    ports: tuple[int, ...] = ()

    @property
    def note(self) -> str:
        normalized = self.mac.upper().replace(":", "-")
        if normalized == ARLO_MAC or self.ip == "192.0.2.10":
            return "Estación base Arlo/Netgear confirmada"
        if normalized == GENERIC_MAC or self.ip == "192.0.2.10":
            return "Cámara genérica RTSP confirmada (Macro-video)"
        if 554 in self.ports or 8554 in self.ports:
            return "Posible cámara RTSP"
        if CAMERA_PORTS.intersection(self.ports):
            return "Posible cámara con servicio local"
        return ""


def local_ipv4() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("1.1.1.1", 80))
        return sock.getsockname()[0]
    finally:
        sock.close()


def warm_arp(network: ipaddress.IPv4Network) -> None:
    def touch(ip: str) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(0.12)
        try:
            sock.connect_ex((ip, 80))
        finally:
            sock.close()

    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as executor:
        list(executor.map(touch, (str(ip) for ip in network.hosts())))


def arp_devices() -> dict[str, str]:
    creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    result = subprocess.run(
        ["arp", "-a"],
        capture_output=True,
        text=True,
        errors="replace",
        timeout=15,
        creationflags=creation_flags,
        check=False,
    )
    devices: dict[str, str] = {}
    for line in result.stdout.splitlines():
        match = re.search(
            r"^\s*(\d{1,3}(?:\.\d{1,3}){3})\s+([0-9a-fA-F:-]{17})\s+",
            line,
        )
        if match:
            devices[match.group(1)] = match.group(2).upper().replace(":", "-")
    return devices


def probe(ip: str, port: int) -> int | None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(0.35)
    try:
        return port if sock.connect_ex((ip, port)) == 0 else None
    finally:
        sock.close()


def scan() -> list[Device]:
    local = ipaddress.ip_address(local_ipv4())
    network = ipaddress.ip_network(f"{local}/24", strict=False)
    warm_arp(network)
    arp = arp_devices()

    jobs: list[tuple[str, int]] = [
        (ip, port) for ip in arp for port in PORTS if ipaddress.ip_address(ip) in network
    ]
    open_by_ip: dict[str, list[int]] = {ip: [] for ip in arp}
    with concurrent.futures.ThreadPoolExecutor(max_workers=80) as executor:
        future_map = {
            executor.submit(probe, ip, port): (ip, port) for ip, port in jobs
        }
        for future in concurrent.futures.as_completed(future_map):
            ip, _ = future_map[future]
            result = future.result()
            if result is not None:
                open_by_ip.setdefault(ip, []).append(result)

    return sorted(
        (
            Device(ip=ip, mac=mac, ports=tuple(sorted(open_by_ip.get(ip, []))))
            for ip, mac in arp.items()
            if ipaddress.ip_address(ip) in network
            and not ipaddress.ip_address(ip).is_multicast
            and not ip.endswith(".255")
        ),
        key=lambda device: tuple(int(part) for part in device.ip.split(".")),
    )


def main() -> int:
    print("CÁMARAS TOAPANTA — DIAGNÓSTICO DE RED")
    print("Hecho por Jimmy Toapanta")
    print("No cambia configuraciones ni prueba contraseñas.\n")
    try:
        devices = scan()
    except Exception as exc:
        print(f"No se pudo revisar la red: {exc}")
        return 1

    print(f"{'IP':<16} {'DIRECCIÓN FÍSICA':<20} {'PUERTOS':<24} OBSERVACIÓN")
    print("-" * 96)
    for device in devices:
        ports = ", ".join(str(port) for port in device.ports) or "sin puertos estándar"
        print(f"{device.ip:<16} {device.mac:<20} {ports:<24} {device.note}")

    print("\nInterpretación:")
    print("- La estación Arlo esperada es 192.0.2.10 (Netgear, video en 554).")
    print("- Las Arlo H8 se conectan a la estación y no aparecen como IP separadas.")
    print("- La cámara genérica confirmada es 192.0.2.10 (RTSP en 554).")
    print("- Una genérica utilizable suele mostrar RTSP 554/8554 u ONVIF.")
    print("- Si no aparece, enciéndala y active RTSP/ONVIF en su aplicación original.")
    print("- No publique esta lista ni comparta contraseñas en capturas.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
