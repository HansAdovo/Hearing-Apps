package com.example.frontendhearingampapp;

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
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestMCLActivity extends AppCompatActivity {

    private Button btnReturnToTitle;
    private Button btnTooSoft;
    private Button btnSlightlySoft;
    private Button btnMostComfortable;
    private Button btnTooLoud;
    private Button btnRepeat;
    private Button btnPause;
    private TextView txtInstructions;
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
    private Map<String, Integer> mostComfortableCounts = new HashMap<>();
    private HashMap<String, List<Integer>> testCounts = new HashMap<>();
    private AudioTrack audioTrack;

    private static final int MIN_VOLUME_DB_HL = 0;
    private static final int MAX_VOLUME_DB_HL = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_mcl);

        btnReturnToTitle = findViewById(R.id.btnReturnToTitle);
        btnTooSoft = findViewById(R.id.btnTooSoft);
        btnSlightlySoft = findViewById(R.id.btnSlightlySoft);
        btnMostComfortable = findViewById(R.id.btnMostComfortable);
        btnTooLoud = findViewById(R.id.btnTooLoud);
        btnRepeat = findViewById(R.id.btnRepeat);
        btnPause = findViewById(R.id.btnPause);
        txtInstructions = findViewById(R.id.txtInstructions);
        imageTopShape = findViewById(R.id.imageTopShape);

        String instructions = getString(R.string.mcl_test_instructions);
        txtInstructions.setText(instructions);

        btnReturnToTitle.setOnClickListener(view -> {
            Intent intent = new Intent(TestMCLActivity.this, MainActivity.class);
            startActivity(intent);
            finish(); // Finish current activity to release resources
        });

        btnTooSoft.setOnClickListener(view -> handleUserResponse("Too Soft"));
        btnSlightlySoft.setOnClickListener(view -> handleUserResponse("Comfortable but Slightly Soft"));
        btnMostComfortable.setOnClickListener(view -> handleUserResponse("Most Comfortable"));
        btnTooLoud.setOnClickListener(view -> handleUserResponse("Too Loud"));
        btnRepeat.setOnClickListener(view -> repeatCurrentTest());
        btnPause.setOnClickListener(view -> togglePauseTest());

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
        String leftEarOnly = getString(R.string.lear_only);
        String rightEarOnly = getString(R.string.rear_only);
        String leftEarToRightEar = getString(R.string.lear_to_rear);
        String rightEarToLeftEar = getString(R.string.rear_to_lear);

        earOrder = earOrder.trim().replaceAll("\\s+", " "); // Normalize whitespace

        if (earOrder.equalsIgnoreCase(leftEarOnly)) {
            currentEar = "left";
        } else if (earOrder.equalsIgnoreCase(rightEarOnly)) {
            currentEar = "right";
        } else if (earOrder.equalsIgnoreCase(leftEarToRightEar)) {
            currentEar = "left";
        } else if (earOrder.equalsIgnoreCase(rightEarToLeftEar)) {
            currentEar = "right";
        } else {
            Log.e("TestMCLActivity", "Unknown ear order: " + earOrder);
        }
        Log.d("TestMCLActivity", "Current ear set to: " + currentEar);
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

        isTestInProgress = true; // Mark the test as in progress

        if (currentFrequencyIndex < frequencies.length) {
            String frequencyStr = frequencies[currentFrequencyIndex];
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

        // Shape shakes and plays sound
        imageTopShape.startAnimation(shakeAnimation);
        audioTrack.play();

        handler.postDelayed(() -> {
            imageTopShape.clearAnimation();
            audioTrack.stop();
            isTestInProgress = false; // Mark the test as finished
            setButtonsEnabled(true); // Re-enable buttons after test
        }, 1000); // Let the shape shake for 1 second
    }

    private void setButtonsEnabled(boolean enabled) {
        btnTooSoft.setEnabled(enabled);
        btnSlightlySoft.setEnabled(enabled);
        btnMostComfortable.setEnabled(enabled);
        btnTooLoud.setEnabled(enabled);
        btnRepeat.setEnabled(enabled);
        btnPause.setEnabled(enabled);
    }

    private void handleUserResponse(String response) {
        Log.d("TestMCLActivity", "User response: " + response + ", current dB HL: " + currentVolumeLevel);

        // Log the current volume level
        String frequencyKey = frequencies[currentFrequencyIndex] + " " + currentEar;
        if (!testCounts.containsKey(frequencyKey)) {
            testCounts.put(frequencyKey, new ArrayList<>());
        }
        testCounts.get(frequencyKey).add(currentVolumeLevel);
        Log.d("TestMCLActivity", "Test counts for " + frequencyKey + ": " + testCounts.get(frequencyKey));

        switch (response) {
            case "Too Soft":
                currentVolumeLevel += 5;
                break;
            case "Comfortable but Slightly Soft":
                currentVolumeLevel += 2.5;
                break;
            case "Most Comfortable":
                if (currentEar.equals("left")) {
                    mclLevelsLeft.put(frequencies[currentFrequencyIndex], currentVolumeLevel);
                } else {
                    mclLevelsRight.put(frequencies[currentFrequencyIndex], currentVolumeLevel);
                }
                increaseComfortableCount(frequencies[currentFrequencyIndex]);
                if (checkConsistency()) {
                    Log.d("TestMCLActivity", "MCL level determined for frequency: " + frequencies[currentFrequencyIndex] + ", Ear: " + currentEar + ", Level: " + currentVolumeLevel);
                    handleEarSwitchOrEnd();
                    return;
                }
                break;
            case "Too Loud":
                currentVolumeLevel -= 5;
                break;
        }

        if (currentVolumeLevel < MIN_VOLUME_DB_HL) {
            currentVolumeLevel = MIN_VOLUME_DB_HL;
            if (currentEar.equals("left")) {
                mclLevelsLeft.put(frequencies[currentFrequencyIndex], currentVolumeLevel);
            } else {
                mclLevelsRight.put(frequencies[currentFrequencyIndex], currentVolumeLevel);
            }
            Log.d("TestMCLActivity", "MCL level determined for frequency: " + frequencies[currentFrequencyIndex] + ", Ear: " + currentEar + ", Level: " + currentVolumeLevel);
            handleEarSwitchOrEnd();
            return;
        } else if (currentVolumeLevel > MAX_VOLUME_DB_HL) {
            currentVolumeLevel = MAX_VOLUME_DB_HL;
            if (currentEar.equals("left")) {
                mclLevelsLeft.put(frequencies[currentFrequencyIndex], currentVolumeLevel);
            } else {
                mclLevelsRight.put(frequencies[currentFrequencyIndex], currentVolumeLevel);
            }
            Log.d("TestMCLActivity", "MCL level determined for frequency: " + frequencies[currentFrequencyIndex] + ", Ear: " + currentEar + ", Level: " + currentVolumeLevel);
            handleEarSwitchOrEnd();
            return;
        }

        runTestSequence(); // Continue to the next test immediately
    }

    private boolean checkConsistency() {
        String currentFrequency = frequencies[currentFrequencyIndex] + " " + currentEar;
        int comfortableCount = mostComfortableCounts.containsKey(currentFrequency) ? mostComfortableCounts.get(currentFrequency) : 0;

        if (comfortableCount >= 3) {
            Log.d("TestMCLActivity", "MCL level determined for frequency: " + frequencies[currentFrequencyIndex] + ", Ear: " + currentEar + ", Level: " + currentVolumeLevel);
            return true;
        }

        return false;
    }

    private void increaseComfortableCount(String frequency) {
        String key = frequency + " " + currentEar;
        int count = mostComfortableCounts.containsKey(key) ? mostComfortableCounts.get(key) : 0;
        mostComfortableCounts.put(key, count + 1);
    }

    private void handleEarSwitchOrEnd() {
        currentFrequencyIndex++;
        if (currentFrequencyIndex >= frequencies.length) {
            if ((currentEar.equals("left") && earOrder.equalsIgnoreCase(getString(R.string.lear_to_rear))) ||
                    (currentEar.equals("right") && earOrder.equalsIgnoreCase(getString(R.string.rear_to_lear)))) {
                currentEar = currentEar.equals("left") ? "right" : "left";
                currentFrequencyIndex = 0;
                currentVolumeLevel = 50; // Reset volume level for the other ear
                isTestInProgress = false; // Mark the test as finished for the current ear
                runTestSequence(); // Continue with the test for the other ear immediately
            } else {
                showResults();
            }
        } else {
            resetForNextFrequency();
        }
    }

    private void resetForNextFrequency() {
        currentVolumeLevel = 50; // Reset volume level for the next frequency
        runTestSequence();
    }

    private void repeatCurrentTest() {
        if (isTestInProgress) return; // Prevent repeat if a test is already in progress
        int frequency = Integer.parseInt(frequencies[currentFrequencyIndex].replace(" Hz", ""));
        playTone(frequency);
    }

    private void togglePauseTest() {
        isPaused = !isPaused;
        if (!isPaused) {
            runTestSequence(); // Resume immediately
        }
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

        // Generate a unique key for this test
        String testKey = String.valueOf(System.currentTimeMillis());

        // Log the generated test key
        Log.d("TestMCLActivity", "Generated testKey: " + testKey);

        // Store group name, patient name, test type
        editor.putString(testKey + "_group_name", patientGroup);
        editor.putString(testKey + "_patient_name", patientName);
        editor.putString(testKey + "_test_type", "MCL");

        // Log the frequencies and MCLs being saved
        Log.d("TestMCLActivity", "Saving test data for frequencies: " + Arrays.toString(frequencies));
        Log.d("TestMCLActivity", "Left MCL levels: " + mclLevelsLeft.toString());
        Log.d("TestMCLActivity", "Right MCL levels: " + mclLevelsRight.toString());
        Log.d("TestMCLActivity", "Test counts: " + testCounts.toString());

        // Store only conducted frequencies and their corresponding MCLs
        for (String frequency : frequencies) {
            String frequencyKeyLeft = testKey + "_" + frequency + "_left";
            String frequencyKeyRight = testKey + "_" + frequency + "_right";

            // Use a map to get the MCL values safely
            Integer leftMCL = mclLevelsLeft.get(frequency);
            Integer rightMCL = mclLevelsRight.get(frequency);

            Log.d("TestMCLActivity", "Checking frequency: " + frequency + " with leftMCL: " + leftMCL + " and rightMCL: " + rightMCL);

            if (leftMCL != null) {
                editor.putInt(frequencyKeyLeft, leftMCL);
                Log.d("TestMCLActivity", "Saving left MCL for key: " + frequencyKeyLeft + " = " + leftMCL);
            }
            if (rightMCL != null) {
                editor.putInt(frequencyKeyRight, rightMCL);
                Log.d("TestMCLActivity", "Saving right MCL for key: " + frequencyKeyRight + " = " + rightMCL);
            }

            // Save test counts only if tests were conducted
            if (testCounts.containsKey(frequency + " left")) {
                editor.putString(frequencyKeyLeft + "_testCounts", testCounts.get(frequency + " left").toString());
                Log.d("TestMCLActivity", "Saving test counts for key: " + frequencyKeyLeft + "_testCounts" + ": " + testCounts.get(frequency + " left").toString());
            }

            if (testCounts.containsKey(frequency + " right")) {
                editor.putString(frequencyKeyRight + "_testCounts", testCounts.get(frequency + " right").toString());
                Log.d("TestMCLActivity", "Saving test counts for key: " + frequencyKeyRight + "_testCounts" + ": " + testCounts.get(frequency + " right").toString());
            }
        }

        editor.apply(); // Save changes
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