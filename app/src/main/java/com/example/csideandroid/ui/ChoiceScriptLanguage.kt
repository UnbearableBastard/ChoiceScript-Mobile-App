package com.example.csideandroid.ui

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

class ChoiceScriptLanguage(
    private var commands: List<String>,
    private var themeColors: EditorThemeColors? = null
) : EmptyLanguage() {

    private val customColorBaseId: Int = 512
    private var commandColorIds: Map<String, Int> = emptyMap()

    @Suppress("unused")
    fun setThemeColors(colors: EditorThemeColors?) {
        themeColors = colors
        commandColorIds = buildCommandColorIds(colors)
        analyzeManager.rerun()
    }

    private fun buildCommandColorIds(colors: EditorThemeColors?): Map<String, Int> {
        val map = colors?.commandColors ?: return emptyMap()
        val keys = map.keys.map { it.lowercase() }.distinct().sorted()
        val out = HashMap<String, Int>(keys.size)
        for ((idx, key) in keys.withIndex()) {
            out[key] = customColorBaseId + idx
        }
        return out
    }

    private inner class ChoiceScriptAnalyzer : SimpleAnalyzeManager<Any>() {

        override fun analyze(text: StringBuilder, delegate: Delegate<Any>): Styles {
            val colors = themeColors
            val optionColor = colors?.optionColor
            val inlineVarColor = colors?.inlineVarColor

            val builder = MappedSpans.Builder(128)

            val lines = text.toString().split('\n')
            for (lineIndex in lines.indices) {
                val line = lines[lineIndex]
                // Always start each line as normal text
                builder.addIfNeeded(lineIndex, 0, EditorColorScheme.TEXT_NORMAL.toLong())

                // Highlight leading ChoiceScript commands: *command
                run {
                    var i = 0
                    while (i < line.length && line[i].isWhitespace()) i++
                    if (i < line.length && line[i] == '*') {
                        val start = i
                        var j = i + 1
                        while (j < line.length) {
                            val ch = line[j]
                            if (!(ch.isLetterOrDigit() || ch == '_' || ch == '-')) break
                            j++
                        }
                        val cmdName = line.substring((i + 1).coerceAtMost(line.length), j)
                        val cmdKey = cmdName.lowercase()
                        val style: Long = (commandColorIds[cmdKey] ?: EditorColorScheme.KEYWORD).toLong()
                        builder.addIfNeeded(lineIndex, start, style)
                        builder.addIfNeeded(lineIndex, j, EditorColorScheme.TEXT_NORMAL.toLong())
                    }
                }

                // Highlight Choice options: #option
                run {
                    var i = 0
                    while (i < line.length && line[i].isWhitespace()) i++
                    if (i < line.length && line[i] == '#') {
                        val style: Long = if (optionColor != null) EditorColorScheme.ANNOTATION.toLong()
                        else EditorColorScheme.ANNOTATION.toLong()
                        builder.addIfNeeded(lineIndex, i, style)
                        // keep until end of line
                    }
                }

                // Highlight inline variables: ${var} or $!{var}
                run {
                    if (inlineVarColor != null) {
                        var searchFrom = 0
                        while (true) {
                            // Look for either "${" or "$!{" patterns
                            val braceStart = line.indexOf("\${", searchFrom)
                            val capStart = line.indexOf("\$!{", searchFrom)
                            val start = when {
                                braceStart == -1 && capStart == -1 -> -1
                                braceStart == -1 -> capStart
                                capStart == -1 -> braceStart
                                else -> kotlin.math.min(braceStart, capStart)
                            }
                            if (start == -1) break
                            // Determine offset: 2 for \${, 3 for \$!{
                            val offset = if (start + 1 < line.length && line[start + 1] == '!') 3 else 2
                            val end = line.indexOf('}', start + offset)
                            if (end == -1) break
                            builder.addIfNeeded(lineIndex, start, EditorColorScheme.LITERAL.toLong())
                            builder.addIfNeeded(lineIndex, end + 1, EditorColorScheme.TEXT_NORMAL.toLong())
                            searchFrom = end + 1
                        }
                    }
                }
            }

            builder.addNormalIfNull()
            builder.determine((lines.size - 1).coerceAtLeast(0))
            return Styles(builder.build())
        }
    }

    private val analyzeManager: AnalyzeManager = ChoiceScriptAnalyzer()

    override fun getAnalyzeManager(): AnalyzeManager = analyzeManager

    fun setCommands(newCommands: List<String>) {
        commands = newCommands
    }

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        val lineNumber = position.line
        val column = position.column
        // There must be at least one character on the line before the cursor
        if (column == 0) return
        val line = content.getLine(lineNumber)
        // Find the position of the last '*' before the cursor
        val starIndex = line.lastIndexOf('*', column - 1)
        if (starIndex < 0) return
        // Ensure the star starts a command, only allow whitespace before it
        for (i in 0 until starIndex) {
            val ch = line[i]
            if (!ch.isWhitespace()) {
                return
            }
        }
        // Extract the already typed portion after '*'
        val prefixStart = starIndex + 1
        if (prefixStart > column) return
        val typedPrefix = line.substring(prefixStart, column)
        val p = typedPrefix.lowercase()

        val matches = mutableListOf<SimpleCompletionItem>()
        for (cmd in commands) {
            if (p.isEmpty() || cmd.startsWith(p)) {
                val item = SimpleCompletionItem("*$cmd", p.length, cmd)
                matches.add(item)
                if (matches.size >= 200) break
            }
        }
        // Sort matches alphabetically to provide deterministic ordering
        matches.sortBy { it.label.toString() }
        if (matches.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            publisher.addItems(
                matches as MutableCollection<io.github.rosemoe.sora.lang.completion.CompletionItem>
            )
        }
    }
}