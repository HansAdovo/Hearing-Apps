package com.auditapp.hearingamp;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

public class AudioProcessingService extends Service {
    private static final String TAG = "AudioProcessingService";
    private boolean isProcessing = false;

    public static final String ACTION_PERMISSIONS_REQUIRED = "com.auditapp.hearingamp.ACTION_PERMISSIONS_REQUIRED";
    public static final String ACTION_PROCESSING_ERROR = "com.auditapp.hearingamp.ACTION_PROCESSING_ERROR";
    public static final String ACTION_UPDATE_PARAMS = "com.auditapp.hearingamp.ACTION_UPDATE_PARAMS";
    public static final String ACTION_SET_AMPLIFICATION = "com.auditapp.hearingamp.ACTION_SET_AMPLIFICATION";

    static {
        System.loadLibrary("hearingamp");
    }

    private native int startAudioProcessing();
    private native void stopAudioProcessing();
    private native void updateAudioParams(float[] thresholds, float[] ratios, float[] attacks, float[] releases, float[] gains);
    private native void setAmplification(float amp);

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_UPDATE_PARAMS.equals(action)) {
                updateParamsFromIntent(intent);
                return START_STICKY;
            } else if (ACTION_SET_AMPLIFICATION.equals(action)) {
                setAmplificationFromIntent(intent);
                return START_STICKY;
            }
        }

        if (!isProcessing) {
            if (checkPermission()) {
                startProcessing();
            } else {
                Log.e(TAG, "Audio permission not granted");
                sendBroadcast(new Intent(ACTION_PERMISSIONS_REQUIRED));
                stopSelf();
            }
        } else {
            Log.d(TAG, "Audio processing already running");
        }
        return START_STICKY;
    }

    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startProcessing() {
        Log.d(TAG, "Starting audio processing");
        try {
            int result = startAudioProcessing();
            if (result == 0) {
                isProcessing = true;
                Log.d(TAG, "Audio processing started successfully");
            } else {
                Log.e(TAG, "Failed to start audio processing. Error code: " + result);
                sendBroadcast(new Intent(ACTION_PROCESSING_ERROR));
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in audio processing", e);
            sendBroadcast(new Intent(ACTION_PROCESSING_ERROR));
            stopSelf();
        }
    }

    private void updateParamsFromIntent(Intent intent) {
        float[] thresholds = intent.getFloatArrayExtra("thresholds");
        float[] ratios = intent.getFloatArrayExtra("ratios");
        float[] attacks = intent.getFloatArrayExtra("attacks");
        float[] releases = intent.getFloatArrayExtra("releases");
        float[] gains = intent.getFloatArrayExtra("gains");

        if (thresholds != null && ratios != null && attacks != null && releases != null && gains != null) {
            updateParams(thresholds, ratios, attacks, releases, gains);
        } else {
            Log.e(TAG, "Invalid parameters received in intent");
        }
    }

    private void setAmplificationFromIntent(Intent intent) {
        float amp = intent.getFloatExtra("amplification", 1.0f);
        if (isProcessing) {
            setAmplification(amp);
            Log.d(TAG, "Amplification set to " + amp);
        } else {
            Log.w(TAG, "Attempted to set amplification while processing is not active");
        }
    }

    public void updateParams(float[] thresholds, float[] ratios, float[] attacks, float[] releases, float[] gains) {
        if (isProcessing) {
            updateAudioParams(thresholds, ratios, attacks, releases, gains);
            Log.d(TAG, "Audio processing parameters updated");
        } else {
            Log.w(TAG, "Attempted to update params while processing is not active");
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        if (isProcessing) {
            Log.d(TAG, "Stopping audio processing");
            stopAudioProcessing();
            isProcessing = false;
        }
        super.onDestroy();
    }
}