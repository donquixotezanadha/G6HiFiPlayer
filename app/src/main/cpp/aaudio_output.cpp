#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>

namespace {
constexpr const char* kTag = "G6AAudio";
std::mutex gMutex;
AAudioStream* gStream = nullptr;
std::string gLastError = "not opened";

void closeLocked() {
    if (gStream != nullptr) {
        AAudioStream_requestStop(gStream);
        AAudioStream_close(gStream);
        gStream = nullptr;
    }
}

std::string reportLocked() {
    if (gStream == nullptr) return std::string("stream=closed; last=") + gLastError;
    std::ostringstream out;
    const int32_t xruns = AAudioStream_getXRunCount(gStream);
    out << "actualRate=" << AAudioStream_getSampleRate(gStream)
        << " format=" << AAudioStream_getFormat(gStream)
        << "(PCM_I16=" << AAUDIO_FORMAT_PCM_I16 << ")"
        << " channels=" << AAudioStream_getChannelCount(gStream)
        << " sharing=" << AAudioStream_getSharingMode(gStream)
        << "(EXCLUSIVE=" << AAUDIO_SHARING_MODE_EXCLUSIVE
        << ",SHARED=" << AAUDIO_SHARING_MODE_SHARED << ")"
        << " deviceId=" << AAudioStream_getDeviceId(gStream)
        << " framesPerBurst=" << AAudioStream_getFramesPerBurst(gStream)
        << " bufferFrames=" << AAudioStream_getBufferSizeInFrames(gStream)
        << " xruns=" << xruns
        << " framesWritten=" << AAudioStream_getFramesWritten(gStream);
    return out.str();
}
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeOpen(JNIEnv*, jobject, jint sampleRate, jint channels) {
    std::lock_guard<std::mutex> lock(gMutex);
    closeLocked();
    gLastError.clear();

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        gLastError = std::string("create builder: ") + AAudio_convertResultToText(result);
        return result;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);

    result = AAudioStreamBuilder_openStream(builder, &gStream);
    if (result != AAUDIO_OK || gStream == nullptr) {
        if (gStream != nullptr) {
            AAudioStream_close(gStream);
            gStream = nullptr;
        }
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        result = AAudioStreamBuilder_openStream(builder, &gStream);
    }
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || gStream == nullptr) {
        gLastError = std::string("open stream: ") + AAudio_convertResultToText(result) +
                     " (" + std::to_string(result) + ")";
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", gLastError.c_str());
        return result;
    }

    result = AAudioStream_requestStart(gStream);
    if (result != AAUDIO_OK) {
        gLastError = std::string("requestStart: ") + AAudio_convertResultToText(result);
        closeLocked();
        return result;
    }

    const std::string report = reportLocked();
    __android_log_print(ANDROID_LOG_INFO, kTag, "AAUDIO_PLAYER_OUTPUT opened requestedRate=%d channels=%d %s",
                        sampleRate, channels, report.c_str());
    return AAUDIO_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeWrite(JNIEnv* env, jobject, jobject buffer,
                                               jint offsetBytes, jint sizeBytes) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gStream == nullptr || buffer == nullptr) return AAUDIO_ERROR_INVALID_STATE;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr || offsetBytes < 0 || sizeBytes < 0) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    const int32_t channels = AAudioStream_getChannelCount(gStream);
    const int32_t frameBytes = channels * static_cast<int32_t>(sizeof(int16_t));
    if (frameBytes <= 0) return AAUDIO_ERROR_INVALID_FORMAT;
    const int32_t frames = sizeBytes / frameBytes;
    if (frames <= 0) return 0;
    const aaudio_result_t result = AAudioStream_write(
        gStream, base + offsetBytes, frames, 100000000LL);
    if (result < 0) {
        gLastError = std::string("write: ") + AAudio_convertResultToText(result);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s; %s", gLastError.c_str(), reportLocked().c_str());
    }
    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativePlay(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gStream == nullptr) return AAUDIO_ERROR_INVALID_STATE;
    const aaudio_result_t result = AAudioStream_requestStart(gStream);
    if (result != AAUDIO_OK) gLastError = std::string("play: ") + AAudio_convertResultToText(result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativePause(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gStream == nullptr) return AAUDIO_ERROR_INVALID_STATE;
    const aaudio_result_t result = AAudioStream_requestPause(gStream);
    if (result != AAUDIO_OK) gLastError = std::string("pause: ") + AAudio_convertResultToText(result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeFlush(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gStream == nullptr) return AAUDIO_ERROR_INVALID_STATE;
    const aaudio_result_t state = AAudioStream_getState(gStream);
    const bool restart = state == AAUDIO_STREAM_STATE_STARTED;
    if (restart) AAudioStream_requestPause(gStream);
    aaudio_result_t result = AAudioStream_requestFlush(gStream);
    if (restart) AAudioStream_requestStart(gStream);
    if (result != AAUDIO_OK) gLastError = std::string("flush: ") + AAudio_convertResultToText(result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gStream == nullptr) return AAUDIO_OK;
    const aaudio_result_t result = AAudioStream_requestStop(gStream);
    if (result != AAUDIO_OK) gLastError = std::string("stop: ") + AAudio_convertResultToText(result);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeRelease(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    closeLocked();
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeSampleRate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gStream ? AAudioStream_getSampleRate(gStream) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeChannelCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gStream ? AAudioStream_getChannelCount(gStream) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeDeviceId(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gStream ? AAudioStream_getDeviceId(gStream) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeSharingMode(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gStream ? AAudioStream_getSharingMode(gStream) : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeFramesWritten(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gStream ? AAudioStream_getFramesWritten(gStream) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeBufferSizeFrames(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gStream ? AAudioStream_getBufferSizeInFrames(gStream) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeXRunCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gStream ? AAudioStream_getXRunCount(gStream) : -1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_AAudioNative_nativeReport(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    const std::string report = reportLocked();
    return env->NewStringUTF(report.c_str());
}
