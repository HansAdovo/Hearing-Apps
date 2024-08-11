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
#include <deque>
#include <array>
#include <condition_variable>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hearingamp", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "hearingamp", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "hearingamp", __VA_ARGS__)

constexpr int DEFAULT_SAMPLE_RATE = 48000;
constexpr int DEFAULT_CHANNEL_COUNT = 2;
constexpr int FRAMES_PER_CALLBACK = 64;
constexpr int BUFFER_SIZE_FRAMES = 1024;
constexpr int NUM_BANDS = 4;

struct WDRCParams {
    float threshold;
    float ratio;
    float attack_time;
    float release_time;
    float gain;
};

class AudioRingBuffer {
public:
    AudioRingBuffer(size_t capacity) : mCapacity(capacity) {}

    void write(const float* data, size_t size) {
        std::unique_lock<std::mutex> lock(mMutex);
        for (size_t i = 0; i < size; ++i) {
            mBuffer.push_back(data[i]);
            if (mBuffer.size() > mCapacity) {
                mBuffer.pop_front();
            }
        }
        lock.unlock();
        mCondVar.notify_one();
    }

    size_t read(float* data, size_t size) {
        std::unique_lock<std::mutex> lock(mMutex);
        mCondVar.wait(lock, [this] { return !mBuffer.empty(); });
        size_t read = std::min(size, mBuffer.size());
        std::copy(mBuffer.begin(), mBuffer.begin() + read, data);
        mBuffer.erase(mBuffer.begin(), mBuffer.begin() + read);
        return read;
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mBuffer.size();
    }

private:
    std::deque<float> mBuffer;
    size_t mCapacity;
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
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
        float *data = static_cast<float*>(audioData);

        if (!mIsProcessing) {
            return oboe::DataCallbackResult::Stop;
        }

        if (stream->getDirection() == oboe::Direction::Input) {
            std::vector<float> processedBuffer(numFrames * stream->getChannelCount(), 0.0f);

            for (int band = 0; band < NUM_BANDS; ++band) {
                for (int i = 0; i < numFrames * stream->getChannelCount(); ++i) {
                    float filteredSample = mFilters[band].process(data[i]);
                    float processedSample = applyWDRC(filteredSample, band);
                    processedBuffer[i] += processedSample / NUM_BANDS;
                }
            }

            for (int i = 0; i < numFrames * stream->getChannelCount(); ++i) {
                processedBuffer[i] = std::clamp(processedBuffer[i] * mAmplification, -1.0f, 1.0f);
            }

            mOutputBuffer.write(processedBuffer.data(), numFrames * stream->getChannelCount());
        } else if (stream->getDirection() == oboe::Direction::Output) {
            size_t framesRead = mOutputBuffer.read(data, numFrames * stream->getChannelCount());
            if (framesRead < numFrames * stream->getChannelCount()) {
                std::fill(data + framesRead, data + numFrames * stream->getChannelCount(), 0.0f);
            }
        }

        return oboe::DataCallbackResult::Continue;
    }

    void setAmplification(float amp) {
        mAmplification = amp;
    }

    void updateParams(const std::array<WDRCParams, NUM_BANDS>& newParams) {
        std::lock_guard<std::mutex> lock(mParamMutex);
        mWDRCParams = newParams;
        LOGD("WDRC parameters updated");
    }

    void stopProcessing() {
        std::lock_guard<std::mutex> lock(mProcessingMutex);
        mIsProcessing = false;
    }

private:
    AudioRingBuffer mInputBuffer;
    AudioRingBuffer mOutputBuffer;
    float mAmplification;
    std::array<BandpassFilter, NUM_BANDS> mFilters;
    std::array<WDRCParams, NUM_BANDS> mWDRCParams;
    std::mutex mParamMutex;
    std::array<float, NUM_BANDS> mEnvelopes;
    std::atomic<bool> mIsProcessing{true};
    std::mutex mProcessingMutex;

    void setupWDRC() {
        for (int i = 0; i < NUM_BANDS; ++i) {
            mWDRCParams[i] = {-40.0f + i * 5.0f, 3.0f + i * 0.5f, 0.01f, 0.1f, 10.0f};
            mEnvelopes[i] = 0.0f;
        }
    }

    float applyWDRC(float input, int band) {
        float& envelope = mEnvelopes[band];
        const WDRCParams& params = mWDRCParams[band];

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

        return input * gainLinear * compressionGain;
    }
};

static HearingAmpEngine *engine = nullptr;
static std::shared_ptr<oboe::AudioStream> inputStream;
static std::shared_ptr<oboe::AudioStream> outputStream;

extern "C" JNIEXPORT jint JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_startAudioProcessing(JNIEnv *env, jobject /* this */) {
    if (engine == nullptr) {
        engine = new HearingAmpEngine();
    } else {
        engine->stopProcessing();  // Ensure any previous processing is stopped
        delete engine;
        engine = new HearingAmpEngine();
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
        return -1;
    }

    LOGI("Output stream opened with sample rate: %d, channels: %d", outputStream->getSampleRate(), outputStream->getChannelCount());

    // Start both streams
    result = inputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream. Error: %s", oboe::convertToText(result));
        return -1;
    }

    result = outputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream. Error: %s", oboe::convertToText(result));
        inputStream->requestStop();
        return -1;
    }

    LOGD("Audio processing started successfully");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_stopAudioProcessing(JNIEnv *env, jobject /* this */) {
    if (engine) {
        engine->stopProcessing();
    }

    std::thread cleanup_thread([]() {
        if (inputStream) {
            inputStream->requestStop();
            inputStream->close();
            inputStream.reset();
        }
        if (outputStream) {
            outputStream->requestStop();
            outputStream->close();
            outputStream.reset();
        }
        delete engine;
        engine = nullptr;
        LOGD("Audio processing stopped and cleaned up");
    });
    cleanup_thread.detach();
}

extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_setAmplification(JNIEnv *env, jobject /* this */, jfloat amp) {
    if (engine) {
        engine->setAmplification(amp);
        LOGD("Amplification set to %.2f", amp);
    } else {
        LOGE("Engine is not initialized");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_updateAudioParams(JNIEnv *env, jobject /* this */,
                                                                      jfloatArray thresholds,
                                                                      jfloatArray ratios,
                                                                      jfloatArray attacks,
                                                                      jfloatArray releases,
                                                                      jfloatArray gains) {
    if (engine == nullptr) {
        LOGE("Engine is not initialized");
        return;
    }

    jfloat* thresholdPtr = env->GetFloatArrayElements(thresholds, nullptr);
    jfloat* ratioPtr = env->GetFloatArrayElements(ratios, nullptr);
    jfloat* attackPtr = env->GetFloatArrayElements(attacks, nullptr);
    jfloat* releasePtr = env->GetFloatArrayElements(releases, nullptr);
    jfloat* gainPtr = env->GetFloatArrayElements(gains, nullptr);

    std::array<WDRCParams, NUM_BANDS> newParams;
    for (int i = 0; i < NUM_BANDS; ++i) {
        newParams[i] = {
                thresholdPtr[i],
                ratioPtr[i],
                attackPtr[i],
                releasePtr[i],
                gainPtr[i]
        };
        LOGD("Band %d: Threshold=%.2f, Ratio=%.2f, Attack=%.2f, Release=%.2f, Gain=%.2f",
             i, newParams[i].threshold, newParams[i].ratio, newParams[i].attack_time,
             newParams[i].release_time, newParams[i].gain);
    }

    engine->updateParams(newParams);

    env->ReleaseFloatArrayElements(thresholds, thresholdPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(ratios, ratioPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(attacks, attackPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(releases, releasePtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(gains, gainPtr, JNI_ABORT);

    LOGD("Audio processing parameters updated");
}