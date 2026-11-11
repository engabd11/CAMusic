package com.engabd.sendpin.audio

import com.engabd.sendpin.ma.MaItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One track reduced to what a mix decision actually needs.
 *
 * Deliberately not a [TrackScan] and not an [MaItem]: a DJ Radio pick is made from
 * *both*, and from whichever half is present. The scan carries the things a tag
 * cannot know — how hard the song goes, how bright it is, what tempo it really runs
 * at — and the library item carries the one thing no amount of signal analysis
 * recovers, which is what the music *is*. Two tracks can sit a beat apart on every
 * measured axis and still be a jazz trio and a techno record, and a set that mixes
 * those is exactly the "random" the listener complains about.
 *
 * Every measured field is nullable and every one of them is allowed to be missing.
 * A library where nothing has been scanned still runs DJ Radio — it just runs it on
 * genre and artist, and says so through [scanned].
 */
data class DjProfile(
    val id: String,
    /** Genre tags, as the library wrote them. Normalised on the way in by [profileOf]. */
    val genres: List<String> = emptyList(),
    val artist: String? = null,
    /** Beats per minute, or 0 when unknown. */
    val bpm: Float = 0f,
    val key: MusicalKey? = null,
    /**
     * How hard the song goes, absolute and comparable across tracks —
     * [IntensityProfile.character]. The single most useful axis here: it is what
     * separates a ballad from a banger regardless of what either is tagged.
     */
    val energy: Float? = null,
    /** 0 bass-heavy .. 1 bright, from [IntensityProfile.tilt] folded into a unit range. */
    val brightness: Float? = null,
    /** [IntensityProfile.tempo] — BPM mapped 0 (ballad) .. 1 (club). */
    val pace: Float? = null,
    /** How much the song moves between its quiet and loud parts, 0..1. */
    val dynamics: Float? = null,
) {
    /** There is offline analysis behind this profile, not just tags. */
    val scanned: Boolean get() = energy != null || bpm > 0f

    companion object {
        /**
         * The profile of [item], with [scan] filled in where one has been made.
         *
         * The artist is [MaItem.subtitle] up to the first comma, matching what
         * [LocalRadio] already seeds its `topSongs` rung from — a track credited
         * "Röyksopp, Susanne Sundfør" is a Röyksopp track for the purpose of "not
         * four in a row by the same act".
         */
        fun profileOf(item: MaItem, scan: TrackScan?): DjProfile {
            val profile = scan?.intensity
            return DjProfile(
                id = item.itemId,
                genres = item.genres.flatMap { splitGenre(it) }.distinct(),
                artist = item.subtitle?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() },
                bpm = scan?.bpm?.takeIf { it > 0f } ?: 0f,
                key = scan?.key,
                energy = profile?.character,
                brightness = profile?.let { ((1f - it.tilt) * 0.5f).coerceIn(0f, 1f) },
                pace = profile?.tempo,
                dynamics = profile?.dynamics,
            )
        }

        /**
         * A genre tag as comparable tokens.
         *
         * Libraries disagree about how to write one down — "Alternative Rock",
         * "alt-rock", "Rock; Indie", "Electronic/Downtempo" — and comparing those as
         * opaque strings makes two spellings of the same music look like two genres.
         * Splitting into words lets "Alternative Rock" and "Indie Rock" share
         * something without claiming they are the same thing.
         */
        fun splitGenre(raw: String): List<String> =
            raw.lowercase()
                .split('/', ';', ',', '&', '-', ' ')
                .map { it.trim() }
                .filter { it.length > 2 && it !in GENRE_STOP_WORDS }

        private val GENRE_STOP_WORDS = setOf("and", "the", "music", "misc", "other", "various")
    }
}

/**
 * How alike two tracks are, and which of a pile of candidates should follow the one
 * that is playing.
 *
 * Pure and stateless, on the same terms as [Camelot]: no Android, no I/O, no player.
 * Everything here is a decision about *ordering*, and an ordering that is wrong is
 * something a test should be able to say out loud.
 *
 * ## What "similar" means
 *
 * A weighted mean over the axes both tracks can answer, each in 0..1:
 *
 * | axis | weight | why |
 * |---|---|---|
 * | genre | 0.34 | The listener's own words for what this is. Nothing measured replaces it. |
 * | energy | 0.20 | [IntensityProfile.character] — absolute, so a lo-fi chorus never reads as a drop. |
 * | rhythm | 0.20 | BPM, folded at half and double time the way a DJ hears it. |
 * | brightness | 0.10 | Spectral tilt. Separates a dense mix from an airy one inside a genre. |
 * | dynamics | 0.06 | How much the song moves. A compressed club track against a live one. |
 * | key | 0.10 | Camelot compatibility — only when Harmonic DJ mode is on. |
 *
 * Weights are normalised over the axes actually used, so a candidate with no scan is
 * still scored fairly on the two it can answer rather than being punished by a pile
 * of zeroes for questions nobody asked it. What *is* applied to it is
 * [UNSCANNED_DISCOUNT], which is a different statement: not "this is a bad match" but
 * "nothing here has checked".
 */
object DjSimilarity {

    private const val W_GENRE = 0.34f
    private const val W_ENERGY = 0.20f
    private const val W_RHYTHM = 0.20f
    private const val W_BRIGHT = 0.10f
    private const val W_DYNAMICS = 0.06f
    private const val W_KEY = 0.10f

    /**
     * What an unscanned candidate keeps of its tag-only score.
     *
     * Not a penalty for being different — it cannot be known to be different. It is
     * the cost of not being *checked*, and it exists so that on a half-scanned
     * library the scanned tracks lead while the rest stay available. A library with
     * nothing scanned discounts every candidate equally, which changes no ordering
     * at all.
     */
    private const val UNSCANNED_DISCOUNT = 0.82f

    /**
     * How close two BPMs have to be to count as the same tempo, as a fraction.
     * Wider than [Camelot.bpmMatch]'s mix tolerance on purpose: this is a graded
     * score over the whole range rather than a yes/no about beatmatching.
     */
    private const val BPM_SPAN = 0.22f

    /**
     * 0..1, how well [candidate] would follow [seed].
     *
     * [harmonic] turns the key axis on — Harmonic DJ mode's setting, passed down
     * rather than read here, because this object has no business knowing what a
     * DataStore is.
     */
    fun score(seed: DjProfile, candidate: DjProfile, harmonic: Boolean = false): Float {
        var sum = 0f
        var weight = 0f

        fun axis(w: Float, value: Float) {
            sum += w * value.coerceIn(0f, 1f)
            weight += w
        }

        // Genre is the one axis that is always asked, even when neither side has
        // tags: two untagged tracks in the same library are a draw, not a mismatch,
        // and scoring them 0 would let a *tagged* mismatch beat them.
        if (seed.genres.isNotEmpty() || candidate.genres.isNotEmpty()) {
            axis(W_GENRE, genreOverlap(seed.genres, candidate.genres))
        }

        pair(seed.energy, candidate.energy)?.let { (a, b) -> axis(W_ENERGY, 1f - abs(a - b)) }
        pair(seed.brightness, candidate.brightness)?.let { (a, b) -> axis(W_BRIGHT, 1f - abs(a - b)) }
        pair(seed.dynamics, candidate.dynamics)?.let { (a, b) -> axis(W_DYNAMICS, 1f - abs(a - b)) }

        // Rhythm prefers the measured BPM and falls back to the scan's own
        // 0..1 pace when only that is present — a scan is allowed to have failed to
        // find a grid and still know roughly how fast the song feels.
        val rhythm = tempoScore(seed, candidate)
        if (rhythm != null) axis(W_RHYTHM, rhythm)

        if (harmonic) {
            val a = seed.key
            val b = candidate.key
            if (a != null && b != null) {
                axis(
                    W_KEY,
                    when {
                        a.tonic == b.tonic && a.mode == b.mode -> 1f
                        Camelot.compatible(a, b) -> 0.75f
                        else -> 0.2f
                    },
                )
            }
        }

        if (weight <= 0f) return 0.5f
        // No same-artist bonus: an act is already the closest match to itself on
        // every axis, and the bonus on top is what kept bringing it back.
        var s = sum / weight
        if (!candidate.scanned) s *= UNSCANNED_DISCOUNT
        return s.coerceIn(0f, 1f)
    }

    /**
     * Tempo agreement, 0..1, or null when neither track can say how fast it is.
     *
     * Folded at half and double time before it is measured: a 174 BPM drum and bass
     * track against an 87 BPM half-time one is the same pulse, and a radio that
     * treats those as opposites would refuse the most natural transition in the set.
     */
    private fun tempoScore(seed: DjProfile, candidate: DjProfile): Float? {
        if (seed.bpm > 0f && candidate.bpm > 0f) {
            var ratio = candidate.bpm / seed.bpm
            while (ratio > 1.45f) ratio /= 2f
            while (ratio < 0.69f) ratio *= 2f
            return (1f - abs(ratio - 1f) / BPM_SPAN).coerceIn(0f, 1f)
        }
        val a = seed.pace ?: return null
        val b = candidate.pace ?: return null
        return (1f - abs(a - b) * 2f).coerceIn(0f, 1f)
    }

    /**
     * How much two genre tag sets have in common, 0..1.
     *
     * A plain Jaccard index over the tokens, with one adjustment: a full overlap in
     * *either* direction scores 1. "Rock" and "Alternative Rock" is the library
     * being terser about one track than the other, not a disagreement about what the
     * two records are, and Jaccard alone would score that 0.5.
     */
    fun genreOverlap(a: List<String>, b: List<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 0.5f     // nothing known either way
        if (a.isEmpty() || b.isEmpty()) return 0.35f    // one side untagged: not evidence against
        val sa = a.toSet()
        val sb = b.toSet()
        val shared = sa.count { it in sb }
        if (shared == 0) return 0f
        if (shared == min(sa.size, sb.size)) return 1f
        return shared.toFloat() / (sa.size + sb.size - shared)
    }

    private fun pair(a: Float?, b: Float?): Pair<Float, Float>? =
        if (a != null && b != null) a to b else null

    /**
     * The score a candidate has to reach at strictness [strictness] (0 loose ..
     * 1 tight).
     *
     * The floor is deliberately not 0: even "surprise me" should not put a spoken
     * word record after a house track when there is anything else on the shelf.
     */
    fun thresholdFor(strictness: Float): Float =
        0.30f + 0.55f * strictness.coerceIn(0f, 1f)
}

/**
 * Picks the running order for DJ Radio.
 *
 * Separate from [DjSimilarity] because a *set* is more than a pile of good pairs.
 * Two things this does that scoring one candidate cannot:
 *
 *  - **It chains.** Each pick is scored against the track before it, not against the
 *    one track the listener started from. That is what lets a set drift somewhere
 *    over an hour while every single transition stays close, which is the whole
 *    difference between a DJ set and a filtered playlist.
 *  - **It refuses to stall.** If nothing clears the threshold, the bar comes down a
 *    step at a time until something does. A radio that goes quiet because it could
 *    not find a perfect match has failed at the only job it has.
 *
 * Stateless: the caller owns the history (see [LocalRadio]), so this stays testable
 * and so two callers cannot fight over one rolling window.
 */
object DjSetBuilder {

    /** How far the bar drops each time nothing clears it. */
    private const val RELAX_STEP = 0.12f

    /**
     * Consecutive picks by one artist allowed. Two in a row reads as a DJ making a
     * point; four reads as a broken shuffle.
     */
    const val ARTIST_RUN_LIMIT = 2

    /**
     * Picks that must pass after a run of [ARTIST_RUN_LIMIT] before that artist may
     * come back.
     *
     * A hard rule, not a penalty: a penalty lost to an artist who matched
     * themselves too well, and the set kept coming back to them. It gives way only
     * when every candidate left is blocked, because on a tiny library refusing
     * means refusing to play.
     */
    const val ARTIST_COOLDOWN = 4

    /** How many recent artists the rule needs to see. */
    const val ARTIST_MEMORY = ARTIST_RUN_LIMIT + ARTIST_COOLDOWN

    /**
     * How much of the similarity a pick owes to the track the *set* started from,
     * rather than to the one just before it.
     *
     * Chained alone, every transition stays close and the set still wanders off: an
     * hour of small steps from a house record can end in drum and bass. A quarter is
     * enough to keep it within reach of the seed while leaving the chain in charge
     * of each transition.
     */
    private const val ANCHOR_WEIGHT = 0.25f

    /**
     * How much of a pick's score a [DjMood] is allowed to be worth.
     *
     * A third, not all of it. A mood is a brief for the *set* — "keep it slow and
     * dark" — and the thing that makes a set a set rather than a filtered playlist
     * is still that each track follows the one before it. Weighted higher, the
     * builder simply plays the library in order of mood fit and every transition
     * inside it is a coin toss; weighted lower, the brief is decoration. At a third
     * the mood decides which corner of the library the set lives in and similarity
     * decides the running order inside it, which is the division of labour
     * [DjMood] is written around.
     */
    private const val MOOD_WEIGHT = 0.34f

    /**
     * Up to [count] tracks to play after [seed], best-first and in playing order.
     *
     * [pool] is whatever the ladder in [LocalRadio] managed to find; [profileOf]
     * resolves each one, which is where the caller gets to do the (suspending, disk-
     * touching) work of finding its scan. [recentArtists] is what has just played,
     * newest last, so a run that started in the previous batch is not restarted here.
     */
    fun build(
        seed: DjProfile?,
        pool: List<MaItem>,
        profileOf: (MaItem) -> DjProfile,
        count: Int,
        strictness: Float,
        harmonic: Boolean,
        recentArtists: List<String> = emptyList(),
        /**
         * The brief the whole set is being held to, or null for "follow the seed",
         * which is what this has always done. See [DjMood].
         */
        mood: DjMood? = null,
        /** The set's first seed, held to by [ANCHOR_WEIGHT]; null to chain alone. */
        anchor: DjProfile? = null,
        /** Added to a candidate's score — the listener's skips and favourites. See [RadioMemory.adjust]. */
        adjust: (MaItem) -> Float = { 0f },
    ): List<MaItem> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        // By id, not by item: the ladder can hand back the same track under two
        // rungs, and a map keyed on the item itself would silently collapse those
        // two entries into one while the list still held both.
        val unique = pool.distinctBy { it.itemId }
        val profiles = unique.associate { it.itemId to profileOf(it) }
        val remaining = unique.toMutableList()
        val picked = ArrayList<MaItem>(count)
        val artistRun = ArrayList(recentArtists)
        // A null seed — the very first track of a set started from a cold library —
        // is carried as null rather than substituted for. There is nothing to be
        // similar *to* yet, [choose] says so by taking the first candidate, and from
        // the second pick on the set has a real reference of its own.
        var current = seed

        while (picked.size < count && remaining.isNotEmpty()) {
            val next = choose(current, remaining, profiles, strictness, harmonic, artistRun, mood, anchor, adjust) ?: break
            remaining.remove(next)
            picked.add(next)
            val profile = profiles.getValue(next.itemId)
            current = profile
            profile.artist?.let { artistRun.add(it) }
        }
        return picked
    }

    /**
     * The best of [remaining] to follow [current], or null if even a fully relaxed
     * bar finds nothing — which can only happen on an empty list.
     */
    private fun choose(
        current: DjProfile?,
        remaining: List<MaItem>,
        profiles: Map<String, DjProfile>,
        strictness: Float,
        harmonic: Boolean,
        artistRun: List<String>,
        mood: DjMood?,
        anchor: DjProfile?,
        adjust: (MaItem) -> Float,
    ): MaItem? {
        // Null unless there is a brief with something to say — [DjMood.ANYTHING] is
        // the listener declining to give one, and folding it away here means the
        // whole of the rest of this method is the code that ran before moods
        // existed, unchanged.
        val brief = mood?.takeIf { !it.open }
        // The artist rule before any scoring, waived only when it would leave nothing.
        val allowed = remaining
            .filterNot { artistBlocked(profiles.getValue(it.itemId).artist, artistRun) }
            .ifEmpty { remaining }
        // With no track to be similar *to* yet, the brief is the only thing there is
        // to answer — so the opener is the best answer to it rather than whatever
        // the pool handed back first.
        if (current == null) {
            if (brief == null) return allowed.firstOrNull()
            return allowed.maxByOrNull { brief.fit(profiles.getValue(it.itemId)) }
        }
        val ranked = allowed
            .map { item ->
                val profile = profiles.getValue(item.itemId)
                var s = DjSimilarity.score(current, profile, harmonic)
                if (anchor != null) {
                    s = (1f - ANCHOR_WEIGHT) * s + ANCHOR_WEIGHT * DjSimilarity.score(anchor, profile, harmonic)
                }
                // The mood takes its third of the whole, so chain, anchor and mood
                // still sum to one: 0.495, 0.165 and 0.34.
                if (brief != null) {
                    s = (1f - MOOD_WEIGHT) * s + MOOD_WEIGHT * brief.fit(profile)
                }
                item to s + adjust(item)
            }
            .sortedByDescending { it.second }

        var bar = DjSimilarity.thresholdFor(strictness)
        while (bar > 0f) {
            ranked.firstOrNull { it.second >= bar }?.let { return it.first }
            bar -= RELAX_STEP
        }
        return ranked.firstOrNull()?.first
    }

    /**
     * [artist] may not go next: they have had [ARTIST_RUN_LIMIT] in a row in [run]
     * (newest last), and fewer than [ARTIST_COOLDOWN] picks have followed.
     */
    fun artistBlocked(artist: String?, run: List<String>): Boolean {
        if (artist == null) return false
        var streak = 0
        for ((i, a) in run.withIndex()) {
            streak = if (a.equals(artist, ignoreCase = true)) streak + 1 else 0
            if (streak >= ARTIST_RUN_LIMIT && run.lastIndex - i < ARTIST_COOLDOWN) return true
        }
        return false
    }

    /**
     * How wide a net to cast for [count] picks at [strictness].
     *
     * A tight set needs more to choose from, not less: the bar rejects more of what
     * comes back, and the answer to that is a bigger pool rather than a lower bar.
     * Capped because every extra candidate is a scan looked up on disk.
     */
    fun poolSizeFor(count: Int, strictness: Float): Int =
        min(120, max(count * 4, (count * (4 + 8 * strictness.coerceIn(0f, 1f))).toInt()))
}
