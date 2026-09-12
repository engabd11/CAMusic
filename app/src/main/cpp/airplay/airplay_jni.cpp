// SPDX-License-Identifier: Apache-2.0
// airplay_jni.cpp — JNI bridge between Kotlin (AirPlayOutput.kt) and the
// vendored airplay2-sender-cpp library.
//
// This file is deliberately thin. The library already ships RaopLoop — a
// complete, tested RaopIo implementation using POSIX sockets with
// generation counters, queued TCP sends, graceful TEARDOWN close, and
// TCP_NODELAY. We use it directly rather than re-implementing it.
//
// What this bridge owns:
//   - The RaopLoop (the I/O host) and the RaopSender (the state machine).
//   - The PCM ring buffer (lock-free SPSC, fed from ExoPlayer's audio
//     thread, drained by the pacer on the loop thread).
//   - A dedicated native thread that runs RaopLoop::run() — the poll loop.
//   - JNI callbacks marshalled back to Kotlin for session launch, PIN
//     requests, and credential persistence.
//
// The sender handles both AirPlay 1 (legacy RTSP) and AirPlay 2 (encrypted
// HAP pairing + ALAC realtime). The auth mode is chosen by the Kotlin side
// based on mDNS TXT record flags (raop_auth.h's Auth enum).

#include "raop_io.h"
#include "raop_sender.h"
#include "raop_loop.h"
#include "raop_log.h"
#include "ring_buffer.h"

#include <jni.h>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <memory>
#include <string>

#define TAG "AirPlayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,   TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,  TAG, __VA_ARGS__)

using namespace fxchain;

// ── The bridge: holds the loop, the sender, the ring buffer, and the thread ─

struct AirPlayBridge {
    std::unique_ptr<RaopLoop> loop;
    std::unique_ptr<RaopSender> sender;
    std::unique_ptr<RingBuffer<int16_t>> ring;
    std::thread loopThread;
    std::atomic<bool> running{false};
    std::atomic<bool> launched{false};

    // JNI callbacks — set from Kotlin via init.
    JavaVM* jvm = nullptr;
    jobject globalRef = nullptr;  // GlobalRef to AirPlayOutput.kt instance
    jmethodID onLaunchedMethod = nullptr;
    jmethodID onClosedMethod = nullptr;
    jmethodID onPinRequiredMethod = nullptr;
    jmethodID onCredentialsMethod = nullptr;

    ~AirPlayBridge() {
        stop();
        if (globalRef && jvm) {
            JNIEnv* env = nullptr;
            bool attached = false;
            if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                jvm->AttachCurrentThread(&env, nullptr);
                attached = true;
            }
            if (env) env->DeleteGlobalRef(globalRef);
            if (attached) jvm->DetachCurrentThread();
        }
    }

    void stop() {
        if (running.exchange(false)) {
            if (loop) loop->requestStop();
            if (loopThread.joinable()) loopThread.join();
            if (sender) sender->stop();
            launched = false;
        }
    }

    void notifyCallback(jmethodID method, const char* arg1 = nullptr,
                        const char* arg2 = nullptr) {
        if (!jvm || !globalRef || !method) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            jvm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
        if (!env) return;

        if (arg1 && arg2) {
            jstring jarg1 = env->NewStringUTF(arg1);
            jstring jarg2 = env->NewStringUTF(arg2);
            env->CallVoidMethod(globalRef, method, jarg1, jarg2);
            env->DeleteLocalRef(jarg1);
            env->DeleteLocalRef(jarg2);
        } else if (arg1) {
            jstring jarg1 = env->NewStringUTF(arg1);
            env->CallVoidMethod(globalRef, method, jarg1);
            env->DeleteLocalRef(jarg1);
        } else {
            env->CallVoidMethod(globalRef, method);
        }

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }

        if (attached) jvm->DetachCurrentThread();
    }
};

// ── JNI entry points ─────────────────────────────────────────────────────

static AirPlayBridge* getBridge(JNIEnv* env, jobject thiz) {
    static jfieldID fid = nullptr;
    if (!fid) {
        jclass cls = env->GetObjectClass(thiz);
        fid = env->GetFieldID(cls, "nativePtr", "J");
        env->DeleteLocalRef(cls);
    }
    jlong ptr = env->GetLongField(thiz, fid);
    return reinterpret_cast<AirPlayBridge*>(ptr);
}

static void logSink(RaopLogLevel level, const std::string& msg) {
    if (level == RaopLogLevel::Warn)
        LOGW("%s", msg.c_str());
    else
        LOGI("%s", msg.c_str());
}

extern "C" {

// ── nativeInit ───────────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeInit(JNIEnv* env, jobject thiz) {
    auto* bridge = new AirPlayBridge();

    env->GetJavaVM(&bridge->jvm);
    bridge->globalRef = env->NewGlobalRef(thiz);

    jclass cls = env->GetObjectClass(thiz);
    bridge->onLaunchedMethod = env->GetMethodID(cls, "onLaunched", "(ZLjava/lang/String;)V");
    bridge->onClosedMethod = env->GetMethodID(cls, "onClosed", "()V");
    bridge->onPinRequiredMethod = env->GetMethodID(cls, "onPinRequired", "(Ljava/lang/String;)V");
    bridge->onCredentialsMethod = env->GetMethodID(cls, "onCredentials", "(Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);

    if (!bridge->onLaunchedMethod || !bridge->onClosedMethod ||
        !bridge->onPinRequiredMethod || !bridge->onCredentialsMethod) {
        LOGE("Failed to find JNI callback methods on AirPlayOutput");
        delete bridge;
        return 0;
    }

    // 44100 Hz stereo = 88200 samples/sec. ~4 seconds of buffer = 352800
    // samples. Next power of 2 = 524288 (512K samples = 1 MB).
    bridge->ring = std::make_unique<RingBuffer<int16_t>>(524288);

    bridge->loop = std::make_unique<RaopLoop>();

    RaopEvents events;
    auto* b = bridge;
    events.launched = [b](bool ok, const std::string& error) {
        b->launched.store(ok);
        // Pass "true"/"false" as arg1 and error as arg2 — onLaunched(Z, String).
        // The JNI side receives these as strings because we can't easily pass
        // a boolean through the string-based notifyCallback. But actually
        // onLaunched takes (boolean, String) — so we need a direct call.
        if (b->jvm && b->globalRef && b->onLaunchedMethod) {
            JNIEnv* env = nullptr;
            bool attached = false;
            if (b->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                b->jvm->AttachCurrentThread(&env, nullptr);
                attached = true;
            }
            if (env) {
                jstring jerr = env->NewStringUTF(error.c_str());
                env->CallVoidMethod(b->globalRef, b->onLaunchedMethod,
                                    ok ? JNI_TRUE : JNI_FALSE, jerr);
                env->DeleteLocalRef(jerr);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            if (attached) b->jvm->DetachCurrentThread();
        }
    };
    events.closed = [b]() {
        b->launched.store(false);
        b->notifyCallback(b->onClosedMethod);
    };
    events.pinRequired = [b](const std::string& deviceName) {
        b->notifyCallback(b->onPinRequiredMethod, deviceName.c_str());
    };
    events.credentialsObtained = [b](const std::string& deviceId, const std::string& credsJson) {
        b->notifyCallback(b->onCredentialsMethod, deviceId.c_str(), credsJson.c_str());
    };

    bridge->sender = std::make_unique<RaopSender>(*bridge->loop, events, logSink);
    bridge->sender->attachRing(bridge->ring.get());
    bridge->sender->setInputFormat(44100);

    return reinterpret_cast<jlong>(bridge);
}

// ── nativeDestroy ────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeDestroy(JNIEnv* env, jobject thiz) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge) return;
    bridge->stop();
    delete bridge;
    static jfieldID fid = nullptr;
    if (!fid) {
        jclass cls = env->GetObjectClass(thiz);
        fid = env->GetFieldID(cls, "nativePtr", "J");
        env->DeleteLocalRef(cls);
    }
    env->SetLongField(thiz, fid, 0);
}

// ── nativeStart ──────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeStart(
        JNIEnv* env, jobject thiz,
        jstring jHost, jint port, jstring jName,
        jint authInt, jboolean airplay2,
        jstring jDeviceId, jstring jCredsJson, jstring jPassword) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge || bridge->running) return;

    const char* host = env->GetStringUTFChars(jHost, nullptr);
    const char* name = env->GetStringUTFChars(jName, nullptr);
    const char* deviceId = jDeviceId ? env->GetStringUTFChars(jDeviceId, nullptr) : "";
    const char* credsJson = jCredsJson ? env->GetStringUTFChars(jCredsJson, nullptr) : "";
    const char* password = jPassword ? env->GetStringUTFChars(jPassword, nullptr) : "";

    auto auth = static_cast<RaopDeviceInfo::Auth>(authInt);
    bridge->sender->setAuth(auth, airplay2, deviceId, credsJson, password);
    bridge->sender->setIdentity({"CAMusic", "02:00:00:00:00:00", "iPhone14,3"});
    bridge->sender->start(host, static_cast<uint16_t>(port), name);

    bridge->running.store(true);
    bridge->loop->clearStopRequest();
    bridge->loopThread = std::thread([bridge]() {
        // RaopLoop::run pumps the sender until requestStop().
        bridge->loop->run(*bridge->sender);
    });

    env->ReleaseStringUTFChars(jHost, host);
    env->ReleaseStringUTFChars(jName, name);
    if (jDeviceId) env->ReleaseStringUTFChars(jDeviceId, deviceId);
    if (jCredsJson) env->ReleaseStringUTFChars(jCredsJson, credsJson);
    if (jPassword) env->ReleaseStringUTFChars(jPassword, password);
}

// ── nativeStop ───────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeStop(JNIEnv* env, jobject thiz) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge) return;
    bridge->stop();
}

// ── nativeWritePcm ───────────────────────────────────────────────────────
// Feed 16-bit interleaved stereo PCM into the ring buffer. Called from the
// AudioProcessor on ExoPlayer's audio thread. Non-blocking: if the ring is
// full, samples are dropped (the sender pushes silence to fill the gap).
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeWritePcm(
        JNIEnv* env, jobject thiz, jbyteArray jBuffer, jint offset, jint length) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge || !bridge->launched.load()) return;

    jsize total = env->GetArrayLength(jBuffer);
    if (length <= 0 || offset < 0 || offset + length > total) return;

    jbyte* bytes = env->GetByteArrayElements(jBuffer, nullptr);
    if (!bytes) return;

    int sampleCount = length / 2;  // 16-bit = 2 bytes per sample
    if (sampleCount > 0) {
        const int16_t* samples = reinterpret_cast<const int16_t*>(bytes + offset);
        bridge->ring->tryPush(std::span<const int16_t>(samples, static_cast<size_t>(sampleCount)));
    }

    env->ReleaseByteArrayElements(jBuffer, bytes, JNI_ABORT);
}

// ── nativeSetVolume ──────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeSetVolume(
        JNIEnv* env, jobject thiz, jfloat volume) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge) return;
    bridge->sender->setVolume(static_cast<double>(volume * 100.0f));
}

// ── nativeSubmitPin ──────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeSubmitPin(
        JNIEnv* env, jobject thiz, jstring jPin) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge) return;
    const char* pin = env->GetStringUTFChars(jPin, nullptr);
    bridge->sender->submitPin(pin);
    env->ReleaseStringUTFChars(jPin, pin);
}

// ── nativeSetNowPlaying ──────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeSetNowPlaying(
        JNIEnv* env, jobject thiz,
        jstring jTitle, jstring jArtist, jstring jAlbum,
        jbyteArray jCover, jstring jCoverMime) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge) return;

    const char* title = jTitle ? env->GetStringUTFChars(jTitle, nullptr) : "";
    const char* artist = jArtist ? env->GetStringUTFChars(jArtist, nullptr) : "";
    const char* album = jAlbum ? env->GetStringUTFChars(jAlbum, nullptr) : "";

    std::string cover, coverMime;
    if (jCover) {
        jsize coverLen = env->GetArrayLength(jCover);
        jbyte* coverBytes = env->GetByteArrayElements(jCover, nullptr);
        if (coverBytes) {
            cover.assign(reinterpret_cast<const char*>(coverBytes), static_cast<size_t>(coverLen));
            env->ReleaseByteArrayElements(jCover, coverBytes, JNI_ABORT);
        }
    }
    if (jCoverMime) {
        const char* mime = env->GetStringUTFChars(jCoverMime, nullptr);
        coverMime = mime;
        env->ReleaseStringUTFChars(jCoverMime, mime);
    }

    bridge->sender->setNowPlaying(title, artist, album, cover, coverMime);

    if (jTitle) env->ReleaseStringUTFChars(jTitle, title);
    if (jArtist) env->ReleaseStringUTFChars(jArtist, artist);
    if (jAlbum) env->ReleaseStringUTFChars(jAlbum, album);
}

// ── nativeIsWaitingForPin ────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeIsWaitingForPin(
        JNIEnv* env, jobject thiz) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge) return JNI_FALSE;
    return bridge->sender->waitingForPin() ? JNI_TRUE : JNI_FALSE;
}

// ── nativeIsActive ───────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeIsActive(
        JNIEnv* env, jobject thiz) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge) return JNI_FALSE;
    return bridge->sender->active() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"