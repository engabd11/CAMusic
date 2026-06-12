#include "audio_engine.h"
#include <oboe/Oboe.h>
#include <android/log.h>
#include <atomic>
#include <vector>
#include <cstring>
#include <mutex>

#define TAG "SendspinAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace oboe;

static constexpr int kBufferSizeFrames = 8192;  // 8k frames → ~170ms at 48kHz
static constexpr int kBytesPerSample24 = 3;

static std::unique_ptr<AudioStream> g_stream = nullptr;
static std::vector<uint8_t> g_ring_buffer;    // Raw packed i24 bytes
static std::atomic<size_t> g_read_idx{0};
static std::atomic<size_t> g_write_idx{0};
static std::mutex g_mutex;
static int g_sample_rate = 0;
static int g_channels = 0;
static int g_bit_depth = 0;
static std::atomic<float> g_volume{1.0f};
static std::atomic<int64_t> g_frames_written{0};
static std::atomic<bool> g_running{false};

static size_t ring_bytes_available_read() {
    size_t w = g_write_idx.load(std::memory_order_acquire);
    size_t r = g_read_idx.load(std::memory_order_acquire);
    if (w >= r) return w - r;
    return g_ring_buffer.size() - r + w;
}

static size_t ring_bytes_available_write() {
    size_t r = g_read_idx.load(std::memory_order_acquire);
    size_t w = g_write_idx.load(std::memory_order_acquire);
    if (w < r) return r - w - 1;
    return g_ring_buffer.size() - w + r - 1;
}

static void ring_read(uint8_t* dest, size_t bytes) {
    size_t r = g_read_idx.load(std::memory_order_relaxed);
    size_t sz = g_ring_buffer.size();
    for (size_t i = 0; i < bytes; i++) {
        dest[i] = g_ring_buffer[r];
        r = (r + 1) % sz;
    }
    g_read_idx.store(r, std::memory_order_release);
}

static void ring_write(const uint8_t* src, size_t bytes) {
    size_t w = g_write_idx.load(std::memory_order_relaxed);
    size_t sz = g_ring_buffer.size();
    for (size_t i = 0; i < bytes; i++) {
        g_ring_buffer[w] = src[i];
        w = (w + 1) % sz;
    }
    g_write_idx.store(w, std::memory_order_release);
}

class SendspinAudioCallback : public AudioStreamDataCallback {
public:
    DataCallbackResult onAudioReady(AudioStream* stream, void* audioData, int32_t numFrames) override {
        int bytes_needed = numFrames * g_channels * kBytesPerSample24;
        int bytes_available = (int)ring_bytes_available_read();

        if (bytes_available >= bytes_needed) {
            ring_read(static_cast<uint8_t*>(audioData), bytes_needed);
            g_frames_written.fetch_add(numFrames, std::memory_order_relaxed);
        } else {
            // Underrun — output silence
            memset(audioData, 0, bytes_needed);
            if (bytes_available > 0) {
                ring_read(static_cast<uint8_t*>(audioData), bytes_available);
            }
        }

        // Apply volume by adjusting samples (simple gain on i24)
        float vol = g_volume.load(std::memory_order_relaxed);
        if (vol < 1.0f) {
            int total_samples = numFrames * g_channels;
            uint8_t* buf = static_cast<uint8_t*>(audioData);
            for (int i = 0; i < total_samples; i++) {
                int32_t sample = (int32_t)(buf[0]) | ((int32_t)(buf[1]) << 8) | ((int32_t)(buf[2]) << 16);
                // Sign-extend from 24-bit
                if (sample & 0x800000) sample |= 0xFF000000;
                sample = (int32_t)(sample * vol);
                buf[0] = (uint8_t)(sample & 0xFF);
                buf[1] = (uint8_t)((sample >> 8) & 0xFF);
                buf[2] = (uint8_t)((sample >> 16) & 0xFF);
                buf += 3;
            }
        }

        return DataCallbackResult::Continue;
    }
};

static SendspinAudioCallback g_callback;

int engine_start(int sample_rate, int channels, int bit_depth) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_running.load()) {
        engine_stop();
    }

    g_sample_rate = sample_rate;
    g_channels = channels;
    g_bit_depth = bit_depth;

    // Ring buffer: ~300ms of audio
    int ring_frames = sample_rate / 3;
    g_ring_buffer.resize(ring_frames * channels * kBytesPerSample24);
    g_read_idx = 0;
    g_write_idx = 0;
    g_frames_written = 0;

    AudioStreamBuilder builder;
    Result result = builder
        .setDirection(Direction::Output)
        .setPerformanceMode(PerformanceMode::LowLatency)
        .setSharingMode(SharingMode::Exclusive)
        .setFormat(AudioFormat::I24)
        .setFormatConversionAllowed(false)
        .setSampleRate(sample_rate)
        .setChannelCount(channels)
        .setUsage(Usage::Media)
        .setContentType(ContentType::Music)
        .setDataCallback(&g_callback)
        .openStream(g_stream);

    if (result != Result::OK) {
        LOGE("Failed to open Exclusive stream: %s. Trying Shared.", convertToText(result));
        // Fallback: try Shared mode
        builder.setSharingMode(SharingMode::Shared);
        result = builder.openStream(g_stream);
        if (result != Result::OK) {
            LOGE("Failed to open Shared stream: %s", convertToText(result));
            return -1;
        }
        LOGD("Opened Shared stream successfully");
    } else {
        LOGD("Opened Exclusive stream successfully — bit-perfect 24-bit!");
    }

    result = g_stream->requestStart();
    if (result != Result::OK) {
        LOGE("Failed to start stream: %s", convertToText(result));
        g_stream->close();
        g_stream.reset();
        return -2;
    }

    g_running.store(true);
    LOGD("Audio engine started: %dHz, %dch, %d-bit", sample_rate, channels, bit_depth);
    return 0;
}

int engine_write(const uint8_t* pcm_data, int byte_count) {
    if (!g_running.load()) return -1;

    // Block if buffer is too full (back-pressure)
    int max_wait = 100;  // 100ms max wait
    while (ring_bytes_available_write() < (size_t)byte_count && max_wait > 0) {
        usleep(1000);
        max_wait--;
    }

    size_t available = ring_bytes_available_write();
    int to_write = (int)std::min((size_t)byte_count, available);
    if (to_write > 0) {
        ring_write(pcm_data, to_write);
    }
    return to_write;
}

void engine_stop() {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_running.store(false);

    if (g_stream) {
        g_stream->requestStop();
        g_stream->close();
        g_stream.reset();
    }
    LOGD("Audio engine stopped");
}

void engine_set_volume(float volume) {
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    g_volume.store(volume, std::memory_order_relaxed);
}

int64_t engine_get_frames_written() {
    return g_frames_written.load(std::memory_order_relaxed);
}

float engine_get_buffer_fill() {
    size_t total = g_ring_buffer.size();
    if (total == 0) return 0.0f;
    return (float)ring_bytes_available_read() / (float)total;
}

// JNI exports
#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_cyborgautomation_sendspin_audio_NativeAudioEngine_nativeStart(
    JNIEnv* env, jobject, jint sampleRate, jint channels, jint bitDepth) {
    return engine_start((int)sampleRate, (int)channels, (int)bitDepth);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cyborgautomation_sendspin_audio_NativeAudioEngine_nativeWrite(
    JNIEnv* env, jobject, jbyteArray pcmData, jint byteCount) {
    jbyte* data = env->GetByteArrayElements(pcmData, nullptr);
    int result = engine_write(reinterpret_cast<const uint8_t*>(data), (int)byteCount);
    env->ReleaseByteArrayElements(pcmData, data, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cyborgautomation_sendspin_audio_NativeAudioEngine_nativeStop(
    JNIEnv*, jobject) {
    engine_stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cyborgautomation_sendspin_audio_NativeAudioEngine_nativeSetVolume(
    JNIEnv*, jobject, jfloat volume) {
    engine_set_volume((float)volume);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_cyborgautomation_sendspin_audio_NativeAudioEngine_nativeGetFramesWritten(
    JNIEnv*, jobject) {
    return (jlong)engine_get_frames_written();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_cyborgautomation_sendspin_audio_NativeAudioEngine_nativeGetBufferFill(
    JNIEnv*, jobject) {
    return (jfloat)engine_get_buffer_fill();
}
