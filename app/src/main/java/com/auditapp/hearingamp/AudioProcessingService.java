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

    static {
        System.loadLibrary("hearingamp");
    }

    private native int startAudioProcessing();
    private native void stopAudioProcessing();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
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
        isProcessing = true;
        int result = startAudioProcessing();
        if (result != 0) {
            Log.e(TAG, "Failed to start audio processing. Error code: " + result);
            stopSelf();
        } else {
            Log.d(TAG, "Audio processing started successfully");
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