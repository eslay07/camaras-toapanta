# Cámaras Toapanta · lectura de portafolio

Proyecto personal para reunir cámaras de distintos proveedores en clientes de escritorio y Android.

**Autor:** Jimmy Toapanta · [Perfil profesional](https://www.linkedin.com/in/jimmy-toapanta-950a441b8/) · [Portafolio](https://github.com/eslay07)

**Tecnologías:** Python · Java Android · RTSP · ONVIF · integración multimedia

## Problema y solución

El uso de cámaras heterogéneas obliga a alternar aplicaciones y resolver diferencias de conexión y reproducción.

Separa la configuración y los proveedores de la presentación; contiene reproducción, reconexión y controles disponibles según la cámara.

## Evidencia para revisar el trabajo

| Archivo o componente | Qué permite revisar |
| --- | --- |
| [pc/visor_camaras/app.py](pc/visor_camaras/app.py) | Interfaz de escritorio. |
| [pc/visor_camaras/video.py](pc/visor_camaras/video.py) | Recorrido de reproducción. |
| [android/src/com/jimmytoapanta/camaras/MainActivity.java](android/src/com/jimmytoapanta/camaras/MainActivity.java) | Cliente Android. |
| [android/src/com/jimmytoapanta/camaras/ArloDirectClient.java](android/src/com/jimmytoapanta/camaras/ArloDirectClient.java) | Adaptador del proveedor. |
| [android/src/com/jimmytoapanta/camaras/SecureStore.java](android/src/com/jimmytoapanta/camaras/SecureStore.java) | Almacenamiento local de configuración sensible. |

## Estado y límites

Compatibilidad dependiente de las cámaras, firmware, red y servicios del proveedor. Las versiones se describen en el README; no se conectó a cámaras reales en esta revisión.

## Cómo evaluar el proyecto

Leer la instalación y los diagnósticos antes de proporcionar credenciales o direcciones propias. No publicar topología, imágenes privadas ni datos de acceso.

Esta actualización documental se basa en la revisión del código y de la documentación disponibles. No añade métricas de ahorro, clientes, usuarios ni resultados de producción que no estén respaldados.

## Capacidad que demuestra

Integrar proveedores y reproducción multimedia manteniendo clientes para plataformas diferentes.

[Volver al README](README.md).
