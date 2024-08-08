#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <oboe/Oboe.h>
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hearingamp", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "hearingamp", __VA_ARGS__)

constexpr int SAMPLE_RATE = 48000;
constexpr int CHANNEL_COUNT = 2;
constexpr int FRAMES_PER_CALLBACK = 192;  // A smaller value for lower latency

struct WDRCParams {
    float threshold;
    float ratio;
    float attack_time;
    float release_time;
    float gain;
};

std::vector<WDRCParams> wdrc_params = {
        {-50, 2.0f, 0.005f, 0.05f, 5},  // low
        {-45, 2.5f, 0.005f, 0.05f, 5},  // mid_low
        {-40, 3.0f, 0.005f, 0.05f, 5},  // mid_high
        {-35, 3.5f, 0.005f, 0.05f, 5}   // high
};

class HearingAmpEngine : public oboe::AudioStreamCallback {
public:
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
        if (stream->getDirection() == oboe::Direction::Input) {
            LOGD("Processing %d input frames", numFrames);
            float *inputData = static_cast<float*>(audioData);
            processAudio(inputData, numFrames);

            if (mProcessedBuffer.size() < numFrames * CHANNEL_COUNT) {
                mProcessedBuffer.resize(numFrames * CHANNEL_COUNT);
            }
            std::copy(inputData, inputData + numFrames * CHANNEL_COUNT, mProcessedBuffer.begin());
        } else if (stream->getDirection() == oboe::Direction::Output) {
            LOGD("Filling %d output frames", numFrames);
            float *outputData = static_cast<float*>(audioData);
            if (mProcessedBuffer.size() >= numFrames * CHANNEL_COUNT) {
                std::copy(mProcessedBuffer.begin(), mProcessedBuffer.begin() + numFrames * CHANNEL_COUNT, outputData);
            } else {
                LOGD("Not enough processed data, filling with zeros");
                std::fill(outputData, outputData + numFrames * CHANNEL_COUNT, 0.0f);
            }
        }

        return oboe::DataCallbackResult::Continue;
    }

    void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override {
        LOGE("Audio stream error before close: %s", oboe::convertToText(error));
    }

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override {
        LOGE("Audio stream error after close: %s", oboe::convertToText(error));
    }

private:
    std::vector<float> envelopes = std::vector<float>(wdrc_params.size(), 0.0f);
    std::vector<float> mProcessedBuffer;
    float noiseGateThreshold = 0.005f;

    void processAudio(float* data, int32_t numFrames) {
        int nonZeroSamples = 0;
        for (int i = 0; i < numFrames * CHANNEL_COUNT; ++i) {
            float sample = data[i];

            // Apply noise gate
            if (std::abs(sample) < noiseGateThreshold) {
                sample = 0.0f;
            } else {
                sample = applyWDRC(sample);
                nonZeroSamples++;
            }

            data[i] = std::clamp(sample, -1.0f, 1.0f);
        }
        LOGD("Processed %d frames, %d non-zero samples", numFrames, nonZeroSamples);
    }

    float applyWDRC(float sample) {
        float output = sample;
        for (size_t i = 0; i < wdrc_params.size(); ++i) {
            const auto& params = wdrc_params[i];
            float inputLevel = std::abs(sample);
            float thresholdLinear = std::pow(10, params.threshold / 20);
            float gainLinear = std::pow(10, params.gain / 20);

            float alphaA = std::exp(-1.0f / (SAMPLE_RATE * params.attack_time));
            float alphaR = std::exp(-1.0f / (SAMPLE_RATE * params.release_time));
            float alpha = inputLevel > envelopes[i] ? alphaA : alphaR;
            envelopes[i] = alpha * envelopes[i] + (1.0f - alpha) * inputLevel;

            float compressionGain = 1.0f;
            if (envelopes[i] > thresholdLinear) {
                compressionGain = std::pow(envelopes[i] / thresholdLinear, 1.0f / params.ratio - 1.0f);
            }

            output *= gainLinear * compressionGain;
        }
        return output;
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

    // Configure and open input stream
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)  // Changed to Shared
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(CHANNEL_COUNT)
            ->setSampleRate(SAMPLE_RATE)
            ->setFramesPerCallback(FRAMES_PER_CALLBACK)
            ->setCallback(engine);

    oboe::Result result = builder.openStream(inputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream. Error: %s", oboe::convertToText(result));
        return -1;
    }

    // Configure and open output stream
    builder.setDirection(oboe::Direction::Output)
            ->setCallback(engine);
    result = builder.openStream(outputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream. Error: %s", oboe::convertToText(result));
        return -1;
    }

    // Start the streams
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