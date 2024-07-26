package com.auditapp.hearingamp;

import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;

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
    private TextView currentSettingTextView;
    private EditText[] expectedLevelEdits = new EditText[10];
    private EditText[] leftMeasuredLevelEdits = new EditText[10];
    private EditText[] rightMeasuredLevelEdits = new EditText[10];
    private ImageButton[] playButtons = new ImageButton[10];
    private AudioTrack[] audioTracks = new AudioTrack[10];
    private Thread[] audioThreads = new Thread[10];

    private int[] frequencies = {250, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000, 8000};

    private float[] er3aLevels = {84.0f, 75.5f, 72.0f, 70.0f, 72.0f, 73.0f, 73.5f, 75.5f, 72.0f, 70.0f};
    private float[] leftMeasuredER3ALevels = {83.9f, 86.9f, 88.8f, 86.5f, 85.6f, 88.1f, 89.5f, 83.2f, 71.0f, 56.7f};
    private float[] rightMeasuredER3ALevels = {83.9f, 86.9f, 88.8f, 86.5f, 85.6f, 88.1f, 89.5f, 83.2f, 71.0f, 56.7f};

    private float[] desiredSPLLevelsLeft = new float[10];
    private float[] desiredSPLLevelsRight = new float[10];

    private String currentSettingName = "";

    private int currentlyPlayingIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);

        initViews();
        setupClickListeners();

        loadCurrentSetting();
        addTextChangeListeners();
        updatePlayButtonsState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAllTones();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCurrentSetting();
        updatePlayButtonsState();
    }

    private void initViews() {
        currentSettingTextView = findViewById(R.id.currentSettingTextView);
        loadER3ALevelsButton = findViewById(R.id.loadER3ALevels);
        clearMeasuredLevelsButton = findViewById(R.id.clearMeasuredLevels);
        saveButton = findViewById(R.id.saveButton);
        saveAsNewButton = findViewById(R.id.saveAsNewButton);
        loadOtherButton = findViewById(R.id.loadOtherButton);
        deleteCurrentButton = findViewById(R.id.deleteCurrentButton);
        btnReturnToTitle = findViewById(R.id.returnToTitleButton);

        int[] expectedLevelIds = {
                R.id.expected_250Hz, R.id.expected_500Hz, R.id.expected_750Hz, R.id.expected_1000Hz,
                R.id.expected_1500Hz, R.id.expected_2000Hz, R.id.expected_3000Hz, R.id.expected_4000Hz,
                R.id.expected_6000Hz, R.id.expected_8000Hz
        };
        int[] leftMeasuredLevelIds = {
                R.id.left_measured_250Hz, R.id.left_measured_500Hz, R.id.left_measured_750Hz, R.id.left_measured_1000Hz,
                R.id.left_measured_1500Hz, R.id.left_measured_2000Hz, R.id.left_measured_3000Hz, R.id.left_measured_4000Hz,
                R.id.left_measured_6000Hz, R.id.left_measured_8000Hz
        };
        int[] rightMeasuredLevelIds = {
                R.id.right_measured_250Hz, R.id.right_measured_500Hz, R.id.right_measured_750Hz, R.id.right_measured_1000Hz,
                R.id.right_measured_1500Hz, R.id.right_measured_2000Hz, R.id.right_measured_3000Hz, R.id.right_measured_4000Hz,
                R.id.right_measured_6000Hz, R.id.right_measured_8000Hz
        };
        int[] playButtonIds = {
                R.id.playButton_250Hz, R.id.playButton_500Hz, R.id.playButton_750Hz, R.id.playButton_1000Hz,
                R.id.playButton_1500Hz, R.id.playButton_2000Hz, R.id.playButton_3000Hz, R.id.playButton_4000Hz,
                R.id.playButton_6000Hz, R.id.playButton_8000Hz
        };

        for (int i = 0; i < frequencies.length; i++) {
            expectedLevelEdits[i] = findViewById(expectedLevelIds[i]);
            leftMeasuredLevelEdits[i] = findViewById(leftMeasuredLevelIds[i]);
            rightMeasuredLevelEdits[i] = findViewById(rightMeasuredLevelIds[i]);
            playButtons[i] = findViewById(playButtonIds[i]);

            int finalI = i;
            playButtons[i].setOnClickListener(v -> handlePlayButtonClick(finalI));
        }
    }

    private synchronized void handlePlayButtonClick(int index) {
        if (currentlyPlayingIndex == index) {
            stopTone(index);
            playButtons[index].setImageResource(android.R.drawable.ic_media_play);
            currentlyPlayingIndex = -1;
        } else {
            if (currentlyPlayingIndex != -1) {
                stopTone(currentlyPlayingIndex);
                playButtons[currentlyPlayingIndex].setImageResource(android.R.drawable.ic_media_play);
            }
            playTone(index);
            playButtons[index].setImageResource(android.R.drawable.ic_media_pause);
            currentlyPlayingIndex = index;
        }
    }

    private synchronized void stopTone(int index) {
        if (audioTracks[index] != null) {
            audioTracks[index].stop();
            audioTracks[index].release();
            audioTracks[index] = null;
        }
        if (audioThreads[index] != null) {
            audioThreads[index].interrupt();
            audioThreads[index] = null;
        }
    }

    private void stopAllTones() {
        for (int i = 0; i < frequencies.length; i++) {
            stopTone(i);
        }
        currentlyPlayingIndex = -1;
    }

    private void setupClickListeners() {
        loadER3ALevelsButton.setOnClickListener(view -> loadER3ALevels());
        clearMeasuredLevelsButton.setOnClickListener(view -> clearMeasuredLevels());
        saveButton.setOnClickListener(view -> saveSettings());
        saveAsNewButton.setOnClickListener(view -> saveAsNew());
        loadOtherButton.setOnClickListener(view -> loadOther());
        deleteCurrentButton.setOnClickListener(view -> deleteCurrent());
        btnReturnToTitle.setOnClickListener(view -> finish());
    }

    private void loadER3ALevels() {
        for (int i = 0; i < frequencies.length; i++) {
            expectedLevelEdits[i].setText(String.format(Locale.US, "%.1f", er3aLevels[i]));
            leftMeasuredLevelEdits[i].setText(String.format(Locale.US, "%.1f", leftMeasuredER3ALevels[i]));
            rightMeasuredLevelEdits[i].setText(String.format(Locale.US, "%.1f", rightMeasuredER3ALevels[i]));
        }
    }

    private void clearMeasuredLevels() {
        for (int i = 0; i < frequencies.length; i++) {
            leftMeasuredLevelEdits[i].setText("");
            rightMeasuredLevelEdits[i].setText("");
        }
    }

    private void saveSettings() {
        if (currentSettingName == null || currentSettingName.isEmpty()) {
            Toast.makeText(this, R.string.no_settings_found, Toast.LENGTH_SHORT).show();
            return;
        }

        calculateDesiredSPLLevels(); // Calculate desired SPL levels before saving

        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Set<String> settings = prefs.getStringSet(SETTINGS_KEY, new HashSet<>());
        settings.add(currentSettingName);
        editor.putStringSet(SETTINGS_KEY, settings);
        editor.putString("currentSettingName", currentSettingName);

        for (int i = 0; i < frequencies.length; i++) {
            editor.putFloat("expectedLevel_" + currentSettingName + "_" + i, parseFloatOrDefault(expectedLevelEdits[i].getText().toString(), 0));
            editor.putFloat("leftMeasuredLevel_" + currentSettingName + "_" + i, parseFloatOrDefault(leftMeasuredLevelEdits[i].getText().toString(), 0));
            editor.putFloat("rightMeasuredLevel_" + currentSettingName + "_" + i, parseFloatOrDefault(rightMeasuredLevelEdits[i].getText().toString(), 0));
            editor.putFloat("desiredSPLLevelLeft_" + currentSettingName + "_" + i, desiredSPLLevelsLeft[i]);
            editor.putFloat("desiredSPLLevelRight_" + currentSettingName + "_" + i, desiredSPLLevelsRight[i]);
        }
        editor.apply();
        Toast.makeText(this, R.string.setting_saved, Toast.LENGTH_SHORT).show();

        currentSettingTextView.setText(String.format(getString(R.string.current_setting), currentSettingName));
        updatePlayButtonsState(); // Enable play buttons after saving
    }

    private void calculateDesiredSPLLevels() {
        for (int i = 0; i < frequencies.length; i++) {
            float expectedLevel = parseFloatOrDefault(expectedLevelEdits[i].getText().toString(), 0);
            float leftMeasuredLevel = parseFloatOrDefault(leftMeasuredLevelEdits[i].getText().toString(), 0);
            float rightMeasuredLevel = parseFloatOrDefault(rightMeasuredLevelEdits[i].getText().toString(), 0);

            desiredSPLLevelsLeft[i] = expectedLevel + (expectedLevel - leftMeasuredLevel);
            desiredSPLLevelsRight[i] = expectedLevel + (expectedLevel - rightMeasuredLevel);

            Log.d(TAG, "Calculated desiredSPLLevelLeft[" + i + "] = " + desiredSPLLevelsLeft[i]);
            Log.d(TAG, "Calculated desiredSPLLevelRight[" + i + "] = " + desiredSPLLevelsRight[i]);
        }
    }

    private float parseFloatOrDefault(String value, float defaultValue) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void saveAsNew() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.save_as_new);
        builder.setMessage(R.string.enter_setting_name);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        builder.setView(input);

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

        builder.setNegativeButton(R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss());
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
            updatePlayButtonsState();
        });
        builder.show();
    }

    private void loadSetting(String settingName) {
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        for (int i = 0; i < frequencies.length; i++) {
            expectedLevelEdits[i].setText(String.format(Locale.US, "%.1f", prefs.getFloat("expectedLevel_" + settingName + "_" + i, er3aLevels[i])));
            leftMeasuredLevelEdits[i].setText(String.format(Locale.US, "%.1f", prefs.getFloat("leftMeasuredLevel_" + settingName + "_" + i, 0.0f)));
            rightMeasuredLevelEdits[i].setText(String.format(Locale.US, "%.1f", prefs.getFloat("rightMeasuredLevel_" + settingName + "_" + i, 0.0f)));
            desiredSPLLevelsLeft[i] = prefs.getFloat("desiredSPLLevelLeft_" + settingName + "_" + i, 0.0f);
            desiredSPLLevelsRight[i] = prefs.getFloat("desiredSPLLevelRight_" + settingName + "_" + i, 0.0f);
            Log.d(TAG, "Loaded desiredSPLLevelLeft[" + i + "] = " + desiredSPLLevelsLeft[i]);
            Log.d(TAG, "Loaded desiredSPLLevelRight[" + i + "] = " + desiredSPLLevelsRight[i]);
        }
    }

    private void deleteCurrent() {
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (currentSettingName == null || currentSettingName.isEmpty()) {
            Toast.makeText(this, R.string.no_setting_to_delete, Toast.LENGTH_SHORT).show();
            return;
        }

        for (int i = 0; i < frequencies.length; i++) {
            editor.remove("expectedLevel_" + currentSettingName + "_" + i);
            editor.remove("leftMeasuredLevel_" + currentSettingName + "_" + i);
            editor.remove("rightMeasuredLevel_" + currentSettingName + "_" + i);
            editor.remove("desiredSPLLevelLeft_" + currentSettingName + "_" + i);
            editor.remove("desiredSPLLevelRight_" + currentSettingName + "_" + i);
        }
        Set<String> settings = prefs.getStringSet(SETTINGS_KEY, new HashSet<>());
        settings.remove(currentSettingName);
        editor.putStringSet(SETTINGS_KEY, settings);
        editor.apply();

        currentSettingName = "";
        editor.putString("currentSettingName", currentSettingName);
        editor.apply();

        currentSettingTextView.setText(getString(R.string.current_setting_no_setting));
        Toast.makeText(this, R.string.setting_deleted, Toast.LENGTH_SHORT).show();

        updatePlayButtonsState();
    }

    private double calculateAmplitude(double desiredDbSpl, double referenceDbSpl) {
        double amplitude = Math.pow(10, (desiredDbSpl - referenceDbSpl) / 20);
        return Math.min(Math.max(amplitude, 0.0), 1.0); // Ensure the amplitude is between 0 and 1
    }

    private void addTextChangeListeners() {
        for (int i = 0; i < frequencies.length; i++) {
            int finalI = i;
            expectedLevelEdits[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (currentlyPlayingIndex == finalI) {
                        updateVolume(finalI);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            leftMeasuredLevelEdits[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (currentlyPlayingIndex == finalI) {
                        updateVolume(finalI);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            rightMeasuredLevelEdits[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (currentlyPlayingIndex == finalI) {
                        updateVolume(finalI);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }
    }

    private void updateVolume(int index) {
        double expectedLevel = parseDoubleOrDefault(expectedLevelEdits[index].getText().toString(), 0);
        double leftMeasuredLevel = parseDoubleOrDefault(leftMeasuredLevelEdits[index].getText().toString(), 0);
        double rightMeasuredLevel = parseDoubleOrDefault(rightMeasuredLevelEdits[index].getText().toString(), 0);

        double leftCorrectionFactor = expectedLevel - leftMeasuredLevel;
        double rightCorrectionFactor = expectedLevel - rightMeasuredLevel;

        double leftDesiredDbSpl = expectedLevel + leftCorrectionFactor;
        double rightDesiredDbSpl = expectedLevel + rightCorrectionFactor;

        // Save the desired SPL levels for each ear separately
        desiredSPLLevelsLeft[index] = (float) leftDesiredDbSpl;
        desiredSPLLevelsRight[index] = (float) rightDesiredDbSpl;

        Log.d(TAG, "updateVolume: index = " + index + ", expectedLevel = " + expectedLevel +
                ", leftMeasuredLevel = " + leftMeasuredLevel + ", rightMeasuredLevel = " + rightMeasuredLevel);
        Log.d(TAG, "updateVolume: leftDesiredDbSpl = " + leftDesiredDbSpl + ", rightDesiredDbSpl = " + rightDesiredDbSpl);

        double leftAmplitude = calculateAmplitude(leftDesiredDbSpl, 100);
        double rightAmplitude = calculateAmplitude(rightDesiredDbSpl, 100);

        if (audioTracks[index] != null) {
            audioTracks[index].setStereoVolume((float) leftAmplitude, (float) rightAmplitude);
        }
    }

    private double parseDoubleOrDefault(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void playTone(int index) {
        if (index < 0 || index >= frequencies.length) return;

        int sampleRate = 44100;
        int numSamples = sampleRate;
        double[] sample = new double[numSamples];
        byte[] generatedSnd = new byte[2 * numSamples];

        double freqOfTone = frequencies[index];

        for (int i = 0; i < numSamples; ++i) {
            sample[i] = Math.sin(2 * Math.PI * i / (sampleRate / freqOfTone));
        }

        int idx = 0;
        for (final double dVal : sample) {
            final short val = (short) ((dVal * 32767));
            generatedSnd[idx++] = (byte) (val & 0x00ff);
            generatedSnd[idx++] = (byte) ((val & 0xff00) >>> 8);
        }

        stopTone(index);

        audioTracks[index] = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                numSamples * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );

        audioTracks[index].play();

        audioThreads[index] = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && audioTracks[index] != null) {
                    audioTracks[index].write(generatedSnd, 0, generatedSnd.length);
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "AudioTrack write failed", e);
            }
        });
        audioThreads[index].start();

        updateVolume(index);
    }

    private void loadCurrentSetting() {
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        currentSettingName = prefs.getString("currentSettingName", "");
        if (!currentSettingName.isEmpty()) {
            currentSettingTextView.setText(String.format(getString(R.string.current_setting), currentSettingName));
            loadSetting(currentSettingName);
        } else {
            currentSettingTextView.setText(getString(R.string.current_setting_no_setting)); // Update this line
        }
    }

    private void updatePlayButtonsState() {
        for (ImageButton playButton : playButtons) {
            playButton.setEnabled(true);
        }
    }
}