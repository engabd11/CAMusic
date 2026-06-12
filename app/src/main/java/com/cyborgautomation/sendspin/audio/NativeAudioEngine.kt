package com.cyborgautomation.sendspin.audio

object NativeAudioEngine {
    init {
        System.loadLibrary("sendspin_audio")
    }

    external fun nativeStart(sampleRate: Int, channels: Int, bitDepth: Int): Int
    external fun nativeWrite(pcmData: ByteArray, byteCount: Int): Int
    external fun nativeStop()
    external fun nativeSetVolume(volume: Float)
    external fun nativeGetFramesWritten(): Long
    external fun nativeGetBufferFill(): Float

    fun start(sampleRate: Int, channels: Int = 2, bitDepth: Int = 24): Int =
        nativeStart(sampleRate, channels, bitDepth)

    fun write(pcmData: ByteArray): Int =
        nativeWrite(pcmData, pcmData.size)

    fun stop() = nativeStop()

    fun setVolume(volume: Float) = nativeSetVolume(volume)

    fun getFramesWritten(): Long = nativeGetFramesWritten()

    fun getBufferFill(): Float = nativeGetBufferFill()
}
