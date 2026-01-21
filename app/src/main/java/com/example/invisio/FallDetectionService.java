package com.example.invisio;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.Vibrator;
import android.util.Log;

/**
 * Fall/Shake Detection Service
 * Detects sudden movements that might indicate a fall or emergency shake
 */
public class FallDetectionService extends Service implements SensorEventListener {

    private static final String TAG = "FallDetection";
    private static final float FALL_THRESHOLD = 25.0f; // m/s² threshold for fall detection
    private static final float SHAKE_THRESHOLD = 20.0f; // m/s² threshold for emergency shake
    private static final long SHAKE_TIME_WINDOW = 500; // milliseconds

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long lastShakeTime = 0;
    private int shakeCount = 0;
    private Vibrator vibrator;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i(TAG, "Fall detection service started");
        } else {
            Log.w(TAG, "Accelerometer not available");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // Keep service running
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            // Calculate total acceleration
            float acceleration = (float) Math.sqrt(x * x + y * y + z * z);

            // Remove gravity (9.8 m/s²)
            float netAcceleration = Math.abs(acceleration - SensorManager.GRAVITY_EARTH);

            long currentTime = System.currentTimeMillis();

            // Detect potential fall (sudden high acceleration)
            if (netAcceleration > FALL_THRESHOLD) {
                Log.w(TAG, "Potential fall detected! Acceleration: " + netAcceleration);
                triggerEmergencyMode("Fall detected");
            }

            // Detect emergency shake (3 quick shakes)
            if (netAcceleration > SHAKE_THRESHOLD) {
                if (currentTime - lastShakeTime < SHAKE_TIME_WINDOW) {
                    shakeCount++;
                    Log.d(TAG, "Shake detected. Count: " + shakeCount);

                    if (shakeCount >= 3) {
                        Log.i(TAG, "Emergency shake pattern detected!");
                        triggerEmergencyMode("Emergency shake");
                        shakeCount = 0; // Reset
                    }
                } else {
                    shakeCount = 1; // Reset count if too much time passed
                }
                lastShakeTime = currentTime;
            }
        }
    }

    private void triggerEmergencyMode(String reason) {
        // Vibrate to confirm detection
        if (vibrator != null) {
            long[] pattern = {0, 200, 100, 200, 100, 200}; // Vibration pattern
            vibrator.vibrate(pattern, -1);
        }

        // Launch emergency activity
        Intent emergencyIntent = new Intent(this, EmergencyActivity.class);
        emergencyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        emergencyIntent.putExtra("trigger_reason", reason);
        startActivity(emergencyIntent);

        Log.i(TAG, "Emergency mode triggered: " + reason);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        Log.i(TAG, "Fall detection service stopped");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}