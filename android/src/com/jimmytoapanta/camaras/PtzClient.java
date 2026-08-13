package com.jimmytoapanta.camaras;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PtzClient {
    interface Callback {
        void onStatus(boolean ok, String message);
    }

    private static final String SOAP = "http://www.w3.org/2003/05/soap-envelope";
    private static final String PTZ = "http://www.onvif.org/ver20/ptz/wsdl";
    private static final String SCHEMA = "http://www.onvif.org/ver10/schema";

    private final AppConfig config;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    PtzClient(AppConfig config) {
        this.config = config;
    }

    void move(float x, float y, Callback callback) {
        String body = "<tptz:ContinuousMove>"
                + "<tptz:ProfileToken>PROFILE_000</tptz:ProfileToken>"
                + "<tptz:Velocity><tt:PanTilt x=\"" + x + "\" y=\"" + y
                + "\"/></tptz:Velocity><tptz:Timeout>PT2S</tptz:Timeout>"
                + "</tptz:ContinuousMove>";
        send("ContinuousMove", body, "MOVIENDO", callback);
    }

    void stop(Callback callback) {
        String body = "<tptz:Stop>"
                + "<tptz:ProfileToken>PROFILE_000</tptz:ProfileToken>"
                + "<tptz:PanTilt>true</tptz:PanTilt>"
                + "<tptz:Zoom>true</tptz:Zoom></tptz:Stop>";
        send("Stop", body, "LISTO", callback);
    }

    void home(Callback callback) {
        String body = "<tptz:GotoHomePosition>"
                + "<tptz:ProfileToken>PROFILE_000</tptz:ProfileToken>"
                + "</tptz:GotoHomePosition>";
        send("GotoHomePosition", body, "POSICIÓN INICIAL", callback);
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private void send(String action, String body, String success, Callback callback) {
        executor.execute(() -> {
            boolean ok = false;
            String message = "PTZ NO DISPONIBLE";
            HttpURLConnection connection = null;
            try {
                Uri uri = Uri.parse(config.genericUrl());
                String host = uri.getHost();
                URL endpoint = new URL("http://" + host + ":8899/onvif/ptz_service");
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(4000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/soap+xml; charset=utf-8; action=\""
                                + PTZ + "/" + action + "\""
                );
                String envelope = "<s:Envelope xmlns:s=\"" + SOAP
                        + "\" xmlns:tptz=\"" + PTZ + "\" xmlns:tt=\"" + SCHEMA
                        + "\"><s:Body>" + body + "</s:Body></s:Envelope>";
                byte[] payload = envelope.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
                int status = connection.getResponseCode();
                ok = status >= 200 && status < 300;
                message = ok ? success : "PTZ RECHAZADO";
            } catch (Exception ignored) {
                message = "PTZ SIN CONEXIÓN";
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            boolean finalOk = ok;
            String finalMessage = message;
            main.post(() -> callback.onStatus(finalOk, finalMessage));
        });
    }
}
