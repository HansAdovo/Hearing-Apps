package com.auditapp.hearingamp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ToggleButton;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

public class RealTimeAmplificationActivity extends AppCompatActivity {

    private static final String TAG = "RealTimeAmplification";
    private ToggleButton toggleAmplification;
    private Button btnReturnToTitle;
    private boolean isReturningToTitle = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_real_time_amplification);

        toggleAmplification = findViewById(R.id.toggleAmplification);
        btnReturnToTitle = findViewById(R.id.btnReturnToTitle);

        toggleAmplification.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startAmplification();
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
    }

    private void startAmplification() {
        Log.d(TAG, "Starting amplification");
        try {
            Intent serviceIntent = new Intent(this, AudioProcessingService.class);
            startService(serviceIntent);
            Log.d(TAG, "Service started");
            Toast.makeText(this, getString(R.string.amplification_started), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error starting amplification", e);
            Toast.makeText(this, getString(R.string.error_starting_amplification), Toast.LENGTH_SHORT).show();
            toggleAmplification.setChecked(false);
        }
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
    }
}