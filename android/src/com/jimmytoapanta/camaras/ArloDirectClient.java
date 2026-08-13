package com.jimmytoapanta.camaras;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class ArloDirectClient {
    interface LoginCallback {
        void onResult(boolean connected, boolean needsCode, String message);
    }

    interface StreamCallback {
        void onResult(String url, String error);
    }

    interface PrivacyCallback {
        void onResult(boolean enabled, String message, String error);
    }

    private static final String AUTH_HOST = "https://ocapi-app.arlo.com";
    private static final String API_HOST = "https://myapi.arlo.com";
    private static final String USER_AGENT = "(iPhone15,2 18_1_1) iOS Arlo 5.4.3";

    private final AppConfig config;
    private final SecureStore secrets;
    private final CookieManager cookieManager;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final Object apiRequestLock = new Object();
    private final List<CameraInfo> cameras =
            Collections.synchronizedList(new ArrayList<>());
    private final Map<String, CameraInfo> bases = new HashMap<>();
    private final Map<String, CachedStream> cachedStreams = new HashMap<>();

    private volatile String token = "";
    private volatile String userId = "";
    private volatile String browserAuthCode = "";
    private volatile String pendingFactorAuthCode = "";
    private volatile boolean needsPairing;
    private volatile boolean connected;

    ArloDirectClient(AppConfig config, SecureStore secrets) {
        this.config = config;
        this.secrets = secrets;
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookieManager);
        restoreCookies();
    }

    boolean isConnected() {
        return connected && cameras.size() >= 2;
    }

    void connect(LoginCallback callback) {
        if (!connecting.compareAndSet(false, true)) {
            postLogin(callback, false, false, "Conexión Arlo en curso…");
            return;
        }
        executor.execute(() -> {
            boolean ok = false;
            boolean needsCode = false;
            String message;
            try {
                if (config.arloEmail().isEmpty() || config.arloPassword().isEmpty()) {
                    throw new ArloException("Configure el correo y la contraseña de Arlo");
                }
                if (restoreSession()) {
                    ok = true;
                    message = connectedMessage();
                } else {
                    LoginStep step = startLogin();
                    if (step.needsCode) {
                        needsCode = true;
                        message = "Escriba el código enviado por Arlo";
                    } else {
                        ok = finishSession();
                        message = ok ? connectedMessage() : "Arlo no devolvió las cámaras";
                    }
                }
            } catch (Exception exception) {
                connected = false;
                message = safeMessage(exception, "No fue posible iniciar sesión en Arlo");
            } finally {
                connecting.set(false);
            }
            postLogin(callback, ok, needsCode, message);
        });
    }

    void submitCode(String code, LoginCallback callback) {
        if (!connecting.compareAndSet(false, true)) {
            postLogin(callback, false, false, "Verificación Arlo en curso…");
            return;
        }
        executor.execute(() -> {
            boolean ok = false;
            String message;
            try {
                if (pendingFactorAuthCode.isEmpty()) {
                    throw new ArloException("Solicite primero un código nuevo");
                }
                JSONObject payload = new JSONObject()
                        .put("factorAuthCode", pendingFactorAuthCode)
                        .put("otp", code.trim())
                        .put("isBrowserTrusted", true);
                ApiResponse response = authRequest(
                        "/api/finishAuth",
                        "POST",
                        payload,
                        true
                );
                requireSuccess(response, "Código de Arlo incorrecto o vencido");
                updateAuth(asObject(response.data));
                pendingFactorAuthCode = "";
                pairBrowserIfNeeded();
                ok = finishSession();
                message = ok ? connectedMessage() : "Arlo no devolvió las cámaras";
            } catch (Exception exception) {
                connected = false;
                message = safeMessage(exception, "No se pudo verificar el código Arlo");
            } finally {
                connecting.set(false);
            }
            postLogin(callback, ok, false, message);
        });
    }

    void resolve(String key, boolean renew, StreamCallback callback) {
        executor.execute(() -> {
            String streamUrl = "";
            String error = null;
            try {
                if (!isConnected()) {
                    throw new ArloException("Inicie sesión en Arlo desde Configurar");
                }
                CameraInfo camera = cameraForKey(key);
                if (camera == null) {
                    throw new ArloException("No se encontró esa cámara Arlo");
                }
                synchronized (cachedStreams) {
                    CachedStream cached = cachedStreams.get(key);
                    if (!renew && cached != null
                            && System.currentTimeMillis() - cached.createdAt < 180_000) {
                        streamUrl = cached.url;
                    }
                }
                if (streamUrl.isEmpty()) {
                    synchronized (cachedStreams) {
                        cachedStreams.remove(key);
                    }
                    StreamResult result = requestStream(camera);
                    streamUrl = result.url;
                    synchronized (cachedStreams) {
                        cachedStreams.put(
                                key,
                                new CachedStream(
                                        streamUrl,
                                        System.currentTimeMillis(),
                                        result.owned
                                )
                        );
                    }
                }
            } catch (Exception exception) {
                error = safeMessage(exception, "Arlo no entregó el video");
            }
            String finalUrl = streamUrl;
            String finalError = error;
            main.post(() -> callback.onResult(finalUrl, finalError));
        });
    }

    void setPrivacy(boolean enabled, PrivacyCallback callback) {
        executor.execute(() -> {
            String error = null;
            String message;
            int changed = 0;
            try {
                if (!isConnected()) {
                    throw new ArloException("Primero conecte la cuenta Arlo");
                }
                List<CameraInfo> snapshot;
                synchronized (cameras) {
                    snapshot = new ArrayList<>(cameras);
                }
                for (CameraInfo camera : snapshot) {
                    notifyPrivacy(camera, enabled);
                    changed++;
                }
                synchronized (cachedStreams) {
                    cachedStreams.clear();
                }
                message = enabled
                        ? changed + " cámaras Arlo apagadas"
                        : changed + " cámaras Arlo reactivadas";
            } catch (Exception exception) {
                message = "";
                error = safeMessage(
                        exception,
                        "No se pudo cambiar la privacidad Arlo"
                );
            }
            String finalMessage = message;
            String finalError = error;
            main.post(() -> callback.onResult(enabled, finalMessage, finalError));
        });
    }

    void clearSession() {
        resetRuntimeSession();
        clearSavedSession();
        cookieManager.getCookieStore().removeAll();
        secrets.remove("arlo_cookies");
    }

    private void resetRuntimeSession() {
        connected = false;
        token = "";
        userId = "";
        browserAuthCode = "";
        pendingFactorAuthCode = "";
        cameras.clear();
        bases.clear();
        synchronized (cachedStreams) {
            cachedStreams.clear();
        }
    }

    private void clearSavedSession() {
        secrets.remove("arlo_token");
        secrets.remove("arlo_user_id");
        secrets.remove("arlo_browser_code");
        secrets.remove("arlo_session_email");
    }

    void releaseStreams() {
        // Solo desprende esta pantalla del stream. No envía "idle" a Arlo:
        // hacerlo cerraría también el video que otro teléfono o la PC esté usando.
        synchronized (cachedStreams) {
            cachedStreams.clear();
        }
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private boolean restoreSession() {
        String savedEmail = secrets.get("arlo_session_email");
        if (!config.arloEmail().equalsIgnoreCase(savedEmail)) {
            clearSession();
            return false;
        }
        token = secrets.get("arlo_token");
        userId = secrets.get("arlo_user_id");
        browserAuthCode = secrets.get("arlo_browser_code");
        if (token.isEmpty() || userId.isEmpty()) {
            return false;
        }
        try {
            ApiResponse validated = authRequest(
                    "/api/validateAccessToken?data=" + System.currentTimeMillis(),
                    "GET",
                    null,
                    true
            );
            if (!validated.ok()) {
                clearSession();
                return false;
            }
            return finishSession();
        } catch (Exception ignored) {
            resetRuntimeSession();
            clearSavedSession();
            return false;
        }
    }

    private LoginStep startLogin() throws Exception {
        // Conserva las cookies del navegador confiable. Así un token vencido
        // no obliga a registrar otra vez este teléfono como un dispositivo nuevo.
        resetRuntimeSession();
        clearSavedSession();
        JSONObject payload = new JSONObject()
                .put("email", config.arloEmail())
                .put(
                        "password",
                        Base64.encodeToString(
                                config.arloPassword().getBytes(StandardCharsets.UTF_8),
                                Base64.NO_WRAP
                        )
                )
                .put("language", "en")
                .put("EnvSource", "prod");
        ApiResponse login = authRequest("/api/auth", "POST", payload, false);
        requireSuccess(login, "Arlo rechazó el correo o la contraseña");
        JSONObject data = asObject(login.data);
        updateAuth(data);
        if (data.optBoolean("authCompleted", false)) {
            return new LoginStep(false);
        }

        ApiResponse trusted = authRequest(
                "/api/getFactorId",
                "POST",
                new JSONObject()
                        .put("factorType", "BROWSER")
                        .put("factorData", "")
                        .put("userId", userId),
                true
        );
        String factorId = "";
        if (trusted.ok()) {
            needsPairing = false;
            factorId = asObject(trusted.data).optString("factorId", "");
            ApiResponse quick = startAuth(factorId);
            requireSuccess(quick, "Arlo no pudo validar este teléfono");
            updateAuth(asObject(quick.data));
            return new LoginStep(false);
        }

        needsPairing = true;
        ApiResponse factorsResponse = authRequest(
                "/api/getFactors?data=" + System.currentTimeMillis(),
                "GET",
                null,
                true
        );
        requireSuccess(
                factorsResponse,
                "Arlo no entregó métodos de verificación"
        );
        JSONObject factorData = asObject(factorsResponse.data);
        JSONArray items = factorData.optJSONArray("items");
        if (items == null) {
            throw new ArloException("La cuenta Arlo no tiene verificación disponible");
        }
        String wanted = config.arloFactorType().toUpperCase(Locale.US);
        for (int index = 0; index < items.length(); index++) {
            JSONObject factor = items.optJSONObject(index);
            if (factor != null
                    && wanted.equalsIgnoreCase(factor.optString("factorType"))) {
                factorId = factor.optString("factorId", "");
                break;
            }
        }
        if (factorId.isEmpty()) {
            throw new ArloException(
                    "No existe verificación Arlo por " + wanted
            );
        }
        ApiResponse started = startAuth(factorId);
        requireSuccess(started, "Arlo no pudo enviar el código");
        pendingFactorAuthCode = asObject(started.data)
                .optString("factorAuthCode", "");
        if (pendingFactorAuthCode.isEmpty()) {
            throw new ArloException("Arlo no entregó la solicitud de verificación");
        }
        return new LoginStep(true);
    }

    private ApiResponse startAuth(String factorId) throws Exception {
        if (factorId == null || factorId.isEmpty()) {
            throw new ArloException("Arlo no entregó el identificador de verificación");
        }
        return authRequest(
                "/api/startAuth",
                "POST",
                new JSONObject()
                        .put("factorId", factorId)
                        .put("factorType", "BROWSER")
                        .put("userId", userId),
                true
        );
    }

    private boolean finishSession() throws Exception {
        ApiResponse validated = authRequest(
                "/api/validateAccessToken?data=" + System.currentTimeMillis(),
                "GET",
                null,
                true
        );
        requireSuccess(validated, "La sesión Arlo no fue validada");
        saveSession();
        apiRequest("/hmsweb/users/session/v3", "GET", null, null);
        loadDevices();
        connected = cameras.size() >= 2;
        return connected;
    }

    private void pairBrowserIfNeeded() {
        if (!needsPairing || browserAuthCode.isEmpty()) {
            return;
        }
        try {
            authRequest(
                    "/api/startPairingFactor",
                    "POST",
                    new JSONObject()
                            .put("factorAuthCode", browserAuthCode)
                            .put("factorData", "")
                            .put("factorType", "BROWSER"),
                    true
            );
        } catch (Exception ignored) {
        }
    }

    private void updateAuth(JSONObject value) throws Exception {
        JSONObject data = value.optJSONObject("accessToken");
        if (data == null) {
            data = value;
        }
        String newToken = data.optString("token", "");
        String newUserId = data.optString("userId", "");
        if (!newToken.isEmpty()) {
            token = newToken;
        }
        if (!newUserId.isEmpty()) {
            userId = newUserId;
        }
        browserAuthCode = data.optString(
                "browserAuthCode",
                browserAuthCode
        );
        if (token.isEmpty() || userId.isEmpty()) {
            throw new ArloException("Arlo no entregó una sesión válida");
        }
    }

    private void saveSession() {
        secrets.put("arlo_token", token);
        secrets.put("arlo_user_id", userId);
        secrets.put("arlo_browser_code", browserAuthCode);
        secrets.put("arlo_session_email", config.arloEmail());
        saveCookies();
    }

    private void saveCookies() {
        JSONArray values = new JSONArray();
        try {
            for (HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
                values.put(
                        new JSONObject()
                                .put("name", cookie.getName())
                                .put("value", cookie.getValue())
                                .put("domain", cookie.getDomain())
                                .put("path", cookie.getPath())
                                .put("maxAge", cookie.getMaxAge())
                                .put("secure", cookie.getSecure())
                                .put("version", cookie.getVersion())
                );
            }
            secrets.put("arlo_cookies", values.toString());
        } catch (Exception ignored) {
        }
    }

    private void restoreCookies() {
        String saved = secrets.get("arlo_cookies");
        if (saved.isEmpty()) {
            return;
        }
        try {
            JSONArray values = new JSONArray(saved);
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) {
                    continue;
                }
                HttpCookie cookie = new HttpCookie(
                        value.optString("name", ""),
                        value.optString("value", "")
                );
                String domain = value.optString("domain", "");
                String path = value.optString("path", "/");
                if (!domain.isEmpty()) {
                    cookie.setDomain(domain);
                }
                cookie.setPath(path.isEmpty() ? "/" : path);
                cookie.setMaxAge(value.optLong("maxAge", -1));
                cookie.setSecure(value.optBoolean("secure", true));
                cookie.setVersion(value.optInt("version", 0));
                String host = domain.replaceFirst("^\\.", "");
                if (host.isEmpty()) {
                    host = "ocapi-app.arlo.com";
                }
                cookieManager.getCookieStore().add(
                        new URI("https://" + host + "/"),
                        cookie
                );
            }
        } catch (Exception ignored) {
            cookieManager.getCookieStore().removeAll();
            secrets.remove("arlo_cookies");
        }
    }

    private void loadDevices() throws Exception {
        ApiResponse response = apiRequest(
                "/hmsweb/v2/users/devices?t=" + System.currentTimeMillis(),
                "GET",
                null,
                null
        );
        requireSuccess(response, "Arlo no entregó la lista de cámaras");
        JSONArray values;
        if (response.data instanceof JSONArray) {
            values = (JSONArray) response.data;
        } else {
            JSONObject object = asObject(response.data);
            values = object.optJSONArray("items");
        }
        if (values == null) {
            throw new ArloException("La respuesta Arlo no contiene dispositivos");
        }
        List<CameraInfo> found = new ArrayList<>();
        bases.clear();
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            if (item == null) {
                continue;
            }
            CameraInfo info = CameraInfo.from(item);
            String type = info.deviceType.toLowerCase(Locale.US);
            if ("camera".equals(type)
                    || "arloq".equals(type)
                    || "arloqs".equals(type)) {
                found.add(info);
            }
            if ("basestation".equals(type)
                    || "arlobridge".equals(type)
                    || "hub".equals(type)) {
                bases.put(info.deviceId, info);
            }
        }
        found.sort(Comparator.comparingInt(ArloDirectClient::cameraRank));
        cameras.clear();
        cameras.addAll(found.subList(0, Math.min(2, found.size())));
        if (cameras.size() < 2) {
            throw new ArloException("La cuenta Arlo no muestra las dos cámaras");
        }
    }

    private StreamResult requestStream(CameraInfo camera) throws Exception {
        String resource = "cameras/" + camera.deviceId;
        Map<String, String> extra = new HashMap<>();
        extra.put("xcloudId", camera.xcloudId);

        JSONObject startBody = streamBody(camera, resource, "set")
                .put(
                        "properties",
                        new JSONObject()
                                .put("activityState", "startUserStream")
                                .put("cameraId", camera.deviceId)
                );
        ApiResponse started = apiRequest(
                "/hmsweb/users/devices/startStream",
                "POST",
                startBody,
                extra
        );
        String url = streamUrl(started);
        if (!url.isEmpty()) {
            return new StreamResult(url, true);
        }

        // La URL de un stream activo puede pertenecer a otra sesión o estar
        // vencida. Solamente se usa como alternativa si Arlo no pudo crear una
        // dirección nueva propia. Nunca se envía idle al cerrar la APK.
        JSONObject getBody = streamBody(camera, resource, "get")
                .put(
                        "properties",
                        new JSONObject().put("cameraId", camera.deviceId)
                );
        ApiResponse existing = apiRequest(
                "/hmsweb/users/devices/startStream",
                "POST",
                getBody,
                extra
        );
        url = streamUrl(existing);
        if (!url.isEmpty()) {
            return new StreamResult(url, false);
        }

        // "Unknown" es una respuesta temporal que aparece especialmente
        // cuando Arlo termina de activar una cámara. Tras consultar el estado,
        // se permite un segundo inicio, siempre sin mandar idle.
        Thread.sleep(700);
        ApiResponse retried = apiRequest(
                "/hmsweb/users/devices/startStream",
                "POST",
                startBody.put("transId", "web!" + UUID.randomUUID()),
                extra
        );
        url = streamUrl(retried);
        if (!url.isEmpty()) {
            return new StreamResult(url, true);
        }
        String detail = firstUsefulError(retried, existing, started);
        throw new ArloException(
                detail.isEmpty()
                        ? "Arlo no devolvió una dirección de video válida"
                        : detail
        );
    }

    private JSONObject streamBody(
            CameraInfo camera,
            String resource,
            String action
    ) throws Exception {
        return new JSONObject()
                .put("action", action)
                .put("from", userId + "_web")
                .put("publishResponse", true)
                .put("responseUrl", "")
                .put("resource", resource)
                .put("to", camera.parentId)
                .put("transId", "web!" + UUID.randomUUID());
    }

    private String streamUrl(ApiResponse response) {
        if (!response.ok()) {
            return "";
        }
        String url = "";
        if (response.data instanceof JSONObject) {
            url = ((JSONObject) response.data).optString("url", "");
        } else if (response.data instanceof String) {
            String value = ((String) response.data).trim();
            if (value.startsWith("rtsp://") || value.startsWith("rtsps://")) {
                url = value;
            } else if (value.startsWith("{")) {
                try {
                    url = new JSONObject(value).optString("url", "");
                } catch (Exception ignored) {
                }
            }
        }
        return url.replace("rtsp://", "rtsps://");
    }

    private static String firstUsefulError(ApiResponse... responses) {
        for (ApiResponse response : responses) {
            if (response != null && response.error != null) {
                String value = response.error.trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private void notifyPrivacy(CameraInfo camera, boolean enabled) throws Exception {
        CameraInfo base = bases.get(camera.parentId);
        String xcloud = base != null && !base.xcloudId.isEmpty()
                ? base.xcloudId
                : camera.xcloudId;
        JSONObject body = new JSONObject()
                .put("action", "set")
                .put("resource", "cameras/" + camera.deviceId)
                .put("publishResponse", true)
                .put(
                        "properties",
                        new JSONObject().put("privacyActive", enabled)
                )
                .put("to", camera.parentId)
                .put("from", userId + "_web")
                .put("transId", "web!" + UUID.randomUUID());
        Map<String, String> extra = new HashMap<>();
        extra.put("xcloudId", xcloud);
        ApiResponse response = apiRequest(
                "/hmsweb/users/devices/notify/" + camera.parentId,
                "POST",
                body,
                extra
        );
        requireSuccess(
                response,
                "Arlo rechazó la privacidad de " + camera.name
        );
    }

    private CameraInfo cameraForKey(String key) {
        int index = "arlo2".equals(key) ? 1 : 0;
        synchronized (cameras) {
            return cameras.size() > index ? cameras.get(index) : null;
        }
    }

    private String connectedMessage() {
        synchronized (cameras) {
            return "ARLO CONECTADO · " + cameras.get(0).name
                    + " · " + cameras.get(1).name;
        }
    }

    private ApiResponse authRequest(
            String path,
            String method,
            JSONObject body,
            boolean authorized
    ) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "en-GB,en;q=0.9,en-US;q=0.8");
        headers.put("Cache-Control", "no-cache");
        headers.put("Content-Type", "application/json");
        headers.put("Origin", "https://my.arlo.com");
        headers.put("Pragma", "no-cache");
        headers.put("Referer", "https://my.arlo.com/");
        headers.put("User-Agent", USER_AGENT);
        headers.put("X-Service-Version", "3");
        headers.put("X-User-Device-Automation-Name", "QlJPV1NFUg==");
        headers.put("X-User-Device-Id", config.arloDeviceId());
        headers.put("X-User-Device-Type", "BROWSER");
        if (authorized) {
            headers.put(
                    "Authorization",
                    Base64.encodeToString(
                            token.getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP
                    )
            );
        }
        return request(AUTH_HOST + path, method, body, headers);
    }

    private ApiResponse apiRequest(
            String path,
            String method,
            JSONObject body,
            Map<String, String> extra
    ) throws Exception {
        String separator = path.contains("?") ? "&" : "?";
        String transaction = "web!" + UUID.randomUUID();
        String fullPath = path + separator + "eventId=" + transaction
                + "&time=" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Accept-Language", "en-GB,en;q=0.9,en-US;q=0.8");
        headers.put("Auth-Version", "2");
        headers.put("Authorization", token);
        headers.put("Cache-Control", "no-cache");
        headers.put("Content-Type", "application/json; charset=utf-8;");
        headers.put("Origin", "https://my.arlo.com");
        headers.put("Pragma", "no-cache");
        headers.put("Referer", "https://my.arlo.com/");
        headers.put("SchemaVersion", "1");
        headers.put("User-Agent", USER_AGENT);
        headers.put("x-transaction-id", transaction);
        if (extra != null) {
            headers.putAll(extra);
        }
        // El backend Arlo no procesa de forma fiable dos startStream
        // simultáneos. PyAarlo aplica el mismo tipo de exclusión mutua.
        synchronized (apiRequestLock) {
            return request(API_HOST + fullPath, method, body, headers);
        }
    }

    private ApiResponse request(
            String address,
            String method,
            JSONObject body,
            Map<String, String> headers
    ) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(25_000);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null && !header.getValue().isEmpty()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        if (body != null && !"GET".equals(method)) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        }
        int httpStatus = connection.getResponseCode();
        InputStream input = httpStatus >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        String text = readAll(input);
        connection.disconnect();
        if (text.trim().isEmpty()) {
            return new ApiResponse(httpStatus, null, "Respuesta vacía");
        }
        Object decoded;
        try {
            decoded = new JSONTokener(text).nextValue();
        } catch (Exception exception) {
            return new ApiResponse(
                    httpStatus >= 400 ? httpStatus : 502,
                    null,
                    "Arlo entregó una respuesta ilegible; se volverá a intentar"
            );
        }
        if (decoded instanceof JSONArray) {
            return new ApiResponse(httpStatus, decoded, "");
        }
        if (!(decoded instanceof JSONObject)) {
            String value = decoded == null ? "" : String.valueOf(decoded).trim();
            String message = "Unknown".equalsIgnoreCase(value)
                    ? "Arlo respondió temporalmente Unknown; reintentando video"
                    : "Arlo entregó una respuesta temporal; reintentando video";
            return new ApiResponse(
                    httpStatus >= 400 ? httpStatus : 503,
                    null,
                    message
            );
        }
        JSONObject root = (JSONObject) decoded;
        if (root.has("meta")) {
            JSONObject meta = root.optJSONObject("meta");
            int code = meta == null ? httpStatus : meta.optInt("code", httpStatus);
            String error = meta == null ? "" : meta.optString("message", "");
            return new ApiResponse(code, root.opt("data"), error);
        }
        if (root.has("success")) {
            boolean success = root.optBoolean("success", false);
            Object data = root.has("data")
                    ? root.opt("data")
                    : new JSONObject();
            return new ApiResponse(
                    success ? 200 : Math.max(400, httpStatus),
                    data,
                    root.optString("message", "")
            );
        }
        return new ApiResponse(httpStatus, root, "");
    }

    private static JSONObject asObject(Object value) throws ArloException {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        throw new ArloException("Arlo entregó una respuesta inesperada");
    }

    private static void requireSuccess(ApiResponse response, String message)
            throws ArloException {
        if (!response.ok()) {
            String detail = response.error == null ? "" : response.error.trim();
            throw new ArloException(
                    detail.isEmpty() ? message : message + " · " + detail
            );
        }
    }

    private static int cameraRank(CameraInfo camera) {
        String name = camera.name.toLowerCase(Locale.US);
        if (name.contains("puerta")) {
            return 0;
        }
        if (name.contains("percha") || name.contains("entrada")) {
            return 1;
        }
        return 10;
    }

    private static String safeMessage(Exception exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return fallback;
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private void postLogin(
            LoginCallback callback,
            boolean ok,
            boolean needsCode,
            String message
    ) {
        main.post(() -> callback.onResult(ok, needsCode, message));
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "{}";
        }
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class LoginStep {
        final boolean needsCode;

        LoginStep(boolean needsCode) {
            this.needsCode = needsCode;
        }
    }

    private static final class ApiResponse {
        final int code;
        final Object data;
        final String error;

        ApiResponse(int code, Object data, String error) {
            this.code = code;
            this.data = data;
            this.error = error;
        }

        boolean ok() {
            return code == 200 && data != null && data != JSONObject.NULL;
        }
    }

    private static final class CachedStream {
        final String url;
        final long createdAt;
        final boolean owned;

        CachedStream(String url, long createdAt, boolean owned) {
            this.url = url;
            this.createdAt = createdAt;
            this.owned = owned;
        }
    }

    private static final class StreamResult {
        final String url;
        final boolean owned;

        StreamResult(String url, boolean owned) {
            this.url = url;
            this.owned = owned;
        }
    }

    private static final class CameraInfo {
        final String name;
        final String deviceId;
        final String parentId;
        final String xcloudId;
        final String deviceType;

        CameraInfo(
                String name,
                String deviceId,
                String parentId,
                String xcloudId,
                String deviceType
        ) {
            this.name = name;
            this.deviceId = deviceId;
            this.parentId = parentId;
            this.xcloudId = xcloudId;
            this.deviceType = deviceType;
        }

        static CameraInfo from(JSONObject value) {
            JSONObject properties = value.optJSONObject("properties");
            String xcloud = value.optString("xCloudId", "");
            if (xcloud.isEmpty() && properties != null) {
                xcloud = properties.optString("xCloudId", "");
            }
            String deviceId = value.optString("deviceId", "");
            return new CameraInfo(
                    value.optString("deviceName", "Arlo"),
                    deviceId,
                    value.optString("parentId", deviceId),
                    xcloud,
                    value.optString("deviceType", "")
            );
        }
    }

    private static final class ArloException extends Exception {
        ArloException(String message) {
            super(message);
        }
    }
}
