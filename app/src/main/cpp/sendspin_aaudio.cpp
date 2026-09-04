#include <jni.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <algorithm>
#include <cstring>
#include <atomic>
#include <mutex>
#include <queue>
#include <vector>

// AAudio bit-perfect PCM output bridge for CAMusic's local player.
// This is a deliberate minimal first cut: it opens a low-latency exclusive stream
// in the requested PCM format and feeds it from a lock-free-ish ring written by
// the ExoPlayer playback thread and consumed by the AAudio callback.

#define LOG_TAG "SendspinAaudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Format codes agreed with AaudioBitperfectOutput.kt, which maps media3's own
// C.ENCODING_* constants onto them. Ours rather than media3's numeric values on
// purpose: those belong to a library this file cannot see, so copying them here
// means a renumbering upstream arrives as a stream opened in the wrong format
// rather than as a build error.
constexpr int FORMAT_I16 = 1;
constexpr int FORMAT_I24_PACKED = 2;
constexpr int FORMAT_I32 = 3;
constexpr int FORMAT_FLOAT = 4;

aaudio_format_t aaudioFormatOf(int formatCode) {
    switch (formatCode) {
        case FORMAT_I16: return AAUDIO_FORMAT_PCM_I16;
        case FORMAT_I24_PACKED: return AAUDIO_FORMAT_PCM_I24_PACKED;
        case FORMAT_I32: return AAUDIO_FORMAT_PCM_I32;
        case FORMAT_FLOAT: return AAUDIO_FORMAT_PCM_FLOAT;
        default: return AAUDIO_FORMAT_INVALID;
    }
}

int bytesPerSampleOf(aaudio_format_t format) {
    switch (format) {
        case AAUDIO_FORMAT_PCM_I16: return 2;
        case AAUDIO_FORMAT_PCM_I24_PACKED: return 3;
        case AAUDIO_FORMAT_PCM_I32: return 4;
        case AAUDIO_FORMAT_PCM_FLOAT: return 4;
        default: return 2;
    }
}

struct AaudioOutput {
    AAudioStream* stream = nullptr;
    std::atomic<int64_t> framesWritten{0};
    std::atomic<int64_t> framesRead{0};
    std::atomic<float> volume{1.0f};
    // Whether the sink is paused, so a stream opened to replace this one on another
    // device does not start playing a player the listener stopped.
    std::atomic<bool> paused{false};
    std::mutex mutex;
    std::queue<std::vector<uint8_t>> chunks;
    int sampleRate = 48000;
    int channels = 2;
    int bytesPerFrame = 4;
    aaudio_format_t format = AAUDIO_FORMAT_PCM_I16;

    static aaudio_data_callback_result_t callback(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames) {
        auto* self = static_cast<AaudioOutput*>(userData);
        return self->onCallback(stream, audioData, numFrames);
    }

    aaudio_data_callback_result_t onCallback(AAudioStream* /*stream*/, void* audioData, int32_t numFrames) {
        const size_t bytesNeeded = static_cast<size_t>(numFrames) * static_cast<size_t>(bytesPerFrame);
        size_t bytesFilled = 0;
        uint8_t* dst = static_cast<uint8_t*>(audioData);

        std::unique_lock<std::mutex> lock(mutex);
        while (bytesFilled < bytesNeeded && !chunks.empty()) {
            auto& front = chunks.front();
            const size_t available = front.size();
            const size_t take = std::min(available, bytesNeeded - bytesFilled);
            std::memcpy(dst + bytesFilled, front.data(), take);
            bytesFilled += take;
            if (take == available) {
                chunks.pop();
            } else {
                // Partial consume: move remaining bytes to front of same chunk.
                front.erase(front.begin(), front.begin() + take);
            }
        }
        lock.unlock();

        if (bytesFilled < bytesNeeded) {
            std::memset(dst + bytesFilled, 0, bytesNeeded - bytesFilled);
        }

        // Apply volume in-place (float path only; integer paths need dither, skip here).
        if (format == AAUDIO_FORMAT_PCM_FLOAT) {
            float* samples = static_cast<float*>(audioData);
            const float v = volume.load();
            const int total = numFrames * channels;
            for (int i = 0; i < total; ++i) samples[i] *= v;
        }

        framesRead += numFrames;
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
};

// Open a stream for `out` on `deviceId` (0 for whatever Android routes to) and
// start it.
//
// The callback's userData is what it writes into, and AAudio only accepts it on
// the builder - there is no setter for an already-open stream - so the output has
// to exist before the stream does. It also outlives every stream opened for it,
// which is what lets a device change swap the stream underneath without touching
// the PCM already queued on it or the frame counters the position is read from.
bool openStreamFor(AaudioOutput* out, int deviceId) {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        LOGE("AAudio_createStreamBuilder failed: %d", result);
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSampleRate(builder, out->sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, out->channels);
    AAudioStreamBuilder_setFormat(builder, out->format);
    AAudioStreamBuilder_setDataCallback(builder, AaudioOutput::callback, out);
    if (deviceId > 0) {
        AAudioStreamBuilder_setDeviceId(builder, deviceId);
    }

    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) {
        LOGE("AAudioStreamBuilder_openStream failed: %d", result);
        return false;
    }

    // AAudio is free to hand back a stream in a different format, rate or channel
    // count to the one asked for. Accepting one would mean feeding it bytes laid
    // out for something else - noise, and noise presented as bit-perfect. Refusing
    // is the whole point: the Kotlin side then falls back to DefaultAudioSink, or,
    // on a device change, to another output.
    const aaudio_format_t gotFormat = AAudioStream_getFormat(stream);
    const int32_t gotRate = AAudioStream_getSampleRate(stream);
    const int32_t gotChannels = AAudioStream_getChannelCount(stream);
    if (gotFormat != out->format || gotRate != out->sampleRate || gotChannels != out->channels) {
        LOGE("AAudio opened %dHz/%dch/fmt=%d, asked for %dHz/%dch/fmt=%d - declining",
             gotRate, gotChannels, gotFormat, out->sampleRate, out->channels, out->format);
        AAudioStream_close(stream);
        return false;
    }

    result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed: %d", result);
        AAudioStream_close(stream);
        return false;
    }

    out->stream = stream;
    LOGI("Opened AAudio stream %dHz/%dch format=%d device=%d",
         out->sampleRate, out->channels, out->format, deviceId);
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeOpenStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint channels, jint formatCode, jint deviceId) {
    const aaudio_format_t format = aaudioFormatOf(formatCode);
    if (format == AAUDIO_FORMAT_INVALID) {
        LOGE("Unsupported format code: %d", formatCode);
        return 0;
    }

    auto* out = new AaudioOutput();
    out->sampleRate = sampleRate;
    out->channels = channels;
    out->format = format;
    out->bytesPerFrame = channels * bytesPerSampleOf(format);

    if (!openStreamFor(out, deviceId)) {
        delete out;
        return 0;
    }
    return reinterpret_cast<jlong>(out);
}

JNIEXPORT jboolean JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeSetDevice(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jint deviceId) {
    if (ptr == 0) return JNI_FALSE;
    auto* out = reinterpret_cast<AaudioOutput*>(ptr);

    // The old stream goes first. AAudio stops its data callback before close
    // returns, so nothing is reading `out` while the replacement is opened - and
    // the PCM already queued on it is simply waiting for whichever stream comes
    // next, which is why a swap costs no audio rather than the ring's worth of it.
    if (out->stream != nullptr) {
        AAudioStream_requestStop(out->stream);
        AAudioStream_close(out->stream);
        out->stream = nullptr;
    }
    if (!openStreamFor(out, deviceId)) return JNI_FALSE;
    if (out->paused.load()) AAudioStream_requestPause(out->stream);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return;
    auto* out = reinterpret_cast<AaudioOutput*>(ptr);
    if (out->stream != nullptr) {
        AAudioStream_requestStop(out->stream);
        AAudioStream_close(out->stream);
    }
    delete out;
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeWrite(
    JNIEnv* env, jobject /*thiz*/, jlong ptr, jbyteArray pcm, jint offset, jint length) {
    if (ptr == 0 || pcm == nullptr || length <= 0 || offset < 0) return 0;

    // offset and length come from Java, and are the only thing between the memcpy
    // below and a read past the end of the array. Check them against the array
    // itself; subtracting rather than adding keeps the bound from overflowing.
    const jsize pcmLength = env->GetArrayLength(pcm);
    if (offset > pcmLength || length > pcmLength - offset) {
        LOGE("nativeWrite out of bounds: offset=%d length=%d array=%d", offset, length, pcmLength);
        return 0;
    }

    auto* out = reinterpret_cast<AaudioOutput*>(ptr);
    const int bytesPerFrame = out->bytesPerFrame;
    if (bytesPerFrame <= 0) return 0;
    const int frames = length / bytesPerFrame;
    if (frames <= 0) return 0;

    // Allocated before the critical region: allocating inside one can block on the
    // GC that the region is holding off.
    std::vector<uint8_t> chunk(static_cast<size_t>(length));

    void* base = env->GetPrimitiveArrayCritical(pcm, nullptr);
    if (base == nullptr) return 0;
    std::memcpy(chunk.data(), static_cast<const uint8_t*>(base) + offset, static_cast<size_t>(length));
    env->ReleasePrimitiveArrayCritical(pcm, base, JNI_ABORT);

    {
        std::lock_guard<std::mutex> lock(out->mutex);
        out->chunks.push(std::move(chunk));
    }
    out->framesWritten += frames;
    return frames;
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeFlush(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return;
    auto* out = reinterpret_cast<AaudioOutput*>(ptr);
    std::lock_guard<std::mutex> lock(out->mutex);
    while (!out->chunks.empty()) out->chunks.pop();
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativePause(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return;
    auto* out = reinterpret_cast<AaudioOutput*>(ptr);
    out->paused.store(true);
    if (out->stream != nullptr) AAudioStream_requestPause(out->stream);
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeResume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return;
    auto* out = reinterpret_cast<AaudioOutput*>(ptr);
    out->paused.store(false);
    if (out->stream != nullptr) AAudioStream_requestStart(out->stream);
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeBufferedFrames(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    auto* out = reinterpret_cast<AaudioOutput*>(ptr);
    const int64_t written = out->framesWritten.load();
    const int64_t read = out->framesRead.load();
    return std::max<int64_t>(0, written - read);
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeSetVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jfloat volume) {
    if (ptr == 0) return;
    auto* out = reinterpret_cast<AaudioOutput*>(ptr);
    out->volume.store(volume);
}

} // extern "C"
