# Cámaras Toapanta Android independiente

Aplicación de seguridad hecha por **Jimmy Toapanta**.

## Qué significa “independiente”

Esta versión no usa la PC como puente ni necesita que el programa de Windows
esté abierto. El teléfono se conecta:

- directamente a la cuenta Arlo para detectar **Puerta Principal** y
  **Entrada Perchas**, activar sus streams y controlar su privacidad;
- directamente por RTSP a la cámara genérica;
- directamente por ONVIF al control de movimiento de la cámara genérica.

Arlo necesita Internet. La cámara genérica y el teléfono deben estar en la
misma red Wi‑Fi.

## Instalación y primera configuración

1. Copie `Camaras_Toapanta_Android_Independiente_v1.3.3.apk` al teléfono.
2. Abra el archivo y permita temporalmente la instalación desde esa fuente.
3. Instálelo sobre la versión anterior. Usa el mismo paquete y la misma firma
   de Jimmy Toapanta.
4. Abra **Cámaras Toapanta** y pulse **Configurar**.
5. Escriba el correo y la contraseña de Arlo.
6. Elija si el código de verificación llega por `EMAIL` o `SMS`.
7. Confirme la dirección de la genérica. Si necesita usuario y contraseña, use
   el formato `rtsp://usuario:clave@IP:554/ruta`.
8. Pulse **Guardar** y escriba el código que envía Arlo.

Dirección preparada para la cámara genérica de esta casa:

```text
rtsp://192.0.2.10:554/live/ch00_1
```

La contraseña Arlo, la sesión y las cookies se guardan cifradas mediante el
almacén seguro de Android.

## Funciones

- una cámara ampliada;
- pantalla dividida para dos cámaras;
- vista de las tres cámaras;
- selección de cámara principal y secundaria;
- audio de la cámara principal;
- movimiento PTZ de la cámara genérica;
- migración automática de la dirección genérica antigua hacia
  `rtsp://192.0.2.10:554/live/ch00_1`;
- decodificador H.265 configurado con la resolución real de la genérica,
  `640 × 720`, manteniendo las Arlo en `1280 × 720`;
- reconexión y renovación automática del video Arlo;
- recuperación de una Arlo que ya esté transmitiendo en otra sesión;
- sesión Arlo confiable guardada una sola vez por teléfono;
- uso compartido del stream activo sin desconectar otros dispositivos;
- solicitud prioritaria de una URL nueva propia para evitar streams activos
  vencidos o pertenecientes a otra sesión;
- manejo seguro de la respuesta temporal `Unknown` de Arlo;
- solicitudes de las dos Arlo ordenadas para evitar operaciones simultáneas
  que la estación base no pueda resolver;
- reintento interno `iniciar → consultar → iniciar` antes de mostrar un error;
- botón global **Pantalla completa** que conserva exactamente la vista actual
  de una, dos o tres cámaras;
- botón `⛶` en cada cámara para verla sola en pantalla completa;
- modo inmersivo que oculta barras, controles y las otras cámaras;
- renderizado corregido para evitar “EN VIVO” con pantalla negra;
- corrección del cierre inmediato al crear la superficie de video;
- superficie de video compatible con desplazamiento y varias cámaras;
- agrupación de paquetes RTP en cuadros completos H.264/H.265;
- diseño vertical y horizontal;
- desplazamiento vertical en teléfonos pequeños;
- botón **Detener todo / Reactivar cámaras**;
- cierre de streams al enviar la APK al fondo para ahorrar batería y red.

## Botón de privacidad

El botón detiene inmediatamente las tres vistas de esta APK y envía
directamente el modo de privacidad a las dos Arlo. Mientras siga activo, las
Arlo tampoco transmitirán en la aplicación oficial.

La cámara genérica se detiene dentro de **Cámaras Toapanta**. Un APK no puede
impedir por sí solo que VLC u otra aplicación abra directamente el RTSP de esa
cámara. Para bloquearla en todas las aplicaciones, el modelo debe ofrecer un
modo de privacidad/apagado propio o debe conectarse a un enchufe inteligente.

## Diseño ligero

El APK ocupa aproximadamente 53 KB y no contiene Python, OpenCV, FFmpeg, VLC ni
bibliotecas nativas. Usa MediaCodec y AudioTrack del propio teléfono para
H.264/H.265 y audio PCMA/AAC.

La aplicación muestra **EN VIVO** únicamente después de que Android haya
decodificado y presentado el primer cuadro. Si no puede hacerlo, muestra un
error concreto y vuelve a intentarlo.

Al cerrar la APK se detienen sus decodificadores locales, pero no se envía la
orden global `idle` a las Arlo. Así no se corta la transmisión utilizada por
otro teléfono o por la PC. El correo de verificación solamente debe solicitarse
de nuevo si Arlo revoca realmente la sesión o si se cambia la cuenta.

Versión Android: **1.3.3**  
Android mínimo: **6.0**
