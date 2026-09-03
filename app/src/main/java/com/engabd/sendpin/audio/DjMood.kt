package com.engabd.sendpin.audio

import com.engabd.sendpin.ma.MaItem
import kotlin.random.Random

/**
 * A brief for a DJ set, in the terms the listener actually thinks in.
 *
 * DJ Radio's original brief was "more like this one", which is a good answer to a
 * question the listener has already answered for themselves by putting something
 * on. It is no answer at all to the other way people reach for music, which is by
 * the time of day and what they are about to do: the set that is right at seven in
 * the morning is wrong at seven in the evening and absurd in a gym, whatever the
 * last thing played happened to be.
 *
 * So a mood is a window on the axes [TrackScan] already measures — how hard the
 * song goes, how fast it runs, how bright the mix is — plus the genre tags the
 * library wrote down, which is the one thing no amount of signal analysis
 * recovers. Nothing new is analysed for this: every number here is already on
 * disk for any track that has been scanned, and a library with nothing scanned
 * falls back to genre alone rather than to nothing.
 *
 * ## Why windows and not a target point
 *
 * A single ideal energy would sort the whole library by distance from it and play
 * the same forty tracks for ever. A *window* says "anything in here belongs", and
 * leaves [DjSimilarity] to decide the running order inside it — which is the
 * division of labour the rest of DJ Radio is already built on: the mood decides
 * what is eligible, similarity decides what follows what.
 */
enum class DjMood(
    /** Stored/analytics id. Stable — the display name is free to change. */
    val id: String,
    val title: String,
    /** One line under the title, saying what pressing it will actually do. */
    val blurb: String,
    /**
     * [IntensityProfile.character] — how hard the song goes, absolute across
     * tracks. Null when the mood does not care.
     */
    val energy: ClosedFloatingPointRange<Float>?,
    /** [IntensityProfile.tempo], 0 ballad .. 1 club. Null when the mood does not care. */
    val pace: ClosedFloatingPointRange<Float>?,
    /** Spectral tilt folded to 0 bass-heavy .. 1 bright. Null when it does not matter. */
    val brightness: ClosedFloatingPointRange<Float>? = null,
    /**
     * Genre tokens this mood is *made* of, in [DjProfile.splitGenre]'s spelling.
     *
     * A bonus rather than a filter: a library tagged nothing but "Rock" would
     * otherwise have every mood come back empty, and the measured axes are the
     * honest half of this anyway.
     */
    val genres: Set<String> = emptySet(),
) {
    /**
     * No brief but the seed's own. What the button did before moods existed, kept
     * as a first-class choice rather than as the absence of one.
     */
    ANYTHING(
        id = "any",
        title = "Surprise me",
        blurb = "No brief — the set follows wherever the first track leads",
        energy = null,
        pace = null,
    ),

    MORNING(
        id = "morning",
        title = "Morning",
        blurb = "Awake but not shouting — bright, unhurried, easy on a first coffee",
        energy = 0.20f..0.62f,
        pace = 0.18f..0.58f,
        brightness = 0.45f..1f,
        genres = setOf("acoustic", "folk", "jazz", "soul", "indie", "bossa", "pop", "singer", "songwriter"),
    ),

    FOCUS(
        id = "focus",
        title = "Focus",
        blurb = "Steady and unobtrusive — nothing that asks to be listened to",
        energy = 0.15f..0.55f,
        pace = 0.25f..0.62f,
        brightness = 0.20f..0.75f,
        genres = setOf("ambient", "downtempo", "minimal", "classical", "instrumental", "post", "electronic", "lofi"),
    ),

    WORKOUT(
        id = "workout",
        title = "Workout",
        blurb = "Fast and relentless — loud tracks that keep their tempo up",
        energy = 0.62f..1f,
        pace = 0.60f..1f,
        genres = setOf("techno", "house", "drum", "bass", "punk", "metal", "rock", "hardcore", "trance", "rap", "hip"),
    ),

    PARTY(
        id = "party",
        title = "Party",
        blurb = "Loud, bright and familiar — for a room with people in it",
        energy = 0.58f..1f,
        pace = 0.48f..0.95f,
        brightness = 0.40f..1f,
        genres = setOf("disco", "funk", "house", "pop", "dance", "soul", "rap", "hip", "reggae"),
    ),

    SUNDOWN(
        id = "sundown",
        title = "Sundown",
        blurb = "Warm and mid-paced — the hour after the day is over",
        energy = 0.30f..0.70f,
        pace = 0.25f..0.62f,
        brightness = 0f..0.62f,
        genres = setOf("soul", "jazz", "blues", "downtempo", "trip", "dub", "reggae", "folk", "r&b"),
    ),

    SLEEP(
        id = "sleep",
        title = "Before sleep",
        blurb = "Slow, quiet and dark — nothing that starts anything",
        energy = 0f..0.32f,
        pace = 0f..0.36f,
        brightness = 0f..0.55f,
        genres = setOf("ambient", "classical", "piano", "drone", "acoustic", "instrumental", "choral", "new"),
    );

    /** Nothing to enforce — see [ANYTHING]. */
    val open: Boolean get() = this == ANYTHING

    /**
     * How well [profile] answers this brief, 0..1.
     *
     * A mean over the axes both the mood and the track can answer, so a track with
     * no scan behind it is judged on its genre tags alone rather than being
     * failed for questions nobody asked it. Every axis is graded rather than
     * pass/fail: a track a little outside the window is a little worse than one
     * inside it, which is what keeps a small library from having nothing to play.
     *
     * [UNSCANNED] is the one flat penalty, and it says the same thing
     * [DjSimilarity.UNSCANNED_DISCOUNT] says: not "this is wrong for the mood" —
     * that cannot be known — but "nothing has checked". On a library where
     * nothing is scanned it applies to everything equally and changes no ordering
     * at all.
     */
    fun fit(profile: DjProfile): Float {
        if (open) return 1f
        var sum = 0f
        var n = 0

        fun axis(value: Float?, window: ClosedFloatingPointRange<Float>?) {
            if (value == null || window == null) return
            sum += inside(value, window)
            n++
        }

        axis(profile.energy, energy)
        axis(profile.pace ?: paceFromBpm(profile.bpm), pace)
        axis(profile.brightness, brightness)

        if (genres.isNotEmpty() && profile.genres.isNotEmpty()) {
            // A hit is worth a full mark and a miss half of one. A genre this mood
            // has never heard of is not evidence *against* — a library's tags are
            // its own, and half the world's records are filed under something this
            // list will never contain.
            sum += if (profile.genres.any { it in genres }) 1f else 0.5f
            n++
        }

        if (n == 0) return 0.5f
        val fit = sum / n
        return if (profile.scanned) fit else fit * UNSCANNED
    }

    /**
     * 1 inside [window], falling away outside it and reaching 0 a [FALLOFF] past
     * either edge. Graded rather than binary — see [fit].
     */
    private fun inside(value: Float, window: ClosedFloatingPointRange<Float>): Float {
        if (value in window) return 1f
        val distance = if (value < window.start) window.start - value else value - window.endInclusive
        return (1f - distance / FALLOFF).coerceIn(0f, 1f)
    }

    companion object {
        /** How far past a window's edge a track is still worth anything. */
        private const val FALLOFF = 0.30f

        /** What an unscanned track keeps of its tag-only fit. See [fit]. */
        private const val UNSCANNED = 0.80f

        /**
         * BPM as [IntensityProfile.tempo] spells it, for a track that has a grid
         * but no intensity profile — 60 BPM and below is 0, 180 and above is 1.
         *
         * The same shape [TrackScan]'s own tempo axis uses, restated here rather
         * than reached for, because this file is deliberately free of the analysis
         * types beyond the one profile it is handed.
         */
        fun paceFromBpm(bpm: Float): Float? =
            if (bpm <= 0f) null else ((bpm - 60f) / 120f).coerceIn(0f, 1f)

        fun byId(id: String?): DjMood? = DjMood.entries.firstOrNull { it.id == id }
    }
}

/**
 * The six songs the DJ Radio button offers to start from.
 *
 * The button used to open on whatever a random page happened to hand back first,
 * which is a coin toss dressed up as a decision: a set is defined by the track it
 * is seeded from, and the listener is the only one who knows which way they want
 * the next hour to go. Six is enough to be a real choice and few enough to read at
 * a glance.
 *
 * ## What makes them six *different* songs
 *
 * Picking six at random from one library reliably produces four of the same thing,
 * because a library is not evenly spread — half of it is usually one or two
 * genres. So they are chosen by **maximum spread** instead: the first is random,
 * and each one after it is whichever remaining candidate is *least* like everything
 * already on the list, measured with the same [DjSimilarity] the set itself is
 * ordered by. That is a farthest-point traversal, and it is the standard answer to
 * "give me a few points that cover this space".
 *
 * Pure and stateless, on the same terms as the rest of this file: no Android, no
 * I/O, no player.
 */
object DjSeeds {

    /**
     * Up to [count] contrasting candidates from [pool].
     *
     * [profileOf] resolves each one, which is where the caller does the
     * (suspending, disk-touching) work of finding its scan. [mood], when given,
     * filters the pool to what answers that brief before the spread is worked out
     * — six well-spread tracks that ignore the brief would be six wrong answers.
     *
     * [random] is a parameter so a test can pin the traversal's one arbitrary
     * decision, which is where it starts.
     */
    fun pick(
        pool: List<MaItem>,
        profileOf: (MaItem) -> DjProfile,
        count: Int = 6,
        mood: DjMood? = null,
        random: Random = Random.Default,
    ): List<MaItem> {
        if (count <= 0) return emptyList()
        val unique = pool.filter { it.itemId.isNotBlank() }.distinctBy { it.itemId }
        if (unique.isEmpty()) return emptyList()
        val profiles = unique.associate { it.itemId to profileOf(it) }

        // The mood filter is a *ranking* that keeps the best half rather than a
        // hard cut, for the reason [DjMood.fit] grades instead of failing: on a
        // library with nothing scanned every fit is the same number, and a hard
        // cut there would return either everything or nothing depending on where
        // the bar happened to sit.
        val eligible = if (mood == null || mood.open) unique else {
            val ranked = unique.sortedByDescending { mood.fit(profiles.getValue(it.itemId)) }
            // A third of the page, or twice the ask, whichever is larger. Wide
            // enough that the spread below has room to find contrast inside the
            // brief; narrow enough that the spread cannot satisfy itself by
            // reaching straight back out of it, which is what a looser cut did —
            // "before sleep" offering three lullabies and three techno records
            // because those are, indeed, maximally different.
            ranked.take(maxOf(count * 2, ranked.size / 3))
        }
        if (eligible.size <= count) return eligible.shuffled(random)

        val remaining = eligible.shuffled(random).toMutableList()
        val picked = ArrayList<MaItem>(count)
        picked.add(remaining.removeAt(0))

        while (picked.size < count && remaining.isNotEmpty()) {
            // Least like anything already chosen. `minBy` over a list that was
            // shuffled first, so a tie — which is the common case on an unscanned
            // library, where everything scores the same — breaks differently each
            // time rather than pinning the same six songs to the button for ever.
            val next = remaining.minByOrNull { candidate ->
                val c = profiles.getValue(candidate.itemId)
                var worst = 0f
                for (chosen in picked) {
                    val s = DjSimilarity.score(profiles.getValue(chosen.itemId), c)
                    if (s > worst) worst = s
                }
                // An artist already on the list is the one repeat that reads as a
                // broken picker rather than as a coincidence, whatever the numbers
                // say about the two songs.
                if (picked.any { sameArtist(profiles.getValue(it.itemId), c) }) worst += ARTIST_REPEAT
                worst
            } ?: break
            remaining.remove(next)
            picked.add(next)
        }
        return picked
    }

    /**
     * The three words under a seed that say why it is on the list — genre, tempo
     * and how hard it goes, whichever of them are known.
     *
     * The point of the picker is that the six are *different*, and six titles a
     * listener has not heard in a while do not show that on their own. Null when
     * the library knows nothing about the track at all, in which case the row
     * shows the artist alone rather than an empty line.
     */
    fun note(profile: DjProfile): String? {
        val parts = buildList {
            profile.genres.firstOrNull()?.let { add(it.replaceFirstChar(Char::uppercase)) }
            profile.bpm.takeIf { it > 0f }?.let { add("${it.toInt()} BPM") }
            energyWord(profile)?.let { add(it) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** How hard the song goes, in one word. Null when nothing has measured it. */
    private fun energyWord(profile: DjProfile): String? {
        val e = profile.energy ?: return null
        return when {
            e < 0.22f -> "hushed"
            e < 0.42f -> "gentle"
            e < 0.62f -> "steady"
            e < 0.82f -> "driving"
            else -> "flat out"
        }
    }

    private fun sameArtist(a: DjProfile, b: DjProfile): Boolean =
        a.artist != null && a.artist.equals(b.artist, ignoreCase = true)

    /** What sharing an artist with something already picked costs a candidate. */
    private const val ARTIST_REPEAT = 0.5f
}
