#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <mutex>
#include <complex>
#include <array>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hearingamp", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "hearingamp", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "hearingamp", __VA_ARGS__)

constexpr int DEFAULT_SAMPLE_RATE = 44100;
constexpr int DEFAULT_CHANNEL_COUNT = 2;
constexpr int FRAMES_PER_CALLBACK = 256;  // Increased for better FFT performance
constexpr int NUM_BANDS = 4;

struct WDRCParams {
    float threshold;
    float ratio;
    float attack_time;
    float release_time;
    float gain;
};

std::array<WDRCParams, NUM_BANDS> wdrc_params = {{
                                                         {-40, 3.0f, 0.01f, 0.1f, 10},  // low
                                                         {-35, 3.5f, 0.01f, 0.1f, 10},  // mid_low
                                                         {-30, 4.0f, 0.01f, 0.1f, 10},  // mid_high
                                                         {-25, 4.5f, 0.01f, 0.1f, 10}   // high
                                                 }};

class FFT {
public:
    FFT(int size) : mSize(size) {
        mWindow.resize(size);
        for (int i = 0; i < size; ++i) {
            mWindow[i] = 0.54f - 0.46f * std::cos(2 * M_PI * i / (size - 1));
        }
    }

    std::vector<std::complex<float>> compute(const std::vector<float>& input) {
        std::vector<std::complex<float>> output(mSize);
        for (int i = 0; i < mSize; ++i) {
            output[i] = input[i] * mWindow[i];
        }

        fft(output, false);
        return output;
    }

    std::vector<float> inverse(const std::vector<std::complex<float>>& input) {
        std::vector<std::complex<float>> temp = input;
        fft(temp, true);

        std::vector<float> output(mSize);
        for (int i = 0; i < mSize; ++i) {
            output[i] = temp[i].real() / mSize;
        }
        return output;
    }

private:
    int mSize;
    std::vector<float> mWindow;

    void fft(std::vector<std::complex<float>>& x, bool inverse) {
        int n = x.size();
        if (n <= 1) return;

        std::vector<std::complex<float>> even(n / 2), odd(n / 2);
        for (int i = 0; i < n / 2; i++) {
            even[i] = x[2 * i];
            odd[i] = x[2 * i + 1];
        }

        fft(even, inverse);
        fft(odd, inverse);

        for (int k = 0; k < n / 2; k++) {
            float angle = (inverse ? 2 : -2) * M_PI * k / n;
            std::complex<float> t = std::polar(1.0f, angle) * odd[k];
            x[k] = even[k] + t;
            x[k + n / 2] = even[k] - t;
        }
    }
};

class HearingAmpEngine : public oboe::AudioStreamCallback {
public:
    HearingAmpEngine() {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
        float *data = static_cast<float*>(audioData);

        if (stream->getDirection() == oboe::Direction::Input) {
            LOGD("Input callback: Processing %d frames", numFrames);
            processAudio(data, numFrames, stream->getChannelCount());

            std::lock_guard<std::mutex> lock(mBufferMutex);
            mProcessedBuffer.insert(mProcessedBuffer.end(), data, data + numFrames * stream->getChannelCount());
            LOGD("Processed buffer size: %zu", mProcessedBuffer.size());
        } else if (stream->getDirection() == oboe::Direction::Output) {
            std::lock_guard<std::mutex> lock(mBufferMutex);
            int framesToCopy = std::min(static_cast<int>(mProcessedBuffer.size() / stream->getChannelCount()), numFrames);

            LOGD("Output callback: Copying %d frames out of %zu available", framesToCopy, mProcessedBuffer.size() / stream->getChannelCount());

            if (framesToCopy > 0) {
                std::copy(mProcessedBuffer.begin(), mProcessedBuffer.begin() + framesToCopy * stream->getChannelCount(), data);
                mProcessedBuffer.erase(mProcessedBuffer.begin(), mProcessedBuffer.begin() + framesToCopy * stream->getChannelCount());
            }

            if (framesToCopy < numFrames) {
                std::fill(data + framesToCopy * stream->getChannelCount(), data + numFrames * stream->getChannelCount(), 0.0f);
            }
        }

        return oboe::DataCallbackResult::Continue;
    }

    void updateParams(const std::array<WDRCParams, NUM_BANDS>& newParams) {
        std::lock_guard<std::mutex> lock(mParamMutex);
        wdrc_params = newParams;
    }

private:
    std::vector<float> mProcessedBuffer;
    std::mutex mBufferMutex;
    std::mutex mParamMutex;
    std::array<WDRCParams, NUM_BANDS> wdrc_params;

    void processAudio(float* data, int32_t numFrames, int32_t channelCount) {
        // Simple amplification
        for (int i = 0; i < numFrames * channelCount; ++i) {
            data[i] *= 2.0f;  // Double the amplitude
            data[i] = std::clamp(data[i], -1.0f, 1.0f);  // Prevent clipping
        }
    }
};

static HearingAmpEngine *engine = nullptr;
static std::shared_ptr<oboe::AudioStream> inputStream;
static std::shared_ptr<oboe::AudioStream> outputStream;

extern "C" JNIEXPORT jint JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_startAudioProcessing(JNIEnv *env, jobject /* this */) {
    if (engine == nullptr) {
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

    // Get the actual sample rate and channel count from the input stream
    int actualSampleRate = inputStream->getSampleRate();
    int actualChannelCount = inputStream->getChannelCount();

    LOGI("Input stream opened with sample rate: %d, channels: %d", actualSampleRate, actualChannelCount);

    // Set up output stream with matching configuration
    builder.setDirection(oboe::Direction::Output)
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
        return -1;
    }

    LOGD("Audio processing started successfully");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_auditapp_hearingamp_AudioProcessingService_stopAudioProcessing(JNIEnv *env, jobject /* this */) {
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
    LOGD("Audio processing stopped");
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
    }

    engine->updateParams(newParams);

    env->ReleaseFloatArrayElements(thresholds, thresholdPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(ratios, ratioPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(attacks, attackPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(releases, releasePtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(gains, gainPtr, JNI_ABORT);

    LOGD("Audio processing parameters updated");
}