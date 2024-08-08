package com.auditapp.hearingamp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String[] LANGUAGES = {"en", "es-rES", "fr-rFR"};
    private TextView switchLanguageButton, appTitle;
    private Button testButton, calibrationButton, viewResultsButton, realTimeAmplificationButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setLanguage(getLanguage()); // Set the language before inflating the layout
        setContentView(R.layout.activity_main);

        initializeViews();
        setClickListeners();
        updateTexts();
    }

    private void initializeViews() {
        switchLanguageButton = findViewById(R.id.switchLanguageButton);
        appTitle = findViewById(R.id.appTitle);
        testButton = findViewById(R.id.testButton);
        calibrationButton = findViewById(R.id.calibrationButton);
        viewResultsButton = findViewById(R.id.viewResultsButton);
        realTimeAmplificationButton = findViewById(R.id.realTimeAmplificationButton);
    }

    private void setClickListeners() {
        switchLanguageButton.setOnClickListener(view -> showChangeLanguageDialog());
        testButton.setOnClickListener(view -> {
            if (isCalibrationSettingSelected()) {
                startActivity(new Intent(this, TestActivity.class));
            } else {
                Toast.makeText(this, getString(R.string.no_calibration_setting_selected), Toast.LENGTH_SHORT).show();
            }
        });
        calibrationButton.setOnClickListener(view -> startActivity(new Intent(this, CalibrationActivity.class)));
        viewResultsButton.setOnClickListener(view -> {
            if (hasTestResults()) {
                startActivity(new Intent(this, ViewResultsActivity.class));
            } else {
                Toast.makeText(this, getString(R.string.no_test_results), Toast.LENGTH_SHORT).show();
            }
        });
        realTimeAmplificationButton.setOnClickListener(view -> startActivity(new Intent(this, RealTimeAmplificationActivity.class)));
    }

    private void showChangeLanguageDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.choose_language))
                .setItems(new CharSequence[]{
                                "English",
                                "Español",
                                "Français"},
                        (dialog, which) -> {
                            String selectedLanguage = LANGUAGES[which];
                            Log.d(TAG, "Selected language: " + selectedLanguage);
                            if (!selectedLanguage.equals(getLanguage())) {
                                setLanguage(selectedLanguage);
                                Log.d(TAG, "Language changed, recreating activity");
                                recreate(); // Recreate the activity to apply the new language
                            } else {
                                Log.d(TAG, "Selected language is already current");
                            }
                        })
                .create().show();
    }

    private void setLanguage(String languageCode) {
        Locale locale;
        if (languageCode.contains("-")) {
            String[] parts = languageCode.split("-");
            locale = new Locale(parts[0], parts[1]);
        } else {
            locale = new Locale(languageCode);
        }
        Locale.setDefault(locale);
        Resources resources = getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        config.setLocale(locale);
        resources.updateConfiguration(config, resources.getDisplayMetrics());

        // Save the selected language
        SharedPreferences.Editor editor = getSharedPreferences("Settings", MODE_PRIVATE).edit();
        editor.putString("Language", languageCode);
        editor.apply();

        Log.d(TAG, "Language set to: " + languageCode);
    }

    private String getLanguage() {
        SharedPreferences prefs = getSharedPreferences("Settings", MODE_PRIVATE);
        return prefs.getString("Language", "en"); // Default to English
    }

    private boolean isCalibrationSettingSelected() {
        SharedPreferences prefs = getSharedPreferences("CalibrationSettings", MODE_PRIVATE);
        return !prefs.getString("currentSettingName", "").isEmpty();
    }

    private boolean hasTestResults() {
        SharedPreferences prefs = getSharedPreferences("TestResults", MODE_PRIVATE);
        return !prefs.getAll().isEmpty();
    }

    private void updateTexts() {
        switchLanguageButton.setText(R.string.switch_language);
        appTitle.setText(R.string.app_title);
        testButton.setText(R.string.test);
        calibrationButton.setText(R.string.calibration);
        viewResultsButton.setText(R.string.view_results);
        realTimeAmplificationButton.setText(R.string.real_time_amplification);

        Log.d(TAG, "Current Locale: " + Locale.getDefault().toString());
        Log.d(TAG, "Texts updated. App title: " + appTitle.getText());
        Log.d(TAG, "Switch Language: " + switchLanguageButton.getText());
        Log.d(TAG, "Test: " + testButton.getText());
        Log.d(TAG, "Calibration: " + calibrationButton.getText());
        Log.d(TAG, "View Results: " + viewResultsButton.getText());
        Log.d(TAG, "Real-Time Amplification: " + realTimeAmplificationButton.getText());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTexts();
    }
}