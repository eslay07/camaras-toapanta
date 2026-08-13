package com.jimmytoapanta.camaras;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 15, 22);
    private static final int SURFACE = Color.rgb(18, 26, 36);
    private static final int SURFACE_2 = Color.rgb(24, 35, 48);
    private static final int TEXT = Color.rgb(244, 247, 250);
    private static final int MUTED = Color.rgb(147, 164, 183);
    private static final int ACCENT = Color.rgb(54, 211, 153);
    private static final int WARNING = Color.rgb(251, 191, 90);

    private AppConfig config;
    private SecureStore secrets;
    private ArloDirectClient arlo;
    private PtzClient ptz;
    private FrameLayout appShell;
    private FrameLayout fullscreenOverlay;
    private GridLayout cameraGrid;
    private LinearLayout normalCameraParent;
    private int normalCameraIndex;
    private Spinner viewSpinner;
    private Spinner primarySpinner;
    private Spinner secondarySpinner;
    private Switch audioSwitch;
    private TextView ptzStatus;
    private TextView connectionStatus;
    private Button privacyButton;
    private final Map<String, CameraTile> tiles = new HashMap<>();
    private boolean updatingControls;
    private boolean resumed;
    private boolean privacyActive;
    private boolean arloBusy;
    private boolean codeDialogShowing;
    private Boolean pendingPrivacyAction;
    private String fullscreenKey;
    private boolean globalFullscreen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        config = new AppConfig(this);
        secrets = new SecureStore(this);
        arlo = new ArloDirectClient(config, secrets);
        ptz = new PtzClient(config);
        privacyActive = config.privacyEnabled();
        pendingPrivacyAction = privacyActive ? Boolean.TRUE : null;
        buildInterface();
        applySavedControls();
        applyPrivacyState(privacyActive);
        relayoutAndSync();
    }

    private void buildInterface() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BG);

        appShell = new FrameLayout(this);
        appShell.setBackgroundColor(Color.BLACK);
        appShell.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        fullscreenOverlay = new FrameLayout(this);
        fullscreenOverlay.setBackgroundColor(Color.BLACK);
        fullscreenOverlay.setVisibility(View.GONE);
        appShell.addView(fullscreenOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(10), dp(7), dp(10), dp(8));
        page.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        setContentView(appShell);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView logo = text("◉", 31, ACCENT);
        logo.setGravity(Gravity.CENTER);
        header.addView(logo, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("CÁMARAS TOAPANTA · ANDROID LIGERO", 18, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleBox.addView(title);
        titleBox.addView(text("Hecho por Jimmy Toapanta", 11, MUTED));
        header.addView(titleBox, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(true);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(2), dp(8), dp(3));
        actionsScroll.addView(actions, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(actionsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(51)
        ));

        Button reconnect = button("Reconectar");
        reconnect.setOnClickListener(view -> reconnectAll());
        actions.addView(reconnect, buttonParams());
        Button globalFullscreenButton = button("Pantalla completa");
        globalFullscreenButton.setOnClickListener(view -> toggleGlobalFullscreen());
        actions.addView(globalFullscreenButton, buttonParams(dp(162)));
        Button settings = button("Configurar");
        settings.setOnClickListener(view -> showSettings());
        actions.addView(settings, buttonParams());
        privacyButton = button("Detener todo");
        privacyButton.setTextColor(Color.rgb(42, 7, 16));
        privacyButton.setBackground(solid(Color.rgb(251, 113, 133), 4));
        privacyButton.setOnClickListener(view -> togglePrivacy());
        actions.addView(privacyButton, buttonParams(dp(142)));
        connectionStatus = text("ARLO: ESPERANDO CONEXIÓN DIRECTA…", 10, WARNING);
        connectionStatus.setPadding(dp(10), 0, dp(8), 0);
        actions.addView(connectionStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
        ));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(true);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(8), 0, dp(8), 0);
        controls.setBackgroundColor(SURFACE);
        scroll.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        controls.addView(text("Vista", 11, MUTED));
        viewSpinner = spinner(new String[]{"1 cámara", "2 cámaras", "3 cámaras"});
        controls.addView(viewSpinner, new LinearLayout.LayoutParams(dp(130), dp(42)));
        controls.addView(space(10));
        controls.addView(text("Principal", 11, MUTED));
        primarySpinner = spinner(AppConfig.CAMERA_NAMES);
        controls.addView(primarySpinner, new LinearLayout.LayoutParams(dp(220), dp(42)));
        controls.addView(space(10));
        controls.addView(text("Segunda", 11, MUTED));
        secondarySpinner = spinner(AppConfig.CAMERA_NAMES);
        controls.addView(secondarySpinner, new LinearLayout.LayoutParams(dp(220), dp(42)));
        controls.addView(space(12));
        audioSwitch = new Switch(this);
        audioSwitch.setText("Audio principal");
        audioSwitch.setTextColor(TEXT);
        audioSwitch.setTextSize(11);
        controls.addView(audioSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
        ));

        HorizontalScrollView ptzScroll = new HorizontalScrollView(this);
        ptzScroll.setHorizontalScrollBarEnabled(true);
        LinearLayout ptzBar = new LinearLayout(this);
        ptzBar.setGravity(Gravity.CENTER_VERTICAL);
        ptzBar.setPadding(dp(8), dp(3), dp(8), dp(3));
        ptzBar.setBackgroundColor(SURFACE);
        ptzScroll.addView(ptzBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(ptzScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        ));
        ptzBar.addView(text("Mover genérica", 11, MUTED));
        Button left = squareButton("←");
        Button up = squareButton("↑");
        Button down = squareButton("↓");
        Button right = squareButton("→");
        bindMove(left, -0.35f, 0f);
        bindMove(up, 0f, 0.35f);
        bindMove(down, 0f, -0.35f);
        bindMove(right, 0.35f, 0f);
        ptzBar.addView(left);
        ptzBar.addView(up);
        ptzBar.addView(down);
        ptzBar.addView(right);
        Button home = squareButton("⌂");
        home.setOnClickListener(view -> ptz.home(this::showPtzStatus));
        ptzBar.addView(home);
        Button stop = squareButton("■");
        stop.setOnClickListener(view -> ptz.stop(this::showPtzStatus));
        ptzBar.addView(stop);
        ptzStatus = text("PTZ LISTO", 10, ACCENT);
        ptzStatus.setPadding(dp(8), 0, 0, 0);
        ptzBar.addView(ptzStatus);
        TextView directInfo = text(
                "MODO INDEPENDIENTE · PC NO NECESARIA",
                10,
                MUTED
        );
        directInfo.setPadding(dp(12), 0, dp(8), 0);
        directInfo.setGravity(Gravity.CENTER_VERTICAL);
        ptzBar.addView(directInfo, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                0f
        ));

        cameraGrid = new GridLayout(this);
        cameraGrid.setBackgroundColor(BG);
        root.addView(cameraGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
        ));
        normalCameraParent = root;
        normalCameraIndex = root.indexOfChild(cameraGrid);

        tiles.put(
                "arlo1",
                new CameraTile(this, "Puerta Principal", "Arlo Pro · VMC4030")
        );
        tiles.put(
                "arlo2",
                new CameraTile(this, "Entrada Perchas", "Arlo Pro · VMC4030")
        );
        tiles.put(
                "generica",
                new CameraTile(this, "Cámara genérica", "H.265 · PCMA · PTZ")
        );
        for (String key : AppConfig.KEYS) {
            CameraTile tile = tiles.get(key);
            if (tile != null) {
                tile.setFullscreenAction(() -> enterCameraFullscreen(key));
            }
        }

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!updatingControls) {
                    saveControls();
                    relayoutAndSync();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        viewSpinner.setOnItemSelectedListener(listener);
        primarySpinner.setOnItemSelectedListener(listener);
        secondarySpinner.setOnItemSelectedListener(listener);
        audioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingControls) {
                saveControls();
                updateAudioSelection();
            }
        });
    }

    private void applySavedControls() {
        updatingControls = true;
        String mode = config.viewMode();
        viewSpinner.setSelection("dual".equals(mode) ? 1 : ("three".equals(mode) ? 2 : 0));
        primarySpinner.setSelection(AppConfig.indexOfKey(config.primary()));
        secondarySpinner.setSelection(AppConfig.indexOfKey(config.secondary()));
        audioSwitch.setChecked(config.audioEnabled());
        updatingControls = false;
    }

    private void saveControls() {
        String mode = viewSpinner.getSelectedItemPosition() == 1
                ? "dual"
                : (viewSpinner.getSelectedItemPosition() == 2 ? "three" : "single");
        String primary = AppConfig.KEYS[primarySpinner.getSelectedItemPosition()];
        String secondary = AppConfig.KEYS[secondarySpinner.getSelectedItemPosition()];
        if ("dual".equals(mode) && primary.equals(secondary)) {
            int next = (primarySpinner.getSelectedItemPosition() + 1) % AppConfig.KEYS.length;
            updatingControls = true;
            secondarySpinner.setSelection(next);
            updatingControls = false;
            secondary = AppConfig.KEYS[next];
        }
        config.saveLayout(mode, primary, secondary, audioSwitch.isChecked());
    }

    private List<String> visibleKeys() {
        List<String> visible = new ArrayList<>();
        if ("single".equals(config.viewMode())) {
            visible.add(config.primary());
        } else if ("dual".equals(config.viewMode())) {
            visible.add(config.primary());
            String secondary = config.secondary();
            if (secondary.equals(config.primary())) {
                secondary = AppConfig.KEYS[(AppConfig.indexOfKey(secondary) + 1) % 3];
            }
            visible.add(secondary);
        } else {
            visible.add("arlo1");
            visible.add("arlo2");
            visible.add("generica");
        }
        return visible;
    }

    private void relayoutAndSync() {
        if (fullscreenKey != null) {
            syncStreams(visibleKeys());
            return;
        }
        List<String> visible = visibleKeys();
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        layoutCameraGrid(visible, portrait);
        if (globalFullscreen) {
            syncStreams(visible);
            return;
        }
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int cameraHeight;
        if (portrait) {
            int perCamera = Math.max(dp(250), Math.round(screenWidth * 0.72f));
            cameraHeight = perCamera * visible.size();
        } else {
            cameraHeight = Math.max(dp(290), screenHeight - dp(215));
        }
        LinearLayout.LayoutParams gridParams =
                (LinearLayout.LayoutParams) cameraGrid.getLayoutParams();
        gridParams.height = cameraHeight;
        cameraGrid.setLayoutParams(gridParams);
        syncStreams(visible);
    }

    private void layoutCameraGrid(List<String> visible, boolean portrait) {
        cameraGrid.removeAllViews();
        if (visible.size() == 1) {
            cameraGrid.setColumnCount(1);
            cameraGrid.setRowCount(1);
            addTile(visible.get(0), 0, 0, 1, 1);
            secondarySpinner.setEnabled(false);
        } else if (visible.size() == 2) {
            cameraGrid.setColumnCount(portrait ? 1 : 2);
            cameraGrid.setRowCount(portrait ? 2 : 1);
            addTile(visible.get(0), 0, 0, 1, 1);
            addTile(visible.get(1), portrait ? 1 : 0, portrait ? 0 : 1, 1, 1);
            secondarySpinner.setEnabled(true);
        } else if (portrait) {
            cameraGrid.setColumnCount(1);
            cameraGrid.setRowCount(3);
            addTile("arlo1", 0, 0, 1, 1);
            addTile("arlo2", 1, 0, 1, 1);
            addTile("generica", 2, 0, 1, 1);
            secondarySpinner.setEnabled(true);
        } else {
            cameraGrid.setColumnCount(2);
            cameraGrid.setRowCount(2);
            addTile("arlo1", 0, 0, 1, 1);
            addTile("arlo2", 0, 1, 1, 1);
            addTile("generica", 1, 0, 1, 2);
            secondarySpinner.setEnabled(true);
        }
    }

    private void addTile(String key, int row, int column, int rowSpan, int columnSpan) {
        CameraTile tile = tiles.get(key);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(row, rowSpan, 1f),
                GridLayout.spec(column, columnSpan, 1f)
        );
        params.width = 0;
        params.height = 0;
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        cameraGrid.addView(tile, params);
    }

    private void syncStreams(List<String> visible) {
        if (!resumed) {
            return;
        }
        if (fullscreenKey != null) {
            visible = new ArrayList<>();
            visible.add(fullscreenKey);
        }
        if (privacyActive) {
            for (CameraTile tile : tiles.values()) {
                tile.stopForPrivacy();
            }
            return;
        }
        Set<String> visibleSet = new HashSet<>(visible);
        for (String key : AppConfig.KEYS) {
            CameraTile tile = tiles.get(key);
            if (!visibleSet.contains(key)) {
                tile.stop();
            } else if (!tile.isDesired()) {
                tile.play(resolverFor(key), audioFor(key));
            }
        }
        updateAudioSelection();
    }

    private void toggleGlobalFullscreen() {
        if (globalFullscreen) {
            exitGlobalFullscreen();
        } else {
            enterGlobalFullscreen();
        }
    }

    private void enterGlobalFullscreen() {
        if (privacyActive) {
            Toast.makeText(
                    this,
                    "Reactive las cámaras antes de abrir pantalla completa",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        if (fullscreenKey != null) {
            exitCameraFullscreen();
        }
        List<String> visible = visibleKeys();
        ViewGroup parent = (ViewGroup) cameraGrid.getParent();
        if (parent != null) {
            parent.removeView(cameraGrid);
        }
        globalFullscreen = true;
        fullscreenOverlay.removeAllViews();
        fullscreenOverlay.addView(cameraGrid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        Set<String> visibleSet = new HashSet<>(visible);
        for (Map.Entry<String, CameraTile> item : tiles.entrySet()) {
            item.getValue().setFullscreenMode(visibleSet.contains(item.getKey()));
        }
        fullscreenOverlay.setVisibility(View.VISIBLE);
        fullscreenOverlay.bringToFront();
        setImmersiveMode(true);
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        layoutCameraGrid(visible, portrait);
        syncStreams(visible);
        Toast.makeText(
                this,
                "Vista completa · pulse Atrás para salir",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void exitGlobalFullscreen() {
        if (!globalFullscreen) {
            return;
        }
        ViewGroup parent = (ViewGroup) cameraGrid.getParent();
        if (parent != null) {
            parent.removeView(cameraGrid);
        }
        globalFullscreen = false;
        for (CameraTile tile : tiles.values()) {
            tile.setFullscreenMode(false);
        }
        fullscreenOverlay.removeAllViews();
        fullscreenOverlay.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
        );
        int index = Math.min(normalCameraIndex, normalCameraParent.getChildCount());
        normalCameraParent.addView(cameraGrid, index, params);
        setImmersiveMode(false);
        relayoutAndSync();
    }

    private void enterCameraFullscreen(String key) {
        if (privacyActive) {
            Toast.makeText(
                    this,
                    "Reactive las cámaras antes de abrir pantalla completa",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        CameraTile tile = tiles.get(key);
        if (tile == null) {
            return;
        }
        if (globalFullscreen) {
            exitGlobalFullscreen();
        }
        if (fullscreenKey != null) {
            exitCameraFullscreen();
        }
        ViewGroup parent = (ViewGroup) tile.getParent();
        if (parent != null) {
            parent.removeView(tile);
        }
        fullscreenKey = key;
        fullscreenOverlay.removeAllViews();
        fullscreenOverlay.addView(tile, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        tile.setFullscreenMode(true);
        fullscreenOverlay.setVisibility(View.VISIBLE);
        fullscreenOverlay.bringToFront();
        setImmersiveMode(true);

        for (Map.Entry<String, CameraTile> item : tiles.entrySet()) {
            if (!key.equals(item.getKey())) {
                item.getValue().stop();
            }
        }
        tile.stop();
        if (resumed) {
            tile.play(resolverFor(key), audioFor(key));
        }
        Toast.makeText(
                this,
                "Pantalla completa · pulse Atrás para salir",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void exitCameraFullscreen() {
        if (fullscreenKey == null) {
            return;
        }
        String key = fullscreenKey;
        CameraTile tile = tiles.get(key);
        if (tile != null) {
            tile.stop();
            fullscreenOverlay.removeView(tile);
            tile.setFullscreenMode(false);
        }
        fullscreenKey = null;
        fullscreenOverlay.removeAllViews();
        fullscreenOverlay.setVisibility(View.GONE);
        setImmersiveMode(false);
        relayoutAndSync();
    }

    private void setImmersiveMode(boolean enabled) {
        int flags = enabled
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_VISIBLE;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private CameraTile.Resolver resolverFor(String key) {
        if ("generica".equals(key)) {
            return (renew, callback) ->
                    callback.onResult(config.genericUrl(), null);
        }
        return (renew, callback) ->
                arlo.resolve(key, renew, callback::onResult);
    }

    private boolean audioFor(String key) {
        String audible = fullscreenKey == null ? config.primary() : fullscreenKey;
        return audioSwitch.isChecked() && key.equals(audible);
    }

    private void updateAudioSelection() {
        for (String key : AppConfig.KEYS) {
            CameraTile tile = tiles.get(key);
            if (tile != null) {
                tile.setAudioEnabled(audioFor(key));
            }
        }
    }

    private void reconnectAll() {
        if (privacyActive) {
            Toast.makeText(
                    this,
                    "Privacidad activa: pulse Reactivar cámaras",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        for (CameraTile tile : tiles.values()) {
            tile.stop();
        }
        cameraGrid.postDelayed(() -> syncStreams(visibleKeys()), 400);
        connectArlo();
    }

    private void connectArlo() {
        if (connectionStatus == null) {
            return;
        }
        if (arlo.isConnected()) {
            showArloConnected("ARLO CONECTADO DIRECTAMENTE");
            if (pendingPrivacyAction != null) {
                sendArloPrivacy(pendingPrivacyAction);
            }
            return;
        }
        if (arloBusy) {
            return;
        }
        if (config.arloEmail().isEmpty() || config.arloPassword().isEmpty()) {
            connectionStatus.setTextColor(WARNING);
            connectionStatus.setText("CONFIGURE SU CUENTA ARLO EN EL TELÉFONO");
            return;
        }
        arloBusy = true;
        connectionStatus.setTextColor(WARNING);
        connectionStatus.setText("INICIANDO SESIÓN DIRECTA EN ARLO…");
        arlo.connect(this::handleArloLogin);
    }

    private void handleArloLogin(
            boolean connected,
            boolean needsCode,
            String message
    ) {
        arloBusy = false;
        if (needsCode) {
            connectionStatus.setTextColor(WARNING);
            connectionStatus.setText("ARLO REQUIERE CÓDIGO DE VERIFICACIÓN");
            showArloCodeDialog();
            return;
        }
        if (!connected) {
            connectionStatus.setTextColor(Color.rgb(251, 113, 133));
            connectionStatus.setText(message);
            return;
        }
        showArloConnected(message);
        if (pendingPrivacyAction != null) {
            sendArloPrivacy(pendingPrivacyAction);
        } else if (!privacyActive && resumed) {
            restartArloTiles();
        }
    }

    private void showArloConnected(String message) {
        connectionStatus.setTextColor(ACCENT);
        connectionStatus.setText(message);
    }

    private void restartArloTiles() {
        CameraTile first = tiles.get("arlo1");
        CameraTile second = tiles.get("arlo2");
        if (first != null) {
            first.stop();
        }
        if (second != null) {
            second.stop();
        }
        cameraGrid.postDelayed(() -> syncStreams(visibleKeys()), 350);
    }

    private void showArloCodeDialog() {
        if (codeDialogShowing || isFinishing()) {
            return;
        }
        codeDialogShowing = true;
        EditText code = edit("Código de Arlo", "");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(22);
        code.setGravity(Gravity.CENTER);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Verificar cuenta Arlo")
                .setMessage(
                        "Escriba el código que Arlo envió al correo o teléfono configurado."
                )
                .setView(code)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Verificar", null)
                .create();
        dialog.setOnDismissListener(ignored -> codeDialogShowing = false);
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(view -> {
                            String value = code.getText().toString().trim();
                            if (value.isEmpty()) {
                                code.setError("Escriba el código");
                                return;
                            }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                    .setEnabled(false);
                            arloBusy = true;
                            connectionStatus.setTextColor(WARNING);
                            connectionStatus.setText("VERIFICANDO CÓDIGO ARLO…");
                            arlo.submitCode(value, (connected, needsCode, message) -> {
                                arloBusy = false;
                                if (!connected) {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                            .setEnabled(true);
                                    code.setError(message);
                                    connectionStatus.setTextColor(
                                            Color.rgb(251, 113, 133)
                                    );
                                    connectionStatus.setText(message);
                                    return;
                                }
                                dialog.dismiss();
                                handleArloLogin(true, false, message);
                            });
                        })
        );
        dialog.show();
    }

    private void togglePrivacy() {
        boolean enable = !privacyActive;
        if (enable) {
            new AlertDialog.Builder(this)
                    .setTitle("Detener todas las cámaras")
                    .setMessage(
                            "La APK detendrá su video y enviará directamente el modo "
                                    + "privacidad a las dos Arlo. Así tampoco transmitirán "
                                    + "en la aplicación oficial de Arlo.\n\n"
                                    + "La genérica se detendrá dentro de Cámaras Toapanta. "
                                    + "Para bloquear también VLC u otra aplicación, esa cámara "
                                    + "necesita una función propia de privacidad o un enchufe inteligente."
                    )
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Detener todo", (dialog, which) ->
                            requestPrivacy(true))
                    .show();
        } else {
            requestPrivacy(false);
        }
    }

    private void requestPrivacy(boolean enabled) {
        pendingPrivacyAction = enabled;
        config.savePrivacy(enabled);
        applyPrivacyState(enabled);
        privacyButton.setEnabled(false);
        connectionStatus.setTextColor(WARNING);
        connectionStatus.setText(
                enabled ? "DETENIENDO CÁMARAS DIRECTAMENTE…"
                        : "REACTIVANDO CÁMARAS DIRECTAMENTE…"
        );
        if (arlo.isConnected()) {
            sendArloPrivacy(enabled);
        } else {
            privacyButton.setEnabled(true);
            connectArlo();
            Toast.makeText(
                    this,
                    enabled
                            ? "Video local detenido; conectando Arlo para apagar las dos cámaras"
                            : "Cámara genérica reactivada; conectando Arlo",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void sendArloPrivacy(boolean enabled) {
        privacyButton.setEnabled(false);
        arlo.setPrivacy(enabled, (privacy, message, error) -> {
            privacyButton.setEnabled(true);
            if (error != null && !error.isEmpty()) {
                connectionStatus.setTextColor(Color.rgb(251, 113, 133));
                connectionStatus.setText("PRIVACIDAD LOCAL · " + error);
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                return;
            }
            pendingPrivacyAction = null;
            connectionStatus.setTextColor(
                    privacy ? Color.rgb(251, 113, 133) : ACCENT
            );
            connectionStatus.setText(
                    privacy
                            ? "PRIVACIDAD ACTIVA · DOS ARLO APAGADAS"
                            : "CÁMARAS REACTIVADAS DIRECTAMENTE"
            );
            if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
            if (!privacy && resumed) {
                restartArloTiles();
            }
        });
    }

    private void applyPrivacyState(boolean enabled) {
        privacyActive = enabled;
        if (privacyButton != null) {
            privacyButton.setText(enabled ? "Reactivar cámaras" : "Detener todo");
            privacyButton.setTextColor(enabled
                    ? Color.rgb(5, 35, 25)
                    : Color.rgb(42, 7, 16));
            privacyButton.setBackground(solid(
                    enabled ? ACCENT : Color.rgb(251, 113, 133),
                    4
            ));
        }
        if (enabled) {
            for (CameraTile tile : tiles.values()) {
                tile.stopForPrivacy();
            }
        } else if (resumed) {
            syncStreams(visibleKeys());
        }
    }

    private void showSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(10), dp(22), 0);
        String oldEmail = config.arloEmail();
        String oldPassword = config.arloPassword();
        EditText email = edit("Correo de Arlo", oldEmail);
        email.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );
        EditText password = edit("Contraseña de Arlo", oldPassword);
        password.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        Spinner factor = spinner(new String[]{"EMAIL", "SMS"});
        factor.setSelection("SMS".equalsIgnoreCase(config.arloFactorType()) ? 1 : 0);
        EditText generic = edit("Dirección RTSP genérica", config.genericUrl());
        form.addView(labelAbove("Cuenta Arlo"));
        form.addView(email);
        form.addView(labelAbove("Contraseña Arlo (guardada cifrada)"));
        form.addView(password);
        form.addView(labelAbove("Dónde recibir el código Arlo"));
        form.addView(factor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        form.addView(labelAbove("RTSP de la cámara genérica"));
        form.addView(generic);

        new AlertDialog.Builder(this)
                .setTitle("Configurar Cámaras Toapanta")
                .setMessage(
                        "La APK se conecta directamente: no necesita una PC. "
                                + "La genérica debe estar en la misma red Wi‑Fi; "
                                + "Arlo requiere Internet."
                )
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String newEmail = email.getText().toString().trim();
                    String newPassword = password.getText().toString();
                    config.saveConnection(
                            newEmail,
                            newPassword,
                            factor.getSelectedItem().toString(),
                            generic.getText().toString()
                    );
                    if (!oldEmail.equalsIgnoreCase(newEmail)
                            || !oldPassword.equals(newPassword)) {
                        arlo.clearSession();
                    }
                    reconnectAll();
                    Toast.makeText(
                            this,
                            "Configuración guardada",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .show();
    }

    private void bindMove(Button button, float x, float y) {
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                ptzStatus.setTextColor(WARNING);
                ptzStatus.setText("MOVIENDO");
                ptz.move(x, y, this::showPtzStatus);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                ptz.stop(this::showPtzStatus);
                return true;
            }
            return true;
        });
    }

    private void showPtzStatus(boolean ok, String message) {
        ptzStatus.setTextColor(ok ? ACCENT : Color.rgb(251, 113, 133));
        ptzStatus.setText(message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (cameraGrid != null) {
            syncStreams(visibleKeys());
            connectArlo();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (cameraGrid != null && fullscreenKey == null) {
            cameraGrid.post(this::relayoutAndSync);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && (fullscreenKey != null || globalFullscreen)) {
            setImmersiveMode(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreenKey != null) {
            exitCameraFullscreen();
            return;
        }
        if (globalFullscreen) {
            exitGlobalFullscreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        resumed = false;
        for (CameraTile tile : tiles.values()) {
            tile.stop();
        }
        arlo.releaseStreams();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        arlo.shutdown();
        ptz.shutdown();
        super.onDestroy();
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                values
        );
        spinner.setAdapter(adapter);
        spinner.setPopupBackgroundDrawable(solid(Color.WHITE, 4));
        spinner.setBackground(solid(Color.WHITE, 4));
        spinner.setPadding(dp(8), 0, dp(4), 0);
        return spinner;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(TEXT);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setBackground(solid(SURFACE_2, 4));
        return button;
    }

    private Button squareButton(String value) {
        Button button = button(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(36));
        params.leftMargin = dp(3);
        button.setLayoutParams(params);
        button.setTextSize(15);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return buttonParams(dp(112));
    }

    private LinearLayout.LayoutParams buttonParams(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(42));
        params.leftMargin = dp(6);
        return params;
    }

    private TextView labelAbove(String value) {
        TextView label = text(value, 12, Color.DKGRAY);
        label.setPadding(0, dp(9), 0, dp(2));
        return label;
    }

    private EditText edit(String hint, String value) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value);
        edit.setSingleLine(true);
        return edit;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private View space(int width) {
        return new View(this) {{
            setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1));
        }};
    }

    private GradientDrawable solid(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
