#include <jni.h>
#include <android/log.h>
#include <atomic>

#include "sendspin_output_engine.h"

// Ported from Massdroid's sendspin_output_jni.cpp
// (github.com/sfortis/massdroid_native, MIT) with the JNI symbol names updated
// to this app's package/class (com.engabd.sendpin.audio.SendspinNativeOutput
// instead of net.asksakis.massdroidv2.data.sendspin.SendspinNativeOutput) — see
// the Kotlin facade of the same name for the corresponding `external fun`s.

#define LOG_TAG "SendspinNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using sendspin::SendspinOutputEngine;

// Diagnostic only, mirrors SendspinOutputEngine::write()'s own throttle: the
// two engines' write diagnostics showed write#1/write#2 succeeding and then
// nothing at all, not even a refused-branch log, for the 50-250 calls that
// followed. That means the refusal isn't inside SendspinOutputEngine::write()
// - it's one of this JNI wrapper's own early returns, which had no logging of
// their own. This settles which one.
static std::atomic<long long> jniWriteCallCount{0};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeCreate(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new SendspinOutputEngine());
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) delete reinterpret_cast<SendspinOutputEngine*>(ptr);
}

JNIEXPORT jboolean JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeStart(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jint sampleRate, jint channels,
    jboolean driftCorrection) {
    if (ptr == 0) return JNI_FALSE;
    auto* engine = reinterpret_cast<SendspinOutputEngine*>(ptr);
    return engine->start(sampleRate, channels, driftCorrection == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->stop();
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeFlush(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->flush();
}

JNIEXPORT jint JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeWrite(
    JNIEnv* env, jobject /*thiz*/, jlong ptr, jbyteArray pcm, jint offset, jint length,
    jlong presentationLocalUs) {
    const long long callNum = jniWriteCallCount.fetch_add(1, std::memory_order_relaxed) + 1;
    const bool logThis = callNum <= 12 || (callNum % 2048) == 0;

    if (ptr == 0 || pcm == nullptr || length <= 0) {
        if (logThis) {
            LOGI("nativeWrite#%lld refused: ptr=%lld pcm=%d length=%d",
                 callNum, (long long)ptr, pcm != nullptr, length);
        }
        return 0;
    }
    auto* engine = reinterpret_cast<SendspinOutputEngine*>(ptr);
    const int32_t bytesPerFrame = engine->bytesPerFrame();
    if (bytesPerFrame <= 0) {
        if (logThis) LOGI("nativeWrite#%lld refused: bytesPerFrame=%d", callNum, bytesPerFrame);
        return 0;
    }
    const int32_t frames = length / bytesPerFrame;
    if (frames <= 0) {
        if (logThis) {
            LOGI("nativeWrite#%lld refused: length=%d bytesPerFrame=%d (frames rounds to 0)",
                 callNum, length, bytesPerFrame);
        }
        return 0;
    }

    // Zero-copy access to the Java PCM buffer; engine->write only does a memcpy
    // into the ring (no JNI calls), so the critical section stays short.
    void* base = env->GetPrimitiveArrayCritical(pcm, nullptr);
    if (base == nullptr) {
        if (logThis) LOGI("nativeWrite#%lld refused: GetPrimitiveArrayCritical returned null", callNum);
        return 0;
    }
    const auto* samples = reinterpret_cast<const int16_t*>(
        static_cast<const uint8_t*>(base) + offset);
    int32_t written = engine->write(samples, frames, presentationLocalUs);
    env->ReleasePrimitiveArrayCritical(pcm, base, JNI_ABORT);
    if (logThis) {
        LOGI("nativeWrite#%lld: length=%d offset=%d frames=%d written=%d",
             callNum, length, offset, frames, written);
    }
    return written;
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeOutputLatencyUs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->outputLatencyUs();
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeBufferedFrames(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->bufferedFrames();
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeSetVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jfloat volume) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->setVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeSetFrozen(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jboolean frozen) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->setFrozen(frozen == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeSetCompressorLevel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jint level) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->setCompressorLevel(level);
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeSetDither(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jboolean enabled) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->setDither(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativePauseStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->pauseStream();
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeResumeStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->resumeStream();
}

JNIEXPORT jboolean JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeIsDisconnected(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return JNI_FALSE;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->isDisconnected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeDeviceId(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->deviceId();
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeDriftEmaUs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->driftEmaUs();
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeUnderrunFrames(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->underrunFrames();
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_SendspinNativeOutput_nativeResampleRateMicros(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 1000000;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->resampleRateMicros();
}

} // extern "C"
