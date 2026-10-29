# Cámaras Toapanta

## Lectura rápida para evaluar el proyecto

Proyecto personal para reunir cámaras de distintos proveedores en clientes de escritorio y Android.

**Tecnologías:** Python · Java Android · RTSP · ONVIF · integración multimedia
**Estado:** Compatibilidad dependiente de las cámaras, firmware, red y servicios del proveedor. Las versiones se describen en el README; no se conectó a cámaras reales en esta revisión.

[Ver el problema, la evidencia de implementación y las limitaciones](PORTFOLIO.md) · [Portafolio del autor](https://github.com/eslay07)


Cámaras Toapanta es un visor de seguridad doméstica que nació de una necesidad bastante concreta: reunir cámaras de tecnologías distintas en una sola interfaz y poder usarlas sin depender de varias aplicaciones abiertas al mismo tiempo.

El proyecto mantiene dos clientes:

- **PC**, orientado a Windows y pensado para funcionar incluso en equipos modestos.
- **Android**, independiente de la PC y capaz de conectarse directamente a las cámaras.

Actualmente el sistema trabaja con dos cámaras Arlo y una cámara IP genérica con RTSP/ONVIF. La idea general, sin embargo, es mantener separada la lógica de cada proveedor para que el visor pueda seguir evolucionando sin convertir todo el proyecto en un bloque difícil de mantener.

## Estado actual

| Componente | Versión | Estado |
|---|---:|---|
| Cliente para PC | 1.5.5 | Principal |
| Cliente Android | 1.3.3 | Funcional e independiente |
| Variante Windows 7 | 1.5.5 | Compatibilidad separada |
| Licencia | MIT | Activa |

El código fuente público no incluye credenciales, sesiones, APK firmados, entornos virtuales ni datos reales de la red doméstica.

## Qué hace

La aplicación permite consultar varias cámaras desde una misma pantalla y manejar las diferencias entre Arlo y una cámara IP local sin obligar al usuario a cambiar constantemente de aplicación.

Entre las funciones principales están:

- vista de una, dos o tres cámaras;
- selección de cámara principal y secundaria;
- audio de la cámara principal;
- pantalla completa global o por cámara;
- reconexión automática de streams;
- soporte para cámaras Arlo;
- soporte para video RTSP;
- control PTZ mediante ONVIF para la cámara genérica;
- modo de privacidad para detener las cámaras;
- acceso protegido para reproducción desde VLC;
- diagnóstico básico de dispositivos y servicios dentro de la red local;
- almacenamiento seguro de credenciales cuando el sistema operativo lo permite.

## Arquitectura general

El proyecto tiene dos implementaciones que comparten el objetivo, pero no dependen una de la otra.

```text
                     ┌──────────────────────┐
                     │       Arlo Cloud     │
                     └──────────┬───────────┘
                                │
                  ┌─────────────┴─────────────┐
                  │                           │
           ┌──────▼──────┐             ┌──────▼──────┐
           │   Cliente   │             │   Cliente   │
           │     PC      │             │   Android   │
           └──────┬──────┘             └──────┬──────┘
                  │                           │
                  │       Red local          │
                  └─────────────┬─────────────┘
                                │
                       ┌────────▼────────┐
                       │ Cámara RTSP /   │
                       │     ONVIF       │
                       └─────────────────┘
```

### Cliente para PC

La versión de escritorio está escrita en Python y concentra la mayor parte de las herramientas de diagnóstico y configuración.

Para Arlo utiliza `pyaarlo` junto con `curl_cffi`. El video local se procesa con OpenCV y componentes auxiliares para audio, imágenes y reproducción.

Dependencias principales:

```text
pyaarlo
curl_cffi
opencv-python-headless
Pillow
keyring
sounddevice
imageio-ffmpeg
```

El cliente también puede actuar como punto de acceso local para abrir las cámaras desde VLC mediante enlaces protegidos.

### Cliente Android

La aplicación Android es independiente del programa de Windows.

El teléfono se conecta directamente a:

- Arlo para autenticación, detección de cámaras y obtención de streams;
- RTSP para el video de la cámara IP;
- ONVIF para el movimiento PTZ de la cámara compatible.

La reproducción utiliza componentes nativos de Android como `MediaCodec` y `AudioTrack`, por lo que la aplicación no necesita incluir Python, OpenCV, FFmpeg o VLC dentro del APK.

La sesión y la información sensible se almacenan utilizando mecanismos seguros del sistema Android.

## Estructura del repositorio

```text
camaras-toapanta/
│
├── android/
│   ├── src/
│   ├── res/
│   ├── AndroidManifest.xml
│   ├── APK_INFO.txt
│   └── README.md
│
├── pc/
│   ├── visor_camaras/
│   ├── diagnostico_red.py
│   ├── seguridad_casa.py
│   ├── requirements.txt
│   ├── INSTALAR_UNA_VEZ.bat
│   ├── CAMARAS_TOAPANTA.bat
│   ├── CAMARAS_TOAPANTA_DEMO.bat
│   ├── DIAGNOSTICAR_RED.bat
│   └── README.md
│
├── PROVENIENCIA.md
├── LICENSE
└── README.md
```

Los README dentro de `pc/` y `android/` contienen información específica de cada cliente. Este documento se mantiene como introducción general al proyecto.

## Instalación rápida en PC

### Requisitos

En Windows reciente se puede utilizar Python 3.10, 3.11 o 3.12.

La variante preparada para Windows 7 SP1 utiliza Python 3.8.10 de 64 bits y se mantiene separada de la línea principal.

### Instalación automática

Dentro de la carpeta `pc`:

```text
INSTALAR_UNA_VEZ.bat
```

El instalador crea y prepara el entorno necesario para el proyecto.

Después se puede iniciar el visor con:

```text
CAMARAS_TOAPANTA.bat
```

Para revisar primero la interfaz sin utilizar cuentas ni cámaras reales:

```text
CAMARAS_TOAPANTA_DEMO.bat
```

No es recomendable copiar `.venv` de una computadora a otra. Cada instalación debe crear su propio entorno.

## Configuración de cámaras

### Arlo

El programa inicia sesión en Arlo y trabaja con las cámaras disponibles para la cuenta configurada.

Si la cuenta utiliza verificación en dos pasos, el código se solicita durante el inicio de sesión.

Para una instalación doméstica es preferible utilizar una cuenta secundaria de Arlo y compartir únicamente las cámaras necesarias con esa cuenta. Esto evita utilizar la cuenta principal en aplicaciones adicionales.

En Windows, las contraseñas pueden guardarse mediante el Administrador de credenciales del sistema.

### Cámara IP genérica

La cámara debe ofrecer al menos uno de estos mecanismos:

- RTSP;
- ONVIF;
- transmisión local compatible.

Una dirección típica tiene este formato:

```text
rtsp://192.0.2.10:554/stream1
```

Las direcciones incluidas en el repositorio utilizan rangos reservados como ejemplo. La configuración real de cada instalación debe permanecer fuera del control de versiones.

Cuando la cámara ofrece ONVIF, el visor puede utilizarlo además para el control PTZ.

## Optimización para equipos con pocos recursos

Una parte importante del cliente de PC se diseñó pensando en hardware antiguo.

Entre las optimizaciones aplicadas están:

- uso del substream de menor resolución de la cámara genérica;
- reducción de la frecuencia de actualización de la interfaz;
- conservación únicamente del cuadro más reciente;
- detención de streams que no están visibles;
- limitación del procesamiento de OpenCV;
- reducción de cada cuadro antes de mostrarlo;
- reutilización de streams Arlo activos cuando es posible;
- prevención de múltiples instancias del visor;
- ausencia de grabaciones acumuladas en memoria.

En la configuración utilizada durante el desarrollo, el substream de la cámara genérica trabaja a `640 × 720`, frente al stream principal de `2304 × 2592`. La diferencia reduce de forma considerable el trabajo de decodificación en equipos antiguos.

## Privacidad

El visor incluye un modo **Detener todo / Reactivar cámaras**.

En Arlo, el control de privacidad puede detener las cámaras también para otros clientes mientras permanezca activo.

En una cámara RTSP genérica existe una diferencia importante: detener el stream dentro de Cámaras Toapanta no impide necesariamente que otra aplicación abra directamente la dirección RTSP. El bloqueo completo depende de las funciones de privacidad que ofrezca el propio dispositivo.

Por ese motivo, este proyecto no debe considerarse un reemplazo de la seguridad propia de la cámara o de una correcta segmentación de red.

## VLC

El cliente de PC puede generar enlaces locales protegidos para abrir las cámaras desde VLC.

Esto resulta útil para consultar un stream sin mantener toda la interfaz del visor en primer plano.

Los códigos y direcciones utilizados para este acceso son datos locales y no deben publicarse en el repositorio.

## Diagnóstico de red

La carpeta `pc` incluye:

```text
DIAGNOSTICAR_RED.bat
```

y:

```text
diagnostico_red.py
```

La utilidad está pensada para revisar la red local e identificar dispositivos que exponen servicios habituales de cámaras.

No realiza ataques de contraseña ni modifica la configuración de los dispositivos.

## Seguridad y datos sensibles

Este repositorio está preparado para mantener fuera del historial público:

- contraseñas;
- cookies y sesiones;
- tokens;
- configuraciones locales;
- bases de datos;
- claves privadas;
- firmas Android;
- APK generados;
- entornos virtuales;
- registros de ejecución;
- información real de la red doméstica.

Las credenciales deben almacenarse mediante las funciones del sistema operativo o en archivos locales excluidos por Git.

Si una credencial llega accidentalmente a un commit público, eliminar el archivo en un commit posterior no es suficiente. La credencial debe revocarse y sustituirse.

## Desarrollo

Antes de confirmar cambios del cliente de PC se puede comprobar que los módulos Python compilen correctamente:

```bash
py -3.12 -m compileall .\pc
```

Para instalar manualmente las dependencias:

```bash
py -3.12 -m pip install -r pc\requirements.txt
```

Los cambios deberían mantenerse pequeños y verificables. Si una modificación afecta solamente a Android, PC o a una variante de compatibilidad, es preferible conservarla aislada del resto.

## Versiones y etiquetas

El repositorio utiliza etiquetas para conservar versiones verificables de cada cliente.

Entre las versiones actualmente consolidadas se encuentran:

```text
pc-v1.5.5
android-v1.3.3
pc-windows7-v1.5.5
```

La rama `main` representa la línea principal del proyecto.

## Limitaciones conocidas

- Arlo depende de servicios externos y puede cambiar su mecanismo de autenticación o entrega de streams.
- Una cámara genérica necesita ofrecer un protocolo accesible, como RTSP u ONVIF.
- El modo de privacidad de una cámara RTSP no puede controlar aplicaciones externas si el propio dispositivo no ofrece un mecanismo de apagado.
- La compatibilidad con hardware antiguo exige priorizar estabilidad y consumo de recursos sobre altas tasas de cuadros.
- La aplicación Android necesita conectividad a Internet para Arlo, aunque la cámara genérica funcione dentro de la red local.

## Origen del proyecto

Cámaras Toapanta es un proyecto personal desarrollado para centralizar la visualización y el control de cámaras instaladas en una red doméstica.

La evolución del proyecto parte de versiones funcionales utilizadas en PC y Android. El historial público se mantiene con commits realizados en sus fechas reales y sin incorporar credenciales ni artefactos privados.

Los detalles sobre la consolidación del repositorio se encuentran en [`PROVENIENCIA.md`](PROVENIENCIA.md).

## Licencia

El proyecto se publica bajo licencia MIT. Consulta [`LICENSE`](LICENSE) para ver el texto completo.

## Autor

**Jimmy Toapanta**
