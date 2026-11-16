package com.radargreen.watch;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

public class RadarActivity extends Activity {
    private static final long AMBIENT_DELAY_MS = 15_000L;
    private static final long ACTIVE_FRAME_MS = 50L;   // ~20 FPS
    private static final long AMBIENT_FRAME_MS = 1000L; // update time/BAT once per second
    private static final float AMBIENT_SCREEN_BRIGHTNESS = 0.035f; // requested window brightness, device may clamp

    private RadarView radarView;
    private RadarData data;
    private boolean ambientMode;
    private long lastInteraction;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        // Keep the physical display on while this activity is foregrounded.
        // Without this flag the radar merely dims its drawing and Android still sleeps.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        data = new RadarData(this);
        radarView = new RadarView();
        setContentView(radarView);
        markInteraction();
        applyScreenBrightness(false);
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 22);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 22 && data != null) data.refreshWeather();
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
        applyScreenBrightness(false);
        super.onPause();
    }

    private void markInteraction() {
        lastInteraction = SystemClock.elapsedRealtime();
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

        RadarView() { super(RadarActivity.this); setClickable(true); }

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
                return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
