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
        if (!isProcessing) {
            if (checkPermission()) {
                startProcessing();
            } else {
                sendBroadcast(new Intent(ACTION_PERMISSIONS_REQUIRED));
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startProcessing() {
        isProcessing = true;
        int result = startAudioProcessing();
        if (result != 0) {
            Log.e(TAG, "Failed to start audio processing. Error code: " + result);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (isProcessing) {
            stopAudioProcessing();
            isProcessing = false;
        }
        super.onDestroy();
    }
}