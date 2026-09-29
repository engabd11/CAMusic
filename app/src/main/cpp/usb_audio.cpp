#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <sys/ioctl.h>
#include <atomic>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

// CAMusic's own USB audio streaming engine: isochronous OUT transfers straight to a
// USB Audio Class DAC over usbfs, bypassing Android's audio stack.
// See docs/plan/usb-bitperfect-driver.md.
//
// Android's Java USB API has no isochronous transfers, so this takes the fd from
// UsbDeviceConnection.getFileDescriptor() and submits URBs itself. The Kotlin side has
// already claimed the interfaces, chosen the alternate setting and set the rate; this
// only moves bytes, and never changes them.

#define LOG_TAG "UsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kNumUrbs = 8;
constexpr int kPacketsPerUrb = 8;

struct Stream {
    int fd = -1;
    unsigned char endpoint = 0;
    int maxPacket = 0;
    int bytesPerFrame = 0;
    int rate = 0;
    // Isochronous service opportunities per second: 1000 at full speed; at high speed
    // 8000 microframes divided by the endpoint's interval.
    int packetsPerSecond = 1000;

    // Frames owed so far, times packetsPerSecond. Each packet takes the whole frames it
    // has earned, so 44.1 kHz at 1000 packets/s comes out as 44 x9 then 45, exactly,
    // with nothing lost to rounding however long it runs.
    uint64_t owed = 0;

    std::vector<usbdevfs_urb*> urbs;
    std::vector<std::vector<uint8_t>> buffers;

    std::mutex mutex;
    std::vector<uint8_t> ring;
    size_t ringHead = 0;
    size_t ringSize = 0;

    std::atomic<bool> running{false};
    std::atomic<int> inFlight{0};
    std::thread thread;

    // Paused: keep the DAC clocked with silence but leave the queue alone.
    std::atomic<bool> paused{false};
    // Bumped by a flush, so frames still in flight from before it are not counted as
    // played audio of the new position.
    std::atomic<int> epoch{0};
    std::vector<int> urbRealFrames = std::vector<int>(kNumUrbs, 0);
    std::vector<int> urbEpoch = std::vector<int>(kNumUrbs, 0);
    // Real (non-padding) frames the DAC has taken, and those submitted but not yet back.
    std::atomic<int64_t> playedFrames{0};
    std::atomic<int64_t> inFlightRealFrames{0};

    std::atomic<int64_t> urbsCompleted{0};
    std::atomic<int64_t> packetErrors{0};
    std::atomic<int64_t> silentFrames{0};
    std::atomic<int64_t> framesSent{0};
    std::atomic<int> lastError{0};

    // Take up to `frames` whole frames from the ring into dst; pad the rest with silence.
    // Returns how many were real audio.
    int fill(uint8_t* dst, int frames) {
        const size_t want = static_cast<size_t>(frames) * bytesPerFrame;
        if (paused.load()) {
            std::memset(dst, 0, want);
            return 0;
        }
        size_t got = 0;
        {
            std::lock_guard<std::mutex> lock(mutex);
            const size_t avail = ringSize - ringSize % bytesPerFrame;
            got = std::min(want, avail);
            size_t first = std::min(got, ring.size() - ringHead);
            std::memcpy(dst, ring.data() + ringHead, first);
            std::memcpy(dst + first, ring.data(), got - first);
            ringHead = (ringHead + got) % ring.size();
            ringSize -= got;
        }
        if (got < want) {
            std::memset(dst + got, 0, want - got);
            silentFrames += static_cast<int64_t>((want - got) / bytesPerFrame);
        }
        framesSent += frames;
        return static_cast<int>(got / bytesPerFrame);
    }

    bool submit(int index) {
        usbdevfs_urb* urb = urbs[index];
        uint8_t* buf = buffers[index].data();
        int offset = 0;
        int real = 0;
        for (int p = 0; p < kPacketsPerUrb; ++p) {
            owed += static_cast<uint64_t>(rate);
            int frames = static_cast<int>(owed / packetsPerSecond);
            owed -= static_cast<uint64_t>(frames) * packetsPerSecond;
            int bytes = frames * bytesPerFrame;
            if (bytes > maxPacket) {
                // Cannot happen with a rate the alternate setting advertises; clamp
                // rather than overrun the endpoint if a descriptor lied.
                frames = maxPacket / bytesPerFrame;
                bytes = frames * bytesPerFrame;
            }
            real += fill(buf + offset, frames);
            urb->iso_frame_desc[p].length = static_cast<unsigned int>(bytes);
            urb->iso_frame_desc[p].actual_length = 0;
            urb->iso_frame_desc[p].status = 0;
            offset += bytes;
        }
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = endpoint;
        urb->status = 0;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = buf;
        urb->buffer_length = offset;
        urb->actual_length = 0;
        urb->start_frame = 0;
        urb->number_of_packets = kPacketsPerUrb;
        urb->error_count = 0;
        urb->signr = 0;
        urb->usercontext = reinterpret_cast<void*>(static_cast<intptr_t>(index));
        urbRealFrames[index] = real;
        urbEpoch[index] = epoch.load();
        if (ioctl(fd, USBDEVFS_SUBMITURB, urb) < 0) {
            lastError = errno;
            LOGW("SUBMITURB failed: %s", strerror(errno));
            return false;
        }
        inFlightRealFrames += real;
        inFlight++;
        return true;
    }

    void run() {
        for (int i = 0; i < kNumUrbs; ++i) {
            if (!submit(i)) break;
        }
        while (inFlight.load() > 0) {
            usbdevfs_urb* done = nullptr;
            if (ioctl(fd, USBDEVFS_REAPURB, &done) < 0) {
                if (errno == EINTR) continue;
                lastError = errno;
                LOGW("REAPURB failed: %s", strerror(errno));
                break;  // Device gone: nothing more will come back.
            }
            inFlight--;
            if (done == nullptr) continue;
            const int doneIndex = static_cast<int>(reinterpret_cast<intptr_t>(done->usercontext));
            if (urbEpoch[doneIndex] == epoch.load()) {
                inFlightRealFrames -= urbRealFrames[doneIndex];
                if (done->status == 0) playedFrames += urbRealFrames[doneIndex];
            }
            if (done->status == 0) {
                urbsCompleted++;
            } else if (done->status != -ENOENT && done->status != -ECONNRESET) {
                lastError = -done->status;
            }
            for (int p = 0; p < done->number_of_packets; ++p) {
                if (done->iso_frame_desc[p].status != 0) packetErrors++;
            }
            if (running.load()) {
                if (!submit(doneIndex)) running = false;
            }
        }
        running = false;
    }

    void stop() {
        running = false;
        for (auto* urb : urbs) ioctl(fd, USBDEVFS_DISCARDURB, urb);  // In flight or not; EINVAL is fine.
        if (thread.joinable()) thread.join();
        // Whatever was discarded was not played; it is not in flight any more either.
        inFlightRealFrames = 0;
    }

    void begin() {
        owed = 0;
        running = true;
        thread = std::thread([this] { run(); });
    }

    // Drop everything queued; frames already on the wire play out but no longer count.
    void flush() {
        std::lock_guard<std::mutex> lock(mutex);
        ringHead = 0;
        ringSize = 0;
        epoch++;
        inFlightRealFrames = 0;
    }

    ~Stream() {
        for (auto* urb : urbs) std::free(urb);
    }
};

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeSpeed(JNIEnv*, jobject, jint fd) {
    return ioctl(fd, USBDEVFS_GET_SPEED);
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeStart(
    JNIEnv*, jobject, jint fd, jint endpoint, jint maxPacket, jint bytesPerFrame, jint rate,
    jint packetsPerSecond) {
    if (fd < 0 || maxPacket <= 0 || bytesPerFrame <= 0 || rate <= 0 || packetsPerSecond <= 0) return 0;
    auto* s = new Stream();
    s->fd = fd;
    s->endpoint = static_cast<unsigned char>(endpoint);
    s->maxPacket = maxPacket;
    s->bytesPerFrame = bytesPerFrame;
    s->rate = rate;
    s->packetsPerSecond = packetsPerSecond;
    s->ring.resize(static_cast<size_t>(rate) * bytesPerFrame);  // One second.
    const size_t urbBytes = sizeof(usbdevfs_urb) + kPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc);
    for (int i = 0; i < kNumUrbs; ++i) {
        s->urbs.push_back(static_cast<usbdevfs_urb*>(std::calloc(1, urbBytes)));
        s->buffers.emplace_back(static_cast<size_t>(maxPacket) * kPacketsPerUrb);
    }
    s->begin();
    LOGI("streaming ep=0x%02x rate=%d frame=%dB maxPacket=%d pps=%d", endpoint, rate, bytesPerFrame, maxPacket,
         packetsPerSecond);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeWrite(
    JNIEnv* env, jobject, jlong ptr, jbyteArray pcm, jint offset, jint length) {
    if (ptr == 0 || pcm == nullptr || offset < 0 || length <= 0) return 0;
    if (offset > env->GetArrayLength(pcm) - length) return 0;
    auto* s = reinterpret_cast<Stream*>(ptr);
    std::vector<uint8_t> chunk(static_cast<size_t>(length));
    env->GetByteArrayRegion(pcm, offset, length, reinterpret_cast<jbyte*>(chunk.data()));
    std::lock_guard<std::mutex> lock(s->mutex);
    const size_t space = s->ring.size() - s->ringSize;
    const size_t take = std::min(space, chunk.size());
    size_t tail = (s->ringHead + s->ringSize) % s->ring.size();
    size_t first = std::min(take, s->ring.size() - tail);
    std::memcpy(s->ring.data() + tail, chunk.data(), first);
    std::memcpy(s->ring.data(), chunk.data() + first, take - first);
    s->ringSize += take;
    return static_cast<jint>(take);
}

JNIEXPORT jint JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeQueuedBytes(JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return 0;
    auto* s = reinterpret_cast<Stream*>(ptr);
    std::lock_guard<std::mutex> lock(s->mutex);
    return static_cast<jint>(s->ringSize);
}

// completed URBs, packet errors, silent (underrun) frames, frames sent, last errno, running
JNIEXPORT jlongArray JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeStats(JNIEnv* env, jobject, jlong ptr) {
    jlongArray out = env->NewLongArray(6);
    if (ptr == 0) return out;
    auto* s = reinterpret_cast<Stream*>(ptr);
    jlong v[6] = {s->urbsCompleted.load(), s->packetErrors.load(), s->silentFrames.load(),
                  s->framesSent.load(), s->lastError.load(), s->running.load() ? 1 : 0};
    env->SetLongArrayRegion(out, 0, 6, v);
    return out;
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeStop(JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return;
    auto* s = reinterpret_cast<Stream*>(ptr);
    s->stop();
    delete s;
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeSetPaused(JNIEnv*, jobject, jlong ptr, jboolean paused) {
    if (ptr != 0) reinterpret_cast<Stream*>(ptr)->paused = paused == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeFlush(JNIEnv*, jobject, jlong ptr) {
    if (ptr != 0) reinterpret_cast<Stream*>(ptr)->flush();
}

JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativePlayedFrames(JNIEnv*, jobject, jlong ptr) {
    return ptr == 0 ? 0 : reinterpret_cast<Stream*>(ptr)->playedFrames.load();
}

// Real frames not yet played: queued in the ring plus submitted and not back.
JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativePendingFrames(JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return 0;
    auto* s = reinterpret_cast<Stream*>(ptr);
    std::lock_guard<std::mutex> lock(s->mutex);
    return static_cast<jlong>(s->ringSize / s->bytesPerFrame) + s->inFlightRealFrames.load();
}

// Stop the transfers but keep the queue, so the DAC can go back to Android for a while
// and the music resumes where it was.
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeHalt(JNIEnv*, jobject, jlong ptr) {
    if (ptr != 0) reinterpret_cast<Stream*>(ptr)->stop();
}

JNIEXPORT void JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeRestart(JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return;
    auto* s = reinterpret_cast<Stream*>(ptr);
    if (!s->running.load() && !s->thread.joinable()) s->begin();
}

// Hand an interface back to the kernel's driver after it has been released, so
// Android's audio sees the DAC again.
JNIEXPORT jboolean JNICALL
Java_com_engabd_sendpin_usb_UsbAudioNative_nativeReattach(JNIEnv*, jobject, jint fd, jint ifno) {
    usbdevfs_ioctl cmd{};
    cmd.ifno = ifno;
    cmd.ioctl_code = USBDEVFS_CONNECT;
    cmd.data = nullptr;
    if (ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0) {
        // EBUSY: the kernel's driver already took this interface back while binding a
        // sibling - snd-usb-audio claims every audio interface of the device at once.
        if (errno == EBUSY) return JNI_TRUE;
        LOGW("reattach interface %d: %s", ifno, strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

}  // extern "C"
