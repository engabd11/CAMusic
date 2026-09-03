package com.engabd.sendpin.audio

/**
 * What is actually coming out of the pipe, however it got here: negotiated by
 * the Sendspin protocol when this phone is the player, or read off the Music
 * Assistant queue's stream details when a remote speaker is.
 *
 * Kept codec-agnostic — the UI decides how loudly to shout about it.
 */
data class StreamQuality(
    val codec: String,          // "FLAC", "OPUS", "MP3", …
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    /** ReplayGain track-level adjustment in dB, or null when not available. */
    val replayGainTrack: Float? = null,
    /** ReplayGain album-level adjustment in dB, or null when not available. */
    val replayGainAlbum: Float? = null,
    /** 2 for stereo, 1 for mono, 6 for 5.1. 0 when the source didn't say. */
    val channels: Int = 0,
    /** The file's size in bytes, when the library reported it. 0 otherwise. */
    val sizeBytes: Long = 0,
) {
    val lossless: Boolean get() = normalizedCodec in LOSSLESS

    /** The gain adjustment being applied, if any. Album gain preferred for album play. */
    val activeGain: Float? get() = replayGainAlbum ?: replayGainTrack

    /** Human-readable gain, e.g. "+2.5 dB RG" or null. */
    val gainLabel: String? get() = activeGain?.let { "%+.1f dB RG".format(it) }

    /**
     * Whether two readings describe the same audio, i.e. nothing was transcoded
     * between them.
     *
     * Bitrate is deliberately excluded. The two sides come from different places —
     * the playing format carries a bitrate from the queue's stream details, the
     * source format is derived from `provider_mappings.audio_format`, which has no
     * bitrate field at all — so comparing [label] strings said "transcoded" for
     * every single track, which is what kept the Source row on screen. Codec, rate
     * and depth are what a transcode actually changes.
     */
    fun sameFormatAs(other: StreamQuality): Boolean =
        normalizedCodec == other.normalizedCodec &&
            sampleRateHz == other.sampleRateHz &&
            bitDepth == other.bitDepth

    /** `audio/flac` and `FLAC` are the same codec — MA sends both spellings. */
    private val normalizedCodec: String get() = codec.substringAfterLast('/').lowercase().trim()

    /**
     * The codec as a listener would name it.
     *
     * Music Assistant reports a track's `content_type`, which is the *container or
     * MIME* name rather than the codec people know: an MP3 comes back as `mpeg`
     * (from `audio/mpeg`), Vorbis as `ogg`, ALAC sometimes as `m4a`. Uppercasing that
     * verbatim produced a badge reading "MPEG" on an ordinary MP3.
     */
    private val displayCodec: String
        get() = when (val c = normalizedCodec) {
            "mpeg", "mp3", "mpga" -> "MP3"
            "mp4", "m4a", "aac", "mp4a" -> "AAC"
            "ogg", "vorbis" -> "Vorbis"
            "x-flac" -> "FLAC"
            "wave", "x-wav" -> "WAV"
            else -> c.uppercase()
        }

    /** Better than CD: the case worth lighting the badge up for. */
    val hiRes: Boolean get() = lossless && (sampleRateHz > 48_000 || bitDepth > 16)

    /**
     * The rate as a bitrate reads it: `805 kb/s`, `3 Mb/s`.
     *
     * `1411k` was accurate and unreadable — a number with a letter stuck on it, in the
     * one place on the screen that is meant to be an at-a-glance boast. Megabits get a
     * decimal only when it carries information, so a 3.0 reads as `3 Mb/s`.
     */
    val bitrateLabel: String?
        get() = when {
            bitrateKbps <= 0 -> null
            bitrateKbps < 1000 -> "$bitrateKbps kb/s"
            else -> {
                val mb = bitrateKbps / 1000.0
                val rounded = Math.round(mb * 10) / 10.0
                if (rounded == Math.floor(rounded)) "${rounded.toInt()} Mb/s"
                else String.format("%.1f Mb/s", rounded)
            }
        }

    /** The badge text, e.g. `FLAC • 96/24 • 3 Mb/s` or `AAC • 256 kb/s`. */
    val label: String
        get() {
            val name = displayCodec
            val detail = when {
                lossless && bitDepth > 0 && sampleRateHz > 0 -> "${khz(sampleRateHz)}/$bitDepth"
                sampleRateHz > 0 && bitrateKbps > 0 && !lossless -> bitrateLabel
                sampleRateHz > 0 -> "${khz(sampleRateHz)}kHz"
                bitrateKbps > 0 -> bitrateLabel
                else -> null
            }
            // The bitrate is a bonus on top of a rate/depth pair — never worth
            // appending when `detail` fell back to being the bitrate itself.
            val br = if (lossless && bitrateKbps > 0 && detail != null && detail != bitrateLabel) {
                " • $bitrateLabel"
            } else ""
            return if (detail == null) name else "$name • $detail$br"
        }

    /** "Stereo", "Mono", "5.1", or null when the source didn't say. */
    val channelLabel: String?
        get() = when (channels) {
            0 -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels channels"
        }

    /**
     * The compact form, for a list row: `FLAC 48/24`, `MP3 320k`.
     *
     * [label]'s middle dots and trailing bitrate are right for a badge that is the
     * only thing on its line, and too much for a track row that already carries a
     * number, a title, a duration and a heart.
     */
    val shortLabel: String
        get() {
            val detail = when {
                lossless && bitDepth > 0 && sampleRateHz > 0 -> "${khz(sampleRateHz)}/$bitDepth"
                !lossless && bitrateKbps > 0 -> "${bitrateKbps}k"
                sampleRateHz > 0 -> "${khz(sampleRateHz)}kHz"
                else -> null
            }
            return if (detail == null) displayCodec else "$displayCodec $detail"
        }

    companion object {
        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "pcm", "dsf", "dff", "ape", "wavpack")

        /**
         * What a [RemotePlayback] is decoding right now, named by the file's own
         * tags — the badge's reading when the library plays its own music.
         *
         * The two halves answer different questions and neither is enough alone.
         * A remote player's status line says the sample rate, bit depth, channel
         * count and — the point of this — the **bitrate it is decoding at right
         * now**, which on a VBR file moves from second to second and on a lossless
         * one is the real measure of what is coming off the disk. What it does not
         * say is what the *codec* is; only the file's tags know that, and a badge
         * reading "44.1/16" with no name on it is not a badge.
         *
         * So: codec from [tagged], everything measurable from [live], and [tagged]
         * again wherever the player declined to say — MPD writes `f` for its own
         * float pipeline and `*` for "not decided yet", both of which
         * `MpdClient.parseAudioFormat` turns into 0. With nothing live at all this
         * is [tagged] unchanged, which is exactly what the badge showed before.
         *
         * The gain and size fields are carried across untouched: they describe the
         * file, and the file has not changed.
         */
        fun live(tagged: StreamQuality?, live: RemoteAudioFormat?): StreamQuality? {
            if (live == null) return tagged
            val known = live.sampleRateHz > 0 || live.bitDepth > 0 ||
                live.bitrateKbps > 0 || live.channels > 0
            if (!known) return tagged
            return StreamQuality(
                // "PCM" rather than a blank: a player that is decoding something is
                // putting linear audio out of it, and an unnamed badge is worse
                // than a general one.
                codec = tagged?.codec?.takeIf { it.isNotBlank() } ?: "PCM",
                sampleRateHz = live.sampleRateHz.takeIf { it > 0 } ?: tagged?.sampleRateHz ?: 0,
                bitDepth = live.bitDepth.takeIf { it > 0 } ?: tagged?.bitDepth ?: 0,
                // Never carried over from the tags: a stale bitrate on a badge that
                // is meant to be live is worse than no bitrate at all.
                bitrateKbps = live.bitrateKbps.takeIf { it > 0 } ?: 0,
                channels = live.channels.takeIf { it > 0 } ?: tagged?.channels ?: 0,
                replayGainTrack = tagged?.replayGainTrack,
                replayGainAlbum = tagged?.replayGainAlbum,
                sizeBytes = tagged?.sizeBytes ?: 0,
            )
        }

        /** 44100 → "44.1", 96000 → "96". */
        internal fun khz(hz: Int): String {
            val k = hz / 1000.0
            return if (k == Math.floor(k)) k.toInt().toString() else String.format("%.1f", k)
        }
    }
}
