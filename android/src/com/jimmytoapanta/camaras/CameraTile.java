package com.jimmytoapanta.camaras;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

final class CameraTile extends FrameLayout
        implements TextureView.SurfaceTextureListener {
    interface ResolveCallback {
        void onResult(String url, String error);
    }

    interface Resolver {
        void resolve(boolean renew, ResolveCallback callback);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout header;
    private final TextureView textureView;
    private final FrameLayout.LayoutParams textureParams;
    private final TextView status;
    private final TextView liveBadge;
    private final Button fullscreenButton;
    private Resolver resolver;
    private RtspPlayer player;
    private Surface renderSurface;
    private Runnable fullscreenAction;
    private boolean desired;
    private boolean audioEnabled;
    private boolean surfaceReady;
    private int generation;

    CameraTile(Context context, String title, String detail) {
        super(context);
        setPadding(dp(1), dp(1), dp(1), dp(1));
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.rgb(5, 8, 13));
        border.setStroke(dp(1), Color.rgb(38, 52, 69));
        setBackground(border);

        header = new FrameLayout(context);
        header.setBackgroundColor(Color.rgb(24, 35, 48));
        LayoutParams headerParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(38),
                Gravity.TOP
        );
        addView(header, headerParams);

        TextView titleView = label(context, "●  " + title, 13, Color.rgb(244, 247, 250));
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT,
                Gravity.START
        );
        titleParams.leftMargin = dp(10);
        header.addView(titleView, titleParams);

        TextView detailView = label(context, detail, 10, Color.rgb(147, 164, 183));
        detailView.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams detailParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        header.addView(detailView, detailParams);

        liveBadge = label(context, "ESPERANDO", 10, Color.rgb(147, 164, 183));
        liveBadge.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT,
                Gravity.END
        );
        badgeParams.rightMargin = dp(52);
        header.addView(liveBadge, badgeParams);

        fullscreenButton = new Button(context);
        fullscreenButton.setText("⛶");
        fullscreenButton.setTextColor(Color.rgb(244, 247, 250));
        fullscreenButton.setTextSize(18);
        fullscreenButton.setAllCaps(false);
        fullscreenButton.setPadding(0, 0, 0, 0);
        fullscreenButton.setBackgroundColor(Color.TRANSPARENT);
        fullscreenButton.setContentDescription("Ver esta cámara en pantalla completa");
        fullscreenButton.setOnClickListener(view -> {
            if (fullscreenAction != null) {
                fullscreenAction.run();
            }
        });
        FrameLayout.LayoutParams fullParams = new FrameLayout.LayoutParams(
                dp(48),
                LayoutParams.MATCH_PARENT,
                Gravity.END
        );
        header.addView(fullscreenButton, fullParams);

        textureView = new TextureView(context);
        // TextureView rechaza fondos Drawable en varios Android antiguos.
        // El contenedor ya aporta el fondo oscuro mientras llega el video.
        textureView.setOpaque(true);
        textureView.setSurfaceTextureListener(this);
        textureParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
        );
        textureParams.topMargin = dp(39);
        addView(textureView, textureParams);

        status = label(context, "Esperando conexión", 13, Color.rgb(147, 164, 183));
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(Color.argb(130, 5, 8, 13));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(46),
                Gravity.CENTER
        );
        statusParams.topMargin = dp(19);
        addView(status, statusParams);
    }

    boolean isDesired() {
        return desired;
    }

    void setFullscreenAction(Runnable action) {
        fullscreenAction = action;
    }

    void setFullscreenMode(boolean enabled) {
        header.setVisibility(enabled ? GONE : VISIBLE);
        textureParams.topMargin = enabled ? 0 : dp(39);
        textureView.setLayoutParams(textureParams);
        requestLayout();
    }

    void play(Resolver resolver, boolean audioEnabled) {
        this.resolver = resolver;
        this.audioEnabled = audioEnabled;
        desired = true;
        generation++;
        if (surfaceReady) {
            resolveAndPlay(false, generation);
        } else {
            showStatus("PREPARANDO PANTALLA", false);
        }
    }

    void setAudioEnabled(boolean enabled) {
        audioEnabled = enabled;
        if (player != null) {
            player.setAudioEnabled(enabled);
        }
    }

    void stop() {
        desired = false;
        resolver = null;
        generation++;
        stopPlayer();
        showStatus("EN PAUSA", false);
    }

    void stopForPrivacy() {
        desired = false;
        resolver = null;
        generation++;
        stopPlayer();
        liveBadge.setText("DETENIDA");
        liveBadge.setTextColor(Color.rgb(251, 113, 133));
        status.setText("PRIVACIDAD ACTIVADA");
        status.setVisibility(VISIBLE);
    }

    private void resolveAndPlay(boolean renew, int requestGeneration) {
        if (!desired || resolver == null || !surfaceReady
                || renderSurface == null || !renderSurface.isValid()) {
            return;
        }
        stopPlayer();
        showStatus(renew ? "RENOVANDO VIDEO…" : "CONECTANDO…", false);
        resolver.resolve(renew, (url, error) -> {
            if (!desired || requestGeneration != generation || !surfaceReady) {
                return;
            }
            if (url == null || url.isEmpty()) {
                retry(error == null ? "Video no disponible" : error, requestGeneration);
                return;
            }
            player = new RtspPlayer(
                    url,
                    renderSurface,
                    audioEnabled,
                    new RtspPlayer.Listener() {
                        @Override
                        public void onStatus(String message) {
                            main.post(() -> {
                                if (requestGeneration == generation) {
                                    showStatus(message, "EN VIVO".equals(message));
                                }
                            });
                        }

                        @Override
                        public void onError(String message) {
                            main.post(() -> {
                                if (requestGeneration == generation) {
                                    retry(message, requestGeneration);
                                }
                            });
                        }
                    }
            );
            player.start();
        });
    }

    private void retry(String message, int requestGeneration) {
        showStatus(message + " · REINTENTO", false);
        main.postDelayed(() -> {
            if (desired && requestGeneration == generation && surfaceReady) {
                resolveAndPlay(true, requestGeneration);
            }
        }, 5000);
    }

    private void stopPlayer() {
        if (player != null) {
            player.stop();
            player = null;
        }
    }

    private void showStatus(String message, boolean live) {
        liveBadge.setText(live ? "EN VIVO" : "CONECTANDO");
        liveBadge.setTextColor(live
                ? Color.rgb(54, 211, 153)
                : Color.rgb(251, 191, 90));
        status.setText(message);
        status.setVisibility(live ? GONE : VISIBLE);
    }

    @Override
    public void onSurfaceTextureAvailable(
            SurfaceTexture surfaceTexture,
            int width,
            int height
    ) {
        releaseRenderSurface();
        renderSurface = new Surface(surfaceTexture);
        surfaceReady = true;
        if (desired && resolver != null) {
            resolveAndPlay(false, generation);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface,
            int width,
            int height
    ) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        surfaceReady = false;
        stopPlayer();
        releaseRenderSurface();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private void releaseRenderSurface() {
        if (renderSurface == null) {
            return;
        }
        try {
            renderSurface.release();
        } catch (Exception ignored) {
        }
        renderSurface = null;
    }

    private static TextView label(Context context, String text, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setSingleLine(true);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
