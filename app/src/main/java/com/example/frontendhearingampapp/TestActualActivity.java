package com.example.frontendhearingampapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
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

public class TestActualActivity extends AppCompatActivity {

    private Button btnReturnToTitle;
    private Button btnRepeat;
    private Button btnPause;
    private Button btnNoSound;
    private TextView txtInstructions;
    private ImageView imageTopShape;
    private ImageView imageBottomShape;
    private TextView txtTestProgress;

    private Handler handler;
    private Runnable testRunnable;

    private boolean isPaused = false;
    private int currentFrequencyIndex = 0;
    private int currentVolumeLevel = 50; // Start at 50 dB HL
    private String[] frequencies;
    private String earOrder;
    private String currentEar = "left"; // Start with the left ear
    private String patientGroup;
    private String patientName;

    private MediaPlayer mediaPlayer;
    private Animation shakeAnimation;

    private int shapeWithSound; // 0 for top, 1 for bottom, 2 for no sound
    private Random random;

    private SharedPreferences calibrationPrefs;
    private float[] calibratedLevels = new float[10];
    private String currentSettingName;

    private boolean initialPhase = true;
    private int stepCount = 0;

    private int correctCount = 0;
    private int incorrectCount = 0;
    private static final int MAX_CORRECT_COUNT = 2; // Define the threshold for correct answers
    private static final int MAX_INCORRECT_COUNT = 3; // Define the threshold for incorrect answers

    private static final int MIN_VOLUME_DB_HL = 0;
    private static final int MAX_VOLUME_DB_HL = 100;

    private boolean thresholdFound = false; // Flag to indicate if threshold is found
    private boolean confirmMaxMinLevel = false; // Flag to confirm max/min level

    private Boolean lastDirectionUp = null;

    private List<Integer> reversalLevels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_actual);

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
            Intent intent = new Intent(TestActualActivity.this, MainActivity.class);
            startActivity(intent);
            finish(); // Finish current activity to release resources
        });

        btnRepeat.setOnClickListener(view -> repeatCurrentTest());
        btnPause.setOnClickListener(view -> togglePauseTest());
        btnNoSound.setOnClickListener(view -> handleNoSound());

        imageTopShape.setOnClickListener(view -> handleShapePress(0));
        imageBottomShape.setOnClickListener(view -> handleShapePress(1));

        handler = new Handler();
        testRunnable = this::runTestSequence;
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
        for (int i = 0; i < calibratedLevels.length; i++) {
            calibratedLevels[i] = calibrationPrefs.getFloat("measuredLevel_" + currentSettingName + "_" + i, 70.0f);
        }
    }

    private void startTestSequence() {
        handler.postDelayed(testRunnable, 1000);
    }

    private void runTestSequence() {
        if (isPaused) return;

        if (currentFrequencyIndex < frequencies.length) {
            String frequencyStr = frequencies[currentFrequencyIndex];
            int frequency = Integer.parseInt(frequencyStr.replace(" Hz", ""));
            playTone(frequency, calibratedLevels[getFrequencyIndex(frequency)]);
        } else {
            if (("left".equals(currentEar) && "L. Ear -> R. Ear".equalsIgnoreCase(earOrder)) ||
                    ("right".equals(currentEar) && "R. Ear -> L. Ear".equalsIgnoreCase(earOrder))) {
                currentEar = currentEar.equals("left") ? "right" : "left";
                currentFrequencyIndex = 0;
                initialPhase = true;
                stepCount = 0;
                currentVolumeLevel = 50; // Reset volume level for the other ear
                thresholdFound = false;
                confirmMaxMinLevel = false; // Reset flag for the other ear
            } else {
                showResults();
            }
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

    private void playTone(int frequency, float calibratedLevel) {
        shapeWithSound = random.nextInt(3); // Randomly choose shape to produce sound or no sound (0 for top, 1 for bottom, 2 for no sound)

        resetMediaPlayer();

        mediaPlayer = MediaPlayer.create(this, getToneResource(frequency));
        Log.d("TestActualActivity", "Playing tone at frequency: " + frequency + " Hz");

        // Reset animations and handlers
        handler.removeCallbacksAndMessages(null);
        imageTopShape.clearAnimation();
        imageBottomShape.clearAnimation();

        // Convert currentVolumeLevel (dB HL) to dB SPL using calibration settings
        float dB_SPL = currentVolumeLevel + (calibratedLevel - 70); // Adjust according to the calibration level

        // Ensure volume doesn't exceed the max allowed volume of 1.0
        float volume = Math.max(0, Math.min(dB_SPL / 100, 1.0f));

        if (currentEar.equals("left")) {
            mediaPlayer.setVolume(volume, 0);
        } else {
            mediaPlayer.setVolume(0, volume);
        }

        Log.d("TestActualActivity", "Setting volume: Left = " + (currentEar.equals("left") ? volume : 0) + ", Right = " + (currentEar.equals("right") ? volume : 0));

        // Log the test being performed
        Log.d("TestActualActivity", "Test: " + (shapeWithSound == 0 ? "Top Shape with Sound" : shapeWithSound == 1 ? "Bottom Shape with Sound" : "No Sound"));

        // First shape shakes
        imageTopShape.startAnimation(shakeAnimation);
        if (shapeWithSound == 0) {
            mediaPlayer.start();
            Log.d("TestActualActivity", "Top shape shakes and plays sound");
        }
        handler.postDelayed(() -> {
            imageTopShape.clearAnimation();
            if (shapeWithSound == 0) {
                mediaPlayer.stop(); // Ensure sound stops after the animation
            }

            // Second shape shakes
            imageBottomShape.startAnimation(shakeAnimation);
            if (shapeWithSound == 1) {
                mediaPlayer.start();
                Log.d("TestActualActivity", "Bottom shape shakes and plays sound");
            }
            handler.postDelayed(() -> {
                imageBottomShape.clearAnimation();
                if (shapeWithSound == 1) {
                    mediaPlayer.stop(); // Ensure sound stops after the animation
                }
            }, 1000);
        }, 1000);

        mediaPlayer.setOnCompletionListener(mp -> {
            handler.postDelayed(() -> {
                imageTopShape.clearAnimation();
                imageBottomShape.clearAnimation();
            }, 1000);
        });
    }

    private void handleShapePress(int shapeIndex) {
        if (thresholdFound) return; // Skip if threshold already found

        boolean isCorrect = (shapeIndex == shapeWithSound);
        int previousVolumeLevel = currentVolumeLevel;
        int nextVolumeLevel = getNextDbHL(isCorrect);
        Log.d("TestActualActivity", "User response: " + (isCorrect ? "Correct" : "Incorrect") + ", previous dB HL: " + previousVolumeLevel + ", current dB HL: " + nextVolumeLevel);

        // Determine the direction of change
        boolean directionUp = previousVolumeLevel < nextVolumeLevel;

        // Check if the direction has changed
        boolean directionChanged = (lastDirectionUp != null) && (lastDirectionUp != directionUp);

        currentVolumeLevel = nextVolumeLevel;

        if (isCorrect) {
            correctCount++;
            incorrectCount = 0;
        } else {
            correctCount = 0;
            incorrectCount++;
        }

        stepCount++;

        if (directionChanged && stepCount >= 2) {
            reversalLevels.add(previousVolumeLevel);
            Log.d("TestActualActivity", "Reversal added: " + previousVolumeLevel + " dB HL, Total reversals: " + reversalLevels.size());
        }

        // Update last direction
        lastDirectionUp = directionUp;

        if (reversalLevels.size() >= 4 || currentVolumeLevel == MIN_VOLUME_DB_HL || currentVolumeLevel == MAX_VOLUME_DB_HL) {
            if (reversalLevels.size() >= 4) {
                int sum = 0;
                for (int level : reversalLevels) {
                    sum += level;
                }
                currentVolumeLevel = sum / reversalLevels.size();
                thresholdFound = true;
                Log.d("TestActualActivity", "Threshold found at frequency: " + frequencies[currentFrequencyIndex] + " Hz, volume level: " + currentVolumeLevel + " dB HL");
            } else if (currentVolumeLevel == MIN_VOLUME_DB_HL || currentVolumeLevel == MAX_VOLUME_DB_HL) {
                if (confirmMaxMinLevel) {
                    thresholdFound = true;
                    Log.d("TestActualActivity", "Threshold found at frequency: " + frequencies[currentFrequencyIndex] + " Hz, volume level: " + currentVolumeLevel + " dB HL");
                } else {
                    confirmMaxMinLevel = true;
                    Log.d("TestActualActivity", (currentVolumeLevel == MIN_VOLUME_DB_HL ? "Minimum" : "Maximum") + " dB HL reached.");
                }
            }

            if (thresholdFound) {
                currentFrequencyIndex++;
                resetTestVariablesForNextFrequency();
            }
        }

        updateTestProgress();
        handler.postDelayed(testRunnable, 1000);
    }

    private void resetTestVariablesForNextFrequency() {
        currentVolumeLevel = 50; // Reset volume level for the next frequency
        correctCount = 0;
        incorrectCount = 0;
        stepCount = 0;
        reversalLevels.clear();
        thresholdFound = false;
        confirmMaxMinLevel = false;
        lastDirectionUp = null; // Reset last direction for the next frequency
    }

    private int getNextDbHL(boolean isCorrect) {
        int nextVolumeLevel = currentVolumeLevel;
        if (isCorrect) {
            if (stepCount == 0) {
                nextVolumeLevel -= 20;
            } else if (stepCount == 1) {
                nextVolumeLevel -= 10;
            } else {
                nextVolumeLevel -= 5;
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
        return Math.max(MIN_VOLUME_DB_HL, Math.min(MAX_VOLUME_DB_HL, nextVolumeLevel));
    }

    private void repeatCurrentTest() {
        if (currentFrequencyIndex < frequencies.length) {
            String frequencyStr = frequencies[currentFrequencyIndex];
            int frequency = Integer.parseInt(frequencyStr.replace(" Hz", ""));
            playTone(frequency, calibratedLevels[getFrequencyIndex(frequency)]);
        }
    }

    private void togglePauseTest() {
        isPaused = !isPaused;
        if (!isPaused) {
            handler.postDelayed(testRunnable, 1000);
        }
    }

    private void handleNoSound() {
        if (thresholdFound) return; // Skip if threshold already found

        boolean isCorrect = (shapeWithSound == 2);
        int previousVolumeLevel = currentVolumeLevel;

        if (!isCorrect) { // Only penalize if it was a false positive
            currentVolumeLevel = Math.min(currentVolumeLevel + (stepCount == 0 ? 20 : stepCount == 1 ? 10 : 5), MAX_VOLUME_DB_HL);
            incorrectCount++;
        } else {
            correctCount = 0;
        }

        Log.d("TestActualActivity", "User response: No Sound, " + (isCorrect ? "Correct" : "Incorrect") + ", previous dB HL: " + previousVolumeLevel + ", current dB HL: " + currentVolumeLevel);

        // Determine the direction of change
        boolean directionUp = previousVolumeLevel < currentVolumeLevel;

        // Check if the direction has changed
        boolean directionChanged = (lastDirectionUp != null) && (lastDirectionUp != directionUp);

        if (directionChanged && stepCount >= 2 && previousVolumeLevel != currentVolumeLevel) {
            reversalLevels.add(previousVolumeLevel);
            Log.d("TestActualActivity", "Reversal added: " + previousVolumeLevel + " dB HL, Total reversals: " + reversalLevels.size());
        }

        // Update last direction
        lastDirectionUp = directionUp;

        if (reversalLevels.size() >= 4 || currentVolumeLevel == MIN_VOLUME_DB_HL || currentVolumeLevel == MAX_VOLUME_DB_HL) {
            if (reversalLevels.size() >= 4) {
                int sum = 0;
                for (int level : reversalLevels) {
                    sum += level;
                }
                currentVolumeLevel = sum / reversalLevels.size();
                thresholdFound = true;
                Log.d("TestActualActivity", "Threshold found at frequency: " + frequencies[currentFrequencyIndex] + " Hz, volume level: " + currentVolumeLevel + " dB HL");
            } else if (currentVolumeLevel == MIN_VOLUME_DB_HL || currentVolumeLevel == MAX_VOLUME_DB_HL) {
                if (confirmMaxMinLevel) {
                    thresholdFound = true;
                    Log.d("TestActualActivity", "Threshold found at frequency: " + frequencies[currentFrequencyIndex] + " Hz, volume level: " + currentVolumeLevel + " dB HL");
                } else {
                    confirmMaxMinLevel = true;
                    Log.d("TestActualActivity", (currentVolumeLevel == MIN_VOLUME_DB_HL ? "Minimum" : "Maximum") + " dB HL reached.");
                }
            }

            if (thresholdFound) {
                currentFrequencyIndex++;
                resetTestVariablesForNextFrequency();
            }
        }

        updateTestProgress();
        handler.postDelayed(testRunnable, 1000);
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
            if (("left".equals(currentEar) && "L. Ear -> R. Ear".equalsIgnoreCase(earOrder)) ||
                    ("right".equals(currentEar) && "R. Ear -> L. Ear".equalsIgnoreCase(earOrder))) {
                currentEar = currentEar.equals("left") ? "right" : "left";
                currentFrequencyIndex = 0;
                initialPhase = true;
                stepCount = 0;
                currentVolumeLevel = 50; // Reset volume level for the other ear
                thresholdFound = false;
                confirmMaxMinLevel = false; // Reset flag for the other ear
                Log.d("TestActualActivity", "Switching to the other ear: " + currentEar);
            } else {
                showResults();
            }
        } else {
            handler.postDelayed(testRunnable, 1000);
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

    private int getToneResource(int frequency) {
        // Map frequencies to resource IDs
        switch (frequency) {
            case 250:
                return R.raw.tone_250hz;
            case 500:
                return R.raw.tone_500hz;
            case 750:
                return R.raw.tone_750hz;
            case 1000:
                return R.raw.tone_1000hz;
            case 1500:
                return R.raw.tone_1500hz;
            case 2000:
                return R.raw.tone_2000hz;
            case 3000:
                return R.raw.tone_3000hz;
            case 4000:
                return R.raw.tone_4000hz;
            case 6000:
                return R.raw.tone_6000hz;
            case 8000:
                return R.raw.tone_8000hz;
            default:
                return R.raw.tone_250hz; // Fallback
        }
    }

    private void resetMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resetMediaPlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        resetMediaPlayer();
        handler.removeCallbacksAndMessages(null); // Release handler resources
    }
}