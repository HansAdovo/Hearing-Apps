/*
 * Hearing Amplification Engine
 *
 * This code implements a real-time hearing amplification system for Android devices using the Oboe library.
 * It uses a multi-band approach, splitting the audio into four frequency ranges:
 * 1. 250-750 Hz
 * 2. 751-1500 Hz
 * 3. 1501-3000 Hz
 * 4. 3001-8000 Hz
 *
 * Key components and features:
 * - Digital biquad bandpass filters separate the input audio into frequency bands.
 * - Each band is processed independently using Wide Dynamic Range Compression (WDRC).
 * - WDRC parameters (threshold, ratio, attack time, release time, and gain) are configurable for each band.
 * - The AudioRingBuffer class allows for thread-safe audio data transfer between input and output streams.
 * - The HearingAmpEngine class is the core processor, implementing oboe::AudioStreamCallback for real-time processing.
 * - JNI functions bridge the Android Java code with the native C++ audio processing, allowing control over:
 *   - Starting and stopping the engine
 *   - Updating audio parameters
 *   - Managing the audio processing lifecycle
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <mutex>
#include <atomic>
#include <thread>
#include <array>
#include <condition_variable>
#include <chrono>
#include <memory>

// Define logging macros for different severity levels
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hearingamp", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "hearingamp", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "hearingamp", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "hearingamp", __VA_ARGS__)
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "hearingamp", __VA_ARGS__)

// Define constants for audio processing
constexpr int DEFAULT_SAMPLE_RATE = 48000;
constexpr int DEFAULT_CHANNEL_COUNT = 2;
constexpr int FRAMES_PER_CALLBACK = 64;
constexpr int BUFFER_SIZE_FRAMES = 512;
constexpr int NUM_BANDS = 4;

// Structure to hold Wide Dynamic Range Compression (WDRC) parameters
struct WDRCParams {
    float threshold;    // Compression threshold in dB
    float ratio;        // Compression ratio
    float attack_time;  // Attack time in seconds
    float release_time; // Release time in seconds
    float gain;         // Gain in dB
};

// Global error flag for thread-safe error handling
std::atomic<bool> gErrorFlag{false};

// Function to set the global error flag
void setErrorFlag() {
    gErrorFlag.store(true, std::memory_order_relaxed);
}

// Function to check and reset the global error flag
bool checkAndResetErrorFlag() {
    return gErrorFlag.exchange(false, std::memory_order_relaxed);
}

// AudioRingBuffer class for managing audio data between threads
class AudioRingBuffer {
public:
    AudioRingBuffer(size_t capacity) : mCapacity(capacity), mBuffer(capacity) {}

    // Write data to the buffer
    void write(const float* data, size_t size) {
        if (!data) {
            LOGE("Attempting to write null data to AudioRingBuffer");
            setErrorFlag();
            return;
        }
        std::lock_guard<std::mutex> lock(mMutex);
        for (size_t i = 0; i < size; ++i) {
            mBuffer[mWriteIndex] = data[i];
            mWriteIndex = (mWriteIndex + 1) % mCapacity;
            if (mSize < mCapacity) {
                ++mSize;
            } else {
                mReadIndex = (mReadIndex + 1) % mCapacity;
            }
        }
        mCondVar.notify_one();
    }

    // Read data from the buffer
    size_t read(float* data, size_t size) {
        if (!data) {
            LOGE("Attempting to read into null buffer from AudioRingBuffer");
            setErrorFlag();
            return 0;
        }
        std::unique_lock<std::mutex> lock(mMutex);
        if (!mCondVar.wait_for(lock, std::chrono::milliseconds(100), [this] { return mSize > 0; })) {
            LOGW("Timeout waiting for data in AudioRingBuffer");
            return 0;
        }
        size_t read = std::min(size, mSize);
        for (size_t i = 0; i < read; ++i) {
            data[i] = mBuffer[mReadIndex];
            mReadIndex = (mReadIndex + 1) % mCapacity;
        }
        mSize -= read;
        return read;
    }

    // Get the current size of the buffer
    size_t size() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mSize;
    }

private:
    std::vector<float> mBuffer;
    size_t mCapacity;
    size_t mSize = 0;
    size_t mReadIndex = 0;
    size_t mWriteIndex = 0;
    mutable std::mutex mMutex;
    std::condition_variable mCondVar;
};

// BandpassFilter class for implementing a digital biquad bandpass filter
class BandpassFilter {
public:
    // Constructor: initialize the filter with given parameters
    BandpassFilter(float sampleRate, float lowFreq, float highFreq) {
        // Calculate filter coefficients using bilinear transform
        float w0 = 2 * M_PI * (lowFreq + highFreq) / 2 / sampleRate;
        float bw = (highFreq - lowFreq) / (lowFreq + highFreq);
        float q = 1 / (2 * sinh(log(2) / 2 * bw * w0 / sin(w0)));
        float alpha = sin(w0) / (2 * q);

        b0 = alpha;
        b1 = 0;
        b2 = -alpha;
        a0 = 1 + alpha;
        a1 = -2 * cos(w0);
        a2 = 1 - alpha;

        x1 = x2 = y1 = y2 = 0;
    }

    // Process a single input sample through the filter
    float process(float input) {
        float output = (b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
        x2 = x1;
        x1 = input;
        y2 = y1;
        y1 = output;
        return output;
    }

private:
    float b0, b1, b2, a0, a1, a2; // Filter coefficients
    float x1, x2, y1, y2; // State variables
};

// HearingAmpEngine class: core audio processing engine
class HearingAmpEngine : public oboe::AudioStreamCallback {
public:
    HearingAmpEngine()
            : mInputBuffer(BUFFER_SIZE_FRAMES * DEFAULT_CHANNEL_COUNT),
              mOutputBuffer(BUFFER_SIZE_FRAMES * DEFAULT_CHANNEL_COUNT),
              mAmplification(2.5f),
              mFilters{
                      BandpassFilter(DEFAULT_SAMPLE_RATE, 250, 750),
                      BandpassFilter(DEFAULT_SAMPLE_RATE, 751, 1500),
                      BandpassFilter(DEFAULT_SAMPLE_RATE, 1501, 3000),
                      BandpassFilter(DEFAULT_SAMPLE_RATE, 3001, 8000)
              } {
        setupWDRC();
        LOGD("HearingAmpEngine constructed with BUFFER_SIZE_FRAMES=%d, FRAMES_PER_CALLBACK=%d", BUFFER_SIZE_FRAMES, FRAMES_PER_CALLBACK);
    }

    // Callback function for processing audio data
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
        // Check for errors
        if (checkAndResetErrorFlag()) {
            LOGE("Error detected, stopping audio processing");
            return oboe::DataCallbackResult::Stop;
        }

        if (!stream || !audioData) {
            LOGE("Invalid stream or audioData in onAudioReady");
            return oboe::DataCallbackResult::Stop;
        }

        float *data = static_cast<float*>(audioData);
        int32_t channelCount = stream->getChannelCount();
        size_t totalFrames = numFrames * channelCount;

        if (!mIsProcessing) {
            LOGD("Processing stopped, returning Stop");
            return oboe::DataCallbackResult::Stop;
        }

        static int callbackCounter = 0;
        static float maxInputSample = 0.0f;
        static float maxOutputSample = 0.0f;

        if (stream->getDirection() == oboe::Direction::Input) {
            // Process input audio
            std::vector<float> processedBuffer(totalFrames, 0.0f);

            for (int i = 0; i < numFrames; ++i) {
                for (int channel = 0; channel < channelCount; ++channel) {
                    size_t index = i * channelCount + channel;
                    if (index >= totalFrames || index >= processedBuffer.size()) {
                        LOGE("Buffer overflow in input processing: index=%zu, totalFrames=%zu, bufferSize=%zu",
                             index, totalFrames, processedBuffer.size());
                        return oboe::DataCallbackResult::Stop;
                    }
                    float sample = data[index];
                    maxInputSample = std::max(maxInputSample, std::abs(sample));

                    // Apply bandpass filters and WDRC
                    float processedSample = 0.0f;
                    for (int band = 0; band < NUM_BANDS; ++band) {
                        float filteredSample = mFilters[band].process(sample);
                        processedSample += applyWDRC(filteredSample, band, channel) / NUM_BANDS;
                    }
                    processedSample = std::clamp(processedSample * mAmplification, -1.0f, 1.0f);
                    processedBuffer[index] = processedSample;
                    maxOutputSample = std::max(maxOutputSample, std::abs(processedSample));
                }
            }

            mOutputBuffer.write(processedBuffer.data(), processedBuffer.size());

            // Log processing statistics periodically
            if (++callbackCounter % 100 == 0) {
                LOGD("Audio processing: MaxInput=%.4f, MaxOutput=%.4f, Frames=%d, BufferSize=%zu",
                     maxInputSample, maxOutputSample, numFrames, mOutputBuffer.size());
                maxInputSample = 0.0f;
                maxOutputSample = 0.0f;
            }
        } else if (stream->getDirection() == oboe::Direction::Output) {
            // Handle output audio
            size_t framesRead = mOutputBuffer.read(data, totalFrames);
            if (framesRead < totalFrames) {
                std::fill(data + framesRead, data + totalFrames, 0.0f);
                LOGW("Buffer underrun: read %zu frames, expected %zu", framesRead, totalFrames);
            }
        } else {
            LOGE("Unknown stream direction");
            return oboe::DataCallbackResult::Stop;
        }

        return oboe::DataCallbackResult::Continue;
    }

    // Update WDRC parameters for both ears
    void updateParams(const std::array<WDRCParams, NUM_BANDS>& leftParams, const std::array<WDRCParams, NUM_BANDS>& rightParams) {
        std::lock_guard<std::mutex> lock(mParamMutex);
        mWDRCParams[0] = leftParams;
        mWDRCParams[1] = rightParams;
        LOGD("WDRC parameters updated for both ears");
    }

    // Stop audio processing
    void stopProcessing() {
        std::lock_guard<std::mutex> lock(mProcessingMutex);
        mIsProcessing = false;
    }

    // Start audio processing
    void startProcessing() {
        std::lock_guard<std::mutex> lock(mProcessingMutex);
        mIsProcessing = true;
    }

private:
    AudioRingBuffer mInputBuffer;
    AudioRingBuffer mOutputBuffer;
    float mAmplification;
    std::array<BandpassFilter, NUM_BANDS> mFilters;
    std::array<std::array<WDRCParams, NUM_BANDS>, 2> mWDRCParams;  // [0] for left, [1] for right
    std::mutex mParamMutex;
    std::array<std::array<float, NUM_BANDS>, 2> mEnvelopes;  // [0] for left, [1] for right
    std::atomic<bool> mIsProcessing{true};
    std::mutex mProcessingMutex;

    // Initialize WDRC parameters
    void setupWDRC() {
        for (int ear = 0; ear < 2; ++ear) {
            for (int i = 0; i < NUM_BANDS; ++i) {
                mWDRCParams[ear][i] = {-40.0f + i * 5.0f, 3.0f + i * 0.5f, 0.01f, 0.1f, 10.0f};
                mEnvelopes[ear][i] = 0.0f;
            }
        }
    }

    // Apply Wide Dynamic Range Compression (WDRC) to a sample
    float applyWDRC(float input, int band, int channel) {
        if (band < 0 || band >= NUM_BANDS || channel < 0 || channel >= 2) {
            LOGE("Invalid band or channel in applyWDRC: band=%d, channel=%d", band, channel);
            setErrorFlag();
            return input;
        }

        float& envelope = mEnvelopes[channel][band];
        const WDRCParams& params = mWDRCParams[channel][band];

        // Calculate attack and release coefficients
        float alphaAttack = std::exp(-1.0f / (DEFAULT_SAMPLE_RATE * params.attack_time));
        float alphaRelease = std::exp(-1.0f / (DEFAULT_SAMPLE_RATE * params.release_time));

        // Envelope detection
        float inputLevel = std::abs(input);
        float alpha = inputLevel > envelope ? alphaAttack : alphaRelease;
        envelope = alpha * envelope + (1.0f - alpha) * inputLevel;

        // Apply compression
        float gainLinear = std::pow(10.0f, params.gain / 20.0f);
        float thresholdLinear = std::pow(10.0f, params.threshold / 20.0f);
        float compressionGain = 1.0f;

        if (envelope > thresholdLinear) {
            compressionGain = std::pow(envelope / thresholdLinear, 1.0f / params.ratio - 1.0f);
        }

        float output = input * gainLinear * compressionGain;

        // Log significant changes in compression gain
        static float lastCompressionGain[NUM_BANDS][2] = {{1.0f}};
        if (std::abs(compressionGain - lastCompressionGain[band][channel]) > 0.1f) {
            LOGD("WDRC: Band=%d, Channel=%d, Threshold=%.2f, Ratio=%.2f, Gain=%.2f, CompGain=%.2f",
                 band, channel, params.threshold, params.ratio, params.gain, compressionGain);
            lastCompressionGain[band][channel] = compressionGain;
        }

        return output;
    }
};

// Global engine and stream pointers
static HearingAmpEngine *engine = nullptr;
static std::shared_ptr<oboe::AudioStream> inputStream;
static std::shared_ptr<oboe::AudioStream> outputStream;

// JNI function to start audio processing
extern "C" JNIEXPORT jint JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeStartAudioProcessing(JNIEnv *env, jobject /* this */) {
    LOGD("Starting audio processing");

    // Check if engine already exists and stop it if necessary
    if (engine != nullptr) {
        LOGW("Engine already exists, stopping previous instance");
        engine->stopProcessing();
        delete engine;
    }

    // Create a new HearingAmpEngine instance
    try {
        engine = new HearingAmpEngine();
    } catch (const std::exception& e) {
        LOGE("Failed to create HearingAmpEngine: %s", e.what());
        return -1;
    }

    oboe::AudioStreamBuilder builder;

    // Set up input stream
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(DEFAULT_CHANNEL_COUNT)
            ->setSampleRate(DEFAULT_SAMPLE_RATE)
            ->setFramesPerCallback(FRAMES_PER_CALLBACK)
            ->setCallback(engine);

    // Open input stream
    oboe::Result result = builder.openStream(inputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream. Error: %s", oboe::convertToText(result));
        delete engine;
        engine = nullptr;
        return -1;
    }

    int actualSampleRate = inputStream->getSampleRate();
    int actualChannelCount = inputStream->getChannelCount();

    LOGI("Input stream opened with sample rate: %d, channels: %d", actualSampleRate, actualChannelCount);

    // Set up output stream with matching configuration
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSampleRate(actualSampleRate)
            ->setChannelCount(actualChannelCount);

    // Open output stream
    result = builder.openStream(outputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream. Error: %s", oboe::convertToText(result));
        inputStream->close();
        delete engine;
        engine = nullptr;
        return -1;
    }

    LOGI("Output stream opened with sample rate: %d, channels: %d", outputStream->getSampleRate(), outputStream->getChannelCount());

    // Start both streams
    result = inputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream. Error: %s", oboe::convertToText(result));
        inputStream->close();
        outputStream->close();
        delete engine;
        engine = nullptr;
        return -1;
    }

    result = outputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream. Error: %s", oboe::convertToText(result));
        inputStream->requestStop();
        inputStream->close();
        outputStream->close();
        delete engine;
        engine = nullptr;
        return -1;
    }

    engine->startProcessing();

    LOGD("Audio processing started successfully");
    return 0;
}

// JNI function to stop audio processing
extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeStopAudioProcessing(JNIEnv *env, jobject /* this */) {
    if (engine) {
        engine->stopProcessing();

        // Stop and close input stream
        if (inputStream) {
            LOGD("Stopping input stream with sample rate: %d, channels: %d", inputStream->getSampleRate(), inputStream->getChannelCount());
            inputStream->requestStop();
            inputStream->close();
            inputStream.reset();
        }
        // Stop and close output stream
        if (outputStream) {
            LOGD("Stopping output stream with sample rate: %d, channels: %d", outputStream->getSampleRate(), outputStream->getChannelCount());
            outputStream->requestStop();
            outputStream->close();
            outputStream.reset();
        }
        delete engine;
        engine = nullptr;
        LOGD("Audio processing stopped and cleaned up");
    } else {
        LOGW("Engine is already stopped or not initialized");
    }
}

// JNI function to start processing (without reinitializing streams)
extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeStartProcessing(JNIEnv *env, jobject /* this */) {
    if (engine != nullptr) {
        LOGD("Starting audio processing");
        engine->startProcessing();
    } else {
        LOGE("Engine is not initialized. Call startAudioProcessing first.");
    }
}

// JNI function to stop processing (without closing streams)
extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeStopProcessing(JNIEnv *env, jobject /* this */) {
    if (engine) {
        engine->stopProcessing();
        LOGD("Audio processing stopped");
    } else {
        LOGW("Engine is already stopped or not initialized");
    }
}

// JNI function to update audio processing parameters
extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeUpdateAudioParams(JNIEnv *env, jobject /* this */,
                                                                            jfloatArray leftThresholds,
                                                                            jfloatArray rightThresholds,
                                                                            jfloatArray leftGains,
                                                                            jfloatArray rightGains,
                                                                            jfloatArray ratios,
                                                                            jfloatArray attacks,
                                                                            jfloatArray releases) {
    if (engine == nullptr) {
        LOGE("Engine is not initialized");
        return;
    }

    // Check if array lengths match the number of bands
    if (env->GetArrayLength(leftThresholds) != NUM_BANDS ||
        env->GetArrayLength(rightThresholds) != NUM_BANDS ||
        env->GetArrayLength(leftGains) != NUM_BANDS ||
        env->GetArrayLength(rightGains) != NUM_BANDS ||
        env->GetArrayLength(ratios) != NUM_BANDS ||
        env->GetArrayLength(attacks) != NUM_BANDS ||
        env->GetArrayLength(releases) != NUM_BANDS) {
        LOGE("Invalid array length in updateAudioParams");
        return;
    }

    // Get pointers to the Java arrays
    jfloat* leftThresholdPtr = env->GetFloatArrayElements(leftThresholds, nullptr);
    jfloat* rightThresholdPtr = env->GetFloatArrayElements(rightThresholds, nullptr);
    jfloat* leftGainPtr = env->GetFloatArrayElements(leftGains, nullptr);
    jfloat* rightGainPtr = env->GetFloatArrayElements(rightGains, nullptr);
    jfloat* ratioPtr = env->GetFloatArrayElements(ratios, nullptr);
    jfloat* attackPtr = env->GetFloatArrayElements(attacks, nullptr);
    jfloat* releasePtr = env->GetFloatArrayElements(releases, nullptr);

    if (!leftThresholdPtr || !rightThresholdPtr || !leftGainPtr || !rightGainPtr || !ratioPtr || !attackPtr || !releasePtr) {
        LOGE("Failed to get float array elements in updateAudioParams");
        return;
    }

    // Prepare WDRC parameters for left and right ears
    std::array<WDRCParams, NUM_BANDS> leftParams, rightParams;
    for (int i = 0; i < NUM_BANDS; ++i) {
        leftParams[i] = {
                leftThresholdPtr[i],
                ratioPtr[i],
                attackPtr[i],
                releasePtr[i],
                leftGainPtr[i]
        };
        rightParams[i] = {
                rightThresholdPtr[i],
                ratioPtr[i],
                attackPtr[i],
                releasePtr[i],
                rightGainPtr[i]
        };
        LOGD("Band %d: Left Threshold=%.2f, Right Threshold=%.2f, Ratio=%.2f, Attack=%.2f, Release=%.2f, Left Gain=%.2f, Right Gain=%.2f",
             i, leftParams[i].threshold, rightParams[i].threshold, leftParams[i].ratio,
             leftParams[i].attack_time, leftParams[i].release_time, leftParams[i].gain, rightParams[i].gain);
    }

    // Update the engine with new parameters
    engine->updateParams(leftParams, rightParams);

    // Release the Java array elements
    env->ReleaseFloatArrayElements(leftThresholds, leftThresholdPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(rightThresholds, rightThresholdPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(leftGains, leftGainPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(rightGains, rightGainPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(ratios, ratioPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(attacks, attackPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(releases, releasePtr, JNI_ABORT);

    LOGD("Audio processing parameters updated for both ears");
}