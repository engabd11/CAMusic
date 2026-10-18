package com.engabd.sendpin.ma

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * TTML (the Apple-style `<p begin end><span begin end>` kind) to enhanced LRC text.
 *
 * Producing LRC text rather than a list keeps one path through the app: [MaLyrics] only
 * ever holds LRC, the sidecar writer saves it, and [LrcParser] reads word stamps the
 * same way whether they came from an LRC file or from here. A span with
 * `ttm:role="x-bg"` becomes a `[bg: ...]` line.
 *
 * Span times are taken as absolute (what Apple and most tools write). If a span starts
 * before its paragraph does, it is read as relative to the paragraph, as the TTML
 * spec has it.
 */
object Ttml {

    private const val TTM_NS = "http://www.w3.org/ns/ttml#metadata"

    /** True when [text] looks like a TTML document rather than LRC or plain lyrics. */
    fun looksLikeTtml(text: String): Boolean {
        val head = text.trimStart().take(400).lowercase()
        return head.startsWith("<tt") || (head.startsWith("<?xml") && "<tt" in head)
    }

    /** Enhanced LRC for [xml], or null if it is not usable TTML. Never throws. */
    fun toEnhancedLrc(xml: String): String? = runCatching { convert(xml) }.getOrNull()

    private class Word(val begin: Long, val end: Long, var text: String)

    private fun convert(xml: String): String? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Lyrics are never allowed to pull in a DTD or an external entity.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        if (paragraphs.length == 0) return null

        val out = StringBuilder()
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as Element
            val begin = parseTime(p.getAttribute("begin")) ?: continue
            val end = parseTime(p.getAttribute("end"))

            val main = mutableListOf<Word>()
            val bg = mutableListOf<Word>()
            collect(p, begin, main, bg, inBackground = false)

            if (main.isEmpty() && bg.isEmpty()) {
                val plain = p.textContent.trim().replace(Regex("\\s+"), " ")
                if (plain.isNotEmpty()) out.append(stamp(begin)).append(plain).append('\n')
                continue
            }
            if (main.isNotEmpty()) appendLine(out, begin, main, end, background = false)
            if (bg.isNotEmpty()) appendLine(out, bg.first().begin, bg, end, background = true)
        }
        return out.toString().trimEnd().takeIf { it.isNotEmpty() }
    }

    private fun collect(node: Element, pBegin: Long, main: MutableList<Word>, bg: MutableList<Word>, inBackground: Boolean) {
        val target = if (inBackground) bg else main
        var child: Node? = node.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE -> {
                    val t = child.nodeValue.orEmpty()
                    if (t.isNotEmpty() && t.isBlank()) {
                        // Whitespace between two spans is the space between two words.
                        target.lastOrNull()?.let { if (!it.text.endsWith(" ")) it.text += " " }
                    }
                }
                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    if (el.localName == "br") {
                        target.lastOrNull()?.let { if (!it.text.endsWith(" ")) it.text += " " }
                    } else if (el.localName == "span") {
                        val role = el.getAttributeNS(TTM_NS, "role").ifEmpty { el.getAttribute("ttm:role") }
                        if (hasSpanChild(el)) {
                            collect(el, pBegin, main, bg, inBackground || role == "x-bg")
                        } else {
                            val into = if (inBackground || role == "x-bg") bg else main
                            val b = parseTime(el.getAttribute("begin"))
                            val e = parseTime(el.getAttribute("end"))
                            val text = el.textContent.replace(Regex("\\s+"), " ")
                            if (b != null && text.isNotEmpty()) {
                                // Absolute unless it would start before its paragraph.
                                val shift = if (pBegin > 0 && b < pBegin) pBegin else 0L
                                val start = b + shift
                                into += Word(start, maxOf(start, (e ?: start) + shift), text)
                            } else if (text.isNotBlank()) {
                                into += Word(pBegin, pBegin, text)
                            }
                        }
                    }
                }
            }
            child = child.nextSibling
        }
    }

    private fun hasSpanChild(el: Element): Boolean {
        var c: Node? = el.firstChild
        while (c != null) {
            if (c.nodeType == Node.ELEMENT_NODE && (c as Element).localName == "span") return true
            c = c.nextSibling
        }
        return false
    }

    private fun appendLine(out: StringBuilder, at: Long, words: List<Word>, paragraphEnd: Long?, background: Boolean) {
        val body = StringBuilder()
        for (w in words) body.append(stamp(w.begin, '<', '>')).append(w.text)
        val last = words.last()
        val closing = if (last.end > last.begin) last.end else paragraphEnd ?: last.begin
        body.trimEndSpaces()
        body.append(stamp(maxOf(closing, last.begin), '<', '>'))
        out.append(stamp(at))
        if (background) out.append("[bg:").append(body).append(']') else out.append(body)
        out.append('\n')
    }

    private fun StringBuilder.trimEndSpaces() {
        while (isNotEmpty() && this[length - 1] == ' ') setLength(length - 1)
    }

    private fun stamp(ms: Long, open: Char = '[', close: Char = ']'): String {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1_000
        val f = ms % 1_000
        return "$open$m:${s.toString().padStart(2, '0')}.${f.toString().padStart(3, '0')}$close"
    }

    /** TTML clock and offset times: `1:02.5`, `00:01:02.500`, `12.5s`, `1500ms`. */
    internal fun parseTime(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return runCatching {
            when {
                t.endsWith("ms") -> t.removeSuffix("ms").toDouble().toLong()
                t.endsWith("s") -> (t.removeSuffix("s").toDouble() * 1000).toLong()
                t.endsWith("m") -> (t.removeSuffix("m").toDouble() * 60_000).toLong()
                t.endsWith("h") -> (t.removeSuffix("h").toDouble() * 3_600_000).toLong()
                ':' in t -> {
                    val parts = t.split(':')
                    var total = 0.0
                    for (part in parts) total = total * 60 + part.toDouble()
                    (total * 1000).toLong()
                }
                else -> (t.toDouble() * 1000).toLong()
            }
        }.getOrNull()
    }
}
