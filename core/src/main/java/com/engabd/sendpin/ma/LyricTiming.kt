package com.engabd.sendpin.ma

/**
 * The pure arithmetic behind word-by-word lyrics: estimating word times for a line the
 * source only timed as a whole, and working out how much of a line has been sung.
 * Kept free of UI so it can be tested without a screen.
 */
object LyricTiming {

    /** No estimated word holds longer than this, however long the line has to last. */
    const val MAX_WORD_MS = 1_200L

    /** A line is assumed to take about this long per character to sing, at most. */
    private const val MAX_MS_PER_CHAR = 200L
    private const val MIN_LINE_MS = 600L
    private const val LAST_LINE_MS = 4_000L

    private val TOKEN = Regex("""\S+\s*""")

    /**
     * [lines] with word times worked out for every line that has none.
     *
     * Words share the span up to the next line in proportion to their length. The span
     * is capped by how long the text could plausibly take to sing, so a short line
     * before an instrumental break does not crawl across ten seconds.
     */
    fun withEstimatedWords(lines: List<LyricLine>): List<LyricLine> =
        lines.mapIndexed { i, line ->
            if (line.words != null || line.text.isBlank()) return@mapIndexed line
            val next = (i + 1 until lines.size).firstOrNull { lines[it].atMs > line.atMs }?.let { lines[it].atMs }
            val words = estimateWords(line.text, line.atMs, next) ?: return@mapIndexed line
            line.copy(words = words)
        }

    /** Split [text] and spread it from [atMs] up to [nextAtMs] (null: last line). */
    fun estimateWords(text: String, atMs: Long, nextAtMs: Long?): List<LyricWord>? {
        val tokens = tokenise(text.trim())
        if (tokens.isEmpty()) return null
        val chars = tokens.sumOf { it.length }
        val gap = if (nextAtMs != null) (nextAtMs - atMs).coerceAtLeast(0L) else LAST_LINE_MS
        val span = minOf(gap, maxOf(MIN_LINE_MS, chars * MAX_MS_PER_CHAR))
        if (span <= 0L) return null
        var cum = 0
        return tokens.map { tok ->
            val start = atMs + span * cum / chars
            cum += tok.length
            val share = span * tok.length / chars
            LyricWord(start, start + minOf(share, MAX_WORD_MS), tok, estimated = true)
        }
    }

    /** Words with their trailing space; each CJK character stands alone. */
    internal fun tokenise(text: String): List<String> {
        val out = mutableListOf<String>()
        for (m in TOKEN.findAll(text)) {
            val chunk = m.value
            val bare = chunk.trimEnd()
            val trailing = chunk.substring(bare.length)
            val pieces = mutableListOf<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < bare.length) {
                val cp = bare.codePointAt(i)
                val n = Character.charCount(cp)
                if (isCjk(cp)) {
                    if (cur.isNotEmpty()) { pieces += cur.toString(); cur.clear() }
                    pieces += bare.substring(i, i + n)
                } else cur.append(bare, i, i + n)
                i += n
            }
            if (cur.isNotEmpty()) pieces += cur.toString()
            if (pieces.isEmpty()) continue
            pieces[pieces.lastIndex] = pieces.last() + trailing
            out += pieces
        }
        return out
    }

    private fun isCjk(cp: Int): Boolean =
        cp in 0x3040..0x30FF || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
            cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF

    /** Index of the word being sung at [headMs]: the last one that has started, or -1. */
    fun activeWordIndex(line: LyricLine, headMs: Long): Int {
        val words = line.words ?: return -1
        return words.indexOfLast { it.atMs <= headMs }
    }

    /** 0..1 through [word] at [headMs]. */
    fun wordProgress(word: LyricWord, headMs: Long): Float {
        val span = word.endMs - word.atMs
        if (headMs <= word.atMs) return 0f
        if (span <= 0L || headMs >= word.endMs) return 1f
        return (headMs - word.atMs).toFloat() / span
    }

    /**
     * How far through [LyricLine.text] the singer has got at [headMs], as a fractional
     * character index. Whole words before the current one count in full; the current
     * one counts by its progress; the gap between two words holds at the end of the
     * earlier one. Trailing spaces are not part of a word's width.
     */
    fun sungChars(line: LyricLine, headMs: Long, stepByWord: Boolean = false): Float {
        val words = line.words ?: return 0f
        var offset = 0
        var pos = 0f
        for (w in words) {
            val len = w.text.trimEnd().length
            // Reduced motion: a word is either not yet sung or wholly sung, no wipe.
            if (headMs >= w.atMs) pos = offset + len * (if (stepByWord) 1f else wordProgress(w, headMs))
            offset += w.text.length
        }
        return pos
    }
}
