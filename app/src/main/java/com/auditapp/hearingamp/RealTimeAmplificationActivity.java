package com.auditapp.hearingamp;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ToggleButton;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

public class RealTimeAmplificationActivity extends AppCompatActivity {

    private static final String TAG = "RealTimeAmplification";
    private static final int PERMISSION_REQUEST_RECORD_AUDIO = 1;
    private ToggleButton toggleAmplification;
    private Button btnReturnToTitle;
    private boolean isReturningToTitle = false;

    private BroadcastReceiver errorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioProcessingService.ACTION_PROCESSING_ERROR)) {
                Toast.makeText(RealTimeAmplificationActivity.this, R.string.audio_processing_error, Toast.LENGTH_LONG).show();
                toggleAmplification.setChecked(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_real_time_amplification);

        toggleAmplification = findViewById(R.id.toggleAmplification);
        btnReturnToTitle = findViewById(R.id.btnReturnToTitle);

        toggleAmplification.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                checkAndRequestAudioPermission();
            } else {
                stopAmplification();
            }
        });

        btnReturnToTitle.setOnClickListener(view -> {
            isReturningToTitle = true;
            if (toggleAmplification.isChecked()) {
                stopAmplification();
                Toast.makeText(this, getString(R.string.amplification_stopped), Toast.LENGTH_SHORT).show();
            }
            finish();
        });

        // Register the receiver with the RECEIVER_NOT_EXPORTED flag
        IntentFilter filter = new IntentFilter(AudioProcessingService.ACTION_PROCESSING_ERROR);
        ContextCompat.registerReceiver(this, errorReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_RECORD_AUDIO);
        } else {
            startAmplification();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAmplification();
            } else {
                Toast.makeText(this, getString(R.string.microphone_permission_denied), Toast.LENGTH_SHORT).show();
                toggleAmplification.setChecked(false);
            }
        }
    }

    private void startAmplification() {
        Log.d(TAG, "Starting amplification");
        try {
            Intent serviceIntent = new Intent(this, AudioProcessingService.class);

            // Read parameters from SharedPreferences
            SharedPreferences sharedPreferences = getSharedPreferences("AudioProcessingParams", MODE_PRIVATE);
            String leftThresholdsStr = sharedPreferences.getString("leftThresholds", null);
            String rightThresholdsStr = sharedPreferences.getString("rightThresholds", null);
            String leftGainsStr = sharedPreferences.getString("leftGains", null);
            String rightGainsStr = sharedPreferences.getString("rightGains", null);
            String ratiosStr = sharedPreferences.getString("ratios", null);
            String attacksStr = sharedPreferences.getString("attacks", null);
            String releasesStr = sharedPreferences.getString("releases", null);

            if (leftThresholdsStr != null && rightThresholdsStr != null && leftGainsStr != null &&
                    rightGainsStr != null && ratiosStr != null && attacksStr != null && releasesStr != null) {

                serviceIntent.setAction(AudioProcessingService.ACTION_UPDATE_PARAMS);
                serviceIntent.putExtra("leftThresholds", stringToFloatArray(leftThresholdsStr));
                serviceIntent.putExtra("rightThresholds", stringToFloatArray(rightThresholdsStr));
                serviceIntent.putExtra("leftGains", stringToFloatArray(leftGainsStr));
                serviceIntent.putExtra("rightGains", stringToFloatArray(rightGainsStr));
                serviceIntent.putExtra("ratios", stringToFloatArray(ratiosStr));
                serviceIntent.putExtra("attacks", stringToFloatArray(attacksStr));
                serviceIntent.putExtra("releases", stringToFloatArray(releasesStr));

                Log.d(TAG, "Sending stored parameters to AudioProcessingService");
            } else {
                Log.w(TAG, "No stored parameters found. Starting service without parameters.");
            }

            startService(serviceIntent);
            Log.d(TAG, "Service started");
            Toast.makeText(this, getString(R.string.amplification_started), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error starting amplification", e);
            Toast.makeText(this, getString(R.string.error_starting_amplification), Toast.LENGTH_SHORT).show();
            toggleAmplification.setChecked(false);
        }
    }

    private float[] stringToFloatArray(String str) {
        String[] items = str.replaceAll("\\[", "").replaceAll("\\]", "").replaceAll("\\s", "").split(",");
        float[] results = new float[items.length];
        for (int i = 0; i < items.length; i++) {
            try {
                results[i] = Float.parseFloat(items[i]);
            } catch (NumberFormatException nfe) {
                Log.e(TAG, "Error parsing float: " + items[i], nfe);
            }
        }
        return results;
    }

    private void stopAmplification() {
        Log.d(TAG, "Stopping amplification");
        Intent serviceIntent = new Intent(this, AudioProcessingService.class);
        stopService(serviceIntent);
        if (!isReturningToTitle) {
            Toast.makeText(this, getString(R.string.amplification_stopped), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (toggleAmplification.isChecked()) {
            stopAmplification();
        }
        unregisterReceiver(errorReceiver);
    }
}