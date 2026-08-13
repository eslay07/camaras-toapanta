# Cámaras Toapanta

Aplicación de seguridad hecha por **Jimmy Toapanta**.

Programa para ver en una sola pantalla:

- dos cámaras Arlo Pro, revisión de hardware H8;
- una cámara genérica con video local RTSP, audio y movimiento PTZ.

La versión 1.2 permite elegir entre una cámara grande, dos cámaras divididas o
las tres cámaras. El audio corresponde siempre a la cámara marcada como
**Principal**.

La versión 1.3 añade el puente protegido para **Cámaras Toapanta Android**. El
visor de PC muestra la IP y el código que debe usar el teléfono. La cámara
genérica, su audio y PTZ siguen usando la red local; la PC entrega al APK las
direcciones de las tres cámaras y controla centralmente la privacidad.

La versión 1.4 añade **Detener todo / Reactivar cámaras**. El modo privacidad
cierra los streams de Windows, Android y VLC. También apaga realmente las dos
Arlo mediante su control de privacidad, por lo que dejan de verse en la
aplicación oficial hasta reactivarlas.

La versión 1.5 conserva la sesión Arlo en una carpeta estable, migra
automáticamente el token que las versiones anteriores dejaron en la carpeta
temporal y evita detener los streams usados por otros dispositivos. También
añade el botón `⛶` a cada cámara para mostrar solamente ese video en pantalla
completa.

La versión 1.5.1 corrige los streams Arlo activos que Arlo todavía anunciaba
aunque ya estuvieran vencidos o pertenecieran a otra sesión. Ahora solicita
primero una URL nueva propia y usa la recuperación remota solamente como
alternativa. El botón global **Pantalla completa** conserva la vista elegida:
una, dos o las tres cámaras.

La versión 1.5.2 añade una recuperación inmediata adicional cuando la estación
base responde temporalmente `Unknown`: iniciar stream, consultar el stream
activo y volver a iniciar una vez. Las operaciones siguen serializadas y el
cierre continúa sin enviar `activityState=idle`.

La versión 1.5.3 corrige el inicio en PC cuando la carpeta `.venv` fue creada
con otra instalación de Python que ya no existe. Todos los accesos comprueban
ahora que el intérprete realmente funciona y reconstruyen automáticamente el
entorno privado con Python 3.8.10 en Windows 7. Este cambio solo afecta al
instalador y a los accesos de PC; no modifica la conexión ni el video Arlo.

La versión 1.5.4 corrige la instalación de componentes en Windows 7 cuando la
actualización de `pip` queda incompleta y aparece `No module named
pip.__main__`. El instalador comprueba `pip`, reconstruye automáticamente el
entorno si está dañado y conserva la versión incluida con Python 3.8.10 para
evitar que el problema vuelva a ocurrir.

La versión 1.5.5 corrige el rechazo de inicio de sesión Arlo causado por la
protección web de Arlo/Cloudflare. Usa el backend `curl_cffi` incorporado por
PyAarlo para imitar correctamente un navegador y, si la confianza guardada de
esta PC venció, aparta solamente la sesión local antigua y solicita un nuevo
código una vez. No cierra las sesiones de teléfonos ni de otros dispositivos.

## Instalación

1. Abra `INSTALAR_UNA_VEZ.bat`.
2. Espere el mensaje **Instalación terminada**.
3. Abra `CAMARAS_TOAPANTA.bat`.
4. En la primera ventana, complete la cuenta Arlo y la dirección de la cámara genérica.

Para comprobar primero el diseño sin usar cuentas ni cámaras, abra
`CAMARAS_TOAPANTA_DEMO.bat`.

Los accesos antiguos `INICIAR_VISOR.bat` e `INICIAR_DEMO.bat` siguen
funcionando para conservar compatibilidad.

Si se llevará a otra computadora, use el archivo
`Camaras_Toapanta_PC_v1.5.5_Windows7_Python38.zip`, descomprímalo allí y ejecute
primero `INSTALAR_UNA_VEZ.bat`. En Windows 7 SP1 use Python 3.8.10 de 64 bits.
En Windows reciente también admite Python 3.10, 3.11 o 3.12.

No copie la carpeta `.venv` de una computadora a otra. La versión distribuida
no la incluye. Si aparece `No Python at ...Python312`, `No module named
pip.__main__` o un rechazo de sesión Arlo, copie los archivos de la versión
1.5.5 sobre la carpeta anterior y abra
`CAMARAS_TOAPANTA.bat`: el entorno roto se detectará y reparará automáticamente.

## Optimización para PC antigua

Esta versión está preparada para un Pentium con 4 GB de RAM o menos:

- usa el substream de la genérica a `640 × 720`, en lugar del principal a
  `2304 × 2592`;
- limita la pantalla a 6 actualizaciones por segundo;
- mantiene solamente el cuadro más reciente de cada cámara;
- detiene los streams que no están visibles en las vistas de una o dos cámaras;
- evita que se abran dos copias y dos sesiones Arlo al mismo tiempo;
- conserva token, cookies e identificador Arlo en `%APPDATA%\CamarasToapanta\arlo`;
- comparte el stream Arlo activo en lugar de apagarlo al cambiar de vista;
- mantiene un puente Android muy pequeño que no decodifica ni retransmite video;
- limita OpenCV a un hilo de procesamiento por stream;
- omite cuadros no esenciales que la pantalla no alcanzaría a mostrar;
- usa decodificación estable por software, compatible con procesadores y
  gráficos integrados antiguos;
- reduce cada cuadro antes de entregarlo a la interfaz;
- no almacena grabaciones en memoria.

El substream reduce casi 13 veces la cantidad de píxeles de la cámara genérica.
Las cámaras Arlo Pro H8 ya transmiten a una resolución mucho menor que el
stream principal de la genérica.

En la prueba local, decodificar el stream principal de la genérica consumió
aproximadamente 150 % de CPU (cerca de un núcleo y medio), mientras que el
substream ligero consumió cerca de 9 %. El resultado exacto cambiará según el
Pentium, pero la diferencia de carga es muy grande.

## Configuración de Arlo

El programa inicia sesión en Arlo, detecta las cámaras disponibles y toma las
dos primeras. Después conserva esos nombres para los siguientes inicios.

Recomendación: cree una cuenta Arlo secundaria, comparta con ella únicamente
estas dos cámaras y use esa cuenta en el visor. Así no entrega al programa la
clave de la cuenta principal.

Si Arlo pide verificación en dos pasos, el código llegará por el método elegido
(`email` o `sms`) y el programa mostrará una ventana para escribirlo.

Después de la primera verificación, la sesión confiable se guarda y se reutiliza
en los siguientes inicios. Cada PC y teléfono mantiene un identificador propio.
Para varios usuarios se recomienda crear cuentas Arlo secundarias y compartir
estas dos cámaras con ellas; así cada persona conserva su propia sesión.

Las credenciales no se guardan dentro del proyecto ni en `config.json`. Si
marca **Guardar contraseñas en Windows**, se almacenan mediante el Administrador
de credenciales de Windows.

## Configuración de la cámara genérica

En la aplicación original de la cámara busque una opción llamada:

- **RTSP**;
- **ONVIF**;
- **NVR / NAS**;
- **Transmisión local** o **Local streaming**.

Active esa opción y copie su dirección. Un ejemplo de formato es:

```text
rtsp://192.0.2.10:554/stream1
```

Escriba usuario y contraseña en sus campos separados. Si pega una dirección
que ya contiene ambos, el visor los separa automáticamente para no dejar la
contraseña en el archivo de configuración.

Si la cámara solo funciona por la nube de su fabricante y no ofrece RTSP,
ONVIF o una dirección HTTP de video, no puede añadirse todavía sin conocer su
marca, modelo y protocolo privado.

## Controles

- **Vista — 1 cámara**: abre la cámara principal en toda el área disponible.
- **Vista — 2 cámaras**: divide la pantalla entre la principal y la segunda.
- **Vista — 3 cámaras**: presenta las tres cámaras al mismo tiempo.
- **Principal / Segunda**: permite elegir qué cámara ocupa cada espacio.
- **Audio principal**: enciende un solo canal de audio para evitar eco y
  reducir el consumo de la PC.
- **Mover genérica**: mantenga presionadas las flechas para girar; suelte para
  detener. `⌂` regresa a la posición inicial y `■` detiene el movimiento.
- **Reconectar**: vuelve a solicitar los videos visibles.
- **Detener todo**: activa la privacidad, cierra todas las vistas y apaga las
  dos Arlo también para su aplicación oficial.
- **Reactivar cámaras**: enciende nuevamente las Arlo y permite las vistas de
  Windows, Android y VLC.
- **Configurar**: cambia cuenta, dirección o credenciales.
- `⛶` en una cámara: muestra únicamente esa cámara, ocultando todos los demás
  controles y elementos de Windows.
- Doble clic sobre el video: abre esa cámara en pantalla completa.
- **Pantalla completa** o `F11`: oculta los controles y conserva exactamente
  la vista actual de una, dos o tres cámaras.
- `Esc`: sale de pantalla completa.

El visor intenta reconectar automáticamente cada video cuando se interrumpe.

## Ver las cámaras con VLC

VLC debe estar conectado a la misma red Wi-Fi de la casa. Para las cámaras
Arlo, mantenga abierta **Cámaras Toapanta** en la PC porque el programa solicita
a Arlo una dirección temporal cada vez que VLC abre la lista.

En VLC use **Medio > Abrir ubicación de red** en Windows o
**Más > Nuevo flujo** en Android. Pegue uno de estos enlaces:

```text
Cámara genérica:
http://192.0.2.10:8765/vlc/generica.m3u?codigo=871066

Arlo Puerta Principal:
http://192.0.2.10:8765/vlc/arlo1.m3u?codigo=871066

Arlo Entrada Perchas:
http://192.0.2.10:8765/vlc/arlo2.m3u?codigo=871066
```

El audio incluido en cada transmisión se reproduce desde VLC. Si no se oye,
active el altavoz de VLC y revise **Audio > Pista de audio**. VLC permite ver
video y audio, pero no controla el movimiento PTZ de la cámara genérica; para
moverla use Cámaras Toapanta en la PC o en Android.

Si una Arlo no abre, cierre su vista en la aplicación oficial de Arlo, espere
unos segundos y vuelva a abrir el enlace en VLC. También puede pulsar
**Reconectar** en Cámaras Toapanta. El código del enlace es privado para la red
de la casa y no debe publicarse.

El enlace VLC protegido de la genérica respeta **Detener todo**. La dirección
RTSP directa de la cámara puede saltarse ese control; para apagarla también en
la aplicación del fabricante, el equipo debe ofrecer apagado remoto o estar
conectado a un enchufe inteligente.

## Diagnóstico de red

Encienda la cámara genérica y abra `DIAGNOSTICAR_RED.bat`. El diagnóstico:

- solo revisa la red local;
- no prueba contraseñas;
- no cambia configuraciones;
- marca dispositivos que exponen puertos comunes de cámaras.

## Datos de esta instalación

El reconocimiento efectuado el 29 de julio de 2026 encontró:

| Dispositivo | Dirección | Identificación |
|---|---|---|
| Estación Arlo | `192.0.2.10` / `9C-3D-CF-72-B5-B3` | Netgear, video seguro en puerto 554 |
| Arlo 1 y Arlo 2 | Sin IP propia en la red doméstica | Enlazadas directamente a la estación base |
| Cámara genérica | `192.0.2.10` / `10-E9-06-D9-E3-6E` | Macro-video `IPCamera`, RTSP/ONVIF, H.265, PTZ y audio PCMA |

La dirección genérica optimizada ya aparece prellenada como
`rtsp://192.0.2.10:554/live/ch00_1`. Si estaba guardada la dirección antigua,
Cámaras Toapanta la migra automáticamente al modo ligero. El equipo entregó el
video sin solicitar autenticación. Conviene habilitar una contraseña desde la
aplicación original de la cámara si existe esa opción.

El servicio ONVIF se identificó como `macro-video-soft`, modelo lógico
`IPCamera`, firmware `2.4`, con movimiento horizontal y vertical. La marca
comercial impresa aún requiere una foto de la etiqueta.

## Estado de las cámaras Arlo

Las dos cámaras están conectadas a la base y funcionan en el celular. Durante
la prueba, **Entrada Perchas** entregó video de `1280 × 720` y **Puerta
Principal** también llegó a mostrar video en Cámaras Toapanta.

El problema de **Puerta Principal** fue una segunda copia antigua del visor que
seguía bloqueada y mantenía otra sesión Arlo. La versión 1.2.1 impide abrir dos
copias, prueba una solicitud alternativa si Arlo ya está transmitiendo al
celular y vuelve a solicitar una URL vencida sin atribuir el problema a la
batería.

## Archivos de la aplicación

La configuración sin contraseñas y el registro se guardan en:

```text
%APPDATA%\CamarasToapanta
```

Si existía la versión anterior, su configuración y sus credenciales se migran
automáticamente sin borrar los datos originales.

La aplicación usa Python, OpenCV para video, FFmpeg y SoundDevice para audio,
Tkinter para la pantalla, PyAarlo para Arlo y ONVIF local para el movimiento.

Nombre: **Cámaras Toapanta**  
Autor: **Jimmy Toapanta**  
Versión: **1.5.5 — renovación segura de autenticación Arlo en PC**
