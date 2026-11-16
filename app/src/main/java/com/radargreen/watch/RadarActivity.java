package com.radargreen.watch;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

public class RadarActivity extends Activity {
    private static final long AMBIENT_DELAY_MS = 30_000L;
    private static final long RELEASE_SCREEN_MS = 60_000L;
    private static final long ACTIVE_FRAME_MS = 50L;   // ~20 FPS
    private static final long AMBIENT_FRAME_MS = 1000L; // update time/BAT once per second
    private static final float AMBIENT_SCREEN_BRIGHTNESS = 0.035f; // requested window brightness, device may clamp

    private RadarView radarView;
    private RadarData data;
    private boolean ambientMode;
    private boolean screenHoldActive;
    private long lastInteraction;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        // Keep the physical display on while this activity is foregrounded.
        // Without this flag the radar merely dims its drawing and Android still sleeps.
        setScreenHold(true);
        data = new RadarData(this);
        radarView = new RadarView();
        setContentView(radarView);
        markInteraction();
        applyScreenBrightness(false);
    }

    private void chooseCity() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(data.weatherCity());
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Weather city / Город")
                .setMessage("Введите город латиницей, например Kazan или Moscow")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    data.setWeatherCity(input.getText().toString());
                    markInteraction();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        data.start();
        markInteraction();
        applyScreenBrightness(false);
        radarView.removeCallbacks(frame);
        radarView.post(frame);
    }

    @Override protected void onPause() {
        radarView.removeCallbacks(frame);
        data.stop();
        setScreenHold(false);
        applyScreenBrightness(false);
        super.onPause();
    }

    private void markInteraction() {
        lastInteraction = SystemClock.elapsedRealtime();
        setScreenHold(true);
        if (ambientMode) {
            ambientMode = false;
            applyScreenBrightness(false);
            if (radarView != null) radarView.invalidate();
        }
    }

    private void updateMode() {
        long idle = SystemClock.elapsedRealtime() - lastInteraction;
        boolean nextMode = idle >= AMBIENT_DELAY_MS;
        if (ambientMode != nextMode) {
            ambientMode = nextMode;
            applyScreenBrightness(ambientMode);
        }
        // Standard Android applications cannot force the display to sleep
        // reliably without device-admin privileges. Release our screen hold
        // at 60s and allow the watch's configured timeout to switch it off.
        setScreenHold(idle < RELEASE_SCREEN_MS);
    }

    private void setScreenHold(boolean hold) {
        if (hold == screenHoldActive) return;
        screenHoldActive = hold;
        if (hold) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void applyScreenBrightness(boolean ambient) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        float desired = ambient ? AMBIENT_SCREEN_BRIGHTNESS : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (params.screenBrightness != desired) {
            params.screenBrightness = desired;
            getWindow().setAttributes(params);
        }
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (radarView == null) return;
            updateMode();
            radarView.invalidate();
            radarView.postDelayed(this, ambientMode ? AMBIENT_FRAME_MS : ACTIVE_FRAME_MS);
        }
    };

    private final class RadarView extends View {
        private final RadarRenderer radar = new RadarRenderer(data);

        RadarView() { super(RadarActivity.this); setClickable(true); setLongClickable(true);
            setOnLongClickListener(v -> { chooseCity(); return true; }); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            radar.draw(canvas, getWidth(), getHeight(), ambientMode);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                markInteraction();
                removeCallbacks(frame);
                post(frame);
            }
            return super.onTouchEvent(event);
        }
    }
}
