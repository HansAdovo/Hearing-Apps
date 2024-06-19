package com.example.frontendhearingampapp;

import android.widget.Toast;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String[] LANGUAGES = {"English", "Spanish", "French"};
    private static final String[] LOCALES = {"en", "es-rES", "fr-rFR"};
    private TextView switchLanguageButton, appTitle;
    private Button testButton, calibrationButton, viewResultsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadLocale();
        setContentView(R.layout.activity_main);

        switchLanguageButton = findViewById(R.id.switchLanguageButton);
        appTitle = findViewById(R.id.appTitle);
        testButton = findViewById(R.id.testButton);
        calibrationButton = findViewById(R.id.calibrationButton);
        viewResultsButton = findViewById(R.id.viewResultsButton);

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

        updateTexts();
    }

    private boolean isCalibrationSettingSelected() {
        SharedPreferences prefs = getSharedPreferences("CalibrationSettings", MODE_PRIVATE);
        String currentSettingName = prefs.getString("currentSettingName", "");
        return !currentSettingName.isEmpty();
    }

    private boolean hasTestResults() {
        SharedPreferences prefs = getSharedPreferences("TestResults", MODE_PRIVATE);
        return !prefs.getAll().isEmpty();
    }

    private void showChangeLanguageDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.choose_language))
                .setItems(new CharSequence[]{
                                getString(R.string.language_english),
                                getString(R.string.language_spanish),
                                getString(R.string.language_french)},
                        (dialog, which) -> {
                            if (!getCurrentLocale().equals(LOCALES[which])) {
                                changeLocale(LOCALES[which]);
                                recreate();  // Recreate the activity to refresh content
                            }
                        })
                .create().show();
    }

    private void changeLocale(String lang) {
        String[] parts = lang.split("-");
        Locale locale;
        if (parts.length > 1) {
            locale = new Locale(parts[0], parts[1]);
        } else {
            locale = new Locale(lang);
        }
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        getResources().getConfiguration().setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        getBaseContext().getResources().updateConfiguration(config, getBaseContext().getResources().getDisplayMetrics());

        SharedPreferences.Editor editor = getSharedPreferences("Settings", MODE_PRIVATE).edit();
        editor.putString("My_Lang", lang);
        editor.apply();

        updateTexts();
    }

    private void loadLocale() {
        SharedPreferences prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String language = prefs.getString("My_Lang", "en");
        changeLocale(language);
    }

    private String getCurrentLocale() {
        return Locale.getDefault().getLanguage();
    }

    private void updateTexts() {
        if (switchLanguageButton != null) switchLanguageButton.setText(R.string.switch_language);
        if (appTitle != null) appTitle.setText(R.string.app_title);
        if (testButton != null) testButton.setText(R.string.test);
        if (calibrationButton != null) calibrationButton.setText(R.string.calibration);
        if (viewResultsButton != null) viewResultsButton.setText(R.string.view_results);
    }
}