package com.radargreen.watch;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Lifecycle-aware, no-network battery and optional step-counter data. */
public final class RadarData implements SensorEventListener {
    private final Context context;
    private final RadarWeather weather;
    private final SensorManager sensors;
    private final Sensor stepCounter;
    private boolean listening;
    private volatile int steps = -1;
    private int battery = -1;
    private long batteryChecked;

    public RadarData(Context context) {
        this.context = context.getApplicationContext();
        weather = new RadarWeather(this.context);
        sensors = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        stepCounter = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
    }

    public void start() {
        weather.start();
        if (!listening && stepCounter != null) {
            listening = sensors.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    public void stop() {
        weather.stop();
        if (listening) sensors.unregisterListener(this);
        listening = false;
    }

    public int battery() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (batteryChecked == 0 || now - batteryChecked > 30000) {
            batteryChecked = now;
            Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status != null) {
                int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) battery = Math.round(100f * level / scale);
            }
        }
        return battery;
    }

    public int steps() { return steps; }

    public String outdoorTemperatureText() { return weather.temperatureText(); }

    public void refreshWeather() { weather.checkNow(); }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_STEP_COUNTER || event.values.length == 0) return;
        long total = (long) event.values[0];
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        SharedPreferences prefs = context.getSharedPreferences("radar_steps", Context.MODE_PRIVATE);
        String storedDay = prefs.getString("date", "");
        long base = prefs.getLong("baseline", -1);
        if (!day.equals(storedDay) || base < 0 || total < base) {
            // Android step-counter values are cumulative since reboot. This baseline
            // is from the first measurement of the day, not guaranteed midnight.
            base = total;
            prefs.edit().putString("date", day).putLong("baseline", base).apply();
        }
        steps = (int)Math.min(99999, Math.max(0L, total - base));
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
