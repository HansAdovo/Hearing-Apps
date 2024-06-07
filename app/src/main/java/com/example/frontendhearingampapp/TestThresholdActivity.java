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

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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

    private Boolean lastDirectionUp = null;

    private int lastFrequency;
    private int lastShapeWithSound;

    private int noSoundCount = 0;
    private int soundTestCount = 0;

    private List<Integer> reversalLevels = new ArrayList<>();
    private AudioTrack audioTrack;
    private Thread audioThread;

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

        Log.d("TestActualActivity", "EarOrder received: " + earOrder);

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
        if (earOrder.equalsIgnoreCase("L. Ear Only") || earOrder.contains("L. Ear Only")) {
            currentEar = "left";
        } else if (earOrder.equalsIgnoreCase("R. Ear Only") || earOrder.contains("R. Ear Only")) {
            currentEar = "right";
        } else if (earOrder.equalsIgnoreCase("L. Ear -> R. Ear") || earOrder.contains("L. Ear -> R. Ear")) {
            currentEar = "left";
        } else if (earOrder.equalsIgnoreCase("R. Ear -> L. Ear") || earOrder.contains("R. Ear -> L. Ear")) {
            currentEar = "right";
        } else {
            Log.e("TestActualActivity", "Unknown ear order: " + earOrder);
        }
        Log.d("TestActualActivity", "Current ear set to: " + currentEar);
    }

    private void loadCalibrationSettings() {
        currentSettingName = calibrationPrefs.getString("currentSettingName", "");
        for (int i = 0; i < desiredSPLLevelsLeft.length; i++) {
            desiredSPLLevelsLeft[i] = calibrationPrefs.getFloat("desiredSPLLevelLeft_" + currentSettingName + "_" + i, 70.0f);
            desiredSPLLevelsRight[i] = calibrationPrefs.getFloat("desiredSPLLevelRight_" + currentSettingName + "_" + i, 70.0f);
            Log.d("TestActualActivity", "Loaded desiredSPLLevelLeft[" + i + "] = " + desiredSPLLevelsLeft[i]);
            Log.d("TestActualActivity", "Loaded desiredSPLLevelRight[" + i + "] = " + desiredSPLLevelsRight[i]);
        }
    }

    private void startTestSequence() {
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
                Log.d("TestActualActivity", "No sound test being conducted");
            } else {
                soundTestCount++;
            }
            playTone(frequency, shapeWithSound);
        } else {
            if ((currentEar.equals("left") && earOrder.equalsIgnoreCase("L. Ear -> R. Ear")) ||
                    (currentEar.equals("right") && earOrder.equalsIgnoreCase("R. Ear -> L. Ear"))) {
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
        Log.d("TestActualActivity", "Desired SPL: " + desiredSPL);

        // Calculate the actual SPL based on the current dB HL level
        float dB_SPL = desiredSPL + (currentVolumeLevel - 70);
        Log.d("TestActualActivity", "Calculated dB SPL: " + dB_SPL);

        // Calculate the amplitude
        float volume = calculateAmplitude(dB_SPL, 100);
        Log.d("TestActualActivity", "Amplitude: " + volume);

        if (currentEar.equals("left")) {
            audioTrack.setStereoVolume(volume, 0);
        } else {
            audioTrack.setStereoVolume(0, volume);
        }

        Log.d("TestActualActivity", "Setting volume: Left = " + (currentEar.equals("left") ? volume : 0) + ", Right = " + (currentEar.equals("right") ? volume : 0));
        Log.d("TestActualActivity", "Frequency: " + frequency + " Hz, Current dB HL: " + currentVolumeLevel + ", Calculated dB SPL: " + dB_SPL + ", Amplitude: " + volume);

        if (shapeWithSound == 2) {
            Log.d("TestActualActivity", "No sound for this test");
        }

        // Disable buttons during test
        setButtonsEnabled(false);

        // First shape shakes and plays sound if applicable
        imageTopShape.startAnimation(shakeAnimation);
        if (shapeWithSound == 0) {
            Log.d("TestActualActivity", "Top shape shakes and plays sound");
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
                Log.d("TestActualActivity", "Bottom shape shakes and plays sound");
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
            Log.d("TestActualActivity", "User response: No Sound, Correct");
        }
        processUserResponse(isCorrect);
    }

    private void processUserResponse(boolean isCorrect) {
        int previousVolumeLevel = currentVolumeLevel;
        int nextVolumeLevel = getNextDbHL(isCorrect);
        Log.d("TestActualActivity", "User response: " + (isCorrect ? "Correct" : "Incorrect") + ", previous dB HL: " + previousVolumeLevel + ", current dB HL: " + nextVolumeLevel);

        // Determine the direction of change
        boolean directionUp = previousVolumeLevel < nextVolumeLevel;

        // Check if the direction has changed
        boolean directionChanged = (lastDirectionUp != null) && (lastDirectionUp != directionUp);

        if (isCorrect && shapeWithSound == 2) {
            // Correct no sound response should not change the volume level
            nextVolumeLevel = currentVolumeLevel;
        } else {
            currentVolumeLevel = nextVolumeLevel;
            stepCount++;
        }

        if (directionChanged && previousVolumeLevel != currentVolumeLevel) {
            reversalLevels.add(previousVolumeLevel);
            Log.d("TestActualActivity", "Reversal added: " + previousVolumeLevel + " dB HL, Total reversals: " + reversalLevels.size());
        }

        // Update last direction
        lastDirectionUp = directionUp;

        // Log if currentVolumeLevel is exactly at the minimum or maximum
        if (currentVolumeLevel == MIN_VOLUME_DB_HL || currentVolumeLevel == MAX_VOLUME_DB_HL) {
            Log.d("TestActualActivity", (currentVolumeLevel == MIN_VOLUME_DB_HL ? "Minimum" : "Maximum") + " dB HL reached.");
        }

        // Check for test termination condition
        Log.d("TestActualActivity", "Checking termination condition: currentVolumeLevel = " + currentVolumeLevel);
        if (reversalLevels.size() >= 4 || currentVolumeLevel <= MIN_VOLUME_DB_HL || currentVolumeLevel >= MAX_VOLUME_DB_HL) {
            if (reversalLevels.size() >= 4) {
                int sum = 0;
                for (int level : reversalLevels) {
                    sum += level;
                }
                currentVolumeLevel = sum / reversalLevels.size();
                thresholdFound = true;
                Log.d("TestActualActivity", "Threshold found at frequency: " + frequencies[currentFrequencyIndex] + " Hz, volume level: " + currentVolumeLevel + " dB HL");
            } else if (currentVolumeLevel < MIN_VOLUME_DB_HL || currentVolumeLevel > MAX_VOLUME_DB_HL) {
                thresholdFound = true;
                currentVolumeLevel = (currentVolumeLevel < MIN_VOLUME_DB_HL) ? MIN_VOLUME_DB_HL : MAX_VOLUME_DB_HL;
                Log.d("TestActualActivity", "Threshold found at frequency: " + frequencies[currentFrequencyIndex] + " Hz, volume level: " + currentVolumeLevel + " dB HL");
            }

            if (thresholdFound) {
                currentFrequencyIndex++;
                resetTestVariablesForNextFrequency();
                if (currentFrequencyIndex >= frequencies.length) {
                    updateTestProgress(); // Call this before showing results
                    showResults();
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

        Log.d("TestActualActivity", "Test progress: " + progressPercentage + "%");

        if (currentFrequencyIndex >= frequencies.length) {
            // Handle ear switch logic based on ear order
            if ((currentEar.equals("left") && "L. Ear -> R. Ear".equalsIgnoreCase(earOrder)) ||
                    (currentEar.equals("right") && "R. Ear -> L. Ear".equalsIgnoreCase(earOrder))) {
                currentEar = currentEar.equals("left") ? "right" : "left";
                currentFrequencyIndex = 0;
                stepCount = 0;
                currentVolumeLevel = 50; // Reset volume level for the other ear
                thresholdFound = false;
                Log.d("TestActualActivity", "Switching to the other ear: " + currentEar);
                runTestSequence(); // Ensure the test continues for the other ear immediately
            } else {
                showResults();
            }
        } else {
            runTestSequence(); // Start the next test immediately
        }
    }

    private void showResults() {
        // Logic to show the test results
        // For example, navigate to a results screen or save the results

        saveResultsToFile();
    }

    private void saveResultsToFile() {
        String filename = patientGroup + "_" + patientName + ".txt";
        String resultData = "Patient Group: " + patientGroup + "\nPatient Name: " + patientName + "\nTest Results:\n";
        resultData += "Frequency - Volume Level\n";

        for (int i = 0; i < frequencies.length; i++) {
            resultData += frequencies[i] + " Hz - " + currentVolumeLevel + " dB HL\n";
        }

        try (FileOutputStream fos = openFileOutput(filename, MODE_PRIVATE)) {
            fos.write(resultData.getBytes());
            Toast.makeText(this, "Results saved to " + filename, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to save results", Toast.LENGTH_SHORT).show();
        }
    }

    private int getFrequencyIndex(int frequency) {
        switch (frequency) {
            case 250: return 0;
            case 500: return 1;
            case 750: return 2;
            case 1000: return 3;
            case 1500: return 4;
            case 2000: return 5;
            case 3000: return 6;
            case 4000: return 7;
            case 6000: return 8;
            case 8000: return 9;
            default: return 0;
        }
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