// SPDX-License-Identifier: Apache-2.0
// airplay_jni.cpp — JNI bridge between Kotlin (AirPlayOutput.kt) and the
// vendored airplay2-sender-cpp library.
//
// This file implements two things:
//
//  1. AndroidRaopIo — a RaopIo implementation using POSIX sockets (connect,
//     send, close for TCP; bind, send, close for UDP). Android's NDK provides
//     the full POSIX socket API, so no platform-specific glue is needed.
//
//  2. JNI entry points — the Kotlin side calls these to start/stop a session,
//     feed PCM, set volume, submit a PIN, and push now-playing metadata.
//
// The RaopSender state machine is sans-I/O: it owns no sockets, no timers, no
// threads. This bridge owns those:
//   - A dedicated native thread runs the poll loop (RaopLoop) that drives the
//     sender's tick(), reads incoming socket data, and reports events.
//   - The PCM ring buffer is lock-free (RingBuffer<int16_t>), fed from the
//     AudioProcessor on ExoPlayer's audio thread, drained by the pacer on the
//     native loop thread.
//
// The sender handles both AirPlay 1 (legacy RTSP) and AirPlay 2 (encrypted
// HAP pairing + ALAC realtime). The auth mode is chosen by the Kotlin side
// based on mDNS TXT record flags (raop_auth.h's Auth enum).

#include "raop_io.h"
#include "raop_sender.h"
#include "raop_log.h"
#include "ring_buffer.h"

#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <sys/poll.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <thread>
#include <atomic>
#include <vector>
#include <string>
#include <chrono>
#include <memory>

#define TAG "AirPlayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,   TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,  TAG, __VA_ARGS__)

using namespace fxchain;
using Clock = RaopSender::Clock;

// ── AndroidRaopIo: RaopIo via POSIX sockets ──────────────────────────────
//
// The sender asks for six things (tcp connect/send/close, udp bind/send/close)
// and gets everything else pushed back into it (onTcpConnected, onTcpData,
// onUdpDatagram, tick). This class manages the sockets and the poll loop that
// drives those callbacks.

class AndroidRaopIo : public RaopIo {
public:
    // Socket slots — fixed, matching RaopTcp / RaopUdp enums.
    int tcpFd_[2] = {-1, -1};     // Control, Event
    int udpFd_[3] = {-1, -1, -1}; // Audio, Control, Timing
    uint16_t udpPort_[3] = {0, 0, 0};

    // The sender — set by the bridge after construction.
    RaopSender* sender = nullptr;

    void tcpConnect(RaopTcp ch, const std::string& host, uint16_t port) override {
        int idx = static_cast<int>(ch);
        if (tcpFd_[idx] >= 0) ::close(tcpFd_[idx]);
        tcpFd_[idx] = -1;

        int fd = ::socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) {
            sender->onTcpConnectFailed(ch, "socket() failed");
            return;
        }
        // Non-blocking connect.
        int flags = ::fcntl(fd, F_GETFL, 0);
        ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);

        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(port);
        if (::inet_pton(AF_INET, host.c_str(), &addr.sin_addr) <= 0) {
            ::close(fd);
            sender->onTcpConnectFailed(ch, "inet_pton failed");
            return;
        }

        int rc = ::connect(fd, (struct sockaddr*)&addr, sizeof(addr));
        if (rc == 0) {
            // Immediate connect (localhost / fast path).
            tcpFd_[idx] = fd;
            struct sockaddr_in local{};
            socklen_t len = sizeof(local);
            ::getsockname(fd, (struct sockaddr*)&local, &len);
            RaopEndpoint localEp{std::string(inet_ntoa(local.sin_addr)), ntohs(local.sin_port)};
            RaopEndpoint peerEp{host, port};
            sender->onTcpConnected(ch, localEp, peerEp);
        } else if (errno == EINPROGRESS) {
            // Pending — the poll loop will detect writability and report connect.
            tcpFd_[idx] = fd;
            connectingTcp_[idx] = true;
            peerHost_[idx] = host;
            peerPort_[idx] = port;
        } else {
            ::close(fd);
            sender->onTcpConnectFailed(ch, std::string("connect: ") + strerror(errno));
        }
    }

    void tcpSend(RaopTcp ch, std::span<const uint8_t> bytes) override {
        int idx = static_cast<int>(ch);
        if (tcpFd_[idx] < 0) return;
        // Non-blocking write — if the kernel buffer is full, we drop. The
        // sender's RTSP is request/response, so backpressure is unlikely
        // at audio rates; the control channel is low-bandwidth.
        ssize_t n = ::send(tcpFd_[idx], bytes.data(), bytes.size(), MSG_NOSIGNAL);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            // Buffer full — queue for later. For now, drop (low-bandwidth control).
            LOGW("tcpSend: would block on channel %d, dropping %zu bytes", idx, bytes.size());
        } else if (n < 0) {
            LOGW("tcpSend: send() failed on channel %d: %s", idx, strerror(errno));
        }
    }

    void tcpClose(RaopTcp ch, bool flush) override {
        int idx = static_cast<int>(ch);
        if (tcpFd_[idx] < 0) return;
        if (flush) {
            // Graceful: shut down write side, let the kernel flush.
            ::shutdown(tcpFd_[idx], SHUT_WR);
        }
        ::close(tcpFd_[idx]);
        tcpFd_[idx] = -1;
        connectingTcp_[idx] = false;
    }

    uint16_t udpBind(RaopUdp s) override {
        int idx = static_cast<int>(s);
        if (udpFd_[idx] >= 0) ::close(udpFd_[idx]);

        int fd = ::socket(AF_INET, SOCK_DGRAM, 0);
        if (fd < 0) return 0;

        // Bind to ephemeral port on all interfaces.
        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_ANY);
        addr.sin_port = 0; // ephemeral

        if (::bind(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
            ::close(fd);
            return 0;
        }

        // Read the assigned port.
        socklen_t len = sizeof(addr);
        ::getsockname(fd, (struct sockaddr*)&addr, &len);
        uint16_t port = ntohs(addr.sin_port);

        // Non-blocking for the poll loop.
        int flags = ::fcntl(fd, F_GETFL, 0);
        ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);

        udpFd_[idx] = fd;
        udpPort_[idx] = port;
        return port;
    }

    void udpSend(RaopUdp s, const RaopEndpoint& to, std::span<const uint8_t> bytes) override {
        int idx = static_cast<int>(s);
        if (udpFd_[idx] < 0) return;

        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(to.port);
        ::inet_pton(AF_INET, to.ip.c_str(), &addr.sin_addr);

        ssize_t n = ::sendto(udpFd_[idx], bytes.data(), bytes.size(), 0,
                             (struct sockaddr*)&addr, sizeof(addr));
        if (n < 0) {
            LOGW("udpSend: sendto() failed: %s", strerror(errno));
        }
    }

    void udpClose(RaopUdp s) override {
        int idx = static_cast<int>(s);
        if (udpFd_[idx] < 0) return;
        ::close(udpFd_[idx]);
        udpFd_[idx] = -1;
        udpPort_[idx] = 0;
    }

    // ── Poll loop ────────────────────────────────────────────────────────
    // Called from the native loop thread. Builds a pollfd set from all open
    // sockets, polls with the sender's next deadline as the timeout, then
    // feeds results back into the sender.

    bool connectingTcp_[2] = {false, false};
    std::string peerHost_[2];
    uint16_t peerPort_[2] = {0, 0};

    void pollOnce(RaopSender& s) {
        std::vector<struct pollfd> pfds;
        // Map pollfd index back to (isTcp, channelIdx).
        struct Slot { bool tcp; int idx; };
        std::vector<Slot> slots;

        // TCP sockets — poll for readable + writable (writable = connect done).
        for (int i = 0; i < 2; i++) {
            if (tcpFd_[i] >= 0) {
                short events = POLLIN;
                if (connectingTcp_[i]) events |= POLLOUT;
                pfds.push_back({tcpFd_[i], events, 0});
                slots.push_back({true, i});
            }
        }
        // UDP sockets — poll for readable (retransmit/timing replies).
        for (int i = 0; i < 3; i++) {
            if (udpFd_[i] >= 0) {
                pfds.push_back({udpFd_[i], POLLIN, 0});
                slots.push_back({false, i});
            }
        }

        // Timeout: the sender's next deadline, or 50ms as a fallback.
        int timeoutMs = 50;
        auto deadline = s.nextDeadline();
        if (deadline) {
            auto now = Clock::now();
            auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          deadline.value() - now).count();
            timeoutMs = static_cast<int>(std::max(ms, (long long)0));
            // Cap at 100ms so we don't sleep too long if the deadline is far.
            timeoutMs = std::min(timeoutMs, 100);
        }

        int rc = ::poll(pfds.data(), pfds.size(), timeoutMs);
        if (rc < 0) {
            if (errno == EINTR) return;
            LOGE("poll() failed: %s", strerror(errno));
            return;
        }

        // Process socket events.
        for (size_t i = 0; i < pfds.size(); i++) {
            if (pfds[i].revents == 0) continue;
            const auto& slot = slots[i];

            if (slot.tcp) {
                // TCP channel.
                if (connectingTcp_[slot.idx] && (pfds[i].revents & POLLOUT)) {
                    // Connect completed — check success.
                    int err = 0;
                    socklen_t errlen = sizeof(err);
                    ::getsockopt(pfds[i].fd, SOL_SOCKET, SO_ERROR, &err, &errlen);
                    connectingTcp_[slot.idx] = false;
                    auto ch = static_cast<RaopTcp>(slot.idx);
                    if (err == 0) {
                        struct sockaddr_in local{};
                        socklen_t len = sizeof(local);
                        ::getsockname(pfds[i].fd, (struct sockaddr*)&local, &len);
                        RaopEndpoint localEp{std::string(inet_ntoa(local.sin_addr)), ntohs(local.sin_port)};
                        RaopEndpoint peerEp{peerHost_[slot.idx], peerPort_[slot.idx]};
                        s.onTcpConnected(ch, localEp, peerEp);
                    } else {
                        s.onTcpConnectFailed(ch, std::string("connect: ") + strerror(err));
                    }
                }
                if (pfds[i].revents & POLLIN) {
                    // Data available — read and feed to sender.
                    uint8_t buf[4096];
                    ssize_t n = ::recv(pfds[i].fd, buf, sizeof(buf), 0);
                    if (n > 0) {
                        s.onTcpData(static_cast<RaopTcp>(slot.idx),
                                    std::span<const uint8_t>(buf, n));
                    } else if (n == 0) {
                        // Peer closed.
                        ::close(pfds[i].fd);
                        tcpFd_[slot.idx] = -1;
                        s.onTcpClosed(static_cast<RaopTcp>(slot.idx), "");
                    } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
                        ::close(pfds[i].fd);
                        tcpFd_[slot.idx] = -1;
                        s.onTcpClosed(static_cast<RaopTcp>(slot.idx),
                                      std::string("recv: ") + strerror(errno));
                    }
                }
                if (pfds[i].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                    ::close(pfds[i].fd);
                    tcpFd_[slot.idx] = -1;
                    s.onTcpClosed(static_cast<RaopTcp>(slot.idx), "socket error");
                }
            } else {
                // UDP channel.
                if (pfds[i].revents & POLLIN) {
                    uint8_t buf[2048];
                    struct sockaddr_in from{};
                    socklen_t fromlen = sizeof(from);
                    ssize_t n = ::recvfrom(pfds[i].fd, buf, sizeof(buf), 0,
                                           (struct sockaddr*)&from, &fromlen);
                    if (n > 0) {
                        RaopEndpoint fromEp{std::string(inet_ntoa(from.sin_addr)),
                                            ntohs(from.sin_port)};
                        s.onUdpDatagram(static_cast<RaopUdp>(slot.idx),
                                        std::span<const uint8_t>(buf, n), fromEp);
                    }
                }
            }
        }

        // Always tick the sender (timers + pacer).
        s.tick();
    }

    void closeAll() {
        for (int i = 0; i < 2; i++) {
            if (tcpFd_[i] >= 0) { ::close(tcpFd_[i]); tcpFd_[i] = -1; }
        }
        for (int i = 0; i < 3; i++) {
            if (udpFd_[i] >= 0) { ::close(udpFd_[i]); udpFd_[i] = -1; }
        }
    }
};

// ── The bridge: holds the sender, the I/O, the ring buffer, and the loop thread ─

struct AirPlayBridge {
    std::unique_ptr<AndroidRaopIo> io;
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
            if (sender) sender->stop();
            if (loopThread.joinable()) loopThread.join();
            if (io) io->closeAll();
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

        if (attached) jvm->DetachCurrentThread();
    }
};

// ── JNI entry points ─────────────────────────────────────────────────────

static AirPlayBridge* getBridge(JNIEnv* env, jobject thiz) {
    // The bridge pointer is stored as a long field on the Kotlin object.
    // We read it via the field ID cached on first call.
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
// Called from AirPlayOutput.kt's init block. Allocates the bridge, the ring
// buffer, and the sender. Returns the native pointer as a long.
JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeInit(JNIEnv* env, jobject thiz) {
    auto* bridge = new AirPlayBridge();

    // Cache the JVM and create a global ref to the Kotlin object for callbacks.
    env->GetJavaVM(&bridge->jvm);
    bridge->globalRef = env->NewGlobalRef(thiz);

    // Cache callback method IDs.
    jclass cls = env->GetObjectClass(thiz);
    bridge->onLaunchedMethod = env->GetMethodID(cls, "onLaunched", "(ZLjava/lang/String;)V");
    bridge->onClosedMethod = env->GetMethodID(cls, "onClosed", "()V");
    bridge->onPinRequiredMethod = env->GetMethodID(cls, "onPinRequired", "(Ljava/lang/String;)V");
    bridge->onCredentialsMethod = env->GetMethodID(cls, "onCredentials", "(Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);

    // 44100 Hz stereo = 88200 samples/sec, ~4 seconds of buffer = 352800 samples.
    // Next power of 2 = 524288 (512K samples = 1MB).
    bridge->ring = std::make_unique<RingBuffer<int16_t>>(524288);

    bridge->io = std::make_unique<AndroidRaopIo>();

    RaopEvents events;
    auto* b = bridge;
    events.launched = [b](bool ok, const std::string& error) {
        b->launched.store(ok);
        b->notifyCallback(b->onLaunchedMethod, ok ? "true" : "false", error.c_str());
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

    bridge->sender = std::make_unique<RaopSender>(*bridge->io, events, logSink);
    bridge->io->sender = bridge->sender.get();
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
    // Set the field to 0 so we don't double-free.
    static jfieldID fid = nullptr;
    if (!fid) {
        jclass cls = env->GetObjectClass(thiz);
        fid = env->GetFieldID(cls, "nativePtr", "J");
        env->DeleteLocalRef(cls);
    }
    env->SetLongField(thiz, fid, 0);
}

// ── nativeStart ──────────────────────────────────────────────────────────
// Begins the session: connect + handshake + stream. Spawns the poll loop thread.
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
    bridge->loopThread = std::thread([bridge]() {
        while (bridge->running.load()) {
            bridge->io->pollOnce(*bridge->sender);
        }
        bridge->sender->stop();
        bridge->io->closeAll();
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
// full, samples are dropped (the sender will push silence to fill the gap).
JNIEXPORT void JNICALL
Java_com_engabd_sendpin_audio_AirPlayOutput_nativeWritePcm(
        JNIEnv* env, jobject thiz, jbyteArray jBuffer, jint offset, jint length) {
    AirPlayBridge* bridge = getBridge(env, thiz);
    if (!bridge || !bridge->launched.load()) return;

    jbyte* bytes = env->GetByteArrayElements(jBuffer, nullptr);
    jsize total = env->GetArrayLength(jBuffer);

    // length is in bytes; samples are 16-bit = 2 bytes each.
    int sampleCount = (length < 0 || offset + length > total) ? 0 : length / 2;
    if (sampleCount > 0) {
        const int16_t* samples = reinterpret_cast<const int16_t*>(bytes + offset);
        bridge->ring->tryPush(std::span<const int16_t>(samples, sampleCount));
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
        cover.assign(reinterpret_cast<const char*>(coverBytes), coverLen);
        env->ReleaseByteArrayElements(jCover, coverBytes, JNI_ABORT);
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