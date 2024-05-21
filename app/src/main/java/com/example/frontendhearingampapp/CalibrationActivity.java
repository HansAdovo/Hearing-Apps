package com.example.frontendhearingampapp;

import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class CalibrationActivity extends AppCompatActivity {

    private static final String TAG = "CalibrationActivity";
    private static final String SETTINGS_PREFS = "CalibrationSettings";
    private static final String SETTINGS_KEY = "settings";

    private Button loadER3ALevelsButton, clearMeasuredLevelsButton, saveButton, saveAsNewButton, loadOtherButton, deleteCurrentButton, btnReturnToTitle;
    private TextView expectedSoundPressureLevel, presentationLevelLabel, presentationLevelValue, currentSettingTextView, frequencyLabel;
    private SeekBar presentationLevel;
    private SeekBar[] measuredLevelSeekBars = new SeekBar[10];
    private Button[] playButtons = new Button[10];
    private TextView[] measuredValueTextViews = new TextView[10];
    private MediaPlayer[] tonePlayers = new MediaPlayer[10];
    private int[] toneResIds = {
            R.raw.tone_250hz,
            R.raw.tone_500hz,
            R.raw.tone_750hz,
            R.raw.tone_1000hz,
            R.raw.tone_1500hz,
            R.raw.tone_2000hz,
            R.raw.tone_3000hz,
            R.raw.tone_4000hz,
            R.raw.tone_6000hz,
            R.raw.tone_8000hz
    };

    private float[] er3aLevels = {84.0f, 75.5f, 72.0f, 70.0f, 72.0f, 73.0f, 73.5f, 75.5f, 72.0f, 70.0f}; // Values from the image

    private String currentSettingName = "";
    private boolean isModified = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocaleManager.loadLocale(this);  // Load the current locale
        setContentView(R.layout.activity_calibration);

        initViews();
        setupClickListeners();
        setupTonePlayers();

        // Load the current setting name and its values from SharedPreferences
        loadCurrentSetting();
        updateTexts(); // Update text elements
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Do not save settings on pause
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCurrentSetting();
        updateTexts(); // Update text elements
    }

    private void initViews() {
        expectedSoundPressureLevel = findViewById(R.id.expectedSoundPressureLevel);
        presentationLevelLabel = findViewById(R.id.presentationLevelLabel);
        presentationLevelValue = findViewById(R.id.presentationLevelValue);
        presentationLevel = findViewById(R.id.presentationLevel);
        currentSettingTextView = findViewById(R.id.currentSettingTextView);
        frequencyLabel = findViewById(R.id.frequencyLabel);

        loadER3ALevelsButton = findViewById(R.id.loadER3ALevels);
        clearMeasuredLevelsButton = findViewById(R.id.clearMeasuredLevels);
        saveButton = findViewById(R.id.saveButton);
        saveAsNewButton = findViewById(R.id.saveAsNewButton);
        loadOtherButton = findViewById(R.id.loadOtherButton);
        deleteCurrentButton = findViewById(R.id.deleteCurrentButton);
        btnReturnToTitle = findViewById(R.id.returnToTitleButton); // Initialize the return button

        // Initialize measured level SeekBars and play buttons
        int[] seekBarIds = {
                R.id.slider_250Hz, R.id.slider_500Hz, R.id.slider_750Hz, R.id.slider_1000Hz,
                R.id.slider_1500Hz, R.id.slider_2000Hz, R.id.slider_3000Hz, R.id.slider_4000Hz,
                R.id.slider_6000Hz, R.id.slider_8000Hz
        };
        int[] playButtonIds = {
                R.id.playButton_250Hz, R.id.playButton_500Hz, R.id.playButton_750Hz, R.id.playButton_1000Hz,
                R.id.playButton_1500Hz, R.id.playButton_2000Hz, R.id.playButton_3000Hz, R.id.playButton_4000Hz,
                R.id.playButton_6000Hz, R.id.playButton_8000Hz
        };
        int[] measuredValueIds = {
                R.id.measuredValue_250Hz, R.id.measuredValue_500Hz, R.id.measuredValue_750Hz, R.id.measuredValue_1000Hz,
                R.id.measuredValue_1500Hz, R.id.measuredValue_2000Hz, R.id.measuredValue_3000Hz, R.id.measuredValue_4000Hz,
                R.id.measuredValue_6000Hz, R.id.measuredValue_8000Hz
        };

        for (int i = 0; i < measuredLevelSeekBars.length; i++) {
            measuredLevelSeekBars[i] = findViewById(seekBarIds[i]);
            playButtons[i] = findViewById(playButtonIds[i]);
            measuredValueTextViews[i] = findViewById(measuredValueIds[i]);
        }

        // Set default presentation level to 70 dB HL
        presentationLevel.setMax(1000); // Adjust max to 1000 to account for one decimal place
        presentationLevel.setProgress(700); // 70.0 dB
        presentationLevelValue.setText("70.0 dB");
        presentationLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float displayValue = progress / 10.0f;
                adjustTones(displayValue);
                presentationLevelValue.setText(String.format(Locale.US, "%.1f dB", displayValue));
                isModified = true;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        for (int i = 0; i < measuredLevelSeekBars.length; i++) {
            final int index = i;
            measuredLevelSeekBars[i].setMax(1000); // Adjust max to 1000 to account for one decimal place
            measuredLevelSeekBars[i].setProgress(700); // 70.0 dB
            measuredLevelSeekBars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float displayValue = progress / 10.0f;
                    measuredValueTextViews[index].setText(String.format(Locale.US, "%.1f dB", displayValue));
                    adjustToneVolume(index, displayValue);
                    isModified = true;
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            playButtons[i].setText(getString(R.string.play));

            playButtons[i].setOnClickListener(v -> playTone(index));
        }
    }

    private void setupClickListeners() {
        // Set click listeners for each button
        loadER3ALevelsButton.setOnClickListener(view -> loadER3ALevels());
        clearMeasuredLevelsButton.setOnClickListener(view -> clearMeasuredLevels());
        saveButton.setOnClickListener(view -> saveSettings());
        saveAsNewButton.setOnClickListener(view -> saveAsNew());
        loadOtherButton.setOnClickListener(view -> loadOther());
        deleteCurrentButton.setOnClickListener(view -> deleteCurrent());

        btnReturnToTitle.setOnClickListener(view -> finish()); // Listener to handle return action
    }

    private void updateTexts() {
        // Update all text elements to use localized strings
        loadER3ALevelsButton.setText(getString(R.string.load_er3a_levels));
        clearMeasuredLevelsButton.setText(getString(R.string.clear_measured_levels));
        saveButton.setText(getString(R.string.save));
        saveAsNewButton.setText(getString(R.string.save_as_new));
        loadOtherButton.setText(getString(R.string.load_other));
        deleteCurrentButton.setText(getString(R.string.delete_current));

        expectedSoundPressureLevel.setText(getString(R.string.expected_sound_pressure_level));
        presentationLevelLabel.setText(getString(R.string.presentation_level));
        frequencyLabel.setText(getString(R.string.frequency_Label));
        currentSettingTextView.setText(String.format(getString(R.string.current_setting), currentSettingName)); // Initialize with the saved setting

        btnReturnToTitle.setText(getString(R.string.return_to_title)); // Set the text for the return button
    }

    private void setupTonePlayers() {
        for (int i = 0; i < tonePlayers.length; i++) {
            tonePlayers[i] = MediaPlayer.create(this, toneResIds[i]);
        }
    }

    private void adjustTones(float presentationLevel) {
        for (int i = 0; i < tonePlayers.length; i++) {
            if (tonePlayers[i].isPlaying()) {
                float volume = presentationLevel / 100.0f; // Assuming 100 dB HL as max level
                tonePlayers[i].setVolume(volume, volume);
            }
        }
    }

    private void adjustToneVolume(int index, float level) {
        if (index < 0 || index >= tonePlayers.length) return;
        float volume = level / 100.0f; // Assuming 100 dB HL as max level
        tonePlayers[index].setVolume(volume, volume);
    }

    private void loadER3ALevels() {
        for (int i = 0; i < measuredLevelSeekBars.length; i++) {
            measuredLevelSeekBars[i].setProgress(Math.round(er3aLevels[i] * 10));
            measuredValueTextViews[i].setText(String.format(Locale.US, "%.1f dB", er3aLevels[i]));
        }
        // Do not mark as modified
        presentationLevel.setProgress(700); // 70.0 dB
        presentationLevelValue.setText("70.0 dB");
    }

    private void clearMeasuredLevels() {
        // Logic to clear measured levels
        for (int i = 0; i < measuredLevelSeekBars.length; i++) {
            measuredLevelSeekBars[i].setProgress(700); // Reset to default value
            measuredValueTextViews[i].setText("70.0 dB");
        }
        // Do not mark as modified
        presentationLevel.setProgress(700); // 70.0 dB
        presentationLevelValue.setText("70.0 dB");
    }

    private void saveSettings() {
        if (currentSettingName == null || currentSettingName.isEmpty()) {
            Toast.makeText(this, R.string.no_settings_found, Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Add the current setting name to the set of settings
        Set<String> settings = prefs.getStringSet(SETTINGS_KEY, new HashSet<>());
        settings.add(currentSettingName);
        editor.putStringSet(SETTINGS_KEY, settings);

        // Save the current setting name
        editor.putString("currentSettingName", currentSettingName);

        editor.putInt("presentationLevel_" + currentSettingName, presentationLevel.getProgress());
        for (int i = 0; i < measuredLevelSeekBars.length; i++) {
            editor.putFloat("measuredLevel_" + currentSettingName + "_" + i, measuredLevelSeekBars[i].getProgress() / 10.0f);
        }
        editor.apply();
        Toast.makeText(this, R.string.setting_saved, Toast.LENGTH_SHORT).show();

        // Update the current setting text view
        currentSettingTextView.setText(String.format(getString(R.string.current_setting), currentSettingName));
    }

    private void saveAsNew() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.save_as_new);
        builder.setMessage(R.string.enter_setting_name);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);  // Set input type to text
        input.setSingleLine(true);  // Ensure single line input
        builder.setView(input);

        // Set the positive button click listener
        builder.setPositiveButton(R.string.confirm, (dialogInterface, which) -> {
            String settingName = input.getText().toString().trim();
            if (settingName.isEmpty()) {
                Toast.makeText(this, R.string.setting_name_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            currentSettingName = settingName;
            currentSettingTextView.setText(String.format(getString(R.string.current_setting), currentSettingName));
            saveSettings();
        });

        // Set the negative button click listener
        builder.setNegativeButton(R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss());

        // Show the dialog
        builder.show();
    }

    private void loadOther() {
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        Set<String> settings = prefs.getStringSet(SETTINGS_KEY, new HashSet<>());

        if (settings.isEmpty()) {
            Toast.makeText(this, R.string.no_settings_found, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] settingsArray = settings.toArray(new String[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.load_other);
        builder.setItems(settingsArray, (dialog, which) -> {
            currentSettingName = settingsArray[which];
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("currentSettingName", currentSettingName);
            editor.apply();
            currentSettingTextView.setText(String.format(getString(R.string.current_setting), currentSettingName));
            loadSetting(currentSettingName);
        });
        builder.show();
    }

    private void loadSetting(String settingName) {
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        presentationLevel.setProgress(prefs.getInt("presentationLevel_" + settingName, 700));
        presentationLevelValue.setText(String.format(Locale.US, "%.1f dB", presentationLevel.getProgress() / 10.0f));
        for (int i = 0; i < measuredLevelSeekBars.length; i++) {
            float level = prefs.getFloat("measuredLevel_" + settingName + "_" + i, 70.0f);
            measuredLevelSeekBars[i].setProgress(Math.round(level * 10));
            measuredValueTextViews[i].setText(String.format(Locale.US, "%.1f dB", level));
        }
        // Do not mark as modified
    }

    private void deleteCurrent() {
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (currentSettingName == null || currentSettingName.isEmpty()) {
            Toast.makeText(this, R.string.no_setting_to_delete, Toast.LENGTH_SHORT).show();
            return;
        }

        editor.remove("presentationLevel_" + currentSettingName);
        for (int i = 0; i < measuredLevelSeekBars.length; i++) {
            editor.remove("measuredLevel_" + currentSettingName + "_" + i);
        }
        Set<String> settings = prefs.getStringSet(SETTINGS_KEY, new HashSet<>());
        settings.remove(currentSettingName);
        editor.putStringSet(SETTINGS_KEY, settings);
        editor.apply();

        if (settings.isEmpty()) {
            currentSettingName = "";
        } else {
            currentSettingName = "";
        }
        editor.putString("currentSettingName", currentSettingName);
        editor.apply();

        currentSettingTextView.setText(String.format(getString(R.string.current_setting), currentSettingName));
        Toast.makeText(this, R.string.setting_deleted, Toast.LENGTH_SHORT).show();
    }

    private void playTone(int index) {
        if (index < 0 || index >= tonePlayers.length) return;

        if (tonePlayers[index].isPlaying()) {
            tonePlayers[index].stop();
            try {
                tonePlayers[index].prepare();
                tonePlayers[index].seekTo(0);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        tonePlayers[index].start();
    }

    private void saveCurrentSetting() {
        if (currentSettingName.isEmpty()) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putString("currentSettingName", currentSettingName);
        editor.putInt("presentationLevel_" + currentSettingName, presentationLevel.getProgress());

        for (int i = 0; i < measuredLevelSeekBars.length; i++) {
            editor.putFloat("measuredLevel_" + currentSettingName + "_" + i, measuredLevelSeekBars[i].getProgress() / 10.0f);
        }

        editor.apply();
    }

    private void loadCurrentSetting() {
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        currentSettingName = prefs.getString("currentSettingName", "");

        if (currentSettingName.isEmpty()) {
            currentSettingTextView.setText(String.format(getString(R.string.current_setting), "None"));
        } else {
            currentSettingTextView.setText(String.format(getString(R.string.current_setting), currentSettingName));
            loadSetting(currentSettingName);
        }
    }
}