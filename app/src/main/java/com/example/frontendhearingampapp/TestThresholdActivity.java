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
import java.util.Random;

public class TestThresholdActivity extends AppCompatActivity {

    private Button btnReturnToTitle;
    private Button btnRepeat;
    private Button btnPause;
    private Button btnNoSound;
    private TextView txtInstructions;
    private ImageView imageTopShape;
    private ImageView imageBottomShape;
    private TextView txtTestProgress;

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
    private String testTypeThreshold;

    private Animation shakeAnimation;

    private int shapeWithSound; // 0 for top, 1 for bottom, 2 for no sound
    private Random random;

    private SharedPreferences calibrationPrefs;
    private float[] desiredSPLLevelsLeft = new float[10];
    private float[] desiredSPLLevelsRight = new float[10];
    private String currentSettingName;

    private int stepCount = 0;

    private static final int MIN_VOLUME_DB_HL = 0;
    private static final int MAX_VOLUME_DB_HL = 100;

    private boolean thresholdFound = false; // Flag to indicate if threshold is found

    private boolean initialStep = true; // Flag to track the initial step

    private Boolean lastDirectionUp = null;

    private int lastFrequency;
    private int lastShapeWithSound;

    private int noSoundCount = 0;
    private int soundTestCount = 0;

    private List<Integer> leftEarThresholds = new ArrayList<>();
    private List<Integer> rightEarThresholds = new ArrayList<>();
    private HashMap<String, List<Integer>> testCounts = new HashMap<>();
    private List<Integer> reversalLevels = new ArrayList<>();
    private AudioTrack audioTrack;
    private Thread audioThread;

    private String leftEarOnly;
    private String rightEarOnly;
    private String leftEarToRightEar;
    private String rightEarToLeftEar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_threshold);

        btnReturnToTitle = findViewById(R.id.btnReturnToTitle);
        btnRepeat = findViewById(R.id.btnRepeat);
        btnPause = findViewById(R.id.btnPause);
        btnNoSound = findViewById(R.id.btnNoSound);
        txtInstructions = findViewById(R.id.txtInstructions);
        imageTopShape = findViewById(R.id.imageTopShape);
        imageBottomShape = findViewById(R.id.imageBottomShape);
        txtTestProgress = findViewById(R.id.txtTestProgress);

        leftEarOnly = getString(R.string.lear_only);
        rightEarOnly = getString(R.string.rear_only);
        leftEarToRightEar = getString(R.string.lear_to_rear);
        rightEarToLeftEar = getString(R.string.rear_to_lear);

        String instructions = getString(R.string.test_instructions);
        txtInstructions.setText(instructions);

        btnReturnToTitle.setOnClickListener(view -> {
            Intent intent = new Intent(TestThresholdActivity.this, MainActivity.class);
            startActivity(intent);
            finish(); // Finish current activity to release resources
        });

        btnRepeat.setOnClickListener(view -> repeatCurrentTest());
        btnPause.setOnClickListener(view -> togglePauseTest());
        btnNoSound.setOnClickListener(view -> handleNoSound());

        imageTopShape.setOnClickListener(view -> handleShapePress(0));
        imageBottomShape.setOnClickListener(view -> handleShapePress(1));

        handler = new Handler();
        shakeAnimation = AnimationUtils.loadAnimation(this, R.anim.shake);
        random = new Random();

        Intent intent = getIntent();
        patientGroup = intent.getStringExtra("patientGroup");
        patientName = intent.getStringExtra("patientName");
        frequencies = intent.getStringArrayExtra("frequencies");
        earOrder = intent.getStringExtra("earOrder");
        testTypeThreshold = getString(R.string.test_type_threshold);

        Log.d("TestThresholdActivity", "Received frequencies: " + Arrays.toString(frequencies));
        Log.d("TestThresholdActivity", "EarOrder received: " + earOrder);

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

        earOrder = earOrder.trim().replaceAll("\\s+", " "); // Remove any leading or trailing whitespace and standardize spaces

        // Normalize the ear order string for consistent comparison
        earOrder = earOrder.replaceAll("\\s+", " ").replace("L.Ear", "L. Ear").replace("R.Ear", "R. Ear");

        // Use normalized ear order for comparisons
        if (earOrder.equalsIgnoreCase(leftEarOnly) || earOrder.contains(leftEarOnly)) {
            currentEar = "left";
        } else if (earOrder.equalsIgnoreCase(rightEarOnly) || earOrder.contains(rightEarOnly)) {
            currentEar = "right";
        } else if (earOrder.equalsIgnoreCase(leftEarToRightEar) || earOrder.contains(leftEarToRightEar)) {
            currentEar = "left";
        } else if (earOrder.equalsIgnoreCase(rightEarToLeftEar) || earOrder.contains(rightEarToLeftEar)) {
            currentEar = "right";
        } else {
            Log.e("TestThresholdActivity", "Unknown ear order: " + earOrder);
        }
        Log.d("TestThresholdActivity", "Current ear set to: " + currentEar);
    }

    private void loadCalibrationSettings() {
        currentSettingName = calibrationPrefs.getString("currentSettingName", "");
        for (int i = 0; i < desiredSPLLevelsLeft.length; i++) {
            desiredSPLLevelsLeft[i] = calibrationPrefs.getFloat("desiredSPLLevelLeft_" + currentSettingName + "_" + i, 70.0f);
            desiredSPLLevelsRight[i] = calibrationPrefs.getFloat("desiredSPLLevelRight_" + currentSettingName + "_" + i, 70.0f);
            Log.d("TestThresholdActivity", "Loaded desiredSPLLevelLeft[" + i + "] = " + desiredSPLLevelsLeft[i]);
            Log.d("TestThresholdActivity", "Loaded desiredSPLLevelRight[" + i + "] = " + desiredSPLLevelsRight[i]);
        }
    }

    private void startTestSequence() {
        Log.d("TestThresholdActivity", "Starting test sequence with frequencies: " + Arrays.toString(frequencies));
        runTestSequence(); // Start the test sequence immediately
    }

    private void runTestSequence() {
        if (isPaused || isTestInProgress) return;

        isTestInProgress = true; // Mark the test as in progress

        if (currentFrequencyIndex < frequencies.length) {
            String frequencyStr = frequencies[currentFrequencyIndex];
            int frequency = Integer.parseInt(frequencyStr.replace(" Hz", ""));
            int shapeWithSound;
            // Ensure "No Sound" does not occur more than twice in a row and there are more sound tests than "No Sound" tests
            if (noSoundCount >= 2 || (soundTestCount < noSoundCount)) {
                shapeWithSound = random.nextInt(2); // Only allow top or bottom shapes
                noSoundCount = 0; // Reset "No Sound" count
            } else {
                shapeWithSound = random.nextInt(3); // Randomly choose shape to produce sound or no sound (0 for top, 1 for bottom, 2 for no sound)
            }

            if (shapeWithSound == 2) {
                noSoundCount++;
                Log.d("TestThresholdActivity", "No sound test being conducted");
            } else {
                soundTestCount++;
            }
            playTone(frequency, shapeWithSound);
        } else {
            if ((currentEar.equals("left") && earOrder.equalsIgnoreCase(leftEarToRightEar)) ||
                    (currentEar.equals("right") && earOrder.equalsIgnoreCase(rightEarToLeftEar))) {
                currentEar = currentEar.equals("left") ? "right" : "left";
                currentFrequencyIndex = 0;
                stepCount = 0;
                currentVolumeLevel = 50; // Reset volume level for the other ear
                thresholdFound = false;
                isTestInProgress = false; // Mark the test as finished for the current ear
                runTestSequence(); // Continue with the test for the other ear immediately
            } else {
                showResults();
            }
        }
    }

    private void playTone(int frequency, int shapeWithSound) {
        this.shapeWithSound = shapeWithSound; // Set the shape with sound from the parameter

        // Store the last test parameters
        lastFrequency = frequency;
        lastShapeWithSound = shapeWithSound;

        stopTone();

        int sampleRate = 44100;
        int numSamples = sampleRate * 2; // 2 seconds of audio
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
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                numSamples * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );

        audioTrack.write(generatedSnd, 0, generatedSnd.length);

        // Retrieve and log the desired SPL value
        float desiredSPL = currentEar.equals("left") ? desiredSPLLevelsLeft[getFrequencyIndex(frequency)] : desiredSPLLevelsRight[getFrequencyIndex(frequency)];
        Log.d("TestThresholdActivity", "Desired SPL: " + desiredSPL);

        // Calculate the actual SPL based on the current dB HL level
        float dB_SPL = desiredSPL + (currentVolumeLevel - 70);
        Log.d("TestThresholdActivity", "Calculated dB SPL: " + dB_SPL);

        // Calculate the amplitude
        float volume = calculateAmplitude(dB_SPL, 100);
        Log.d("TestThresholdActivity", "Amplitude: " + volume);

        if (currentEar.equals("left")) {
            audioTrack.setStereoVolume(volume, 0);
        } else {
            audioTrack.setStereoVolume(0, volume);
        }

        Log.d("TestThresholdActivity", "Setting volume: Left = " + (currentEar.equals("left") ? volume : 0) + ", Right = " + (currentEar.equals("right") ? volume : 0));
        Log.d("TestThresholdActivity", "Frequency: " + frequency + " Hz, Current dB HL: " + currentVolumeLevel + ", Calculated dB SPL: " + dB_SPL + ", Amplitude: " + volume);

        if (shapeWithSound == 2) {
            Log.d("TestThresholdActivity", "No sound for this test");
        }

        // Disable buttons during test
        setButtonsEnabled(false);

        // First shape shakes and plays sound if applicable
        imageTopShape.startAnimation(shakeAnimation);
        if (shapeWithSound == 0) {
            Log.d("TestThresholdActivity", "Top shape shakes and plays sound");
            audioTrack.play();
        }
        handler.postDelayed(() -> {
            imageTopShape.clearAnimation();
            if (shapeWithSound == 0) {
                audioTrack.stop();
            }

            // Second shape shakes and plays sound if applicable
            imageBottomShape.startAnimation(shakeAnimation);
            if (shapeWithSound == 1) {
                Log.d("TestThresholdActivity", "Bottom shape shakes and plays sound");
                audioTrack.play();
            }
            handler.postDelayed(() -> {
                imageBottomShape.clearAnimation();
                if (shapeWithSound == 1) {
                    audioTrack.stop();
                }
                isTestInProgress = false; // Mark the test as finished
                setButtonsEnabled(true); // Re-enable buttons after test
            }, 2000); // Let the second shape shake for 2 seconds
        }, 2000); // Let the first shape shake for 2 seconds

        handler.postDelayed(() -> {
            imageTopShape.clearAnimation();
            imageBottomShape.clearAnimation();
            if (shapeWithSound != 2) {
                audioTrack.stop();
            }
            isTestInProgress = false; // Mark the test as finished
            setButtonsEnabled(true); // Re-enable buttons after test
        }, 4000); // Ensure to stop the tone after the total duration

        // Add current dB HL to testCounts
        String frequencyKey = currentFrequencyIndex + " " + currentEar;
        if (!testCounts.containsKey(frequencyKey)) {
            testCounts.put(frequencyKey, new ArrayList<>());
        }
        testCounts.get(frequencyKey).add(currentVolumeLevel);

        // Log the test counts
        Log.d("TestThresholdActivity", "Test counts for " + frequencyKey + ": " + testCounts.get(frequencyKey));
    }

    private void setButtonsEnabled(boolean enabled) {
        btnRepeat.setEnabled(enabled);
        btnPause.setEnabled(enabled);
        btnNoSound.setEnabled(enabled);
        imageTopShape.setEnabled(enabled);
        imageBottomShape.setEnabled(enabled);
    }

    private void handleShapePress(int shapeIndex) {
        if (thresholdFound || isTestInProgress) return; // Skip if threshold already found or test is in progress

        boolean isCorrect = (shapeIndex == shapeWithSound);
        processUserResponse(isCorrect);
    }

    private void handleNoSound() {
        if (thresholdFound || isTestInProgress) return; // Skip if threshold already found or test is in progress

        boolean isCorrect = (shapeWithSound == 2);
        if (isCorrect) {
            Log.d("TestThresholdActivity", "User response: No Sound, Correct");
        }
        processUserResponse(isCorrect);
    }

    private void processUserResponse(boolean isCorrect) {
        int previousVolumeLevel = currentVolumeLevel;
        int nextVolumeLevel = getNextDbHL(isCorrect);
        Log.d("TestThresholdActivity", "User response: " + (isCorrect ? "Correct" : "Incorrect") + ", previous dB HL: " + previousVolumeLevel + ", current dB HL: " + nextVolumeLevel);

        // Determine the direction of change
        boolean directionUp = previousVolumeLevel < nextVolumeLevel;

        // Check if the direction has changed
        boolean directionChanged = (lastDirectionUp != null) && (lastDirectionUp != directionUp);

        // Handle no sound responses
        if (!(isCorrect && shapeWithSound == 2)) {
            currentVolumeLevel = nextVolumeLevel;
            stepCount++;
        } else {
            directionChanged = false; // Correct "no sound" responses should not affect reversals
        }

        // Only count reversals if the direction changed, it's not the initial step, and the volume level changed
        if (!initialStep && directionChanged && previousVolumeLevel != currentVolumeLevel) {
            reversalLevels.add(previousVolumeLevel);
            Log.d("TestThresholdActivity", "Reversal added: " + previousVolumeLevel + " dB HL, Total reversals: " + reversalLevels.size());
        }

        // Reset the initialStep flag after the first step
        if (initialStep) {
            initialStep = false;
        }

        // Update last direction
        lastDirectionUp = directionUp;

        // Log if currentVolumeLevel is exactly at the minimum or maximum
        if (currentVolumeLevel == MIN_VOLUME_DB_HL || currentVolumeLevel == MAX_VOLUME_DB_HL) {
            Log.d("TestThresholdActivity", (currentVolumeLevel == MIN_VOLUME_DB_HL ? "Minimum" : "Maximum") + " dB HL reached.");
        }

        // Check for test termination condition
        Log.d("TestThresholdActivity", "Checking termination condition: currentVolumeLevel = " + currentVolumeLevel);
        if (reversalLevels.size() >= 4 || currentVolumeLevel <= MIN_VOLUME_DB_HL || currentVolumeLevel >= MAX_VOLUME_DB_HL) {
            if (reversalLevels.size() >= 4) {
                int sum = 0;
                for (int level : reversalLevels) {
                    sum += level;
                }
                currentVolumeLevel = sum / reversalLevels.size();
                thresholdFound = true;
                Log.d("TestThresholdActivity", "Threshold found at frequency: " + frequencies[currentFrequencyIndex] + " , volume level: " + currentVolumeLevel + " dB HL");

                // Save threshold based on current ear
                if (currentEar.equals("left")) {
                    leftEarThresholds.add(currentVolumeLevel);
                    Log.d("TestThresholdActivity", "Left ear threshold added: " + currentVolumeLevel + " dB HL");
                } else {
                    rightEarThresholds.add(currentVolumeLevel);
                    Log.d("TestThresholdActivity", "Right ear threshold added: " + currentVolumeLevel + " dB HL");
                }

            } else if (currentVolumeLevel < MIN_VOLUME_DB_HL || currentVolumeLevel > MAX_VOLUME_DB_HL) {
                thresholdFound = true;
                currentVolumeLevel = (currentVolumeLevel < MIN_VOLUME_DB_HL) ? MIN_VOLUME_DB_HL : MAX_VOLUME_DB_HL;
                Log.d("TestThresholdActivity", "Threshold found at frequency: " + frequencies[currentFrequencyIndex] + " , volume level: " + currentVolumeLevel + " dB HL");

                // Save threshold based on current ear
                if (currentEar.equals("left")) {
                    leftEarThresholds.add(currentVolumeLevel);
                    Log.d("TestThresholdActivity", "Left ear threshold added: " + currentVolumeLevel + " dB HL");
                } else {
                    rightEarThresholds.add(currentVolumeLevel);
                    Log.d("TestThresholdActivity", "Right ear threshold added: " + currentVolumeLevel + " dB HL");
                }
            }

            if (thresholdFound) {
                currentFrequencyIndex++;
                resetTestVariablesForNextFrequency();
                if (currentFrequencyIndex >= frequencies.length) {
                    if (earOrder.contains("->") && ((earOrder.equalsIgnoreCase(leftEarToRightEar) && currentEar.equals("left")) ||
                            (earOrder.equalsIgnoreCase(rightEarToLeftEar) && currentEar.equals("right")))) {
                        currentEar = currentEar.equals("left") ? "right" : "left";
                        currentFrequencyIndex = 0;
                        stepCount = 0;
                        currentVolumeLevel = 50; // Reset volume level for the other ear
                        thresholdFound = false;
                        Log.d("TestThresholdActivity", "Switching to the other ear: " + currentEar);
                        runTestSequence(); // Ensure the test continues for the other ear immediately
                    } else {
                        showResults();
                    }
                    return;
                }
            }
        }

        updateTestProgress();
        runTestSequence();
    }

    private void resetTestVariablesForNextFrequency() {
        currentVolumeLevel = 50; // Reset volume level for the next frequency
        stepCount = 0;
        reversalLevels.clear();
        thresholdFound = false;
        lastDirectionUp = null; // Reset last direction for the next frequency
    }

    private int getNextDbHL(boolean isCorrect) {
        int nextVolumeLevel = currentVolumeLevel;
        if (isCorrect) {
            if (shapeWithSound != 2) { // Only reduce volume for correct responses with sound
                if (stepCount == 0) {
                    nextVolumeLevel -= 20;
                } else if (stepCount == 1) {
                    nextVolumeLevel -= 10;
                } else {
                    nextVolumeLevel -= 5;
                }
            }
        } else {
            if (stepCount == 0) {
                nextVolumeLevel += 20;
            } else if (stepCount == 1) {
                nextVolumeLevel += 10;
            } else {
                nextVolumeLevel += 5;
            }
        }
        // Ensure the next volume level is within the allowed range
        return Math.max(MIN_VOLUME_DB_HL - 1, Math.min(MAX_VOLUME_DB_HL + 1, nextVolumeLevel)); // Allow for one step beyond the min/max for proper checking
    }

    private void repeatCurrentTest() {
        if (isTestInProgress) return; // Prevent repeat if a test is already in progress
        playTone(lastFrequency, lastShapeWithSound);
    }

    private void togglePauseTest() {
        isPaused = !isPaused;
        if (!isPaused) {
            runTestSequence(); // Resume immediately
        }
    }

    private void updateTestProgress() {
        int progressPercentage = 0;

        if (earOrder.contains("->")) {
            int totalSteps = frequencies.length * 2; // Two ears
            int completedSteps = currentFrequencyIndex + (currentEar.equals("right") ? frequencies.length : 0);

            // Special handling for "R. Ear -> L. Ear" to ensure correct progress calculation
            if ("R. Ear -> L. Ear".equalsIgnoreCase(earOrder)) {
                if ("right".equals(currentEar)) {
                    completedSteps = currentFrequencyIndex;
                } else {
                    completedSteps = frequencies.length + currentFrequencyIndex;
                }
            }

            progressPercentage = (completedSteps * 100) / totalSteps;
        } else {
            progressPercentage = (currentFrequencyIndex * 100) / frequencies.length;
        }

        String progressText = getString(R.string.test_progress, progressPercentage);
        txtTestProgress.setText(progressText);

        Log.d("TestThresholdActivity", "Test progress: " + progressPercentage + "%");

        // Check if the test is complete
        if (progressPercentage >= 100) {
            showResults();
        } else if (currentFrequencyIndex >= frequencies.length) {
            // Handle ear switch logic based on ear order
            if ((currentEar.equals("left") && leftEarToRightEar.equalsIgnoreCase(earOrder)) ||
                    (currentEar.equals("right") && rightEarToLeftEar.equalsIgnoreCase(earOrder))) {
                currentEar = currentEar.equals("left") ? "right" : "left";
                currentFrequencyIndex = 0;
                stepCount = 0;
                currentVolumeLevel = 50; // Reset volume level for the other ear
                thresholdFound = false;
                Log.d("TestThresholdActivity", "Switching to the other ear: " + currentEar);
                runTestSequence(); // Ensure the test continues for the other ear immediately
            }
        } else {
            runTestSequence(); // Start the next test immediately
        }
    }

    private void showResults() {
        // Save test data to SharedPreferences
        saveTestData();

        // Transition to ViewResultsActivity
        transitionToViewResults();
    }

    private void saveTestData() {
        SharedPreferences sharedPreferences = getSharedPreferences("TestResults", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        // Generate a unique key for this test
        String testKey = String.valueOf(System.currentTimeMillis());

        // Log the generated test key
        Log.d("TestThresholdActivity", "Generated testKey: " + testKey);

        // Store group name, patient name, test type
        editor.putString(testKey + "_group_name", patientGroup);
        editor.putString(testKey + "_patient_name", patientName);
        editor.putString(testKey + "_test_type", testTypeThreshold);

        // Log the frequencies and thresholds being saved
        Log.d("TestThresholdActivity", "Saving test data for frequencies: " + Arrays.toString(frequencies));
        Log.d("TestThresholdActivity", "Left thresholds: " + leftEarThresholds.toString());
        Log.d("TestThresholdActivity", "Right thresholds: " + rightEarThresholds.toString());
        Log.d("TestThresholdActivity", "Test counts: " + testCounts.toString());

        // Store only conducted frequencies and their corresponding thresholds
        for (int i = 0; i < frequencies.length; i++) {
            String frequency = frequencies[i];

            // Ensure we do not access an index out of bounds
            int leftThreshold = (i < leftEarThresholds.size()) ? leftEarThresholds.get(i) : -1;
            int rightThreshold = (i < rightEarThresholds.size()) ? rightEarThresholds.get(i) : -1;

            String frequencyKeyLeft = testKey + "_" + frequency + "_left";
            String frequencyKeyRight = testKey + "_" + frequency + "_right";

            Log.d("TestThresholdActivity", "Checking frequency: " + frequency + " with leftThreshold: " + leftThreshold + " and rightThreshold: " + rightThreshold);

            if (leftThreshold != -1) {
                editor.putInt(frequencyKeyLeft, leftThreshold);
                Log.d("TestThresholdActivity", "Saving left threshold for key: " + frequencyKeyLeft + " = " + leftThreshold);
            }
            if (rightThreshold != -1) {
                editor.putInt(frequencyKeyRight, rightThreshold);
                Log.d("TestThresholdActivity", "Saving right threshold for key: " + frequencyKeyRight + " = " + rightThreshold);
            }

            // Save test counts only if tests were conducted
            if (testCounts.containsKey(i + " left")) {
                editor.putString(frequencyKeyLeft + "_testCounts", testCounts.get(i + " left").toString());
                Log.d("TestActualActivity", "Saving test counts for key: " + frequencyKeyLeft + "_testCounts" + ": " + testCounts.get(i + " left").toString());
            }

            if (testCounts.containsKey(i + " right")) {
                editor.putString(frequencyKeyRight + "_testCounts", testCounts.get(i + " right").toString());
                Log.d("TestActualActivity", "Saving test counts for key: " + frequencyKeyRight + "_testCounts" + ": " + testCounts.get(i + " right").toString());
            }
        }

        editor.apply(); // Save changes
        Log.d("TestThresholdActivity", "SharedPreferences saved: " + sharedPreferences.getAll().toString());
    }

    private void transitionToViewResults() {
        Intent intent = new Intent(TestThresholdActivity.this, ViewResultsActivity.class);
        startActivity(intent);
        finish(); // Finish the current activity
    }

    private int getFrequencyIndex(int frequency) {
        int[] predefinedFrequencies = {250, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000, 8000}; // Example list
        for (int i = 0; i < predefinedFrequencies.length; i++) {
            if (predefinedFrequencies[i] == frequency) {
                Log.d("TestThresholdActivity", "Frequency " + frequency + " found at index: " + i);
                return i;
            }
        }
        Log.d("TestThresholdActivity", "Frequency " + frequency + " not found, returning -1");
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
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
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