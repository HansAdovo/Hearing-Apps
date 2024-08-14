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

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hearingamp", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "hearingamp", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "hearingamp", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "hearingamp", __VA_ARGS__)
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "hearingamp", __VA_ARGS__)

constexpr int DEFAULT_SAMPLE_RATE = 48000;
constexpr int DEFAULT_CHANNEL_COUNT = 2;
constexpr int FRAMES_PER_CALLBACK = 64;
constexpr int BUFFER_SIZE_FRAMES = 512;
constexpr int NUM_BANDS = 4;

struct WDRCParams {
    float threshold;
    float ratio;
    float attack_time;
    float release_time;
    float gain;
};

std::atomic<bool> gErrorFlag{false};

void setErrorFlag() {
    gErrorFlag.store(true, std::memory_order_relaxed);
}

bool checkAndResetErrorFlag() {
    return gErrorFlag.exchange(false, std::memory_order_relaxed);
}

class AudioRingBuffer {
public:
    AudioRingBuffer(size_t capacity) : mCapacity(capacity), mBuffer(capacity) {}

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

class BandpassFilter {
public:
    BandpassFilter(float sampleRate, float lowFreq, float highFreq) {
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

    float process(float input) {
        float output = (b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
        x2 = x1;
        x1 = input;
        y2 = y1;
        y1 = output;
        return output;
    }

private:
    float b0, b1, b2, a0, a1, a2;
    float x1, x2, y1, y2;
};

class HearingAmpEngine : public oboe::AudioStreamCallback {
public:
    HearingAmpEngine()
            : mInputBuffer(BUFFER_SIZE_FRAMES * DEFAULT_CHANNEL_COUNT),
              mOutputBuffer(BUFFER_SIZE_FRAMES * DEFAULT_CHANNEL_COUNT),
              mAmplification(2.0f),
              mFilters{
                      BandpassFilter(DEFAULT_SAMPLE_RATE, 20, 300),
                      BandpassFilter(DEFAULT_SAMPLE_RATE, 300, 1000),
                      BandpassFilter(DEFAULT_SAMPLE_RATE, 1000, 3000),
                      BandpassFilter(DEFAULT_SAMPLE_RATE, 3000, 6000)
              } {
        setupWDRC();
        LOGD("HearingAmpEngine constructed with BUFFER_SIZE_FRAMES=%d, FRAMES_PER_CALLBACK=%d", BUFFER_SIZE_FRAMES, FRAMES_PER_CALLBACK);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
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

            // Log every 100 callbacks (adjust as needed)
            if (++callbackCounter % 100 == 0) {
                LOGD("Audio processing: MaxInput=%.4f, MaxOutput=%.4f, Frames=%d, BufferSize=%zu",
                     maxInputSample, maxOutputSample, numFrames, mOutputBuffer.size());
                maxInputSample = 0.0f;
                maxOutputSample = 0.0f;
            }
        } else if (stream->getDirection() == oboe::Direction::Output) {
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

    void updateParams(const std::array<WDRCParams, NUM_BANDS>& leftParams, const std::array<WDRCParams, NUM_BANDS>& rightParams) {
        std::lock_guard<std::mutex> lock(mParamMutex);
        mWDRCParams[0] = leftParams;
        mWDRCParams[1] = rightParams;
        LOGD("WDRC parameters updated for both ears");
    }

    void stopProcessing() {
        std::lock_guard<std::mutex> lock(mProcessingMutex);
        mIsProcessing = false;
    }

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

    void setupWDRC() {
        for (int ear = 0; ear < 2; ++ear) {
            for (int i = 0; i < NUM_BANDS; ++i) {
                mWDRCParams[ear][i] = {-40.0f + i * 5.0f, 3.0f + i * 0.5f, 0.01f, 0.1f, 10.0f};
                mEnvelopes[ear][i] = 0.0f;
            }
        }
    }

    float applyWDRC(float input, int band, int channel) {
        if (band < 0 || band >= NUM_BANDS || channel < 0 || channel >= 2) {
            LOGE("Invalid band or channel in applyWDRC: band=%d, channel=%d", band, channel);
            setErrorFlag();
            return input;
        }

        float& envelope = mEnvelopes[channel][band];
        const WDRCParams& params = mWDRCParams[channel][band];

        float alphaAttack = std::exp(-1.0f / (DEFAULT_SAMPLE_RATE * params.attack_time));
        float alphaRelease = std::exp(-1.0f / (DEFAULT_SAMPLE_RATE * params.release_time));

        float inputLevel = std::abs(input);
        float alpha = inputLevel > envelope ? alphaAttack : alphaRelease;
        envelope = alpha * envelope + (1.0f - alpha) * inputLevel;

        float gainLinear = std::pow(10.0f, params.gain / 20.0f);
        float thresholdLinear = std::pow(10.0f, params.threshold / 20.0f);
        float compressionGain = 1.0f;

        if (envelope > thresholdLinear) {
            compressionGain = std::pow(envelope / thresholdLinear, 1.0f / params.ratio - 1.0f);
        }

        float output = input * gainLinear * compressionGain;

        static float lastCompressionGain[NUM_BANDS][2] = {{1.0f}};
        if (std::abs(compressionGain - lastCompressionGain[band][channel]) > 0.1f) {
            LOGD("WDRC: Band=%d, Channel=%d, Threshold=%.2f, Ratio=%.2f, Gain=%.2f, CompGain=%.2f",
                 band, channel, params.threshold, params.ratio, params.gain, compressionGain);
            lastCompressionGain[band][channel] = compressionGain;
        }

        return output;
    }
};

static HearingAmpEngine *engine = nullptr;
static std::shared_ptr<oboe::AudioStream> inputStream;
static std::shared_ptr<oboe::AudioStream> outputStream;

extern "C" JNIEXPORT jint JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeStartAudioProcessing(JNIEnv *env, jobject /* this */) {
    LOGD("Starting audio processing");

    if (engine != nullptr) {
        LOGW("Engine already exists, stopping previous instance");
        engine->stopProcessing();
        delete engine;
    }

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

extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeStopAudioProcessing(JNIEnv *env, jobject /* this */) {
    if (engine) {
        engine->stopProcessing();

        if (inputStream) {
            LOGD("Stopping input stream with sample rate: %d, channels: %d", inputStream->getSampleRate(), inputStream->getChannelCount());
            inputStream->requestStop();
            inputStream->close();
            inputStream.reset();
        }
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

extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeStartProcessing(JNIEnv *env, jobject /* this */) {
    if (engine != nullptr) {
        LOGD("Starting audio processing");
        engine->startProcessing();
    } else {
        LOGE("Engine is not initialized. Call startAudioProcessing first.");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_nativeStopProcessing(JNIEnv *env, jobject /* this */) {
    if (engine) {
        engine->stopProcessing();
        LOGD("Audio processing stopped");
    } else {
        LOGW("Engine is already stopped or not initialized");
    }
}

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

    engine->updateParams(leftParams, rightParams);

    env->ReleaseFloatArrayElements(leftThresholds, leftThresholdPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(rightThresholds, rightThresholdPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(leftGains, leftGainPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(rightGains, rightGainPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(ratios, ratioPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(attacks, attackPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(releases, releasePtr, JNI_ABORT);

    LOGD("Audio processing parameters updated for both ears");
}