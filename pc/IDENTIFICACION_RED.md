# Identificación de red — Cámaras Toapanta

Aplicación hecha por **Jimmy Toapanta**.

Fecha: 29 de julio de 2026  
Red revisada: `192.0.2.10/24`  
Equipo del visor: `192.0.2.10`

## Resultado confirmado

La estación central Arlo es:

```text
IP:      192.0.2.10
MAC:     9C-3D-CF-72-B5-B3
Marca:   Netgear
Servicio: TCP 554, RTSP con cifrado
```

Esto coincide con la arquitectura Arlo: las dos cámaras se comunican con la
base en una red privada y por eso no figuran como dos clientes Wi-Fi separados.

El valor **H8** que muestra la cuenta es una revisión de hardware del modelo
Arlo Pro original. El identificador de modelo que normalmente devuelve la
cuenta para esa generación es `VMC4030`.

## Cámara genérica

La cámara genérica quedó identificada en:

```text
IP:       192.0.2.10
MAC:      10-E9-06-D9-E3-6E
Servicio: RTSP en TCP 554
Servidor: "macro-video rtsp server"
ONVIF:    TCP 8899, fabricante "macro-video-soft"
Modelo lógico: IPCamera
Firmware: 2.4
PTZ:      horizontal/vertical, posición inicial y hasta 100 presets
Video principal: H.265, 2304 × 2592, aproximadamente 14 fps
Substream:       H.265, 640 × 720, aproximadamente 14 fps
Audio:    PCMA, 8 kHz, mono
URL usada por Cámaras Toapanta:
          rtsp://192.0.2.10:554/live/ch00_1
```

El servidor respondió a `OPTIONS` y `DESCRIBE` sin pedir usuario ni
contraseña. La ruta `/live/ch00_1` ofrece el substream de menor resolución y es
la elegida por Cámaras Toapanta para reducir fuertemente el consumo de CPU y
memoria. El perfil ONVIF `PROFILE_000` respondió correctamente a movimiento y
parada; Cámaras Toapanta 1.2 usa ese perfil para los controles PTZ.

“Macro-video” identifica el software RTSP interno, pero no permite atribuir con
certeza la marca comercial ni el modelo exterior. Una foto nítida de la
etiqueta inferior o posterior permitiría completar ese dato.

Como el video está abierto a cualquier equipo de la red doméstica, se recomienda
activar autenticación RTSP desde la aplicación original si ofrece esa opción y
mantener la red Wi-Fi protegida.

## Prueba de video Arlo

El 29 de julio de 2026 la cuenta Arlo detectó dos cámaras `VMC4030`:

- **Entrada Perchas**: video confirmado a `1280 × 720`.
- **Puerta Principal**: video confirmado después de liberar una sesión Arlo
  duplicada.

La visualización correcta en el celular y la transmisión obtenida por el visor
descartan una batería agotada o una desconexión de la base. El problema era una
copia antigua bloqueada del programa y la reutilización de una URL RTSPS
temporal. Cámaras Toapanta 1.2.1 impide dobles instancias y renueva el stream.

## Alcance y seguridad

El reconocimiento fue de presencia, fabricante y puertos estándar. No intentó
contraseñas, no explotó servicios y no modificó router, cámaras ni estación
Arlo.
