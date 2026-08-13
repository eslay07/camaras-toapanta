package com.jimmytoapanta.camaras;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.util.Base64;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Reproductor RTSP mínimo para las cámaras de esta instalación.
 * Usa MediaCodec del teléfono: no incluye VLC, FFmpeg ni OpenCV.
 */
final class RtspPlayer {
    interface Listener {
        void onStatus(String message);
        void onError(String message);
    }

    private final String streamUrl;
    private final Surface surface;
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean audioEnabled;
    private volatile Socket socket;
    private volatile MediaPlayer platformPlayer;
    private Thread thread;

    RtspPlayer(String streamUrl, Surface surface, boolean audioEnabled, Listener listener) {
        this.streamUrl = streamUrl;
        this.surface = surface;
        this.audioEnabled = audioEnabled;
        this.listener = listener;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "RTSP-Toapanta");
        thread.start();
    }

    void setAudioEnabled(boolean enabled) {
        audioEnabled = enabled;
        MediaPlayer active = platformPlayer;
        if (active != null) {
            try {
                active.setVolume(enabled ? 1f : 0f, enabled ? 1f : 0f);
            } catch (Exception ignored) {
            }
        }
    }

    void stop() {
        running = false;
        closeQuietly(socket);
        releasePlatformPlayer();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void run() {
        VideoDecoder video = null;
        AudioDecoder audio = null;
        try {
            listener.onStatus("SOLICITANDO VIDEO…");
            URI uri = URI.create(streamUrl);
            String scheme = uri.getScheme() == null ? "rtsp" : uri.getScheme().toLowerCase(Locale.US);
            int port = uri.getPort() > 0 ? uri.getPort() : ("rtsps".equals(scheme) ? 322 : 554);
            Socket transportSocket = new Socket();
            transportSocket.connect(new InetSocketAddress(uri.getHost(), port), 8000);
            socket = transportSocket;
            Socket activeSocket = transportSocket;
            if ("rtsps".equals(scheme)) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket secureSocket = (SSLSocket) factory.createSocket(
                        transportSocket,
                        uri.getHost(),
                        port,
                        true
                );
                secureSocket.setUseClientMode(true);
                secureSocket.startHandshake();
                activeSocket = secureSocket;
                socket = secureSocket;
            }
            activeSocket.setSoTimeout(10000);

            RtspIo io = new RtspIo(activeSocket, authorization(uri));
            io.request("OPTIONS", streamUrl, null);
            RtspResponse describe = io.request(
                    "DESCRIBE",
                    streamUrl,
                    mapOf("Accept", "application/sdp")
            );
            if (describe.status != 200) {
                throw new IOException("Arlo/cámara rechazó el video (" + describe.status + ")");
            }
            SdpDescription sdp = SdpDescription.parse(describe.body);
            if (sdp.video == null) {
                throw new IOException("El stream no contiene video");
            }
            String contentBase = describe.headers.get("content-base");
            if (contentBase == null || contentBase.isEmpty()) {
                contentBase = streamUrl;
            }

            int[] videoSize = videoSizeHint(uri, sdp.video);
            video = new VideoDecoder(
                    sdp.video,
                    surface,
                    videoSize[0],
                    videoSize[1]
            );
            String session = null;
            int videoChannel = 0;
            int audioChannel = -1;
            String videoControl = resolveControl(contentBase, sdp.video.control);
            RtspResponse videoSetup = io.request(
                    "SETUP",
                    videoControl,
                    mapOf("Transport", "RTP/AVP/TCP;unicast;interleaved=0-1")
            );
            if (videoSetup.status != 200) {
                throw new IOException("No se pudo preparar el canal de video");
            }
            session = sessionId(videoSetup.headers.get("session"));
            if (sdp.audio != null) {
                try {
                    String audioControl = resolveControl(contentBase, sdp.audio.control);
                    Map<String, String> headers = mapOf(
                            "Transport", "RTP/AVP/TCP;unicast;interleaved=2-3"
                    );
                    headers.put("Session", session);
                    RtspResponse audioSetup = io.request("SETUP", audioControl, headers);
                    if (audioSetup.status == 200) {
                        audioChannel = 2;
                        audio = AudioDecoder.create(sdp.audio);
                    }
                } catch (Exception ignored) {
                    audio = null;
                    audioChannel = -1;
                }
            }
            Map<String, String> playHeaders = new HashMap<>();
            playHeaders.put("Session", session);
            playHeaders.put("Range", "npt=0.000-");
            RtspResponse play = io.request("PLAY", contentBase, playHeaders);
            if (play.status != 200) {
                throw new IOException("No se pudo iniciar el video");
            }

            listener.onStatus("ESPERANDO PRIMER CUADRO…");
            long lastPacketAt = System.currentTimeMillis();
            long lastVideoPacketAt = lastPacketAt;
            long playbackStartedAt = lastPacketAt;
            boolean pictureShown = false;
            while (running) {
                try {
                    InterleavedFrame frame = io.readInterleaved();
                    if (frame == null) {
                        continue;
                    }
                    lastPacketAt = System.currentTimeMillis();
                    if (frame.channel == videoChannel) {
                        lastVideoPacketAt = lastPacketAt;
                        video.consume(frame.data);
                        if (!pictureShown && video.hasRenderedFrame()) {
                            pictureShown = true;
                            listener.onStatus("EN VIVO");
                        }
                    } else if (frame.channel == audioChannel && audio != null && audioEnabled) {
                        audio.consume(frame.data);
                    }
                    if (!pictureShown
                            && lastPacketAt - playbackStartedAt > 18_000) {
                        throw new IOException(
                                "Video conectado, pero sin imagen decodificada"
                        );
                    }
                    if (pictureShown
                            && lastPacketAt - lastVideoPacketAt > 10_000) {
                        throw new IOException("El video dejó de enviar cuadros");
                    }
                } catch (SocketTimeoutException timeout) {
                    if (System.currentTimeMillis() - lastPacketAt > 9000) {
                        throw new IOException("El video dejó de responder");
                    }
                }
            }
        } catch (Exception exception) {
            if (running) {
                URI failedUri = URI.create(streamUrl);
                boolean fallbackWorked = "rtsp".equalsIgnoreCase(failedUri.getScheme())
                        && playWithAndroidMediaPlayer();
                if (!fallbackWorked && running) {
                    String message = exception.getMessage();
                    listener.onError(message == null || message.isEmpty()
                            ? "No se pudo abrir el video"
                            : message);
                }
            }
        } finally {
            running = false;
            socket = null;
            closeQuietly(video);
            closeQuietly(audio);
        }
    }

    private boolean playWithAndroidMediaPlayer() {
        MediaPlayer fallback = new MediaPlayer();
        AtomicBoolean firstFrame = new AtomicBoolean();
        platformPlayer = fallback;
        try {
            listener.onStatus("PROBANDO REPRODUCTOR ANDROID…");
            fallback.setAudioStreamType(AudioManager.STREAM_MUSIC);
            fallback.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    firstFrame.set(true);
                    listener.onStatus("EN VIVO");
                }
                return false;
            });
            fallback.setDataSource(streamUrl);
            fallback.setSurface(surface);
            fallback.setVolume(audioEnabled ? 1f : 0f, audioEnabled ? 1f : 0f);
            fallback.prepare();
            if (!running) {
                return false;
            }
            fallback.start();
            listener.onStatus("ESPERANDO PRIMER CUADRO…");
            long startedAt = System.currentTimeMillis();
            while (running) {
                Thread.sleep(500);
                if (!fallback.isPlaying()) {
                    throw new IOException("El reproductor Android detuvo el video");
                }
                if (!firstFrame.get()
                        && System.currentTimeMillis() - startedAt > 18_000) {
                    throw new IOException(
                            "Reproductor Android conectado, pero sin imagen"
                    );
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            releasePlatformPlayer();
        }
    }

    private void releasePlatformPlayer() {
        MediaPlayer active = platformPlayer;
        platformPlayer = null;
        if (active == null) {
            return;
        }
        try {
            active.stop();
        } catch (Exception ignored) {
        }
        try {
            active.release();
        } catch (Exception ignored) {
        }
    }

    private static Map<String, String> mapOf(String key, String value) {
        Map<String, String> result = new HashMap<>();
        result.put(key, value);
        return result;
    }

    private static String authorization(URI uri) {
        String userInfo = uri.getRawUserInfo();
        if (userInfo == null || userInfo.isEmpty()) {
            return "";
        }
        try {
            userInfo = URLDecoder.decode(userInfo, "UTF-8");
        } catch (Exception ignored) {
        }
        return "Basic " + Base64.encodeToString(
                userInfo.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
        );
    }

    private static String resolveControl(String base, String control) {
        if (control == null || control.isEmpty() || "*".equals(control)) {
            return base;
        }
        if (control.startsWith("rtsp://") || control.startsWith("rtsps://")) {
            return control;
        }
        URI baseUri = URI.create(base);
        if (control.startsWith("/")) {
            int port = baseUri.getPort();
            return baseUri.getScheme() + "://" + baseUri.getHost()
                    + (port > 0 ? ":" + port : "") + control;
        }
        return base.endsWith("/") ? base + control : base + "/" + control;
    }

    private static int[] videoSizeHint(URI uri, Track track) {
        if (track.width > 0 && track.height > 0) {
            return new int[]{track.width, track.height};
        }
        String scheme = uri.getScheme() == null
                ? "rtsp"
                : uri.getScheme().toLowerCase(Locale.US);
        String path = uri.getPath() == null
                ? ""
                : uri.getPath().toLowerCase(Locale.US);
        if ("rtsp".equals(scheme)) {
            // Cámara macro-video de esta instalación. ch00_1 es el substream
            // ligero real de 640 × 720; ch00_0 es el stream principal.
            if (path.endsWith("ch00_0")) {
                return new int[]{2304, 2592};
            }
            return new int[]{640, 720};
        }
        // Las dos Arlo continúan usando exactamente su tamaño anterior.
        return new int[]{1280, 720};
    }

    private static String sessionId(String raw) throws IOException {
        if (raw == null || raw.isEmpty()) {
            throw new IOException("El servidor no entregó una sesión RTSP");
        }
        int separator = raw.indexOf(';');
        return separator >= 0 ? raw.substring(0, separator).trim() : raw.trim();
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) {
            return;
        }
        try {
            if (closeable instanceof Socket) {
                ((Socket) closeable).close();
            } else if (closeable instanceof Closeable) {
                ((Closeable) closeable).close();
            }
        } catch (Exception ignored) {
        }
    }

    private static final class RtspIo {
        private final DataInputStream input;
        private final OutputStream output;
        private final String authorization;
        private int sequence = 1;

        RtspIo(Socket socket, String authorization) throws IOException {
            this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.output = new BufferedOutputStream(socket.getOutputStream());
            this.authorization = authorization;
        }

        RtspResponse request(String method, String url, Map<String, String> extra)
                throws IOException {
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
            request.append("CSeq: ").append(sequence++).append("\r\n");
            request.append("User-Agent: Camaras-Toapanta-Android/1.0\r\n");
            if (!authorization.isEmpty()) {
                request.append("Authorization: ").append(authorization).append("\r\n");
            }
            if (extra != null) {
                for (Map.Entry<String, String> header : extra.entrySet()) {
                    if (header.getValue() != null && !header.getValue().isEmpty()) {
                        request.append(header.getKey()).append(": ")
                                .append(header.getValue()).append("\r\n");
                    }
                }
            }
            request.append("\r\n");
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            return readResponse();
        }

        RtspResponse readResponse() throws IOException {
            String statusLine = readLine(input);
            while (statusLine.isEmpty()) {
                statusLine = readLine(input);
            }
            if (!statusLine.startsWith("RTSP/")) {
                throw new IOException("Respuesta RTSP no válida");
            }
            String[] statusParts = statusLine.split(" ", 3);
            int status = statusParts.length > 1 ? Integer.parseInt(statusParts[1]) : 500;
            Map<String, String> headers = new HashMap<>();
            String line;
            while (!(line = readLine(input)).isEmpty()) {
                int separator = line.indexOf(':');
                if (separator > 0) {
                    headers.put(
                            line.substring(0, separator).trim().toLowerCase(Locale.US),
                            line.substring(separator + 1).trim()
                    );
                }
            }
            int length = 0;
            try {
                length = Integer.parseInt(headers.get("content-length"));
            } catch (Exception ignored) {
            }
            byte[] body = new byte[Math.max(0, length)];
            input.readFully(body);
            return new RtspResponse(
                    status,
                    headers,
                    new String(body, StandardCharsets.UTF_8)
            );
        }

        InterleavedFrame readInterleaved() throws IOException {
            int first = input.read();
            if (first < 0) {
                throw new EOFException("El servidor cerró el video");
            }
            if (first != '$') {
                String rest = readLine(input);
                if (first == 'R' && rest.startsWith("TSP/")) {
                    while (!readLine(input).isEmpty()) {
                        // Descarta respuestas a keep-alive.
                    }
                }
                return null;
            }
            int channel = input.readUnsignedByte();
            int length = input.readUnsignedShort();
            if (length <= 0 || length > 65535) {
                throw new IOException("Paquete RTP no válido");
            }
            byte[] data = new byte[length];
            input.readFully(data);
            return new InterleavedFrame(channel, data);
        }

        private static String readLine(DataInputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(128);
            int previous = -1;
            while (true) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException();
                }
                if (previous == '\r' && value == '\n') {
                    break;
                }
                if (previous >= 0) {
                    line.write(previous);
                }
                previous = value;
            }
            return new String(line.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class RtspResponse {
        final int status;
        final Map<String, String> headers;
        final String body;

        RtspResponse(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class InterleavedFrame {
        final int channel;
        final byte[] data;

        InterleavedFrame(int channel, byte[] data) {
            this.channel = channel;
            this.data = data;
        }
    }

    private static final class RtpPacket {
        final long timestamp;
        final boolean marker;
        final byte[] payload;

        RtpPacket(long timestamp, boolean marker, byte[] payload) {
            this.timestamp = timestamp;
            this.marker = marker;
            this.payload = payload;
        }

        static RtpPacket parse(byte[] packet) {
            if (packet == null || packet.length < 12 || (packet[0] & 0xC0) != 0x80) {
                return null;
            }
            int csrcCount = packet[0] & 0x0F;
            boolean extension = (packet[0] & 0x10) != 0;
            boolean padding = (packet[0] & 0x20) != 0;
            int offset = 12 + csrcCount * 4;
            if (offset > packet.length) {
                return null;
            }
            if (extension) {
                if (offset + 4 > packet.length) {
                    return null;
                }
                int words = ((packet[offset + 2] & 0xFF) << 8)
                        | (packet[offset + 3] & 0xFF);
                offset += 4 + words * 4;
            }
            int end = packet.length;
            if (padding && end > offset) {
                end -= packet[end - 1] & 0xFF;
            }
            if (offset >= end) {
                return null;
            }
            long timestamp = ((packet[4] & 0xFFL) << 24)
                    | ((packet[5] & 0xFFL) << 16)
                    | ((packet[6] & 0xFFL) << 8)
                    | (packet[7] & 0xFFL);
            byte[] payload = new byte[end - offset];
            System.arraycopy(packet, offset, payload, 0, payload.length);
            return new RtpPacket(timestamp, (packet[1] & 0x80) != 0, payload);
        }
    }

    private static final class Track {
        String media;
        int payloadType;
        String codec = "";
        int clockRate = 90000;
        int channels = 1;
        int width;
        int height;
        String control = "";
        final Map<String, String> formatParameters = new HashMap<>();
    }

    private static final class SdpDescription {
        Track video;
        Track audio;

        static SdpDescription parse(String text) {
            SdpDescription description = new SdpDescription();
            Track current = null;
            for (String rawLine : text.replace("\r", "").split("\n")) {
                String line = rawLine.trim();
                if (line.startsWith("m=")) {
                    String[] parts = line.substring(2).split("\\s+");
                    if (parts.length >= 4) {
                        current = new Track();
                        current.media = parts[0];
                        try {
                            current.payloadType = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException ignored) {
                            current.payloadType = -1;
                        }
                        if ("video".equals(current.media)) {
                            description.video = current;
                        } else if ("audio".equals(current.media)) {
                            description.audio = current;
                        }
                    }
                } else if (current != null && line.startsWith("a=rtpmap:")) {
                    int space = line.indexOf(' ');
                    if (space > 0) {
                        String[] details = line.substring(space + 1).split("/");
                        current.codec = details[0].toUpperCase(Locale.US);
                        if (details.length > 1) {
                            try {
                                current.clockRate = Integer.parseInt(details[1]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (details.length > 2) {
                            try {
                                current.channels = Integer.parseInt(details[2]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                } else if (current != null && line.startsWith("a=fmtp:")) {
                    int space = line.indexOf(' ');
                    if (space > 0) {
                        String values = line.substring(space + 1);
                        for (String item : values.split(";")) {
                            int equals = item.indexOf('=');
                            if (equals > 0) {
                                current.formatParameters.put(
                                        item.substring(0, equals).trim().toLowerCase(Locale.US),
                                        item.substring(equals + 1).trim()
                                );
                            }
                        }
                    }
                } else if (current != null && line.startsWith("a=framesize:")) {
                    int space = line.indexOf(' ');
                    if (space > 0) {
                        String[] dimensions = line.substring(space + 1).split("-");
                        if (dimensions.length == 2) {
                            try {
                                current.width = Integer.parseInt(dimensions[0].trim());
                                current.height = Integer.parseInt(dimensions[1].trim());
                            } catch (NumberFormatException ignored) {
                                current.width = 0;
                                current.height = 0;
                            }
                        }
                    }
                } else if (current != null && line.startsWith("a=control:")) {
                    current.control = line.substring("a=control:".length()).trim();
                }
            }
            return description;
        }
    }

    private static final class VideoDecoder implements Closeable {
        private static final byte[] START_CODE = {0, 0, 0, 1};

        private final Track track;
        private final MediaCodec codec;
        private final ByteArrayOutputStream fragmented = new ByteArrayOutputStream(256 * 1024);
        private final ByteArrayOutputStream accessUnit = new ByteArrayOutputStream(512 * 1024);
        private long firstTimestamp = -1;
        private long accessTimestamp = -1;
        private long accessPresentationUs;
        private volatile boolean renderedFrame;

        VideoDecoder(
                Track track,
                Surface surface,
                int width,
                int height
        ) throws IOException {
            this.track = track;
            boolean hevc = track.codec.contains("265") || track.codec.contains("HEVC");
            String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            MediaFormat format = MediaFormat.createVideoFormat(
                    mime,
                    Math.max(16, width),
                    Math.max(16, height)
            );
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            if (hevc) {
                byte[] configuration = joinBase64(
                        track,
                        "sprop-vps",
                        "sprop-sps",
                        "sprop-pps"
                );
                if (configuration.length > 0) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(configuration));
                }
            } else {
                byte[][] configuration = h264Configuration(track);
                if (configuration[0].length > 0) {
                    format.setByteBuffer(
                            "csd-0",
                            ByteBuffer.wrap(configuration[0])
                    );
                }
                if (configuration[1].length > 0) {
                    format.setByteBuffer(
                            "csd-1",
                            ByteBuffer.wrap(configuration[1])
                    );
                }
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, surface, null, 0);
            codec.start();
        }

        void consume(byte[] rawPacket) {
            RtpPacket packet = RtpPacket.parse(rawPacket);
            if (packet == null || packet.payload.length < 1) {
                return;
            }
            if (firstTimestamp < 0) {
                firstTimestamp = packet.timestamp;
            }
            long elapsed = (packet.timestamp - firstTimestamp) & 0xFFFFFFFFL;
            long presentationUs = elapsed * 1_000_000L / Math.max(1, track.clockRate);
            if (accessTimestamp >= 0
                    && accessTimestamp != packet.timestamp
                    && accessUnit.size() > 0) {
                queueAccessUnit();
            }
            if (accessTimestamp != packet.timestamp) {
                accessTimestamp = packet.timestamp;
                accessPresentationUs = presentationUs;
            }
            if (track.codec.contains("265") || track.codec.contains("HEVC")) {
                consumeH265(packet.payload);
            } else {
                consumeH264(packet.payload);
            }
            if (packet.marker) {
                queueAccessUnit();
            }
            drain();
        }

        boolean hasRenderedFrame() {
            return renderedFrame;
        }

        private void consumeH265(byte[] payload) {
            if (payload.length < 2) {
                return;
            }
            int type = (payload[0] >> 1) & 0x3F;
            if (type <= 47) {
                appendNal(payload);
            } else if (type == 48) {
                int offset = 2;
                while (offset + 2 <= payload.length) {
                    int size = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
                    offset += 2;
                    if (size <= 0 || offset + size > payload.length) {
                        break;
                    }
                    byte[] nal = new byte[size];
                    System.arraycopy(payload, offset, nal, 0, size);
                    appendNal(nal);
                    offset += size;
                }
            } else if (type == 49 && payload.length >= 3) {
                int fuHeader = payload[2] & 0xFF;
                boolean start = (fuHeader & 0x80) != 0;
                boolean end = (fuHeader & 0x40) != 0;
                if (start) {
                    fragmented.reset();
                    fragmented.write((payload[0] & 0x81) | ((fuHeader & 0x3F) << 1));
                    fragmented.write(payload[1]);
                }
                if (fragmented.size() > 0) {
                    fragmented.write(payload, 3, payload.length - 3);
                    if (end) {
                        appendNal(fragmented.toByteArray());
                        fragmented.reset();
                    }
                }
            }
        }

        private void consumeH264(byte[] payload) {
            int type = payload[0] & 0x1F;
            if (type >= 1 && type <= 23) {
                appendNal(payload);
            } else if (type == 24) {
                int offset = 1;
                while (offset + 2 <= payload.length) {
                    int size = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
                    offset += 2;
                    if (size <= 0 || offset + size > payload.length) {
                        break;
                    }
                    byte[] nal = new byte[size];
                    System.arraycopy(payload, offset, nal, 0, size);
                    appendNal(nal);
                    offset += size;
                }
            } else if (type == 28 && payload.length >= 2) {
                int fuHeader = payload[1] & 0xFF;
                boolean start = (fuHeader & 0x80) != 0;
                boolean end = (fuHeader & 0x40) != 0;
                if (start) {
                    fragmented.reset();
                    fragmented.write((payload[0] & 0xE0) | (fuHeader & 0x1F));
                }
                if (fragmented.size() > 0) {
                    fragmented.write(payload, 2, payload.length - 2);
                    if (end) {
                        appendNal(fragmented.toByteArray());
                        fragmented.reset();
                    }
                }
            }
        }

        private void appendNal(byte[] nal) {
            if (nal == null || nal.length == 0) {
                return;
            }
            if (accessUnit.size() + nal.length + START_CODE.length
                    > 4 * 1024 * 1024) {
                accessUnit.reset();
                return;
            }
            accessUnit.write(START_CODE, 0, START_CODE.length);
            accessUnit.write(nal, 0, nal.length);
        }

        private void queueAccessUnit() {
            if (accessUnit.size() == 0) {
                accessTimestamp = -1;
                return;
            }
            try {
                int inputIndex = codec.dequeueInputBuffer(10_000);
                if (inputIndex < 0) {
                    accessUnit.reset();
                    accessTimestamp = -1;
                    return;
                }
                ByteBuffer input = codec.getInputBuffer(inputIndex);
                byte[] data = accessUnit.toByteArray();
                if (input == null || input.capacity() < data.length) {
                    codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            accessPresentationUs,
                            0
                    );
                    accessUnit.reset();
                    accessTimestamp = -1;
                    return;
                }
                input.clear();
                input.put(data);
                codec.queueInputBuffer(
                        inputIndex,
                        0,
                        data.length,
                        accessPresentationUs,
                        0
                );
            } catch (Exception ignored) {
            } finally {
                accessUnit.reset();
                accessTimestamp = -1;
            }
        }

        private void drain() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (true) {
                    int outputIndex = codec.dequeueOutputBuffer(info, 0);
                    if (outputIndex >= 0) {
                        boolean picture =
                                (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                        codec.releaseOutputBuffer(outputIndex, true);
                        if (picture) {
                            renderedFrame = true;
                        }
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                            || outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        continue;
                    } else {
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
        }

        private static byte[] joinBase64(Track track, String... keys) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (String key : keys) {
                String value = track.formatParameters.get(key);
                if (value == null || value.isEmpty()) {
                    continue;
                }
                try {
                    output.write(START_CODE, 0, START_CODE.length);
                    byte[] decoded = Base64.decode(value, Base64.DEFAULT);
                    output.write(decoded, 0, decoded.length);
                } catch (Exception ignored) {
                }
            }
            return output.toByteArray();
        }

        private static byte[][] h264Configuration(Track track) {
            String values = track.formatParameters.get("sprop-parameter-sets");
            if (values == null) {
                return new byte[][]{new byte[0], new byte[0]};
            }
            String[] parts = values.split(",");
            byte[][] result = {new byte[0], new byte[0]};
            for (int index = 0; index < Math.min(2, parts.length); index++) {
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    output.write(START_CODE, 0, START_CODE.length);
                    byte[] decoded = Base64.decode(
                            parts[index].trim(),
                            Base64.DEFAULT
                    );
                    output.write(decoded, 0, decoded.length);
                    result[index] = output.toByteArray();
                } catch (Exception ignored) {
                }
            }
            return result;
        }
    }

    private abstract static class AudioDecoder implements Closeable {
        final Track track;

        AudioDecoder(Track track) {
            this.track = track;
        }

        abstract void consume(byte[] rawPacket);

        static AudioDecoder create(Track track) throws IOException {
            if ("PCMA".equals(track.codec) || "PCMU".equals(track.codec)) {
                return new G711Decoder(track);
            }
            if (track.codec.contains("MPEG4-GENERIC") || track.codec.contains("AAC")) {
                return new AacDecoder(track);
            }
            throw new IOException("Audio " + track.codec + " no compatible");
        }
    }

    private static final class G711Decoder extends AudioDecoder {
        private final AudioTrack output;

        G711Decoder(Track track) {
            super(track);
            int rate = Math.max(8000, track.clockRate);
            int minimum = AudioTrack.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            output = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    Math.max(minimum * 2, 4096),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            output.play();
        }

        @Override
        void consume(byte[] rawPacket) {
            RtpPacket packet = RtpPacket.parse(rawPacket);
            if (packet == null) {
                return;
            }
            byte[] pcm = new byte[packet.payload.length * 2];
            boolean alaw = "PCMA".equals(track.codec);
            for (int index = 0; index < packet.payload.length; index++) {
                short sample = alaw
                        ? decodeAlaw(packet.payload[index])
                        : decodeUlaw(packet.payload[index]);
                pcm[index * 2] = (byte) (sample & 0xFF);
                pcm[index * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
            }
            output.write(pcm, 0, pcm.length);
        }

        @Override
        public void close() {
            try {
                output.pause();
                output.flush();
                output.release();
            } catch (Exception ignored) {
            }
        }

        private static short decodeAlaw(byte value) {
            int sample = (value ^ 0x55) & 0xFF;
            int magnitude = ((sample & 0x0F) << 4) + 8;
            int segment = (sample & 0x70) >> 4;
            if (segment >= 1) {
                magnitude += 0x100;
            }
            if (segment > 1) {
                magnitude <<= segment - 1;
            }
            return (short) ((sample & 0x80) != 0 ? magnitude : -magnitude);
        }

        private static short decodeUlaw(byte value) {
            int sample = (~value) & 0xFF;
            int magnitude = ((sample & 0x0F) << 3) + 0x84;
            magnitude <<= (sample & 0x70) >> 4;
            return (short) ((sample & 0x80) != 0 ? 0x84 - magnitude : magnitude - 0x84);
        }
    }

    private static final class AacDecoder extends AudioDecoder {
        private final MediaCodec codec;
        private final AudioTrack output;

        AacDecoder(Track track) throws IOException {
            super(track);
            int rate = Math.max(8000, track.clockRate);
            int channels = Math.max(1, track.channels);
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    rate,
                    channels
            );
            String config = track.formatParameters.get("config");
            if (config != null && !config.isEmpty()) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(hex(config)));
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, 0);
            codec.start();
            int mask = channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO
                    : AudioFormat.CHANNEL_OUT_STEREO;
            int minimum = AudioTrack.getMinBufferSize(
                    rate,
                    mask,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            output = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build(),
                    Math.max(minimum * 2, 8192),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            output.play();
        }

        @Override
        void consume(byte[] rawPacket) {
            RtpPacket packet = RtpPacket.parse(rawPacket);
            if (packet == null || packet.payload.length < 4) {
                return;
            }
            int headerBits = ((packet.payload[0] & 0xFF) << 8)
                    | (packet.payload[1] & 0xFF);
            int headerBytes = (headerBits + 7) / 8;
            int dataOffset = 2 + headerBytes;
            if (dataOffset > packet.payload.length) {
                return;
            }
            int auSize = packet.payload.length - dataOffset;
            if (headerBits >= 16) {
                int auHeader = ((packet.payload[2] & 0xFF) << 8)
                        | (packet.payload[3] & 0xFF);
                auSize = Math.min(auSize, auHeader >> 3);
            }
            try {
                int inputIndex = codec.dequeueInputBuffer(0);
                if (inputIndex >= 0) {
                    ByteBuffer input = codec.getInputBuffer(inputIndex);
                    if (input != null && input.capacity() >= auSize) {
                        input.clear();
                        input.put(packet.payload, dataOffset, auSize);
                        codec.queueInputBuffer(inputIndex, 0, auSize, 0, 0);
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                    }
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (true) {
                    int outputIndex = codec.dequeueOutputBuffer(info, 0);
                    if (outputIndex < 0) {
                        break;
                    }
                    ByteBuffer pcm = codec.getOutputBuffer(outputIndex);
                    if (pcm != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        pcm.position(info.offset);
                        pcm.get(data, 0, info.size);
                        output.write(data, 0, data.length);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() {
            try {
                codec.stop();
                codec.release();
            } catch (Exception ignored) {
            }
            try {
                output.pause();
                output.flush();
                output.release();
            } catch (Exception ignored) {
            }
        }

        private static byte[] hex(String value) {
            String clean = value.replaceAll("[^0-9A-Fa-f]", "");
            byte[] data = new byte[clean.length() / 2];
            for (int index = 0; index < data.length; index++) {
                data[index] = (byte) Integer.parseInt(
                        clean.substring(index * 2, index * 2 + 2),
                        16
                );
            }
            return data;
        }
    }
}
