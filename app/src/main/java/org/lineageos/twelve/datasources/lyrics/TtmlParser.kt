/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.lyrics

import org.lineageos.twelve.models.Lyrics
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Reads lyrics written as TTML, where every line may carry the time of each of its words and
 * background vocals live inside their own span.
 */
object TtmlParser {
    fun parse(ttml: String): Lyrics? = runCatching {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Lyrics come from the network, no external entities
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(InputSource(StringReader(ttml)))

        val agentTypes = mutableMapOf<String, String>()
        document.getElementsByTagName("ttm:agent").let { agents ->
            for (i in 0 until agents.length) {
                (agents.item(i) as? Element)?.let { agent ->
                    agent.getAttribute("xml:id").takeIf { it.isNotEmpty() }?.let {
                        agentTypes[it] = agent.getAttribute("type")
                    }
                }
            }
        }

        var leadAgent: String? = null
        val lines = mutableListOf<Lyrics.Line>()

        val paragraphs = document.getElementsByTagName("p")
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue

            val agent = paragraph.getAttribute("ttm:agent").takeIf { it.isNotEmpty() }
            val isGroup = agent != null && agentTypes[agent] == "group"
            if (leadAgent == null && agent != null && !isGroup) {
                leadAgent = agent
            }
            val isOpposite = agent != null && !isGroup && agent != leadAgent

            val main = mutableListOf<Lyrics.Word>()
            val background = mutableListOf<Lyrics.Word>()
            collectWords(paragraph, main, background, insideBackground = false)

            val paragraphStart = parseTime(paragraph.getAttribute("begin"))
            val paragraphEnd = parseTime(paragraph.getAttribute("end"))

            if (main.isNotEmpty()) {
                lines += buildLine(main, paragraphStart, paragraphEnd, false, isOpposite)
            } else if (background.isEmpty()) {
                // No spans, the line is synchronized as a whole
                val text = paragraph.textContent.replace(WHITESPACE, " ").trim()
                if (text.isNotEmpty()) {
                    lines += Lyrics.Line(
                        text = text,
                        durationMs = paragraphStart?.let { it..(paragraphEnd ?: it) },
                        isOpposite = isOpposite,
                    )
                }
            }

            if (background.isNotEmpty()) {
                lines += buildLine(background, null, null, true, isOpposite)
            }
        }

        lines.takeIf { it.isNotEmpty() }?.let { found ->
            // Main lines first when they start together, otherwise by time
            Lyrics(found.withIndex().sortedWith(
                compareBy({ it.value.durationMs?.first ?: Long.MAX_VALUE }, { it.index })
            ).map { it.value })
        }
    }.getOrNull()

    private fun buildLine(
        words: List<Lyrics.Word>,
        paragraphStart: Long?,
        paragraphEnd: Long?,
        isBackground: Boolean,
        isOpposite: Boolean,
    ): Lyrics.Line {
        val cleaned = words.toMutableList()

        if (isBackground) {
            // Background vocals come wrapped in parentheses
            cleaned.firstOrNull()?.let {
                cleaned[0] = it.copy(text = it.text.trimStart().removePrefix("("))
            }
            cleaned.lastOrNull()?.let {
                cleaned[cleaned.lastIndex] = it.copy(text = it.text.trimEnd().removeSuffix(")"))
            }
        }

        cleaned.lastOrNull()?.let {
            cleaned[cleaned.lastIndex] = it.copy(text = it.text.trimEnd())
        }

        val start = cleaned.first().startMs.let { first ->
            when (isBackground) {
                true -> first
                false -> minOf(first, paragraphStart ?: first)
            }
        }
        val end = cleaned.maxOf { it.endMs }.let { last ->
            when (isBackground) {
                true -> last
                false -> maxOf(last, paragraphEnd ?: last)
            }
        }

        return Lyrics.Line(
            text = cleaned.joinToString("") { it.text },
            durationMs = start..end,
            words = cleaned,
            isBackground = isBackground,
            isOpposite = isOpposite,
        )
    }

    private fun collectWords(
        parent: Element,
        main: MutableList<Lyrics.Word>,
        background: MutableList<Lyrics.Word>,
        insideBackground: Boolean,
    ) {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)

            when (child.nodeType) {
                Node.TEXT_NODE -> {
                    // Space between two spans belongs to the word before it
                    val text = child.nodeValue.orEmpty().replace(WHITESPACE, " ")
                    val target = if (insideBackground) background else main
                    if (text.isNotEmpty() && target.isNotEmpty()) {
                        target[target.lastIndex] = target.last().let { it.copy(text = it.text + text) }
                    }
                }

                Node.ELEMENT_NODE -> {
                    val span = child as Element
                    if (span.tagName != "span") {
                        continue
                    }

                    when (span.getAttribute("ttm:role")) {
                        "x-bg" -> collectWords(span, main, background, insideBackground = true)
                        "x-translation", "x-roman" -> Unit
                        else -> {
                            val start = parseTime(span.getAttribute("begin"))
                            val end = parseTime(span.getAttribute("end"))
                            val hasChildSpans = (0 until span.childNodes.length).any {
                                (span.childNodes.item(it) as? Element)?.tagName == "span"
                            }

                            when {
                                hasChildSpans ->
                                    collectWords(span, main, background, insideBackground)

                                start != null && end != null -> {
                                    val text = span.textContent.orEmpty()
                                        .replace(WHITESPACE, " ")
                                    if (text.isNotBlank()) {
                                        (if (insideBackground) background else main)
                                            .add(Lyrics.Word(text, start, maxOf(end, start)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Clock time ("1:02.5", "01:02:03.250") or offset time ("12.5s", "340ms", "12.5") to
     * milliseconds.
     */
    fun parseTime(value: String): Long? {
        val raw = value.trim()
        if (raw.isEmpty()) {
            return null
        }

        return runCatching {
            when {
                raw.endsWith("ms") -> raw.removeSuffix("ms").toDouble().toLong()
                raw.endsWith("s") -> (raw.removeSuffix("s").toDouble() * 1000).toLong()
                raw.contains(':') -> raw.split(':').fold(0.0) { total, part ->
                    total * 60 + part.toDouble()
                }.let { (it * 1000).toLong() }

                else -> (raw.toDouble() * 1000).toLong()
            }
        }.getOrNull()
    }

    private val WHITESPACE = Regex("\\s+")
}
