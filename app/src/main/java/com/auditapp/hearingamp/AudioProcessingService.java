package com.auditapp.hearingamp;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

public class AudioProcessingService extends Service {
    private static final String TAG = "AudioProcessingService";
    private boolean isProcessing = false;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread processingThread;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static float AMPLIFICATION_FACTOR = 5.0f; // Increased from 2.0f to 5.0f
    private static final int BUFFER_SIZE_FACTOR = 8;

    public static final String ACTION_PERMISSIONS_REQUIRED = "com.auditapp.hearingamp.ACTION_PERMISSIONS_REQUIRED";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isProcessing) {
            if (checkPermission()) {
                startProcessing();
            } else {
                sendBroadcast(new Intent(ACTION_PERMISSIONS_REQUIRED));
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startProcessing() {
        isProcessing = true;
        initializeAudio();
        processingThread = new Thread(this::processAudio);
        processingThread.start();
    }

    private void initializeAudio() {
        if (!checkPermission()) {
            Log.e(TAG, "Audio permission not granted");
            return;
        }

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
        int bufferSize = minBufferSize * BUFFER_SIZE_FACTOR;

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size obtained");
            return;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    bufferSize);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to initialize AudioRecord: " + e.getMessage());
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG_OUT)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                        CHANNEL_CONFIG_OUT, AUDIO_FORMAT, bufferSize, AudioTrack.MODE_STREAM);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to initialize AudioTrack: " + e.getMessage());
            return;
        }

        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize");
            return;
        }

        audioRecord.startRecording();
        audioTrack.play();
        Log.d(TAG, "Audio initialized successfully with buffer size: " + bufferSize);

        // Uncomment the next line to play a test tone
        // playTestTone();
    }

    private void processAudio() {
        short[] buffer = new short[AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) / 2];
        long totalProcessed = 0;
        long startTime = System.currentTimeMillis();

        while (isProcessing && audioRecord != null && audioTrack != null) {
            int shortsRead = audioRecord.read(buffer, 0, buffer.length);
            Log.d(TAG, "Read " + shortsRead + " shorts from AudioRecord");

            if (shortsRead > 0) {
                // Simple amplification without other processing
                for (int i = 0; i < shortsRead; i++) {
                    float input = buffer[i] / 32768.0f;
                    float output = input * AMPLIFICATION_FACTOR;
                    buffer[i] = (short) (Math.max(-1, Math.min(1, output)) * 32767);
                }

                audioTrack.write(buffer, 0, shortsRead);
                Log.d(TAG, "Wrote " + shortsRead + " shorts to AudioTrack");

                totalProcessed += shortsRead;

                if (totalProcessed % (SAMPLE_RATE * 5) == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    Log.d(TAG, String.format("Processed %d samples in %d ms (%.2f samples/sec)",
                            totalProcessed, elapsed, (totalProcessed * 1000.0 / elapsed)));
                }
            }
        }
    }

    private void playTestTone() {
        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized for test tone");
            return;
        }

        int duration = 3; // seconds
        int numSamples = SAMPLE_RATE * duration;
        short[] sample = new short[numSamples];
        double frequency = 440; // Hz

        for (int i = 0; i < numSamples; ++i) {
            sample[i] = (short) (Math.sin(2 * Math.PI * i / (SAMPLE_RATE / frequency)) * 32767);
        }

        audioTrack.write(sample, 0, sample.length);
        Log.d(TAG, "Test tone played");
    }

    public void setAmplificationFactor(float factor) {
        AMPLIFICATION_FACTOR = factor;
    }

    @Override
    public void onDestroy() {
        isProcessing = false;
        if (processingThread != null) {
            processingThread.interrupt();
            try {
                processingThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error joining processing thread", e);
            }
        }
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
        }
        super.onDestroy();
    }
}