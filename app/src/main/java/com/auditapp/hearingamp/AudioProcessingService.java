package com.auditapp.hearingamp;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AudioProcessingService extends Service {
    private static final String TAG = "AudioProcessingService";
    private boolean isProcessing = false;
    private float[] storedLeftThresholds, storedRightThresholds, storedLeftGains, storedRightGains, storedRatios, storedAttacks, storedReleases;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    public static final String ACTION_PERMISSIONS_REQUIRED = "com.auditapp.hearingamp.ACTION_PERMISSIONS_REQUIRED";
    public static final String ACTION_PROCESSING_ERROR = "com.auditapp.hearingamp.ACTION_PROCESSING_ERROR";

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        AudioProcessingService getService() {
            return AudioProcessingService.this;
        }
    }

    static {
        System.loadLibrary("hearingamp");
    }

    private native int nativeStartAudioProcessing();
    private native void nativeStopAudioProcessing();
    private native void nativeStartProcessing();
    private native void nativeStopProcessing();
    private native void nativeUpdateAudioParams(float[] leftThresholds, float[] rightThresholds,
                                                float[] leftGains, float[] rightGains,
                                                float[] ratios, float[] attacks, float[] releases);

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public boolean startProcessing() {
        Log.d(TAG, "startProcessing called");
        if (!isProcessing) {
            if (checkPermission()) {
                int result = nativeStartAudioProcessing();
                if (result == 0) {
                    isProcessing = true;
                    Log.d(TAG, "Audio processing started successfully");
                    applyStoredParams();  // Apply stored parameters
                    nativeStartProcessing();  // Call the native method to start processing
                    return true;
                } else {
                    Log.e(TAG, "Failed to start audio processing. Error code: " + result);
                    sendBroadcast(new Intent(ACTION_PROCESSING_ERROR));
                }
            } else {
                Log.e(TAG, "Audio permission not granted");
                sendBroadcast(new Intent(ACTION_PERMISSIONS_REQUIRED));
            }
        } else {
            Log.d(TAG, "Audio processing already running");
            return true;
        }
        return false;
    }

    public void stopProcessing() {
        Log.d(TAG, "stopProcessing called");
        executorService.execute(() -> {
            if (isProcessing) {
                nativeStopProcessing();  // Call the native method to stop processing
                nativeStopAudioProcessing();  // Call the native method to clean up resources
                isProcessing = false;
                Log.d(TAG, "Audio processing stopped");
            }
        });
    }

    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
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
            nativeUpdateAudioParams(storedLeftThresholds, storedRightThresholds, storedLeftGains, storedRightGains, storedRatios, storedAttacks, storedReleases);
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

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        stopProcessing();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        super.onDestroy();
    }
}