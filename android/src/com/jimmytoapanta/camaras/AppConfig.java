package com.jimmytoapanta.camaras;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

final class AppConfig {
    static final String[] KEYS = {"arlo1", "arlo2", "generica"};
    static final String[] CAMERA_NAMES = {
            "Arlo · Puerta Principal",
            "Arlo · Entrada Perchas",
            "Genérica · IPCamera"
    };

    private final SharedPreferences preferences;
    private final SecureStore secrets;

    AppConfig(Context context) {
        preferences = context.getSharedPreferences("camaras_toapanta", Context.MODE_PRIVATE);
        secrets = new SecureStore(context);
    }

    String arloEmail() {
        return preferences.getString("arlo_email", "");
    }

    String arloPassword() {
        return secrets.get("arlo_password");
    }

    String arloFactorType() {
        return preferences.getString("arlo_factor", "EMAIL");
    }

    String arloDeviceId() {
        String value = preferences.getString("arlo_device_id", "");
        if (value == null || value.isEmpty()) {
            value = UUID.randomUUID().toString();
            preferences.edit().putString("arlo_device_id", value).apply();
        }
        return value;
    }

    String genericUrl() {
        String value = preferences.getString(
                "generic_url",
                "rtsp://192.0.2.10:554/live/ch00_1"
        );
        value = value == null ? "" : value.trim();
        if (value.isEmpty()
                || "rtsp://192.0.2.10:554".equalsIgnoreCase(value)
                || "rtsp://192.0.2.10:554/".equalsIgnoreCase(value)) {
            value = "rtsp://192.0.2.10:554/live/ch00_1";
            preferences.edit().putString("generic_url", value).apply();
        }
        return value;
    }

    String viewMode() {
        return preferences.getString("view_mode", "single");
    }

    String primary() {
        return preferences.getString("primary", "arlo1");
    }

    String secondary() {
        return preferences.getString("secondary", "generica");
    }

    boolean audioEnabled() {
        return preferences.getBoolean("audio", false);
    }

    boolean privacyEnabled() {
        return preferences.getBoolean("privacy", false);
    }

    void saveConnection(
            String email,
            String password,
            String factorType,
            String genericUrl
    ) {
        preferences.edit()
                .putString("arlo_email", email.trim())
                .putString("arlo_factor", factorType.trim().toUpperCase())
                .putString("generic_url", genericUrl.trim())
                .apply();
        if (!password.isEmpty()) {
            secrets.put("arlo_password", password);
        }
    }

    void saveLayout(String mode, String primary, String secondary, boolean audio) {
        preferences.edit()
                .putString("view_mode", mode)
                .putString("primary", primary)
                .putString("secondary", secondary)
                .putBoolean("audio", audio)
                .apply();
    }

    void savePrivacy(boolean enabled) {
        preferences.edit().putBoolean("privacy", enabled).apply();
    }

    static int indexOfKey(String key) {
        for (int index = 0; index < KEYS.length; index++) {
            if (KEYS[index].equals(key)) {
                return index;
            }
        }
        return 0;
    }
}
