package com.radargreen.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Weather via wttr.in; city selection without GPS or dangerous Android permissions. */
final class RadarWeather {
    private static final long HOUR_MS = 60 * 60 * 1000L;
    private static final long CHECK_INTERVAL_MS = 60 * 1000L;
    private static final long RETRY_MS = 15 * 60 * 1000L;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean active;
    private volatile boolean busy;
    private volatile boolean hasTemperature;
    private volatile int temperature;
    private volatile String city;
    private long lastCheckElapsed;
    private long lastFailedWall;
    private long lastSuccessfulWall;

    RadarWeather(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("radar_weather", Context.MODE_PRIVATE);
        city = prefs.getString("city", "Kazan");
        hasTemperature = prefs.getBoolean("has_temperature", false);
        temperature = prefs.getInt("temperature_c", 0);
        lastSuccessfulWall = prefs.getLong("updated_at", 0);
        lastFailedWall = prefs.getLong("failed_at", 0);
    }

    String city() { return city; }

    void setCity(String chosen) {
        if (chosen == null) return;
        String clean = chosen.trim();
        if (clean.length() == 0 || clean.length() > 60) return;
        city = clean;
        hasTemperature = false; // Don't show weather from the old city
        lastSuccessfulWall = 0;
        lastFailedWall = 0;
        prefs.edit().putString("city", clean).putBoolean("has_temperature", false)
                .putLong("updated_at", 0).putLong("failed_at", 0).apply();
        checkNow();
    }

    void start() { active = true; checkNow(); }
    void stop() { active = false; }

    String temperatureText() {
        if (active && SystemClock.elapsedRealtime() - lastCheckElapsed >= CHECK_INTERVAL_MS) checkNow();
        if (!hasTemperature) return "OUT --";
        return String.format(Locale.US, "OUT %s%dC", temperature > 0 ? "+" : "", temperature);
    }

    void checkNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::checkNow); return; }
        if (!active || busy) return;
        long now = System.currentTimeMillis();
        lastCheckElapsed = SystemClock.elapsedRealtime();
        if (now >= lastSuccessfulWall && now - lastSuccessfulWall < HOUR_MS) return;
        if (now >= lastFailedWall && now - lastFailedWall < RETRY_MS) return;
        if (!isConnected()) return;
        fetchAsync(city);
    }

    private boolean isConnected() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void fetchAsync(String requestCity) {
        if (busy || !isConnected()) return;
        busy = true;
        io.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String path = URLEncoder.encode(requestCity, "UTF-8").replace("+", "%20");
                String endpoint = "https://wttr.in/" + path + "?format=j1";
                conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "RadarGreenWatch/2.3 (personal smartwatch weather; hourly)");
                if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
                StringBuilder body = new StringBuilder();
                try (BufferedReader input = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = input.readLine()) != null && body.length() < 180000) body.append(line);
                }
                JSONArray conditions = new JSONObject(body.toString()).getJSONArray("current_condition");
                final int value = (int) Math.round(Double.parseDouble(conditions.getJSONObject(0).getString("temp_C")));
                if (value < -100 || value > 70) throw new Exception("Bad temperature");
                main.post(() -> {
                    busy = false;
                    if (!requestCity.equals(city)) { checkNow(); return; }
                    hasTemperature = true;
                    temperature = value;
                    lastSuccessfulWall = System.currentTimeMillis();
                    lastFailedWall = 0;
                    prefs.edit().putInt("temperature_c", value).putBoolean("has_temperature", true)
                        .putLong("updated_at", lastSuccessfulWall).putLong("failed_at", 0).apply();
                });
            } catch (Exception ex) {
                main.post(() -> {
                    busy = false;
                    if (!requestCity.equals(city)) { checkNow(); return; }
                    lastFailedWall = System.currentTimeMillis();
                    prefs.edit().putLong("failed_at", lastFailedWall).apply();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
