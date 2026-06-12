package com.engabd.sendpin.audio

class NativeFlacDecoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitDepth: Int,
) : AutoCloseable {

    private var handle: Long = 0

    init {
        handle = nativeCreate(sampleRate, channels, bitDepth)
    }

    val isAvailable: Boolean get() = handle != 0L

    fun decode(flacData: ByteArray, pcmOut: ByteArray): Int {
        return nativeDecode(handle, flacData, pcmOut, pcmOut.size)
    }

    fun reset() {
        nativeReset(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    companion object {
        init {
            System.loadLibrary("sendpin_audio")
        }
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int, bitDepth: Int): Long
    private external fun nativeDecode(handle: Long, flacData: ByteArray, pcmOut: ByteArray, maxBytes: Int): Int
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
