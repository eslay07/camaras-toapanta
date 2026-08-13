# Cámaras Toapanta — guía para VLC

Aplicación hecha por **Jimmy Toapanta**.

## Antes de comenzar

1. Conecte la PC o el teléfono a la misma red Wi-Fi de las cámaras.
2. Para ver las cámaras Arlo, abra **Cámaras Toapanta** en la PC y déjela
   funcionando.
3. Mantenga la aplicación de PC abierta para que los tres enlaces respeten el
   botón **Detener todo**.

## Enlaces

### Cámara genérica

```text
http://192.0.2.10:8765/vlc/generica.m3u?codigo=871066
```

### Arlo — Puerta Principal

```text
http://192.0.2.10:8765/vlc/arlo1.m3u?codigo=871066
```

### Arlo — Entrada Perchas

```text
http://192.0.2.10:8765/vlc/arlo2.m3u?codigo=871066
```

## VLC en Android

1. Instale y abra **VLC para Android**.
2. Toque **Más**.
3. Entre en **Nuevo flujo**.
4. Pegue el enlace de la cámara.
5. Toque la flecha para reproducir.
6. Toque el icono del altavoz si el audio está silenciado.
7. Para cambiar de cámara, regrese y abra otro enlace.

## VLC en Windows

1. Abra **VLC media player**.
2. Entre en **Medio > Abrir ubicación de red**.
3. Pegue el enlace de la cámara.
4. Pulse **Reproducir**.
5. Para pantalla completa, haga doble clic en el video o pulse `F`.
6. Si no hay sonido, revise **Audio > Pista de audio** y quite el silencio.

## Si una cámara Arlo no abre

1. Cierre la visualización de esa cámara en la aplicación oficial de Arlo.
2. Espere 10 segundos.
3. En la PC pulse **Reconectar** dentro de Cámaras Toapanta.
4. Cierre el intento anterior en VLC y vuelva a pegar el enlace.
5. Compruebe que la PC y el teléfono estén conectados a la misma red Wi-Fi.

Los tres enlaces respetan el botón **Detener todo** de Cámaras Toapanta. VLC
reproduce el video y el audio, pero no mueve la cámara genérica. Para usar
las flechas PTZ, abra Cámaras Toapanta en Windows o Android.

El código `871066` protege los enlaces dentro de la red local. No publique
estos enlaces en Internet ni abra el puerto `8765` en el router.
