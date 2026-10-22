#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

#include <cmath>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>

namespace {
constexpr char kTag[] = "G6OboeProbe";
std::mutex gMutex;
std::shared_ptr<oboe::AudioStream> gStream;

class ToneCallback final : public oboe::AudioStreamDataCallback {
public:
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *stream, void *audioData, int32_t numFrames) override {
        auto *output = static_cast<float *>(audioData);
        const int32_t channels = stream->getChannelCount();
        const double sampleRate = static_cast<double>(stream->getSampleRate());
        for (int32_t frame = 0; frame < numFrames; ++frame) {
            const float sample = static_cast<float>(0.08 * std::sin(phase_));
            phase_ += 2.0 * M_PI * 440.0 / sampleRate;
            if (phase_ >= 2.0 * M_PI) phase_ -= 2.0 * M_PI;
            for (int32_t channel = 0; channel < channels; ++channel) {
                output[frame * channels + channel] = sample;
            }
        }
        return oboe::DataCallbackResult::Continue;
    }
private:
    double phase_ = 0.0;
};

ToneCallback gCallback;

const char *sharingName(oboe::SharingMode mode) {
    return mode == oboe::SharingMode::Exclusive ? "EXCLUSIVE" : "SHARED";
}
const char *formatName(oboe::AudioFormat format) {
    switch (format) {
        case oboe::AudioFormat::Float: return "FLOAT";
        case oboe::AudioFormat::I16: return "PCM16";
        case oboe::AudioFormat::I24: return "PCM24";
        case oboe::AudioFormat::I32: return "PCM32";
        default: return "Unspecified/Other";
    }
}
const char *performanceName(oboe::PerformanceMode mode) {
    switch (mode) {
        case oboe::PerformanceMode::LowLatency: return "LOW_LATENCY";
        case oboe::PerformanceMode::PowerSaving: return "POWER_SAVING";
        default: return "NONE";
    }
}

std::string streamInfo(const char *prefix) {
    std::ostringstream out;
    out << prefix;
    if (!gStream) {
        out << "\nstream=closed";
        return out.str();
    }
    out << "\nactualSampleRate=" << gStream->getSampleRate()
        << "\nactualFormat=" << formatName(gStream->getFormat())
        << "\nactualChannels=" << gStream->getChannelCount()
        << "\nactualSharingMode=" << sharingName(gStream->getSharingMode())
        << "\nactualPerformanceMode=" << performanceName(gStream->getPerformanceMode())
        << "\nbufferSizeFrames=" << gStream->getBufferSizeInFrames()
        << "\nframesPerBurst=" << gStream->getFramesPerBurst()
        << "\nstreamState=" << oboe::convertToText(gStream->getState())
        << "\nrouteNote=Stream properties do not prove AudioFlinger DIRECT or bit-perfect output";
    return out.str();
}

oboe::Result openOnce(int32_t sampleRate, oboe::SharingMode mode) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(mode);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(2);
    builder.setSampleRate(sampleRate);
    builder.setUsage(oboe::Usage::Media);
    builder.setDataCallback(&gCallback);
    return builder.openStream(gStream);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_oboeprobe_MainActivity_nativeOpen(
        JNIEnv *env, jobject, jint sampleRate, jboolean requestExclusive) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gStream) {
        gStream->requestStop();
        gStream->close();
        gStream.reset();
    }

    const auto requestedMode = requestExclusive
            ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared;
    oboe::Result result = openOnce(sampleRate, requestedMode);
    std::ostringstream prefix;
    prefix << "requestedSampleRate=" << sampleRate
           << "\nrequestedSharingMode=" << sharingName(requestedMode)
           << "\nexclusiveOpenResult=" << oboe::convertToText(result);

    if (result != oboe::Result::OK && requestExclusive) {
        if (gStream) {
            gStream->close();
            gStream.reset();
        }
        const oboe::Result fallback = openOnce(sampleRate, oboe::SharingMode::Shared);
        prefix << "\nsharedFallbackResult=" << oboe::convertToText(fallback);
        if (fallback != oboe::Result::OK) {
            if (gStream) {
                gStream->close();
                gStream.reset();
            }
            const std::string message = prefix.str() + "\nopen=FAILED";
            __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
            return env->NewStringUTF(message.c_str());
        }
        prefix << "\nopenedUsing=SHARED_FALLBACK";
    } else if (result != oboe::Result::OK) {
        if (gStream) {
            gStream->close();
            gStream.reset();
        }
        const std::string message = prefix.str() + "\nopen=FAILED";
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
        return env->NewStringUTF(message.c_str());
    } else {
        prefix << "\nopenedUsing=" << sharingName(requestedMode);
    }

    const std::string message = streamInfo(prefix.str().c_str());
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_oboeprobe_MainActivity_nativeStart(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gStream) return env->NewStringUTF("start=FAILED\nreason=no stream open");
    const oboe::Result result = gStream->requestStart();
    const std::string message = std::string("startResult=") + oboe::convertToText(result)
            + "\n" + streamInfo("tone=440Hz low-level test tone; not music playback");
    __android_log_print(result == oboe::Result::OK ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                        kTag, "%s", message.c_str());
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_oboeprobe_MainActivity_nativeStop(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gStream) return env->NewStringUTF("stop=already closed");
    const oboe::Result result = gStream->requestStop();
    const std::string message = std::string("stopResult=") + oboe::convertToText(result)
            + "\n" + streamInfo("tone stopped");
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_oboeprobe_MainActivity_nativeClose(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gStream) return env->NewStringUTF("stream=already closed");
    const oboe::Result result = gStream->close();
    gStream.reset();
    const std::string message = std::string("closeResult=") + oboe::convertToText(result)
            + "\nstream=closed";
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
    return env->NewStringUTF(message.c_str());
}
