package com.auditapp.hearingamp;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.Arrays;

public class AudioProcessingService extends Service {
    private static final String TAG = "AudioProcessingService";
    private boolean isProcessing = false;
    private float[] storedLeftThresholds, storedRightThresholds, storedLeftGains, storedRightGains, storedRatios, storedAttacks, storedReleases;

    public static final String ACTION_PERMISSIONS_REQUIRED = "com.auditapp.hearingamp.ACTION_PERMISSIONS_REQUIRED";
    public static final String ACTION_PROCESSING_ERROR = "com.auditapp.hearingamp.ACTION_PROCESSING_ERROR";
    public static final String ACTION_UPDATE_PARAMS = "com.auditapp.hearingamp.ACTION_UPDATE_PARAMS";
    public static final String ACTION_SET_AMPLIFICATION = "com.auditapp.hearingamp.ACTION_SET_AMPLIFICATION";

    static {
        System.loadLibrary("hearingamp");
    }

    private native int startAudioProcessing();
    private native void stopAudioProcessing();
    private native void updateAudioParams(float[] leftThresholds, float[] rightThresholds,
                                          float[] leftGains, float[] rightGains,
                                          float[] ratios, float[] attacks, float[] releases);
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
            } else if (ACTION_SET_AMPLIFICATION.equals(action)) {
                setAmplificationFromIntent(intent);
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
                applyStoredParams();  // Apply stored parameters
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
        Log.d(TAG, "Received UPDATE_PARAMS intent");
        float[] leftThresholds = intent.getFloatArrayExtra("leftThresholds");
        float[] rightThresholds = intent.getFloatArrayExtra("rightThresholds");
        float[] leftGains = intent.getFloatArrayExtra("leftGains");
        float[] rightGains = intent.getFloatArrayExtra("rightGains");
        float[] ratios = intent.getFloatArrayExtra("ratios");
        float[] attacks = intent.getFloatArrayExtra("attacks");
        float[] releases = intent.getFloatArrayExtra("releases");

        if (leftThresholds != null && rightThresholds != null && leftGains != null && rightGains != null &&
                ratios != null && attacks != null && releases != null) {
            Log.d(TAG, "Received parameters:");
            Log.d(TAG, "Left Thresholds: " + Arrays.toString(leftThresholds));
            Log.d(TAG, "Right Thresholds: " + Arrays.toString(rightThresholds));
            Log.d(TAG, "Left Gains: " + Arrays.toString(leftGains));
            Log.d(TAG, "Right Gains: " + Arrays.toString(rightGains));
            Log.d(TAG, "Ratios: " + Arrays.toString(ratios));
            Log.d(TAG, "Attacks: " + Arrays.toString(attacks));
            Log.d(TAG, "Releases: " + Arrays.toString(releases));
            updateParams(leftThresholds, rightThresholds, leftGains, rightGains, ratios, attacks, releases);
        } else {
            Log.e(TAG, "Invalid parameters received in intent");
        }
        if (isProcessing) {
            applyStoredParams();
        } else {
            Log.d(TAG, "Parameters stored. Will be applied when processing starts.");
        }
    }

    private void applyStoredParams() {
        if (storedLeftThresholds != null) {
            Log.d(TAG, "Applying stored parameters:");
            Log.d(TAG, "Left Thresholds: " + Arrays.toString(storedLeftThresholds));
            Log.d(TAG, "Right Thresholds: " + Arrays.toString(storedRightThresholds));
            Log.d(TAG, "Left Gains: " + Arrays.toString(storedLeftGains));
            Log.d(TAG, "Right Gains: " + Arrays.toString(storedRightGains));
            Log.d(TAG, "Ratios: " + Arrays.toString(storedRatios));
            Log.d(TAG, "Attacks: " + Arrays.toString(storedAttacks));
            Log.d(TAG, "Releases: " + Arrays.toString(storedReleases));
            updateAudioParams(storedLeftThresholds, storedRightThresholds, storedLeftGains, storedRightGains, storedRatios, storedAttacks, storedReleases);
            Log.d(TAG, "Stored audio processing parameters applied");
        } else {
            Log.w(TAG, "No stored parameters to apply");
        }
    }

    public void updateParams(float[] leftThresholds, float[] rightThresholds,
                             float[] leftGains, float[] rightGains,
                             float[] ratios, float[] attacks, float[] releases) {
        // Store parameters
        this.storedLeftThresholds = leftThresholds;
        this.storedRightThresholds = rightThresholds;
        this.storedLeftGains = leftGains;
        this.storedRightGains = rightGains;
        this.storedRatios = ratios;
        this.storedAttacks = attacks;
        this.storedReleases = releases;

        if (isProcessing) {
            applyStoredParams();
        } else {
            Log.d(TAG, "Parameters stored. Will be applied when processing starts.");
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