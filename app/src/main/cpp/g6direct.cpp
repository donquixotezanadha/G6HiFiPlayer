// Build 1.57: replicate UAPP's native android::AudioTrack DIRECT path (evidence: UAPP_EVIDENCE.md).
// UAPP does dlopen(NULL) + dlsym() for android::AudioTrack symbols that libandroid_runtime already
// loaded into every app process, so no private library has to be dlopen'ed by path.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <unistd.h>
#include <new>
#include <atomic>

#define TAG "G6Direct"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {
const char* kSetCandidates[] = {
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_iRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tiiPK18audio_attributes_tbf",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_iRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tjiPK18audio_attributes_tbf",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_iRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tjiPK18audio_attributes_tbfi",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_jRKNS_2spINS_7IMemoryEEEbiNS0_13transfer_typeEPK20audio_offload_info_tiiPK18audio_attributes_tb",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_jRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tiiPK18audio_attributes_tb",
    "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_jRKNS_2spINS_7IMemoryEEEbiNS0_13transfer_typeEPK20audio_offload_info_tiiPK18audio_attributes_tbf",
};
const char* kOther[] = {
    "_ZN7android10AudioTrackC1Ev",
    "_ZN7android10AudioTrackC2Ev",
    "_ZN7android10AudioTrackD1Ev",
    "_ZN7android10AudioTrack5startEv",
    "_ZN7android10AudioTrack4stopEv",
    "_ZN7android10AudioTrack5flushEv",
    "_ZN7android10AudioTrack5writeEPKvmb",
    "_ZNK7android10AudioTrack7stoppedEv",
    "_ZNK7android7RefBase9incStrongEPKv",
    "_ZNK7android7RefBase9decStrongEPKv",
    "_ZN7android11AudioSystem16newAudioUniqueIdE21audio_unique_id_use_t",
};

void* openGlobal() {
    void* h = dlopen(nullptr, RTLD_NOW);
    return h;
}

// Build 1.58: only one native track at a time (a second DIRECT request while the first is still
// open is demoted to the mixer by the audio policy, which spoiled the 1.57 control test).
std::atomic<bool> gBusy{false};
std::atomic<bool> gStop{false};

// Sends text to NativeDirect.report() right away, so the person sees the lines while the tone is still playing.
void report(JNIEnv* env, const std::string& text) {
    jclass cls = env->FindClass("id/g6hifiplayer/NativeDirect");
    if (!cls) { env->ExceptionClear(); return; }
    jmethodID m = env->GetStaticMethodID(cls, "report", "(Ljava/lang/String;)V");
    if (!m) { env->ExceptionClear(); env->DeleteLocalRef(cls); return; }
    jstring s = env->NewStringUTF(text.c_str());
    env->CallStaticVoidMethod(cls, m, s);
    env->DeleteLocalRef(s);
    env->DeleteLocalRef(cls);
}

struct BusyGuard {
    ~BusyGuard() { gBusy.store(false); }
};
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_id_g6hifiplayer_NativeDirect_stop(JNIEnv*, jobject) {
    gStop.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_NativeDirect_probe(JNIEnv* env, jobject) {
    std::string out;
    char buf[400];
    void* h = openGlobal();
    snprintf(buf, sizeof buf, "NATIVE_PROBE dlopen(NULL)=%p\n", h);
    out += buf;
    if (h) {
        int i = 0;
        for (const char* s : kSetCandidates) {
            void* p = dlsym(h, s);
            snprintf(buf, sizeof buf, "NATIVE_PROBE set#%d=%p\n", i++, p);
            out += buf;
        }
        for (const char* s : kOther) {
            void* p = dlsym(h, s);
            snprintf(buf, sizeof buf, "NATIVE_PROBE %s=%p\n", s, p);
            out += buf;
        }
    }
    void* ml = dlopen("/system/lib64/libmedia.so", RTLD_NOW);
    snprintf(buf, sizeof buf, "NATIVE_PROBE dlopen(/system/lib64/libmedia.so)=%p err=%s\n", ml, ml ? "-" : (dlerror() ? dlerror() : "?"));
    out += buf;
    return env->NewStringUTF(out.c_str());
}

// flags 1 = AUDIO_OUTPUT_FLAG_DIRECT, format 6 = AUDIO_FORMAT_PCM_24_BIT_PACKED (values seen in UAPP's log)
extern "C" JNIEXPORT jstring JNICALL
Java_id_g6hifiplayer_NativeDirect_play(JNIEnv* env, jobject, jint rate, jint seconds, jint flags, jint format) {
    std::string out;
    char buf[300];
    bool expected = false;
    if (!gBusy.compare_exchange_strong(expected, true)) {
        return env->NewStringUTF("NATIVE_BUSY another native test is still running; wait for it or press Stop\n");
    }
    BusyGuard busyGuard;
    gStop.store(false);
    void* h = openGlobal();
    if (!h) return env->NewStringUTF("NATIVE_PLAY ERROR dlopen(NULL) failed");
    void* setFn = nullptr;
    int setIdx = -1;
    for (int i = 0; i < (int)(sizeof(kSetCandidates) / sizeof(kSetCandidates[0])); i++) {
        setFn = dlsym(h, kSetCandidates[i]);
        if (setFn) { setIdx = i; break; }
    }
    auto ctor = (void (*)(void*))dlsym(h, "_ZN7android10AudioTrackC1Ev");
    auto start = (int (*)(void*))dlsym(h, "_ZN7android10AudioTrack5startEv");
    auto stop = (void (*)(void*))dlsym(h, "_ZN7android10AudioTrack4stopEv");
    auto wr = (long (*)(void*, const void*, size_t, bool))dlsym(h, "_ZN7android10AudioTrack5writeEPKvmb");
    auto inc = (void (*)(const void*, const void*))dlsym(h, "_ZNK7android7RefBase9incStrongEPKv");
    auto dec = (void (*)(const void*, const void*))dlsym(h, "_ZNK7android7RefBase9decStrongEPKv");
    auto uid = (int (*)(int))dlsym(h, "_ZN7android11AudioSystem16newAudioUniqueIdE21audio_unique_id_use_t");
    snprintf(buf, sizeof buf, "NATIVE_PLAY symbols set#%d=%p ctor=%p start=%p stop=%p write=%p inc=%p dec=%p newUniqueId=%p\n",
             setIdx, setFn, (void*)ctor, (void*)start, (void*)stop, (void*)wr, (void*)inc, (void*)dec, (void*)uid);
    out += buf;
    if (!setFn || !ctor || !start || !stop || !wr || !inc || !dec) { out += "NATIVE_PLAY ABORT missing symbol\n"; return env->NewStringUTF(out.c_str()); }

    void* obj = ::operator new(8192);
    memset(obj, 0, 8192);
    ctor(obj);
    inc(obj, obj);
    int session = uid ? uid(1 /* AUDIO_UNIQUE_ID_USE_SESSION */) : 0;
    void* nullSp[2] = {nullptr, nullptr};  // sp<IMemory> sharedBuffer = null
    typedef int (*SetFn)(void*, int, unsigned, int, unsigned, size_t, int, void*, void*, int, void*, bool, int, int, const void*, int, int, const void*, bool, float, int);
    int frameCount = rate * 2 / 5;  // 0.4 s like UAPP (38400 at 96 kHz)
    int st = ((SetFn)setFn)(obj, 3 /*STREAM_MUSIC*/, (unsigned)rate, format, 3 /*OUT_STEREO*/, (size_t)frameCount, flags,
                            nullptr, nullptr, 0, nullSp, false, session, 3 /*TRANSFER_SYNC*/, nullptr, -1, -1, nullptr, false, 1.0f, 0);
    snprintf(buf, sizeof buf, "NATIVE_PLAY set(rate=%d format=%d flags=%d frameCount=%d session=%d)=%d\n", rate, format, flags, frameCount, session, st);
    out += buf;
    if (st == 0) {
        int sr = start(obj);
        snprintf(buf, sizeof buf, "NATIVE_PLAY start=%d\n", sr);
        out += buf;
        // Which output did the policy give us? AudioFlinger names its threads AudioOut_<hex handle>.
        // On this boot the mixer threads were 13/21/29; a handle that is not one of the mixer threads in
        // `dumpsys media.audio_flinger` means a separate (DIRECT) thread.
        auto getOutput = (int (*)(const void*))dlsym(h, "_ZNK7android10AudioTrack9getOutputEv");
        auto getRate = (unsigned (*)(const void*))dlsym(h, "_ZNK7android10AudioTrack13getSampleRateEv");
        auto getFlags = (int (*)(const void*))dlsym(h, "_ZNK7android10AudioTrack8getFlagsEv");
        int outHandle = getOutput ? getOutput(obj) : -999;
        unsigned outRate = getRate ? getRate(obj) : 0;
        int outFlags = getFlags ? getFlags(obj) : -999;
        snprintf(buf, sizeof buf, "NATIVE_PLAY_ROUTE getOutput=%d (thread AudioOut_%X) sampleRate=%u trackFlags=0x%x [symbols: getOutput=%p getSampleRate=%p getFlags=%p]\n",
                 outHandle, (unsigned)outHandle, outRate, (unsigned)outFlags, (void*)getOutput, (void*)getRate, (void*)getFlags);
        out += buf;
        out += "NATIVE_PLAY playing now; run the adb dumpsys command during the tone\n";
        report(env, out);
        out.clear();
        const int bytesPerFrame = (format == 6) ? 6 : 4;
        const int chunkFrames = 1024;
        std::vector<uint8_t> chunk(chunkFrames * bytesPerFrame);
        double phase = 0, inc2 = 2 * M_PI * 1000.0 / rate;
        long totalFrames = (long)rate * seconds, done = 0, bad = 0;
        while (done < totalFrames && !gStop.load()) {
            for (int i = 0; i < chunkFrames; i++) {
                int v = (int)(std::sin(phase) * 0.03 * 8388607.0);
                phase += inc2;
                for (int c = 0; c < 2; c++) {
                    uint8_t* p = &chunk[(i * 2 + c) * (bytesPerFrame / 2)];
                    if (format == 6) { p[0] = v & 0xff; p[1] = (v >> 8) & 0xff; p[2] = (v >> 16) & 0xff; }
                    else { int s = v >> 8; p[0] = s & 0xff; p[1] = (s >> 8) & 0xff; }
                }
            }
            long n = wr(obj, chunk.data(), chunk.size(), true);
            if (n <= 0) { if (++bad > 5) break; else continue; }
            done += n / bytesPerFrame;
        }
        snprintf(buf, sizeof buf, "NATIVE_PLAY wrote %ld of %ld frames%s\n", done, totalFrames, gStop.load() ? " (stopped by user)" : "");
        out += buf;
        stop(obj);
    }
    dec(obj, obj);
    out += "NATIVE_PLAY done\n";
    return env->NewStringUTF(out.c_str());
}
