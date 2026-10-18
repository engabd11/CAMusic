package com.engabd.sendpin.ma

/**
 * LRC text to [LyricLine]s, including the enhanced ("A2") extension that stamps single
 * words: `[00:12.34]<00:12.34>Word <00:12.80>next <00:13.40>`.
 *
 * A stamp after the last word of a line, with nothing following it, says when that word
 * ends. Without one the last word runs to the next line (see [resolveOpenEnds]).
 * A line whose body is `[bg: ...]` is a backing vocal.
 *
 * Everything here is tolerant: a stamp that is out of order, a word with no text or a
 * line that mixes stamped and plain words never throws, it just loses the bit it cannot
 * place. Plain LRC (no `<>` stamps) comes back exactly as before, with `words == null`.
 */
internal object LrcParser {

    private val LRC_STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{2,3}))?]""")
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val BACKGROUND = Regex("""^\[bg:\s*(.*?)\s*]$""", RegexOption.IGNORE_CASE)

    /** How long a line's final word may hold when only the next line closes it. */
    const val MAX_TAIL_MS = 2_500L
    private const val NO_NEXT_TAIL_MS = 1_500L
    private const val OPEN = -1L

    fun parse(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val stamps = LRC_STAMP.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            var body = raw.substring(stamps.last().range.last + 1)
            var background = false
            BACKGROUND.matchEntire(body.trim())?.let { body = it.groupValues[1]; background = true }
            val times = stamps.map { stampMs(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            val parsed = parseBody(body, times.first())
            for (t in times) {
                // A repeated chorus is one body at several stamps; the word stamps are
                // written against the first, so the others slide by the difference.
                val shift = t - times.first()
                val words = parsed.second?.map {
                    it.copy(atMs = it.atMs + shift, endMs = if (it.endMs == OPEN) OPEN else it.endMs + shift)
                }
                out += LyricLine(t, parsed.first, words, background)
            }
        }
        return resolveOpenEnds(out.sortedBy { it.atMs })
    }

    private fun stampMs(mm: String, ss: String, frac: String): Long {
        // The fraction is 2 or 3 digits (centi- or milliseconds); word stamps may have 1.
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.padEnd(3, '0').take(3).toLong()
        }
        return mm.toLong() * 60_000 + ss.toLong() * 1_000 + fracMs
    }

    private fun parseBody(body: String, lineAt: Long): Pair<String, List<LyricWord>?> {
        val stamps = WORD_STAMP.findAll(body).toList()
        if (stamps.isEmpty()) return body.trim() to null
        val words = mutableListOf<LyricWord>()
        val times = stamps.map { stampMs(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

        // Words before the first stamp belong to the line start.
        val lead = body.substring(0, stamps[0].range.first)
        if (lead.isNotBlank()) words += LyricWord(lineAt, maxOf(lineAt, times[0]), lead)

        for (i in stamps.indices) {
            val segEnd = if (i + 1 < stamps.size) stamps[i + 1].range.first else body.length
            val seg = body.substring(stamps[i].range.last + 1, segEnd)
            if (seg.isBlank()) {
                // A bare space between two stamps still separates the words around it.
                if (seg.isNotEmpty() && words.isNotEmpty() && !words.last().text.endsWith(' ')) {
                    words[words.lastIndex] = words.last().copy(text = words.last().text + " ")
                }
                continue
            }
            val end = if (i + 1 < stamps.size) maxOf(times[i], times[i + 1]) else OPEN
            words += LyricWord(times[i], end, seg)
        }
        if (words.isEmpty()) return "" to null
        words[0] = words[0].copy(text = words[0].text.trimStart())
        words[words.lastIndex] = words.last().copy(text = words.last().text.trimEnd())
        val text = words.joinToString("") { it.text }.trim()
        if (text.isEmpty()) return "" to null
        return text to words
    }

    /** Close the last word of each line: the next line's start, but never absurdly long. */
    fun resolveOpenEnds(lines: List<LyricLine>): List<LyricLine> =
        lines.mapIndexed { i, line ->
            val words = line.words
            if (words == null || words.last().endMs != OPEN) return@mapIndexed line
            val last = words.last()
            val next = (i + 1 until lines.size).firstOrNull { lines[it].atMs > last.atMs }?.let { lines[it].atMs }
            val end = if (next != null) minOf(next, last.atMs + MAX_TAIL_MS) else last.atMs + NO_NEXT_TAIL_MS
            line.copy(words = words.dropLast(1) + last.copy(endMs = maxOf(end, last.atMs)))
        }
}
