package com.radargreen.watch;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class RadarWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new RadarEngine(); }

    private class RadarEngine extends Engine {
        private static final long AMBIENT_DELAY_MS = 15_000L;
        private static final long ACTIVE_FRAME_MS = 50L;
        private static final long AMBIENT_FRAME_MS = 1000L;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final RadarData data = new RadarData(RadarWallpaperService.this);
        private final RadarRenderer renderer = new RadarRenderer(data);
        private boolean visible;
        private boolean ambientMode;
        private long lastInteraction = SystemClock.elapsedRealtime();
        private final Runnable frame = new Runnable() {
            @Override public void run() { draw(); }
        };

        RadarEngine() {
            setTouchEventsEnabled(true);
        }

        private void markInteraction() {
            lastInteraction = SystemClock.elapsedRealtime();
            ambientMode = false;
        }

        private void updateMode() {
            ambientMode = SystemClock.elapsedRealtime() - lastInteraction >= AMBIENT_DELAY_MS;
        }

        private void draw() {
            handler.removeCallbacks(frame);
            if (!visible) return;
            updateMode();
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) renderer.draw(canvas, canvas.getWidth(), canvas.getHeight(), ambientMode);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }
            if (visible) handler.postDelayed(frame, ambientMode ? AMBIENT_FRAME_MS : ACTIVE_FRAME_MS);
        }

        @Override public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                data.start();
                markInteraction();
                draw();
            } else {
                data.stop();
                handler.removeCallbacks(frame);
            }
        }

        @Override public void onSurfaceChanged(SurfaceHolder h, int format, int w, int ht) {
            super.onSurfaceChanged(h, format, w, ht);
            draw();
        }

        @Override public void onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                markInteraction();
                draw();
            }
            super.onTouchEvent(event);
        }

        @Override public void onDestroy() {
            visible = false;
            data.stop();
            handler.removeCallbacks(frame);
            super.onDestroy();
        }
    }
}
