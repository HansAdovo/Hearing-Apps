#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <mutex>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hearingamp", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "hearingamp", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "hearingamp", __VA_ARGS__)

constexpr int DEFAULT_SAMPLE_RATE = 48000;
constexpr int DEFAULT_CHANNEL_COUNT = 2;
constexpr int FRAMES_PER_CALLBACK = 384;

struct WDRCParams {
    float threshold;
    float ratio;
    float attack_time;
    float release_time;
    float gain;
};

std::vector<WDRCParams> wdrc_params = {
        {-40, 1.5f, 0.010f, 0.100f, 3},  // low
        {-35, 2.0f, 0.010f, 0.100f, 3},  // mid_low
        {-30, 2.5f, 0.010f, 0.100f, 3},  // mid_high
        {-25, 3.0f, 0.010f, 0.100f, 3}   // high
};

class CircularBuffer {
public:
    CircularBuffer(size_t capacity) : mCapacity(capacity), mSize(0), mHead(0), mTail(0) {
        mBuffer.resize(capacity);
    }

    void push(float value) {
        mBuffer[mTail] = value;
        mTail = (mTail + 1) % mCapacity;
        if (mSize < mCapacity) {
            mSize++;
        } else {
            mHead = (mHead + 1) % mCapacity;
        }
    }

    float pop() {
        if (mSize == 0) return 0.0f;
        float value = mBuffer[mHead];
        mHead = (mHead + 1) % mCapacity;
        mSize--;
        return value;
    }

    size_t size() const { return mSize; }
    bool empty() const { return mSize == 0; }
    void clear() { mSize = 0; mHead = 0; mTail = 0; }

private:
    std::vector<float> mBuffer;
    size_t mCapacity;
    size_t mSize;
    size_t mHead;
    size_t mTail;
};

class HearingAmpEngine : public oboe::AudioStreamCallback {
public:
    HearingAmpEngine() : mProcessedBuffer(FRAMES_PER_CALLBACK * DEFAULT_CHANNEL_COUNT * 4) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
        int32_t channelCount = stream->getChannelCount();

        if (stream->getDirection() == oboe::Direction::Input) {
            float *inputData = static_cast<float*>(audioData);
            int nonZeroSamples = processAudio(inputData, numFrames, channelCount);
            LOGD("Processed %d input frames, %d non-zero samples", numFrames, nonZeroSamples);

            std::lock_guard<std::mutex> lock(mBufferMutex);
            for (int i = 0; i < numFrames * channelCount; ++i) {
                mProcessedBuffer.push(inputData[i]);
            }
        } else if (stream->getDirection() == oboe::Direction::Output) {
            float *outputData = static_cast<float*>(audioData);
            std::lock_guard<std::mutex> lock(mBufferMutex);
            if (mProcessedBuffer.size() >= numFrames * channelCount) {
                for (int i = 0; i < numFrames * channelCount; ++i) {
                    outputData[i] = mProcessedBuffer.pop();
                }
            } else {
                LOGD("Buffer underrun: %zu samples available, %d needed", mProcessedBuffer.size(), numFrames * channelCount);
                std::fill(outputData, outputData + numFrames * channelCount, 0.0f);
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
    CircularBuffer mProcessedBuffer;
    std::mutex mBufferMutex;
    float noiseGateThreshold = 0.01f;
    float noiseGateKnee = 0.005f;
    float prevOutputLeft = 0.0f, prevOutputRight = 0.0f;

    int processAudio(float* data, int32_t numFrames, int32_t channelCount) {
        if (data == nullptr) {
            LOGE("Null audio data received");
            return 0;
        }

        int nonZeroSamples = 0;
        for (int i = 0; i < numFrames * channelCount; ++i) {
            float sample = data[i];

            if (std::abs(sample) > 1e-6) {
                nonZeroSamples++;
            }

            // Apply noise gate with soft knee
            if (std::abs(sample) < noiseGateThreshold - noiseGateKnee) {
                sample = 0.0f;
            } else if (std::abs(sample) < noiseGateThreshold + noiseGateKnee) {
                float factor = (std::abs(sample) - (noiseGateThreshold - noiseGateKnee)) / (2 * noiseGateKnee);
                sample *= factor;
            }

            if (sample != 0.0f) {
                sample = applyWDRC(sample);
            }

            // Apply low-pass filter
            if (i % channelCount == 0) {
                sample = lowPassFilter(sample, prevOutputLeft);
            } else {
                sample = lowPassFilter(sample, prevOutputRight);
            }

            // Apply limiter
            sample = limit(sample);

            data[i] = sample;
        }
        return nonZeroSamples;
    }

    float applyWDRC(float sample) {
        float output = sample;
        for (size_t i = 0; i < wdrc_params.size(); ++i) {
            const auto& params = wdrc_params[i];
            float inputLevel = std::abs(sample);
            float thresholdLinear = std::pow(10, params.threshold / 20);
            float gainLinear = std::pow(10, params.gain / 20);

            float alphaA = std::exp(-1.0f / (DEFAULT_SAMPLE_RATE * params.attack_time));
            float alphaR = std::exp(-1.0f / (DEFAULT_SAMPLE_RATE * params.release_time));
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

    float lowPassFilter(float input, float &prevOutput, float alpha = 0.1f) {
        float output = alpha * input + (1 - alpha) * prevOutput;
        prevOutput = output;
        return output;
    }

    float limit(float sample, float threshold = 0.9f) {
        return std::clamp(sample, -threshold, threshold);
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
            ->setSharingMode(oboe::SharingMode::Shared)
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

    LOGI("Input stream opened: SampleRate=%d, Channels=%d, Format=%d",
         inputStream->getSampleRate(), inputStream->getChannelCount(),
         static_cast<int>(inputStream->getFormat()));

    // Configure and open output stream
    builder.setDirection(oboe::Direction::Output)
            ->setSampleRate(inputStream->getSampleRate())  // Match input sample rate
            ->setChannelCount(inputStream->getChannelCount())  // Match input channel count
            ->setCallback(engine);

    result = builder.openStream(outputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream. Error: %s", oboe::convertToText(result));
        return -1;
    }

    LOGI("Output stream opened: SampleRate=%d, Channels=%d, Format=%d",
         outputStream->getSampleRate(), outputStream->getChannelCount(),
         static_cast<int>(outputStream->getFormat()));

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