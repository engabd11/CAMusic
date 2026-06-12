package com.cyborgautomation.sendspin.audio

class AudioOutput(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitDepth: Int,
) : AutoCloseable {

    private var started = false

    fun start(): Boolean {
        if (started) close()
        val result = NativeAudioEngine.start(sampleRate, channels, bitDepth)
        started = result == 0
        return started
    }

    fun write(pcmBytes: ByteArray): Int {
        return NativeAudioEngine.write(pcmBytes)
    }

    fun setVolume(volume: Float) {
        NativeAudioEngine.setVolume(volume)
    }

    fun getBufferFill(): Float = NativeAudioEngine.getBufferFill()

    fun getFramesWritten(): Long = NativeAudioEngine.getFramesWritten()

    override fun close() {
        NativeAudioEngine.stop()
        started = false
    }
}
