package com.auditapp.hearingamp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestMCLActivity extends AppCompatActivity {

    private Button btnReturnToTitle, btnRepeat, btnDone;
    private Button[] ratingButtons = new Button[11];
    private SeekBar volumeSlider;
    private TextView txtInstructions, txtCurrentVolume;
    private ImageView imageTopShape;

    private Handler handler;

    private boolean isPaused = false;
    private boolean isTestInProgress = false; // Flag to track if a test is in progress
    private int currentFrequencyIndex = 0;
    private int currentVolumeLevel = 50; // Start at 50 dB HL
    private String[] frequencies;
    private String earOrder;
    private String currentEar = "left"; // Start with the left ear
    private String patientGroup;
    private String patientName;

    private Animation shakeAnimation;

    private SharedPreferences calibrationPrefs;
    private float[] desiredSPLLevelsLeft = new float[10];
    private float[] desiredSPLLevelsRight = new float[10];
    private String currentSettingName;

    private Map<String, Integer> mclLevelsLeft = new HashMap<>();
    private Map<String, Integer> mclLevelsRight = new HashMap<>();
    private HashMap<String, List<Integer>> testCounts = new HashMap<>();
    private AudioTrack audioTrack;

    private static final int MIN_VOLUME_DB_HL = 0;
    private static final int MAX_VOLUME_DB_HL = 100;

    private static final int TONE_DURATION = 1000; // 1 second
    private static final int INTERVAL_DURATION = 1000; // 1 second
    private static final int REPEAT_COUNT = 3;

    private String[] ears = {"left", "right"};
    private int currentEarIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_mcl);

        btnReturnToTitle = findViewById(R.id.btnReturnToTitle);
        btnRepeat = findViewById(R.id.btnRepeat);
        btnDone = findViewById(R.id.btnDone);
        volumeSlider = findViewById(R.id.volumeSlider);
        txtInstructions = findViewById(R.id.txtInstructions);
        txtCurrentVolume = findViewById(R.id.txtCurrentVolume);
        imageTopShape = findViewById(R.id.imageTopShape);

        for (int i = 0; i <= 10; i++) {
            int resID = getResources().getIdentifier("btnRating" + i, "id", getPackageName());
            ratingButtons[i] = findViewById(resID);
            int finalI = i;
            ratingButtons[i].setOnClickListener(view -> handleUserRating(finalI));
        }

        volumeSlider.setMax(MAX_VOLUME_DB_HL);
        volumeSlider.setProgress(currentVolumeLevel);
        txtCurrentVolume.setText(String.valueOf(currentVolumeLevel)); // Initial volume display

        // Disable SeekBar touch
        volumeSlider.setOnTouchListener((v, event) -> true);

        String instructions = getString(R.string.mcl_test_instructions);
        txtInstructions.setText(instructions);

        btnReturnToTitle.setOnClickListener(view -> {
            Intent intent = new Intent(TestMCLActivity.this, MainActivity.class);
            startActivity(intent);
            finish(); // Finish current activity to release resources
        });

        btnRepeat.setOnClickListener(view -> repeatCurrentTest());
        btnDone.setOnClickListener(view -> handleDone());

        handler = new Handler();
        shakeAnimation = AnimationUtils.loadAnimation(this, R.anim.shake);

        Intent intent = getIntent();
        patientGroup = intent.getStringExtra("patientGroup");
        patientName = intent.getStringExtra("patientName");
        frequencies = intent.getStringArrayExtra("frequencies");
        earOrder = intent.getStringExtra("earOrder");

        Log.d("TestThresholdActivity", "Received frequencies: " + Arrays.toString(frequencies));
        Log.d("TestMCLActivity", "EarOrder received: " + earOrder);

        if (frequencies == null || frequencies.length == 0) {
            Toast.makeText(this, "No frequencies found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        calibrationPrefs = getSharedPreferences("CalibrationSettings", MODE_PRIVATE);
        loadCalibrationSettings();

        applyEarOrderSettings();
        startTestSequence();
    }

    private void applyEarOrderSettings() {
        earOrder = earOrder.trim().replaceAll("\\s+", " "); // Normalize whitespace

        if (earOrder.equalsIgnoreCase(getString(R.string.lear_only)) || earOrder.equalsIgnoreCase(getString(R.string.lear_to_rear))) {
            currentEarIndex = 0; // Start with left ear
        } else if (earOrder.equalsIgnoreCase(getString(R.string.rear_only)) || earOrder.equalsIgnoreCase(getString(R.string.rear_to_lear))) {
            currentEarIndex = 1; // Start with right ear
        } else {
            Log.e("TestMCLActivity", "Unknown ear order: " + earOrder);
            currentEarIndex = 0; // Default to left ear
        }
        currentEar = ears[currentEarIndex];
        Log.d("TestMCLActivity", "Initial ear set to: " + currentEar);
    }

    private void loadCalibrationSettings() {
        currentSettingName = calibrationPrefs.getString("currentSettingName", "");
        for (int i = 0; i < desiredSPLLevelsLeft.length; i++) {
            desiredSPLLevelsLeft[i] = calibrationPrefs.getFloat("desiredSPLLevelLeft_" + currentSettingName + "_" + i, 70.0f);
            desiredSPLLevelsRight[i] = calibrationPrefs.getFloat("desiredSPLLevelRight_" + currentSettingName + "_" + i, 70.0f);
            Log.d("TestMCLActivity", "Loaded desiredSPLLevelLeft[" + i + "] = " + desiredSPLLevelsLeft[i]);
            Log.d("TestMCLActivity", "Loaded desiredSPLLevelRight[" + i + "] = " + desiredSPLLevelsRight[i]);
        }
    }

    private void startTestSequence() {
        Log.d("TestMCLActivity", "Starting test sequence with frequencies: " + Arrays.toString(frequencies));
        runTestSequence(); // Start the test sequence immediately
    }

    private void runTestSequence() {
        if (isPaused || isTestInProgress) return;

        isTestInProgress = true;

        if (currentFrequencyIndex < frequencies.length) {
            String frequencyStr = frequencies[currentFrequencyIndex];
            currentEar = ears[currentEarIndex];
            String frequencyKey = frequencyStr + " " + currentEar;

            // Initialize the testCounts list for this frequency and ear
            if (!testCounts.containsKey(frequencyKey)) {
                testCounts.put(frequencyKey, new ArrayList<>());
                testCounts.get(frequencyKey).add(currentVolumeLevel); // Add initial value (which is 50 at the start)
                Log.d("TestMCLActivity", "Initialized testCounts for " + frequencyKey + " with initial value: " + currentVolumeLevel);
            }

            int frequency = Integer.parseInt(frequencyStr.replace(" Hz", ""));
            playTone(frequency);
        } else {
            handleEarSwitchOrEnd();
        }
    }

    private void playTone(int frequency) {
        stopTone();

        int sampleRate = 44100;
        int numSamples = sampleRate; // 1 second of audio
        double[] sample = new double[numSamples];
        byte[] generatedSnd = new byte[2 * numSamples];

        double freqOfTone = frequency;

        for (int i = 0; i < numSamples; ++i) {
            sample[i] = Math.sin(2 * Math.PI * i / (sampleRate / freqOfTone));
        }

        int idx = 0;
        for (final double dVal : sample) {
            final short val = (short) ((dVal * 32767));
            generatedSnd[idx++] = (byte) (val & 0x00ff);
            generatedSnd[idx++] = (byte) ((val & 0xff00) >>> 8);
        }

        audioTrack = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                numSamples * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );

        audioTrack.write(generatedSnd, 0, generatedSnd.length);

        // Retrieve and log the desired SPL value
        float desiredSPL = currentEar.equals("left") ? desiredSPLLevelsLeft[getFrequencyIndex(frequency)] : desiredSPLLevelsRight[getFrequencyIndex(frequency)];
        Log.d("TestMCLActivity", "Desired SPL: " + desiredSPL);

        // Calculate the actual SPL based on the current dB HL level
        float dB_SPL = desiredSPL + (currentVolumeLevel - 70);
        Log.d("TestMCLActivity", "Calculated dB SPL: " + dB_SPL);

        // Calculate the amplitude
        float volume = calculateAmplitude(dB_SPL, 100);
        Log.d("TestMCLActivity", "Amplitude: " + volume);

        if (currentEar.equals("left")) {
            audioTrack.setStereoVolume(volume, 0);
        } else {
            audioTrack.setStereoVolume(0, volume);
        }

        Log.d("TestMCLActivity", "Setting volume: Left = " + (currentEar.equals("left") ? volume : 0) + ", Right = " + (currentEar.equals("right") ? volume : 0));
        Log.d("TestMCLActivity", "Frequency: " + frequency + " Hz, Current dB HL: " + currentVolumeLevel + ", Calculated dB SPL: " + dB_SPL + ", Amplitude: " + volume);

        // Disable buttons during test
        setButtonsEnabled(false);

        // Play the tone three times
        for (int i = 0; i < REPEAT_COUNT; i++) {
            final int playCount = i;
            handler.postDelayed(() -> {
                imageTopShape.startAnimation(shakeAnimation);
                audioTrack.play();

                handler.postDelayed(() -> {
                    imageTopShape.clearAnimation();
                    audioTrack.stop();

                    if (playCount == REPEAT_COUNT - 1) {
                        isTestInProgress = false;
                        setButtonsEnabled(true);
                    }
                }, TONE_DURATION);
            }, i * (TONE_DURATION + INTERVAL_DURATION));
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        for (Button btn : ratingButtons) {
            btn.setEnabled(enabled);
        }
        btnRepeat.setEnabled(enabled);
        btnDone.setEnabled(enabled);
    }

    private void handleUserRating(int rating) {
        Log.d("TestMCLActivity", "User rating: " + rating);

        // Adjust the volume level based on the rating
        if (rating < 5) {
            currentVolumeLevel += (5 - rating);
        } else if (rating > 5) {
            currentVolumeLevel -= (rating - 5);
        }

        // Ensure volume is within bounds
        currentVolumeLevel = Math.max(MIN_VOLUME_DB_HL, Math.min(currentVolumeLevel, MAX_VOLUME_DB_HL));

        // Update the slider
        volumeSlider.setProgress(currentVolumeLevel);
        txtCurrentVolume.setText(String.valueOf(currentVolumeLevel)); // Update the displayed volume

        // Log the current volume level
        String frequencyKey = frequencies[currentFrequencyIndex] + " " + currentEar;
        List<Integer> counts = testCounts.get(frequencyKey);

        // Only add the new volume level if it's different from the last one
        if (counts.get(counts.size() - 1) != currentVolumeLevel) {
            counts.add(currentVolumeLevel);
            Log.d("TestMCLActivity", "Added new volume level to testCounts for " + frequencyKey + ": " + currentVolumeLevel);
        }

        Log.d("TestMCLActivity", "Test counts for " + frequencyKey + ": " + counts);

        // Play the tone with the new volume
        runTestSequence();
    }

    private void handleDone() {
        Log.d("TestMCLActivity", "Test completed at volume level: " + currentVolumeLevel);

        String frequencyKey = frequencies[currentFrequencyIndex] + " " + currentEar;

        // Only add the current volume level if it's different from the last one
        if (!testCounts.containsKey(frequencyKey)) {
            testCounts.put(frequencyKey, new ArrayList<>());
        }
        List<Integer> counts = testCounts.get(frequencyKey);
        if (counts.isEmpty() || counts.get(counts.size() - 1) != currentVolumeLevel) {
            counts.add(currentVolumeLevel);
            Log.d("TestMCLActivity", "Added current volume level to testCounts for " + frequencyKey);
        }

        if (currentEar.equals("left")) {
            mclLevelsLeft.put(frequencies[currentFrequencyIndex], currentVolumeLevel);
        } else {
            mclLevelsRight.put(frequencies[currentFrequencyIndex], currentVolumeLevel);
        }

        handleEarSwitchOrEnd();
    }

    private void handleEarSwitchOrEnd() {
        boolean isTestingBothEars = !earOrder.equalsIgnoreCase(getString(R.string.lear_only)) && !earOrder.equalsIgnoreCase(getString(R.string.rear_only));

        if (isTestingBothEars) {
            if (earOrder.equalsIgnoreCase(getString(R.string.lear_to_rear))) {
                if (currentEarIndex == 0) {
                    currentEarIndex = 1; // Switch to right ear
                } else {
                    currentEarIndex = 0; // Switch back to left ear
                    currentFrequencyIndex++; // Move to next frequency
                }
            } else if (earOrder.equalsIgnoreCase(getString(R.string.rear_to_lear))) {
                if (currentEarIndex == 1) {
                    currentEarIndex = 0; // Switch to left ear
                } else {
                    currentEarIndex = 1; // Switch back to right ear
                    currentFrequencyIndex++; // Move to next frequency
                }
            }
        } else {
            // For single ear testing, just move to the next frequency
            currentFrequencyIndex++;
        }

        if (currentFrequencyIndex >= frequencies.length) {
            showResults();
        } else {
            currentEar = ears[currentEarIndex];
            resetForNextFrequency();
            runTestSequence();
        }

        Log.d("TestMCLActivity", "Switched to ear: " + currentEar + ", Frequency index: " + currentFrequencyIndex);
    }

    private void resetForNextFrequency() {
        currentVolumeLevel = 50;
        volumeSlider.setProgress(currentVolumeLevel); // Reset the slider to 50
        txtCurrentVolume.setText(String.valueOf(currentVolumeLevel)); // Update the displayed volume
        runTestSequence();
    }

    private void repeatCurrentTest() {
        if (isTestInProgress) return; // Prevent repeat if a test is already in progress
        int frequency = Integer.parseInt(frequencies[currentFrequencyIndex].replace(" Hz", ""));
        playTone(frequency);
    }

    private void showResults() {
        // Save MCL test data to SharedPreferences
        saveMCLTestData();

        // Transition to ViewResultsActivity
        transitionToViewResults();
    }

    private void saveMCLTestData() {
        SharedPreferences sharedPreferences = getSharedPreferences("TestResults", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        String testKey = String.valueOf(System.currentTimeMillis());

        editor.putString(testKey + "_group_name", patientGroup);
        editor.putString(testKey + "_patient_name", patientName);
        editor.putString(testKey + "_test_type", "MCL");

        for (int i = 0; i < frequencies.length; i++) {
            String frequency = frequencies[i];

            for (int j = 0; j < ears.length; j++) {
                // Skip the ear that wasn't tested for single-ear tests
                if ((earOrder.equalsIgnoreCase(getString(R.string.lear_only)) && j == 1) ||
                        (earOrder.equalsIgnoreCase(getString(R.string.rear_only)) && j == 0)) {
                    continue;
                }

                String ear = ears[j];
                String frequencyKey = testKey + "_" + frequency + "_" + ear;

                Map<String, Integer> mclLevels = (j == 0) ? mclLevelsLeft : mclLevelsRight;
                Integer mclValue = mclLevels.get(frequency);

                if (mclValue != null) {
                    editor.putInt(frequencyKey, mclValue);
                    Log.d("TestMCLActivity", "Saving MCL for key: " + frequencyKey + " = " + mclValue);
                }

                String testCountKey = frequency + " " + ears[j];
                if (testCounts.containsKey(testCountKey)) {
                    editor.putString(frequencyKey + "_testCounts", testCounts.get(testCountKey).toString());
                    Log.d("TestMCLActivity", "Saving test counts for key: " + frequencyKey + "_testCounts" + ": " + testCounts.get(testCountKey).toString());
                }
            }
        }

        editor.apply();
        Log.d("TestMCLActivity", "SharedPreferences saved: " + sharedPreferences.getAll().toString());
    }

    private void transitionToViewResults() {
        Intent intent = new Intent(TestMCLActivity.this, ViewResultsActivity.class);
        startActivity(intent);
        finish(); // Finish the current activity
    }

    private int getFrequencyIndex(int frequency) {
        int[] predefinedFrequencies = {250, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000, 8000}; // Example list
        for (int i = 0; i < predefinedFrequencies.length; i++) {
            if (predefinedFrequencies[i] == frequency) {
                Log.d("TestMCLActivity", "Frequency " + frequency + " found at index: " + i);
                return i;
            }
        }
        Log.d("TestMCLActivity", "Frequency " + frequency + " not found, returning -1");
        return -1;
    }

    private float calculateAmplitude(float desiredDbSpl, float referenceDbSpl) {
        return (float) Math.min(Math.max(Math.pow(10, (desiredDbSpl - referenceDbSpl) / 20), 0.0), 1.0); // Ensure the amplitude is between 0 and 1
    }

    private void stopTone() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTone();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTone();
        handler.removeCallbacksAndMessages(null); // Release handler resources
    }
}