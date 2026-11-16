package com.radargreen.watch;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Polls Open-Meteo at most once/hour on success, only with connectivity and location consent. */
final class RadarWeather {
    private static final long HOUR_MS = 60 * 60 * 1000L;
    private static final long CHECK_INTERVAL_MS = 60 * 1000L;
    private static final long RETRY_MS = 15 * 60 * 1000L;
    private static final long LOCATION_MAX_AGE_MS = 24 * HOUR_MS;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean active;
    private volatile boolean busy;
    private volatile int temperature;
    private volatile boolean hasTemperature;
    private long lastCheckElapsed;
    private long lastFailedWall;
    private long lastSuccessfulWall;
    private LocationManager locationManager;
    private LocationListener pendingLocation;

    RadarWeather(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("radar_weather", Context.MODE_PRIVATE);
        hasTemperature = prefs.getBoolean("has_temperature", false);
        temperature = prefs.getInt("temperature_c", 0);
        lastSuccessfulWall = prefs.getLong("updated_at", 0);
        lastFailedWall = prefs.getLong("failed_at", 0);
    }

    void start() {
        active = true;
        checkNow();
    }

    void stop() {
        active = false;
        cancelLocation();
    }

    String temperatureText() {
        if (active && SystemClock.elapsedRealtime() - lastCheckElapsed >= CHECK_INTERVAL_MS) {
            checkNow();
        }
        if (!hasTemperature) return "OUT --";
        return String.format(Locale.US, "OUT %s%dC", temperature > 0 ? "+" : "", temperature);
    }

    void checkNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::checkNow);
            return;
        }
        if (!active || busy) return;
        long now = System.currentTimeMillis();
        lastCheckElapsed = SystemClock.elapsedRealtime();
        // A device clock can jump backward; that simply forces a fresh check.
        if (now >= lastSuccessfulWall && now - lastSuccessfulWall < HOUR_MS) return;
        if (now >= lastFailedWall && now - lastFailedWall < RETRY_MS) return;
        if (!isConnected()) return; // Absolutely no weather or location request offline.
        if (!hasLocationPermission()) return;

        Location location = lastUsableLocation();
        if (location != null) {
            fetchAsync(location);
        } else {
            requestOneLocationFix();
        }
    }

    private boolean isConnected() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private boolean hasLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private Location lastUsableLocation() {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return null;
        Location best = null;
        for (String provider : locationManager.getProviders(true)) {
            try {
                Location possible = locationManager.getLastKnownLocation(provider);
                if (possible != null && (best == null || possible.getTime() > best.getTime())) best = possible;
            } catch (SecurityException ignored) { }
        }
        if (best == null || Math.abs(System.currentTimeMillis() - best.getTime()) > LOCATION_MAX_AGE_MS) return null;
        return best;
    }

    private void requestOneLocationFix() {
        if (locationManager == null || pendingLocation != null) return;
        String provider;
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
            else if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                     locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
            else return;
        } catch (Exception ignored) { return; }

        busy = true;
        pendingLocation = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                cancelLocation();
                busy = false;
                if (active && isConnected()) fetchAsync(location);
            }
            @Override public void onStatusChanged(String p, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try {
            locationManager.requestLocationUpdates(provider, 0, 0f, pendingLocation, Looper.getMainLooper());
            main.postDelayed(() -> {
                if (pendingLocation != null) {
                    cancelLocation();
                    busy = false;
                    recordFailure();
                }
            }, 20_000);
        } catch (SecurityException | IllegalArgumentException ignored) {
            cancelLocation(); busy = false;
        }
    }

    private void cancelLocation() {
        if (locationManager != null && pendingLocation != null) {
            try { locationManager.removeUpdates(pendingLocation); } catch (SecurityException ignored) { }
        }
        pendingLocation = null;
        busy = false;
    }

    private void fetchAsync(Location location) {
        if (busy || !isConnected()) return;
        busy = true;
        final double lat = location.getLatitude(), lon = location.getLongitude();
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String endpoint = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m&temperature_unit=celsius&forecast_days=1",
                    lat, lon);
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
                StringBuilder body = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = in.readLine()) != null) body.append(line);
                }
                JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
                final int rounded = (int) Math.round(current.getDouble("temperature_2m"));
                main.post(() -> {
                    temperature = rounded;
                    hasTemperature = true;
                    lastSuccessfulWall = System.currentTimeMillis();
                    lastFailedWall = 0L;
                    prefs.edit().putInt("temperature_c", rounded).putBoolean("has_temperature", true)
                        .putLong("updated_at", lastSuccessfulWall).putLong("failed_at", 0L).apply();
                    busy = false;
                });
            } catch (Exception ignored) {
                main.post(() -> { busy = false; recordFailure(); });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void recordFailure() {
        lastFailedWall = System.currentTimeMillis();
        prefs.edit().putLong("failed_at", lastFailedWall).apply();
    }
}
