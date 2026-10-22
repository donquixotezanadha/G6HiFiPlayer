#include <aaudio/AAudio.h>
#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>

namespace {
struct ToneState {
    std::atomic<double> phase{0.0};
    int32_t sampleRate = 48000;
};

std::mutex gMutex;
AAudioStream* gStream = nullptr;
ToneState* gTone = nullptr;
std::string gLastReport = "No AAudio stream has been opened.";

aaudio_data_callback_result_t dataCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames) {
    auto* tone = static_cast<ToneState*>(userData);
    auto* output = static_cast<int16_t*>(audioData);
    if (tone == nullptr || output == nullptr) return AAUDIO_CALLBACK_RESULT_STOP;

    double phase = tone->phase.load(std::memory_order_relaxed);
    const double step = 2.0 * M_PI * 440.0 / static_cast<double>(tone->sampleRate);
    for (int32_t frame = 0; frame < numFrames; ++frame) {
        const auto sample = static_cast<int16_t>(std::sin(phase) * 655.0); // about 2% full scale
        output[frame * 2] = sample;
        output[frame * 2 + 1] = sample;
        phase += step;
        if (phase >= 2.0 * M_PI) phase -= 2.0 * M_PI;
    }
    tone->phase.store(phase, std::memory_order_relaxed);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void stopLocked() {
    if (gStream != nullptr) {
        AAudioStream_requestStop(gStream);
        AAudioStream_close(gStream);
        gStream = nullptr;
    }
    delete gTone;
    gTone = nullptr;
}

std::string streamReport(AAudioStream* stream, const std::string& prefix) {
    if (stream == nullptr) return prefix;
    std::ostringstream out;
    out << prefix << "\n";
    out << "stream state: " << AAudioStream_getState(stream) << "\n";
    out << "actual sample rate: " << AAudioStream_getSampleRate(stream) << " Hz\n";
    out << "actual format: " << AAudioStream_getFormat(stream)
        << " (AAUDIO_FORMAT_PCM_I16=" << AAUDIO_FORMAT_PCM_I16 << ")\n";
    out << "actual channel count: " << AAudioStream_getChannelCount(stream) << "\n";
    out << "actual sharing mode: " << AAudioStream_getSharingMode(stream)
        << " (EXCLUSIVE=" << AAUDIO_SHARING_MODE_EXCLUSIVE
        << ", SHARED=" << AAUDIO_SHARING_MODE_SHARED << ")\n";
    out << "actual device ID: " << AAudioStream_getDeviceId(stream) << "\n";
    out << "frames per burst: " << AAudioStream_getFramesPerBurst(stream) << "\n";
    out << "buffer capacity frames: " << AAudioStream_getBufferCapacityInFrames(stream) << "\n";
    out << "buffer size frames: " << AAudioStream_getBufferSizeInFrames(stream) << "\n";
    out << "xrun count: " << AAudioStream_getXRunCount(stream) << "\n";
    out << "frames written: " << AAudioStream_getFramesWritten(stream) << "\n";
    out << "performance mode: " << AAudioStream_getPerformanceMode(stream) << "\n";
    out << "direction: " << AAudioStream_getDirection(stream) << " (OUTPUT=0)\n";
    return out.str();
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_directprobe_MainActivity_nativeStart(
        JNIEnv* env, jobject, jint sampleRate, jint deviceId, jboolean exclusive) {
    std::lock_guard<std::mutex> lock(gMutex);
    stopLocked();
    gLastReport.clear();

    ToneState* tone = new ToneState();
    tone->sampleRate = sampleRate;
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        delete tone;
        gLastReport = "AAudio_createStreamBuilder failed: " + std::string(AAudio_convertResultToText(result));
        return env->NewStringUTF(gLastReport.c_str());
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(builder, exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, tone);
    if (deviceId > 0) AAudioStreamBuilder_setDeviceId(builder, deviceId);

    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) {
        delete tone;
        std::ostringstream out;
        out << "OPEN FAILED: " << AAudio_convertResultToText(result)
            << " (" << result << ")\nrequested sample rate: " << sampleRate
            << "\nrequested format: PCM_I16\nrequested sharing mode: "
            << (exclusive ? "EXCLUSIVE" : "SHARED")
            << "\nrequested device ID: " << deviceId;
        gLastReport = out.str();
        return env->NewStringUTF(gLastReport.c_str());
    }

    gStream = stream;
    gTone = tone;
    result = AAudioStream_requestStart(gStream);
    std::ostringstream prefix;
    prefix << "OPENED: requested sample rate=" << sampleRate
           << " Hz, format=PCM_I16, sharing="
           << (exclusive ? "EXCLUSIVE" : "SHARED")
           << ", requested device ID=" << deviceId
           << "\nrequestStart result: " << AAudio_convertResultToText(result)
           << " (" << result << ")";
    gLastReport = streamReport(gStream, prefix.str());
    return env->NewStringUTF(gLastReport.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_directprobe_MainActivity_nativeGetReport(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    gLastReport = streamReport(gStream, gLastReport);
    return env->NewStringUTF(gLastReport.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_directprobe_MainActivity_nativeStop(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gStream != nullptr) {
        gLastReport = streamReport(gStream, "Stream stopped by user.");
        stopLocked();
    } else {
        gLastReport = "No active AAudio stream.";
    }
    return env->NewStringUTF(gLastReport.c_str());
}
