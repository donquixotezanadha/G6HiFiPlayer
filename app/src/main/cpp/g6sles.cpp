// Build 1.38: OpenSL ES player that asks the framework for AUDIO_FORMAT_PCM_24_BIT_PACKED.
// Wilhelm (libOpenSLES) maps a 24-bit signed PCM source to AUDIO_FORMAT_PCM_24_BIT_PACKED and creates
// the native AudioTrack itself, so no hidden Java API and no private library is needed.
// The same track format (0x6, flags 0) is what UAPP shows in the G6 audio_flinger DIRECT thread dump.
#include <jni.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>
#include <pthread.h>
#include <time.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <string>

#define TAG "G6SLES"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

constexpr int kBuffers = 16;
constexpr int kMaxChunk = 49152;  // bytes per queued buffer (upper bound)

SLObjectItf gEngineObj = nullptr;
SLObjectItf gMixObj = nullptr;
SLObjectItf gPlayerObj = nullptr;
SLEngineItf gEngine = nullptr;
SLPlayItf gPlay = nullptr;
SLAndroidSimpleBufferQueueItf gQueue = nullptr;

uint8_t gBuf[kBuffers][kMaxChunk];
size_t gLen[kBuffers];
int gFree = 0;
int gWriteIdx = 0;
int gReadIdx = 0;
int gFrameBytes = 6;
int gChunkBytes = kMaxChunk;
uint64_t gDoneBytes = 0;
bool gAbort = false;
pthread_mutex_t gMu = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t gCv = PTHREAD_COND_INITIALIZER;

void onBufferDone(SLAndroidSimpleBufferQueueItf, void*) {
    pthread_mutex_lock(&gMu);
    gDoneBytes += gLen[gReadIdx];
    gReadIdx = (gReadIdx + 1) % kBuffers;
    gFree++;
    pthread_cond_signal(&gCv);
    pthread_mutex_unlock(&gMu);
}

std::string failure(const char* stage, SLresult r) {
    char tmp[96];
    snprintf(tmp, sizeof(tmp), "FAILED stage=%s result=%u", stage, (unsigned) r);
    return std::string(tmp);
}

void destroyAll() {
    if (gPlay != nullptr) (*gPlay)->SetPlayState(gPlay, SL_PLAYSTATE_STOPPED);
    if (gQueue != nullptr) (*gQueue)->Clear(gQueue);
    if (gPlayerObj != nullptr) (*gPlayerObj)->Destroy(gPlayerObj);
    if (gMixObj != nullptr) (*gMixObj)->Destroy(gMixObj);
    if (gEngineObj != nullptr) (*gEngineObj)->Destroy(gEngineObj);
    gPlayerObj = nullptr; gMixObj = nullptr; gEngineObj = nullptr;
    gEngine = nullptr; gPlay = nullptr; gQueue = nullptr;
}

// attempt 0 = SL_ANDROID_DATAFORMAT_PCM_EX signed 24-bit, attempt 1 = plain SL_DATAFORMAT_PCM 24-bit.
SLresult createPlayer(int attempt, int rate, int channels, int bits, int perfMode) {
    SLDataLocator_AndroidSimpleBufferQueue locBq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, (SLuint32) kBuffers};
    SLuint32 mask = channels == 1 ? SL_SPEAKER_FRONT_CENTER : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT);
    SLAndroidDataFormat_PCM_EX fmtEx;
    SLDataFormat_PCM fmtPlain;
    SLDataSource src;
    src.pLocator = &locBq;
    if (attempt == 0) {
        memset(&fmtEx, 0, sizeof(fmtEx));
        fmtEx.formatType = SL_ANDROID_DATAFORMAT_PCM_EX;
        fmtEx.numChannels = (SLuint32) channels;
        fmtEx.sampleRate = (SLuint32) rate * 1000;
        fmtEx.bitsPerSample = bits == 16 ? SL_PCMSAMPLEFORMAT_FIXED_16 : SL_PCMSAMPLEFORMAT_FIXED_24;
        fmtEx.containerSize = bits == 16 ? SL_PCMSAMPLEFORMAT_FIXED_16 : SL_PCMSAMPLEFORMAT_FIXED_24;
        fmtEx.channelMask = mask;
        fmtEx.endianness = SL_BYTEORDER_LITTLEENDIAN;
        fmtEx.representation = SL_ANDROID_PCM_REPRESENTATION_SIGNED_INT;
        src.pFormat = &fmtEx;
    } else {
        memset(&fmtPlain, 0, sizeof(fmtPlain));
        fmtPlain.formatType = SL_DATAFORMAT_PCM;
        fmtPlain.numChannels = (SLuint32) channels;
        fmtPlain.samplesPerSec = (SLuint32) rate * 1000;
        fmtPlain.bitsPerSample = bits == 16 ? SL_PCMSAMPLEFORMAT_FIXED_16 : SL_PCMSAMPLEFORMAT_FIXED_24;
        fmtPlain.containerSize = bits == 16 ? SL_PCMSAMPLEFORMAT_FIXED_16 : SL_PCMSAMPLEFORMAT_FIXED_24;
        fmtPlain.channelMask = mask;
        fmtPlain.endianness = SL_BYTEORDER_LITTLEENDIAN;
        src.pFormat = &fmtPlain;
    }
    SLDataLocator_OutputMix locOut = {SL_DATALOCATOR_OUTPUTMIX, gMixObj};
    SLDataSink snk = {&locOut, nullptr};
    const SLInterfaceID ids[2] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_ANDROIDCONFIGURATION};
    const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_FALSE};
    SLresult r = (*gEngine)->CreateAudioPlayer(gEngine, &gPlayerObj, &src, &snk, 2, ids, req);
    if (r != SL_RESULT_SUCCESS) { gPlayerObj = nullptr; return r; }

    SLAndroidConfigurationItf cfg = nullptr;
    // perfMode 0 = leave the player configuration untouched (framework defaults), 1..4 = explicit performance mode.
    if (perfMode != 0 &&
        (*gPlayerObj)->GetInterface(gPlayerObj, SL_IID_ANDROIDCONFIGURATION, &cfg) == SL_RESULT_SUCCESS && cfg) {
        SLint32 stream = SL_ANDROID_STREAM_MEDIA;
        (*cfg)->SetConfiguration(cfg, SL_ANDROID_KEY_STREAM_TYPE, &stream, sizeof(stream));
        SLuint32 perf = SL_ANDROID_PERFORMANCE_NONE;
        if (perfMode == 2) perf = SL_ANDROID_PERFORMANCE_POWER_SAVING;
        else if (perfMode == 3) perf = SL_ANDROID_PERFORMANCE_LATENCY;
        else if (perfMode == 4) perf = SL_ANDROID_PERFORMANCE_LATENCY_EFFECTS;
        (*cfg)->SetConfiguration(cfg, SL_ANDROID_KEY_PERFORMANCE_MODE, &perf, sizeof(perf));
    }
    r = (*gPlayerObj)->Realize(gPlayerObj, SL_BOOLEAN_FALSE);
    if (r != SL_RESULT_SUCCESS) {
        (*gPlayerObj)->Destroy(gPlayerObj);
        gPlayerObj = nullptr;
    }
    return r;
}

}  // namespace

extern "C" {

static jstring openImpl(JNIEnv* env, jint rate, jint channels, jint bits, jint perfMode) {
    destroyAll();
    pthread_mutex_lock(&gMu);
    gAbort = false; gFree = kBuffers; gWriteIdx = 0; gReadIdx = 0; gDoneBytes = 0;
    gFrameBytes = channels * (bits / 8);
    gChunkBytes = (kMaxChunk / gFrameBytes) * gFrameBytes;
    pthread_mutex_unlock(&gMu);

    std::string status;
    SLresult r = slCreateEngine(&gEngineObj, 0, nullptr, 0, nullptr, nullptr);
    if (r != SL_RESULT_SUCCESS) { gEngineObj = nullptr; status = failure("slCreateEngine", r); goto done; }
    r = (*gEngineObj)->Realize(gEngineObj, SL_BOOLEAN_FALSE);
    if (r != SL_RESULT_SUCCESS) { status = failure("engine.Realize", r); destroyAll(); goto done; }
    r = (*gEngineObj)->GetInterface(gEngineObj, SL_IID_ENGINE, &gEngine);
    if (r != SL_RESULT_SUCCESS) { status = failure("engine.GetInterface", r); destroyAll(); goto done; }
    r = (*gEngine)->CreateOutputMix(gEngine, &gMixObj, 0, nullptr, nullptr);
    if (r != SL_RESULT_SUCCESS) { gMixObj = nullptr; status = failure("CreateOutputMix", r); destroyAll(); goto done; }
    r = (*gMixObj)->Realize(gMixObj, SL_BOOLEAN_FALSE);
    if (r != SL_RESULT_SUCCESS) { status = failure("mix.Realize", r); destroyAll(); goto done; }

    {
        const char* names[2] = {"PCM_EX_S24", "PCM_PLAIN_24"};
        std::string log;
        bool ok = false;
        for (int attempt = 0; attempt < 2 && !ok; attempt++) {
            r = createPlayer(attempt, rate, channels, bits, perfMode);
            char tmp[64];
            snprintf(tmp, sizeof(tmp), "%s=0x%x ", names[attempt], (unsigned) r);
            log += tmp;
            ok = (r == SL_RESULT_SUCCESS);
        }
        if (!ok) { status = "FAILED stage=CreateAudioPlayer/Realize " + log; destroyAll(); goto done; }
        r = (*gPlayerObj)->GetInterface(gPlayerObj, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &gQueue);
        if (r != SL_RESULT_SUCCESS) { status = failure("GetInterface(bufferqueue)", r); destroyAll(); goto done; }
        r = (*gQueue)->RegisterCallback(gQueue, onBufferDone, nullptr);
        if (r != SL_RESULT_SUCCESS) { status = failure("RegisterCallback", r); destroyAll(); goto done; }
        r = (*gPlayerObj)->GetInterface(gPlayerObj, SL_IID_PLAY, &gPlay);
        if (r != SL_RESULT_SUCCESS) { status = failure("GetInterface(play)", r); destroyAll(); goto done; }
        char tmp[160];
        snprintf(tmp, sizeof(tmp), "OK rate=%d channels=%d bits=%d perfMode=%d buffers=%d chunkBytes=%d tries: ", (int) rate, (int) channels, (int) bits, (int) perfMode, kBuffers, gChunkBytes);
        status = std::string(tmp) + log;
    }
done:
    LOGI("nativeOpen: %s", status.c_str());
    return env->NewStringUTF(status.c_str());
}

JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_SlesPacked24_nativeOpen(JNIEnv* env, jobject, jint rate, jint channels) {
    return openImpl(env, rate, channels, 24, 1);
}

// Build 1.56: route matrix entry point (bits 16 or 24, perfMode 0..4, see createPlayer).
JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_SlesPacked24_nativeOpenEx(JNIEnv* env, jobject, jint rate, jint channels, jint bits, jint perfMode) {
    return openImpl(env, rate, channels, bits == 16 ? 16 : 24, perfMode);
}

// Blocking write: copies into free queue buffers and enqueues them. Returns bytes accepted, -1 aborted, -2 enqueue error.
JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_SlesPacked24_nativeWrite(JNIEnv* env, jobject, jbyteArray data, jint offset, jint length) {
    if (gQueue == nullptr) return -2;
    jint written = 0;
    while (written < length) {
        int n = length - written;
        if (n > gChunkBytes) n = gChunkBytes;
        pthread_mutex_lock(&gMu);
        while (gFree == 0 && !gAbort) {
            timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 100 * 1000000L;
            if (ts.tv_nsec >= 1000000000L) { ts.tv_sec += 1; ts.tv_nsec -= 1000000000L; }
            pthread_cond_timedwait(&gCv, &gMu, &ts);
        }
        if (gAbort) { pthread_mutex_unlock(&gMu); return -1; }
        int idx = gWriteIdx;
        gWriteIdx = (gWriteIdx + 1) % kBuffers;
        gFree--;
        gLen[idx] = (size_t) n;
        pthread_mutex_unlock(&gMu);
        env->GetByteArrayRegion(data, offset + written, n, reinterpret_cast<jbyte*>(gBuf[idx]));
        SLresult r = (*gQueue)->Enqueue(gQueue, gBuf[idx], (SLuint32) n);
        if (r != SL_RESULT_SUCCESS) {
            LOGI("Enqueue failed 0x%x", (unsigned) r);
            return -2;
        }
        written += n;
    }
    return written;
}

JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_SlesPacked24_nativePlay(JNIEnv*, jobject) {
    if (gPlay == nullptr) return -1;
    return (jint) (*gPlay)->SetPlayState(gPlay, SL_PLAYSTATE_PLAYING);
}

JNIEXPORT jlong JNICALL
Java_id_g6hifiplayer_SlesPacked24_nativeFramesPlayed(JNIEnv*, jobject) {
    pthread_mutex_lock(&gMu);
    jlong frames = (jlong) (gDoneBytes / (uint64_t) gFrameBytes);
    pthread_mutex_unlock(&gMu);
    return frames;
}

JNIEXPORT void JNICALL
Java_id_g6hifiplayer_SlesPacked24_nativeAbort(JNIEnv*, jobject) {
    pthread_mutex_lock(&gMu);
    gAbort = true;
    pthread_cond_broadcast(&gCv);
    pthread_mutex_unlock(&gMu);
}

JNIEXPORT void JNICALL
Java_id_g6hifiplayer_SlesPacked24_nativeClose(JNIEnv*, jobject) {
    destroyAll();
}

}  // extern "C"
