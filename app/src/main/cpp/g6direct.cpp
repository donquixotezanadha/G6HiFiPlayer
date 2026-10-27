// Build 1.60: native DIRECT output for the main player.
// Same method as UAPP (proved in Engine Lab 1.58/1.59): dlopen(NULL) + dlsym() of android::AudioTrack symbols that
// libandroid_runtime has already loaded into the app process, then set() with flags = DIRECT and format = 24-bit packed.
// This file only wraps the native track (open / start / pause / flush / stop / write / position / close);
// the Kotlin side (DirectAudioOutput) plugs it into Media3.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <new>
#include <mutex>

#define TAG "G6Direct"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

typedef int (*SetFnT)(void*, int, unsigned, int, unsigned, size_t, int, void*, void*, int, void*, bool, int, int,
                      const void*, int, int, const void*, bool, float, int);

// set() signatures for the Android 9 variants seen on the G6 (same list as Engine Lab 1.57-1.59).
const char* kSetCandidates[] = {
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_iRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tiiPK18audio_attributes_tbf",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_iRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tjiPK18audio_attributes_tbf",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_iRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tjiPK18audio_attributes_tbfi",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_jRKNS_2spINS_7IMemoryEEEbiNS0_13transfer_typeEPK20audio_offload_info_tiiPK18audio_attributes_tb",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_jRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tiiPK18audio_attributes_tb",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_jRKNS_2spINS_7IMemoryEEEbiNS0_13transfer_typeEPK20audio_offload_info_tiiPK18audio_attributes_tbf",
};

struct Fns {
    bool ready = false;
    void* h = nullptr;
    void* setFn = nullptr;
    int setIdx = -1;
    void (*ctor)(void*) = nullptr;
    int (*start)(void*) = nullptr;
    void (*stop)(void*) = nullptr;
    void (*pause)(void*) = nullptr;
    void (*flush)(void*) = nullptr;
    long (*wr)(void*, const void*, size_t, bool) = nullptr;
    void (*inc)(const void*, const void*) = nullptr;
    void (*dec)(const void*, const void*) = nullptr;
    int (*uid)(int) = nullptr;
    int (*getOutput)(const void*) = nullptr;
    unsigned (*getRate)(const void*) = nullptr;
    int (*getFlags)(const void*) = nullptr;
    int (*getPosition)(const void*, unsigned*) = nullptr;
};

Fns gF;
std::once_flag gOnce;
std::mutex gLogMutex;
std::string gLastLog;

struct Stream {
    void* obj = nullptr;
    int rate = 0;
    int format = 0;
    int frameCount = 0;
    int session = 0;
};

void setLog(const std::string& s) {
    {
        std::lock_guard<std::mutex> lock(gLogMutex);
        gLastLog = s;
    }
    LOGI("%s", s.c_str());
}

template <typename T>
T sym(const char* name) {
    return reinterpret_cast<T>(dlsym(gF.h, name));
}

void resolve() {
    gF.h = dlopen(nullptr, RTLD_NOW);
    if (!gF.h) { setLog("resolve: dlopen(NULL) failed"); return; }
    for (int i = 0; i < (int)(sizeof(kSetCandidates) / sizeof(kSetCandidates[0])); i++) {
        gF.setFn = dlsym(gF.h, kSetCandidates[i]);
        if (gF.setFn) { gF.setIdx = i; break; }
    }
    gF.ctor = sym<void (*)(void*)>("_ZN7android10AudioTrackC1Ev");
    gF.start = sym<int (*)(void*)>("_ZN7android10AudioTrack5startEv");
    gF.stop = sym<void (*)(void*)>("_ZN7android10AudioTrack4stopEv");
    gF.pause = sym<void (*)(void*)>("_ZN7android10AudioTrack5pauseEv");
    gF.flush = sym<void (*)(void*)>("_ZN7android10AudioTrack5flushEv");
    gF.wr = sym<long (*)(void*, const void*, size_t, bool)>("_ZN7android10AudioTrack5writeEPKvmb");
    gF.inc = sym<void (*)(const void*, const void*)>("_ZNK7android7RefBase9incStrongEPKv");
    gF.dec = sym<void (*)(const void*, const void*)>("_ZNK7android7RefBase9decStrongEPKv");
    gF.uid = sym<int (*)(int)>("_ZN7android11AudioSystem16newAudioUniqueIdE21audio_unique_id_use_t");
    gF.getOutput = sym<int (*)(const void*)>("_ZNK7android10AudioTrack9getOutputEv");
    gF.getRate = sym<unsigned (*)(const void*)>("_ZNK7android10AudioTrack13getSampleRateEv");
    gF.getFlags = sym<int (*)(const void*)>("_ZNK7android10AudioTrack8getFlagsEv");
    gF.getPosition = sym<int (*)(const void*, unsigned*)>("_ZNK7android10AudioTrack11getPositionEPj");
    gF.ready = gF.setFn && gF.ctor && gF.start && gF.stop && gF.flush && gF.wr && gF.inc && gF.dec;
    char buf[400];
    snprintf(buf, sizeof buf,
             "resolve ready=%d set#%d ctor=%d start=%d stop=%d pause=%d flush=%d write=%d inc=%d dec=%d uid=%d getOutput=%d getPosition=%d",
             gF.ready, gF.setIdx, gF.ctor != nullptr, gF.start != nullptr, gF.stop != nullptr, gF.pause != nullptr,
             gF.flush != nullptr, gF.wr != nullptr, gF.inc != nullptr, gF.dec != nullptr, gF.uid != nullptr,
             gF.getOutput != nullptr, gF.getPosition != nullptr);
    setLog(buf);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_id_g6hifiplayer_NativeDirectStream_available(JNIEnv*, jobject) {
    std::call_once(gOnce, resolve);
    return gF.ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_NativeDirectStream_lastLog(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gLogMutex);
    return env->NewStringUTF(gLastLog.c_str());
}

// format 6 = AUDIO_FORMAT_PCM_24_BIT_PACKED, flags 1 = AUDIO_OUTPUT_FLAG_DIRECT (values seen in UAPP's log).
// Returns a handle, or 0 when the track could not be created (reason in lastLog()).
extern "C" JNIEXPORT jlong JNICALL
Java_id_g6hifiplayer_NativeDirectStream_open(JNIEnv*, jobject, jint rate, jint format, jint flags, jint bufferMs) {
    std::call_once(gOnce, resolve);
    if (!gF.ready) { setLog("open: native symbols missing"); return 0; }
    void* obj = ::operator new(8192);
    memset(obj, 0, 8192);
    gF.ctor(obj);
    gF.inc(obj, obj);
    int session = gF.uid ? gF.uid(1 /* AUDIO_UNIQUE_ID_USE_SESSION */) : 0;
    void* nullSp[2] = {nullptr, nullptr};  // sp<IMemory> sharedBuffer = null
    int frameCount = (int)((long long)rate * bufferMs / 1000);
    int st = reinterpret_cast<SetFnT>(gF.setFn)(obj, 3 /*STREAM_MUSIC*/, (unsigned)rate, format, 3 /*OUT_STEREO*/,
                                                (size_t)frameCount, flags, nullptr, nullptr, 0, nullSp, false, session,
                                                3 /*TRANSFER_SYNC*/, nullptr, -1, -1, nullptr, false, 1.0f, 0);
    char buf[300];
    snprintf(buf, sizeof buf, "open set(rate=%d format=%d flags=%d frameCount=%d session=%d)=%d", rate, format, flags, frameCount, session, st);
    setLog(buf);
    if (st != 0) {
        gF.dec(obj, obj);
        return 0;
    }
    Stream* s = new Stream();
    s->obj = obj;
    s->rate = rate;
    s->format = format;
    s->frameCount = frameCount;
    s->session = session;
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_NativeDirectStream_start(JNIEnv*, jobject, jlong h) {
    Stream* s = reinterpret_cast<Stream*>(h);
    return s ? gF.start(s->obj) : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_NativeDirectStream_pause(JNIEnv*, jobject, jlong h) {
    Stream* s = reinterpret_cast<Stream*>(h);
    if (!s || !gF.pause) return -1;
    gF.pause(s->obj);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_id_g6hifiplayer_NativeDirectStream_flush(JNIEnv*, jobject, jlong h) {
    Stream* s = reinterpret_cast<Stream*>(h);
    if (s && gF.flush) gF.flush(s->obj);
}

extern "C" JNIEXPORT void JNICALL
Java_id_g6hifiplayer_NativeDirectStream_stop(JNIEnv*, jobject, jlong h) {
    Stream* s = reinterpret_cast<Stream*>(h);
    if (s) gF.stop(s->obj);
}

// Non-blocking write. Returns the bytes accepted (a multiple of the frame size), or a negative status
// (-11 = buffer full / would block, -32 = dead object).
extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_NativeDirectStream_write(JNIEnv* env, jobject, jlong h, jbyteArray data, jint offset, jint length) {
    Stream* s = reinterpret_cast<Stream*>(h);
    if (!s || length <= 0) return -1;
    std::vector<uint8_t> tmp((size_t)length);
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte*>(tmp.data()));
    long n = gF.wr(s->obj, tmp.data(), (size_t)length, false);
    return (jint)n;
}

// Frames played since the last flush (AudioTrack::getPosition), or -1 when unavailable.
extern "C" JNIEXPORT jlong JNICALL
Java_id_g6hifiplayer_NativeDirectStream_position(JNIEnv*, jobject, jlong h) {
    Stream* s = reinterpret_cast<Stream*>(h);
    if (!s || !gF.getPosition) return -1;
    unsigned p = 0;
    int st = gF.getPosition(s->obj, &p);
    return st == 0 ? (jlong)p : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_id_g6hifiplayer_NativeDirectStream_session(JNIEnv*, jobject, jlong h) {
    Stream* s = reinterpret_cast<Stream*>(h);
    return s ? s->session : 0;
}

// Which AudioFlinger output the policy gave us: AudioOut_<hex handle>, rate and track flags. A handle that is one of the
// mixer threads means the request was demoted; a separate thread with flags & 1 is the DIRECT output.
extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_NativeDirectStream_routeInfo(JNIEnv* env, jobject, jlong h) {
    Stream* s = reinterpret_cast<Stream*>(h);
    char buf[200];
    if (!s) { snprintf(buf, sizeof buf, "closed"); return env->NewStringUTF(buf); }
    int outHandle = gF.getOutput ? gF.getOutput(s->obj) : -999;
    unsigned outRate = gF.getRate ? gF.getRate(s->obj) : 0;
    int outFlags = gF.getFlags ? gF.getFlags(s->obj) : -999;
    snprintf(buf, sizeof buf, "AudioOut_%X (id %d) rate=%u flags=0x%x session=%d", (unsigned)outHandle, outHandle, outRate, (unsigned)outFlags, s->session);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT void JNICALL
Java_id_g6hifiplayer_NativeDirectStream_close(JNIEnv*, jobject, jlong h) {
    Stream* s = reinterpret_cast<Stream*>(h);
    if (!s) return;
    gF.stop(s->obj);
    gF.dec(s->obj, s->obj);
    delete s;
}
