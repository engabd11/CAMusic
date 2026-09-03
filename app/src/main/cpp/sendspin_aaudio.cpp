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

// AAudio data format constants mapped from media3 C encoding values.
constexpr int ENCODING_PCM_16BIT = 0x00000002; // C.ENCODING_PCM_16BIT
constexpr int ENCODING_PCM_24BIT = 0x0000000a; // C.ENCODING_PCM_24BIT
constexpr int ENCODING_PCM_32BIT = 0x0000000e; // C.ENCODING_PCM_32BIT
constexpr int ENCODING_PCM_FLOAT  = 0x00000004; // C.ENCODING_PCM_FLOAT

aaudio_format_t aaudioFormatOf(int encoding) {
    switch (encoding) {
        case ENCODING_PCM_16BIT: return AAUDIO_FORMAT_PCM_I16;
        case ENCODING_PCM_24BIT: return AAUDIO_FORMAT_PCM_I24;
        case ENCODING_PCM_32BIT: return AAUDIO_FORMAT_PCM_I32;
        case ENCODING_PCM_FLOAT: return AAUDIO_FORMAT_PCM_FLOAT;
        default: return AAUDIO_FORMAT_INVALID;
    }
}

int bytesPerSampleOf(aaudio_format_t format) {
    switch (format) {
        case AAUDIO_FORMAT_PCM_I16: return 2;
        case AAUDIO_FORMAT_PCM_I24: return 3;
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

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeOpenStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint channels, jint encoding, jint deviceId) {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        LOGE("AAudio_createStreamBuilder failed: %d", result);
        return 0;
    }

    const aaudio_format_t format = aaudioFormatOf(encoding);
    if (format == AAUDIO_FORMAT_INVALID) {
        LOGE("Unsupported encoding: %d", encoding);
        AAudioStreamBuilder_delete(builder);
        return 0;
    }

    // The callback's userData is what it writes into, and AAudio only accepts it
    // on the builder - there is no setter for an already-open stream - so the
    // output has to exist before the stream does.
    auto* out = new AaudioOutput();
    out->sampleRate = sampleRate;
    out->channels = channels;
    out->format = format;
    out->bytesPerFrame = channels * bytesPerSampleOf(format);

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setFormat(builder, format);
    AAudioStreamBuilder_setDataCallback(builder, AaudioOutput::callback, out);
    if (deviceId > 0) {
        AAudioStreamBuilder_setDeviceId(builder, deviceId);
    }

    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) {
        LOGE("AAudioStreamBuilder_openStream failed: %d", result);
        delete out;
        return 0;
    }
    out->stream = stream;

    // AAudio is free to hand back a stream in a different format, rate or channel
    // count to the one asked for. Accepting one would mean feeding it bytes laid
    // out for something else - noise, and noise presented as bit-perfect. Refusing
    // is the whole point: the Kotlin side then falls back to DefaultAudioSink.
    const aaudio_format_t gotFormat = AAudioStream_getFormat(stream);
    const int32_t gotRate = AAudioStream_getSampleRate(stream);
    const int32_t gotChannels = AAudioStream_getChannelCount(stream);
    if (gotFormat != format || gotRate != sampleRate || gotChannels != channels) {
        LOGE("AAudio opened %dHz/%dch/fmt=%d, asked for %dHz/%dch/fmt=%d - declining",
             gotRate, gotChannels, gotFormat, sampleRate, channels, format);
        AAudioStream_close(stream);
        delete out;
        return 0;
    }

    result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed: %d", result);
        AAudioStream_close(stream);
        delete out;
        return 0;
    }

    LOGI("Opened AAudio stream %dHz/%dch format=%d device=%d", sampleRate, channels, encoding, deviceId);
    return reinterpret_cast<jlong>(out);
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
    if (out->stream != nullptr) AAudioStream_requestPause(out->stream);
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeResume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return;
    auto* out = reinterpret_cast<AaudioOutput*>(ptr);
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
