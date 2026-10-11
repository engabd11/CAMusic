package com.engabd.sendpin.ui.screens.settings

import com.engabd.sendpin.ui.screens.SettingsSection
import com.engabd.sendpin.ui.screens.SettingsSection.APPEARANCE
import com.engabd.sendpin.ui.screens.SettingsSection.AUDIO
import com.engabd.sendpin.ui.screens.SettingsSection.DRIVING
import com.engabd.sendpin.ui.screens.SettingsSection.LIGHTS_SYNC
import com.engabd.sendpin.ui.screens.SettingsSection.PROVIDERS
import com.engabd.sendpin.ui.screens.SettingsSection.SYSTEM_ABOUT

/**
 * One thing Settings can find: a card or a control, the words someone might type for
 * it, and the page it lives on.
 *
 * @param route the page within [section], or null for the section's own body.
 * @param advanced shown only with Advanced on; opening it switches Advanced on.
 */
internal data class SettingsEntry(
    val title: String,
    val keywords: String,
    val section: SettingsSection,
    val route: String? = null,
    val advanced: Boolean = false,
)

/**
 * Search for Settings.
 *
 * Settings has seven sections, two dozen pages and well over a hundred controls, and
 * the only way to a control was to know which section someone had filed it under:
 * ReplayGain under Audio › Volume & gain, the swipe gesture under Audio › Playback
 * behaviour, the floating driving window under Driving › Driving mode. Typing what
 * you want is faster than guessing where it lives.
 *
 * Hand-written entries rather than an index scraped from the screen, so each can carry
 * the words people actually use ("hi-res", "normalise", "pip") that the card's own
 * title does not. `SettingsSearchTest` checks every page has at least one.
 */
internal object SettingsSearch {

    val ENTRIES: List<SettingsEntry> = listOf(
        // ── Media Providers & Accounts ───────────────────────────────────
        SettingsEntry("Your libraries", "servers library switch active order navidrome jellyfin plex emby mpd music assistant", PROVIDERS),
        SettingsEntry("Add a server", "new library connect navidrome subsonic gonic airsonic jellyfin emby plex mpd moode volumio foobar spotify qobuz tidal this device local files phone", PROVIDERS, PICK_ROUTE),
        SettingsEntry("Search all libraries", "search every server unified results", PROVIDERS),
        SettingsEntry("Music Assistant player", "ma player name rename speaker sendspin stream format latency trim announcements pairing pin guest", PROVIDERS),
        SettingsEntry("Music on this phone", "this device local files folder sd card permission", PROVIDERS),

        // ── Audio Engine & DSP ──────────────────────────────────────────
        SettingsEntry("Output mode", "standard high resolution hi-res hires usb bit-perfect bitperfect dac exclusive 24-bit lossless", AUDIO, AUDIO_OUTPUT_ROUTE),
        SettingsEntry("Signal path", "format sample rate bit depth codec quality decoder sink", AUDIO, AUDIO_OUTPUT_ROUTE),
        SettingsEntry("Output sample rate", "44.1 48 96 192 khz resample rate", AUDIO, AUDIO_OUTPUT_ROUTE),
        SettingsEntry("Digital volume on DACs", "usb dac volume hardware software", AUDIO, AUDIO_OUTPUT_ROUTE),
        SettingsEntry("Play at original quality", "music assistant original transcode bypass resample", AUDIO, AUDIO_OUTPUT_ROUTE),
        SettingsEntry("Equaliser", "eq equalizer bass treble ten band graphic presets parametric autoeq", AUDIO, AUDIO_EQ_ROUTE),
        SettingsEntry("Automatic headroom", "preamp clipping distortion eq", AUDIO, AUDIO_EQ_ROUTE),
        SettingsEntry("Music Assistant DSP", "ma dsp parametric server equaliser", AUDIO, AUDIO_EQ_ROUTE),
        SettingsEntry("ReplayGain", "replay gain loudness volume level normalise normalize album track", AUDIO, AUDIO_GAIN_ROUTE),
        SettingsEntry("Crossfade", "overlap the songs fade gapless transition between tracks mix", AUDIO, AUDIO_BETWEEN_ROUTE),
        SettingsEntry("Beat-matched fade", "beat match tempo smart crossfade dj", AUDIO, AUDIO_BETWEEN_ROUTE),
        SettingsEntry("Spread out artists when shuffling", "shuffle artist spread repeat variety", AUDIO, AUDIO_BETWEEN_ROUTE),
        SettingsEntry("End of the queue", "autoplay keep playing continue radio similar queue runs out", AUDIO, AUDIO_BETWEEN_ROUTE),
        SettingsEntry("DJ Radio", "dj radio mix mood key matching harmonic", AUDIO, AUDIO_BETWEEN_ROUTE),
        SettingsEntry("ListenBrainz", "scrobble scrobbling listens history", AUDIO, AUDIO_SCROBBLE_ROUTE),
        SettingsEntry("Last.fm", "lastfm last fm scrobble scrobbling", AUDIO, AUDIO_SCROBBLE_ROUTE),
        SettingsEntry("Listens waiting to be sent", "scrobble queue offline pending", AUDIO, AUDIO_SCROBBLE_ROUTE),
        SettingsEntry("Visualiser by default", "visualizer visualiser spectrum cover now playing", AUDIO, AUDIO_BEHAVIOUR_ROUTE),
        SettingsEntry("Swipe to skip", "gesture swipe next previous now playing", AUDIO, AUDIO_BEHAVIOUR_ROUTE),
        SettingsEntry("Shake, flip and tap", "gesture sensor shake flip pocket tap hands off", AUDIO, AUDIO_BEHAVIOUR_ROUTE),
        SettingsEntry("Harmonic DJ mode", "key camelot harmonic mixing scans", AUDIO, AUDIO_BEHAVIOUR_ROUTE),
        SettingsEntry("Listening DNA", "key tempo bpm stats statistics scans", AUDIO, AUDIO_BEHAVIOUR_ROUTE),
        SettingsEntry("Lyrics", "lyrics online synced lrclib timing offset words", AUDIO, AUDIO_BEHAVIOUR_ROUTE, advanced = true),

        // ── Illumination & Sync ─────────────────────────────────────────
        SettingsEntry("How the lights hear the music", "light sync route hue direct home assistant transport", LIGHTS_SYNC),
        SettingsEntry("Hue Bridge", "philips hue bridge pair pairing entertainment area lights", LIGHTS_SYNC, BRIDGE_ROUTE),
        SettingsEntry("Home Assistant", "ha token lights url", LIGHTS_SYNC, HA_ROUTE),
        SettingsEntry("Track analysis", "scan sweep offline analysis beats bpm library", LIGHTS_SYNC, ANALYSIS_ROUTE),
        SettingsEntry("Phone audio", "capture other apps listen microphone spotify youtube", LIGHTS_SYNC, LISTEN_ROUTE),

        // ── Interface & Appearance ──────────────────────────────────────
        SettingsEntry("Theme", "dark light oled black follow system night", APPEARANCE, LOOK_THEME_ROUTE),
        SettingsEntry("Accent colour", "accent color colour album art tint", APPEARANCE, LOOK_THEME_ROUTE),
        SettingsEntry("Now Playing layout", "tab overlay full screen player", APPEARANCE, LOOK_PLAYER_ROUTE),
        SettingsEntry("Mini player", "mini bar player above tabs browse", APPEARANCE, LOOK_PLAYER_ROUTE),
        SettingsEntry("Seek bar", "progress bar wave line pill glow scrubber", APPEARANCE, LOOK_PLAYER_ROUTE),
        SettingsEntry("Library look", "category buttons tiles cards covers shape size", APPEARANCE, LOOK_LIBRARY_ROUTE),
        SettingsEntry("Library categories", "which buttons order reorder hide categories albums artists genres", APPEARANCE, LOOK_LIBRARY_ROUTE),
        SettingsEntry("Spotlight", "featured album today library extras", APPEARANCE, LOOK_LIBRARY_ROUTE),
        SettingsEntry("Album-colour backdrop", "album colour color background backdrop library", APPEARANCE, LOOK_LIBRARY_ROUTE),
        SettingsEntry("Page style", "classic gallery album artist page detail", APPEARANCE, LOOK_PAGES_ROUTE),
        SettingsEntry("Album page sections", "album page shelves sections credits", APPEARANCE, LOOK_PAGES_ROUTE),
        SettingsEntry("Artist page sections", "artist page shelves sections similar top songs", APPEARANCE, LOOK_PAGES_ROUTE),
        SettingsEntry("Album bloom", "chameleon canvas bloom glow ambient album light", APPEARANCE, LOOK_MOTION_ROUTE),
        SettingsEntry("Glass blur", "blur glass frosted translucent", APPEARANCE, LOOK_MOTION_ROUTE),
        SettingsEntry("Reduced motion", "animation motion animations accessibility", APPEARANCE, LOOK_MOTION_ROUTE, advanced = true),

        // ── Driving & Android Auto ──────────────────────────────────────
        SettingsEntry("Driving mode", "car driving bluetooth trigger start automatically", DRIVING, DRIVE_MODE_ROUTE),
        SettingsEntry("How the driving controls appear", "floating window bar overlay picture in picture pip", DRIVING, DRIVE_MODE_ROUTE),
        SettingsEntry("Speed limit alert", "speed limit camera gps safety warning alert", DRIVING, DRIVE_SAFETY_ROUTE),
        SettingsEntry("Android Auto", "car android auto projection", DRIVING, DRIVE_AUTO_ROUTE),
        SettingsEntry("Android Auto layout", "car screen grid list rows round artwork", DRIVING, DRIVE_AUTO_ROUTE),
        SettingsEntry("What the car shows", "car shelves libraries items per shelf content", DRIVING, DRIVE_AUTO_ROUTE),
        SettingsEntry("The car's playback screen", "car rewind fast forward transport buttons", DRIVING, DRIVE_AUTO_ROUTE),
        SettingsEntry("Android Automotive", "automotive head unit built in car two pane", DRIVING, DRIVE_AUTO_ROUTE),

        // ── System, Storage & About ─────────────────────────────────────
        SettingsEntry("Downloads", "offline downloads storage space delete", SYSTEM_ABOUT, SYS_STORAGE_ROUTE),
        SettingsEntry("Download storage limit", "storage limit cap space gigabytes", SYSTEM_ABOUT, SYS_STORAGE_ROUTE),
        SettingsEntry("Downloads only on Wi-Fi", "wifi wi-fi mobile data when to fetch downloads", SYSTEM_ABOUT, SYS_STORAGE_ROUTE),
        SettingsEntry("Backup & restore", "backup restore export import settings transfer new phone", SYSTEM_ABOUT, SYS_BACKUP_ROUTE),
        SettingsEntry("Diagnostics", "logs crash report debug bug", SYSTEM_ABOUT, SYS_DIAGNOSTICS_ROUTE),
        SettingsEntry("USB DAC report", "usb dac descriptors report rates", SYSTEM_ABOUT, SYS_DIAGNOSTICS_ROUTE),
        SettingsEntry("About", "version licence license source github", SYSTEM_ABOUT, SYS_ABOUT_ROUTE),
    )

    /**
     * Entries matching [query], best first. Every word typed must start a word in the
     * entry (its title, its keywords or where it lives), so "rep gain" finds
     * ReplayGain and "bass" finds the equaliser. A match in the title counts for more
     * than one in the keywords, and a title that starts with the whole query more
     * still.
     */
    fun search(query: String, entries: List<SettingsEntry> = ENTRIES, where: (SettingsEntry) -> String = { "" }, limit: Int = 15): List<SettingsEntry> {
        val terms = tokens(query)
        if (terms.isEmpty()) return emptyList()
        val whole = normal(query)
        return entries.mapNotNull { entry ->
            val title = tokens(entry.title)
            val all = title + tokens(entry.keywords) + tokens(where(entry))
            var score = 0
            for (t in terms) {
                score += when {
                    title.any { it == t } -> 6
                    title.any { it.startsWith(t) } -> 4
                    all.any { it == t } -> 3
                    all.any { it.startsWith(t) } -> 2
                    else -> return@mapNotNull null
                }
            }
            if (normal(entry.title).startsWith(whole)) score += 5
            entry to score
        }
            .sortedWith(compareByDescending<Pair<SettingsEntry, Int>> { it.second }.thenBy { it.first.title.length })
            .take(limit)
            .map { it.first }
    }

    private fun normal(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    internal fun tokens(s: String): List<String> = normal(s).split(Regex("[^a-z0-9.]+")).map { it.trim('.') }.filter { it.isNotEmpty() }
}
