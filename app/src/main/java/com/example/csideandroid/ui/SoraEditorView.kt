package com.example.csideandroid.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListPopupWindow
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeEclipse
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.schemes.SchemeNotepadXX
import io.github.rosemoe.sora.widget.schemes.SchemeVS2019
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

class SoraEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {

    private val editor: CodeEditor = CodeEditor(context)
    private var language: ChoiceScriptLanguage = ChoiceScriptLanguage(emptyList())

    // Commands used for filtering the popup
    private var commandsList: List<String> = emptyList()

    // Auto indent state
    private var isApplyingAutoIndent: Boolean = false


    // Keep the last query active so Prev/Next advances through results
    private var activeSearchQuery: String? = null
    private val activeSearchOptions = EditorSearcher.SearchOptions(false, false)
    private val popupAnchor: View = View(context).apply {
        layoutParams = LayoutParams(1, 1)
        visibility = View.INVISIBLE
    }

    private val popupAdapter: ArrayAdapter<String> = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, mutableListOf()) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val tv = view.findViewById<android.widget.TextView>(android.R.id.text1)
            tv.setTextColor(android.graphics.Color.WHITE)
            return view
        }
    }

    private val commandPopup: ListPopupWindow = ListPopupWindow(context).apply {
        anchorView = popupAnchor
        setAdapter(popupAdapter)
        isModal = false
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#424242")))

        // Small scrollable square
        width = dp(220)
        height = dp(200)

        // Helps avoid being hidden behind the keyboard
        inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED
        softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        setOnItemClickListener { _, _, position, _ ->
            val selected = popupAdapter.getItem(position) ?: return@setOnItemClickListener
            applySelectedCommand(selected)
            dismiss()
        }
    }

    init {
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(popupAnchor)
        editor.setColorScheme(SchemeDarcula())
        editor.isLineNumberEnabled = true
        editor.isHighlightCurrentLine = true
        editor.isWordwrap = true
        editor.props.symbolPairAutoCompletion = true
        editor.setEditorLanguage(language)

        // Enable auto-capitalization for sentences
        editor.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE

        // Disable Sora's built-in auto-completion popup to avoid double popup issue.
        editor.getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java).isEnabled = false

        // Update popup on each edit (typing/backspace/paste)
        editor.subscribeAlways(ContentChangeEvent::class.java) { event ->
            if (event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                commandPopup.dismiss()
                return@subscribeAlways
            }
            updateCommandPopup()

            // Auto-indent only for user-inserted newlines.
            val changedText = event.changedText.toString()
            if (!isApplyingAutoIndent && event.action == ContentChangeEvent.ACTION_INSERT) {
                val newlineCount = changedText.count { it == '\n' }
                val looksLikeSingleNewlineInsert = newlineCount == 1 &&
                        (changedText == "\n" || changedText == "\r\n" ||
                                changedText.startsWith("\n") || changedText.startsWith("\r\n"))

                if (looksLikeSingleNewlineInsert) {
                    // Post to ensure the cursor has moved to the next line in the underlying document
                    editor.post { autoIndentOnNewline() }
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
    private fun pxToSp(px: Float): Float = px / resources.displayMetrics.scaledDensity
    private val MIN_FONT_SP = 14f
    private val MAX_FONT_SP = 32f

    private fun updateCommandPopup() {
        if (commandsList.isEmpty()) {
            commandPopup.dismiss()
            return
        }

        // Cursor position
        val cursor = editor.cursor
        val line = cursor.leftLine
        val col = cursor.leftColumn

        val lineText = editor.text.getLine(line).toString()
        val safeCol = col.coerceIn(0, lineText.length)
        val beforeCursor = lineText.substring(0, safeCol)

        // FIX: Look for the last whitespace OR the last double quote
        val lastBoundary = maxOf(
            beforeCursor.lastIndexOf(' '),
            beforeCursor.lastIndexOf('\t'),
            beforeCursor.lastIndexOf('"') // Add this line
        )
        val token = beforeCursor.substring(lastBoundary + 1)

        // This check now works even if the boundary was a "
        if (!(token.startsWith("*") || token.startsWith("$") || token.startsWith("@"))) {
            commandPopup.dismiss()
            return
        }

        val isStar   = token.startsWith("*")
        val isDollar = token.startsWith("$")
        val isAt     = token.startsWith("@")

        val typed = token.substring(1).lowercase() // chars after trigger

        // Filter matches
        val matches: List<String> = if (isStar) {
            commandsList
                .asSequence()
                .filter { typed.isEmpty() || it.startsWith(typed) }
                .take(200)
                .map { "*$it" }
                .toList()
        } else if (isDollar) {
            val exprs = listOf("\${}", "\$!{}")
            exprs
                .asSequence()
                .filter { typed.isEmpty() || it.substring(1).startsWith(typed) }
                .toList()
        } else {
            // @ trigger — multireplace expressions
            val exprs = listOf("@{}", "@!{}")
            exprs
                .asSequence()
                .filter { typed.isEmpty() || it.substring(1).startsWith(typed) }
                .toList()
        }

        if (matches.isEmpty()) {
            commandPopup.dismiss()
            return
        }

        // Move popupAnchor to caret position
        var x: Float
        var yBottom: Float
        try {
            x = editor.getCharOffsetX(line, safeCol)
            yBottom = editor.getCharOffsetY(line, safeCol)
        } catch (_: Throwable) {
            // Fallback: approximate using row height
            x = 0f
            yBottom = (editor.getRowHeight() * line - editor.offsetY).toFloat()
        }

        val lp = popupAnchor.layoutParams as LayoutParams
        lp.leftMargin = x.toInt().coerceAtLeast(0)
        // Place popup slightly below the caret (bottom of char is yBottom)
        val rowHeight = try { editor.getRowHeight() } catch (_: Throwable) { dp(18) }
        lp.topMargin = (yBottom + (rowHeight * 0.15f)).toInt().coerceAtLeast(0)
        popupAnchor.layoutParams = lp

        // Refresh adapter contents
        popupAdapter.clear()
        popupAdapter.addAll(matches)
        popupAdapter.notifyDataSetChanged()

        // Ensure anchor is set (some devices can reset it)
        commandPopup.anchorView = popupAnchor

        if (!commandPopup.isShowing) {
            commandPopup.show()
        }
    }

    private fun countLeadingSpaces(s: String): Int {
        var i = 0
        while (i < s.length && s[i] == ' ') i++
        return i
    }

    private fun isBlockOpener(trimmedLine: String): Boolean {
        if (trimmedLine.startsWith("#")) return true
        if (!trimmedLine.startsWith("*")) return false
        // Command block openers: *choice, *fake_choice, *if, *elseif, *else, *selectable_if
        val afterStar = trimmedLine.substring(1).lowercase()
        return afterStar.startsWith("choice") ||
                afterStar.startsWith("fake_choice") ||
                afterStar.startsWith("if ") ||
                afterStar.startsWith("if\t") ||
                afterStar == "if" ||
                afterStar.startsWith("elseif ") ||
                afterStar.startsWith("elseif\t") ||
                afterStar == "elseif" ||
                afterStar.startsWith("else") ||
                afterStar.startsWith("selectable_if")
    }

    private fun isReturn(trimmedLine: String): Boolean {
        if (!trimmedLine.startsWith("*")) return false
        val afterStar = trimmedLine.substring(1).lowercase()
        return afterStar.startsWith("return ") ||
                afterStar.startsWith("return\t") ||
                afterStar == "return"
    }

    private fun autoIndentOnNewline() {
        if (isApplyingAutoIndent) return

        val cursor = editor.cursor
        val line = cursor.leftLine
        val col = cursor.leftColumn

        // Only auto-indent when on a fresh line
        if (line <= 0) return

        val prevLineText = editor.text.getLine(line - 1).toString()
        val prevTrimmed = prevLineText.trimStart()

        val targetIndent =
            if (isReturn(prevTrimmed)) 0
            else {
                val base = countLeadingSpaces(prevLineText)
                // Add 4 spaces if it's a block opener.
                base + if (isBlockOpener(prevTrimmed)) 4 else 0
            }

        // Current indentation on this line
        val currentLineText = editor.text.getLine(line).toString()
        val currentIndent = countLeadingSpaces(currentLineText)
        val textAfterIndent = currentLineText.substring(currentIndent)

        if (currentIndent == targetIndent && textAfterIndent.isEmpty()) return

        if (textAfterIndent.isNotEmpty()) {
        }

        isApplyingAutoIndent = true
        try {
            // Remove whatever indent is already there and insert the correct one
            editor.text.beginBatchEdit()
            editor.text.delete(line, 0, line, currentIndent)
            if (targetIndent > 0) {
                editor.text.insert(line, 0, " ".repeat(targetIndent))
            }
            // Move cursor to the end of the new indentation
            editor.setSelection(line, targetIndent)
            editor.text.endBatchEdit()
        } finally {
            isApplyingAutoIndent = false
        }
    }

    private fun applySelectedCommand(selectedWithStar: String) {
        val selected = selectedWithStar.removePrefix("*")
        val cursor = editor.cursor
        val line = cursor.leftLine
        val col = cursor.leftColumn

        val lineText = editor.text.getLine(line).toString()
        val safeCol = col.coerceIn(0, lineText.length)
        val beforeCursor = lineText.substring(0, safeCol)

        // Find the "current token" immediately before the cursor, but treat quotes and punctuation
        // as token boundaries too
        val lastDelim = run {
            var i = beforeCursor.length - 1
            while (i >= 0) {
                when (beforeCursor[i]) {
                    ' ', '\t', '"', '\'', '(', ')', '[', ']', '{', '}', ',', ';', ':', '=', '+', '-', '/', '\\', '<', '>', '|', '&', '!', '?', '\n', '\r' -> break
                }
                i--
            }
            i
        }

        val token = beforeCursor.substring((lastDelim + 1).coerceIn(0, beforeCursor.length))
        if (token.isEmpty()) return

        // Support any completion triggers you show in the popup (currently *-commands, $-expressions and @-multireplace),
        // even when they appear inside quotes.
        if (!(token.startsWith("*") || token.startsWith("$") || token.startsWith("@"))) return

        if (token.startsWith("*")) {
            val chosen = selectedWithStar.removePrefix("*")
            val already = token.substring(1) // what user typed after '*'
            val suffix = if (chosen.startsWith(already, ignoreCase = true)) {
                chosen.substring(already.length)
            } else {
                // If mismatch, insert full command text after *
                chosen
            }

            if (suffix.isNotEmpty()) {
                editor.insertText(suffix, suffix.length)
            }
        } else if (token.startsWith("$") || token.startsWith("@")) {
            val trigger = token[0].toString() // "$" or "@"
            val chosen = selectedWithStar // keep leading trigger char (e.g., ${} or @{})
            val chosenAfter = chosen.removePrefix(trigger)
            val alreadyAfter = token.substring(1) // what user typed after trigger
            val suffix = if (chosenAfter.startsWith(alreadyAfter, ignoreCase = true)) {
                chosenAfter.substring(alreadyAfter.length)
            } else {
                chosenAfter
            }

            if (suffix.isNotEmpty()) {
                editor.insertText(suffix, suffix.length)
            }
        }
    }

    fun setTextContent(text: String?) {
        editor.post { editor.setText(text ?: "") }
    }

    suspend fun getTextContent(): String = withContext(Dispatchers.Main) {
        editor.text.toString()
    }

    fun setCommands(commands: List<String>) {
        commandsList = commands
        language = ChoiceScriptLanguage(commands)
        editor.setEditorLanguage(language)
        // Update popup immediately if user already has * on screen
        editor.post { updateCommandPopup() }
    }

    fun setTheme(themeName: String) {
        val scheme: EditorColorScheme = when (themeName.lowercase()) {
            "darcula", "monokai", "dark", "monokai-dark" -> SchemeDarcula()
            "vs2019", "vs" -> SchemeVS2019()
            "eclipse" -> SchemeEclipse()
            "notepad", "notepadxx" -> SchemeNotepadXX()
            else -> SchemeGitHub()
        }
        editor.setColorScheme(scheme)
    }

    fun applyThemeColors(colors: EditorThemeColors) {
        // Keep in sync with ChoiceScriptLanguage.customColorBaseId
        val customColorBaseId = 512

        val scheme = object : EditorColorScheme() {
            init {
                setColor(WHOLE_BACKGROUND, colors.background)
                setColor(TEXT_NORMAL, colors.text)
                setColor(LINE_NUMBER, colors.lineNumber)
                setColor(LINE_NUMBER_BACKGROUND, colors.background)
                // Subtle current line highlight
                val currentLine = (colors.optionColor and 0x00FFFFFF) or (0x1F shl 24)
                setColor(CURRENT_LINE, currentLine)


                // This keeps selection visible in dark themes.
                val selectionBg = (colors.optionColor and 0x00FFFFFF) or (0x3D shl 24)

                fun setColorIfExists(fieldName: String, color: Int) {
                    try {
                        val f = EditorColorScheme::class.java.getField(fieldName)
                        val id = f.getInt(null)
                        setColor(id, color)
                    } catch (_: Throwable) {
                        // ignore
                    }
                }

                setColor(SELECTION_INSERT, selectionBg)
                setColor(SELECTION_HANDLE, colors.optionColor)

                // Common alternative names across versions
                setColorIfExists("SELECTION_BACKGROUND", selectionBg)
                setColorIfExists("SELECTION_BG", selectionBg)
                setColorIfExists("SELECTED_TEXT_BACKGROUND", selectionBg)
                setColorIfExists("SELECTION", selectionBg)

                // Syntax categories used by ChoiceScriptLanguage
                setColor(KEYWORD, colors.defaultCommandColor)
                setColor(ANNOTATION, colors.optionColor)
                setColor(LITERAL, colors.inlineVarColor)

                // Allocate custom IDs and map to actual colors
                val lowerMap = colors.commandColors.entries.associate { it.key.lowercase() to it.value }
                val keys = lowerMap.keys.distinct().sorted()
                for ((idx, key) in keys.withIndex()) {
                    val c = lowerMap[key] ?: continue
                    setColor(customColorBaseId + idx, c)
                }
            }
        }
        editor.setColorScheme(scheme)

        // Forward palette to language if it supports it.
        runCatching {
            val m = editor.editorLanguage.javaClass.getMethod("setThemeColors", EditorThemeColors::class.java)
            m.invoke(editor.editorLanguage, colors)
        }
        editor.invalidate()
    }

    fun setFontSizeSp(sp: Float) {
        val clamped = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        editor.setTextSizePx(spToPx(clamped))
    }

    fun getFontSizeSp(): Float {
        return runCatching { pxToSp(editor.textSizePx) }.getOrElse { MIN_FONT_SP }
    }

    fun setFontSizePx(px: Int) {
        val clamped = px.coerceIn(spToPx(MIN_FONT_SP).toInt(), spToPx(MAX_FONT_SP).toInt())
        editor.setTextSizePx(clamped.toFloat())
    }

    fun undo() = editor.undo()
    fun redo() = editor.redo()
    fun indent() {
        // Indent current line or all lines covered by the current selection by 4 spaces.
        editor.requestFocus()

        val cursor = editor.cursor
        val leftLine = cursor.leftLine
        val rightLine = cursor.rightLine
        val leftCol = cursor.leftColumn
        val rightCol = cursor.rightColumn

        val hasSelection = (leftLine != rightLine) || (leftCol != rightCol)

        // Determine the line range affected.
        var startLine = minOf(leftLine, rightLine)
        var endLine = maxOf(leftLine, rightLine)

        // If selection ends at column 0 on a later line, that line is not actually selected.
        if (hasSelection) {
            val selEndsAtLineStart = (rightCol == 0 && rightLine > leftLine) || (leftCol == 0 && leftLine > rightLine)
            if (selEndsAtLineStart) endLine -= 1
        }

        if (endLine < startLine) {
            startLine = minOf(leftLine, rightLine)
            endLine = startLine
        }

        runCatching {
            editor.text.beginBatchEdit()
            for (ln in startLine..endLine) {
                editor.text.insert(ln, 0, "    ")
            }
        }.also {
            editor.text.endBatchEdit()
        }
    }

    fun outdent() {
        // Outdent current line or all selected lines by removing up to 4 leading spaces.
        editor.requestFocus()

        val cursor = editor.cursor
        val leftLine = cursor.leftLine
        val rightLine = cursor.rightLine
        val leftCol = cursor.leftColumn
        val rightCol = cursor.rightColumn

        val hasSelection = (leftLine != rightLine) || (leftCol != rightCol)

        var startLine = minOf(leftLine, rightLine)
        var endLine = maxOf(leftLine, rightLine)
        if (hasSelection) {
            val selEndsAtLineStart = (rightCol == 0 && rightLine > leftLine) || (leftCol == 0 && leftLine > rightLine)
            if (selEndsAtLineStart) endLine -= 1
        }
        if (endLine < startLine) {
            startLine = minOf(leftLine, rightLine)
            endLine = startLine
        }

        runCatching {
            editor.text.beginBatchEdit()
            for (ln in startLine..endLine) {
                val lineText = editor.text.getLine(ln).toString()
                var toRemove = 0
                while (toRemove < 4 && toRemove < lineText.length && lineText[toRemove] == ' ') {
                    toRemove++
                }
                if (toRemove > 0) {
                    editor.text.delete(ln, 0, ln, toRemove)
                }
            }
        }.also {
            editor.text.endBatchEdit()
        }
    }

    fun findNext(query: String) {
        val trimmed = query.trim()
        if (!ensureActiveSearch(trimmed)) return
        editor.searcher.gotoNext()
    }

    fun findPrev(query: String) {
        val trimmed = query.trim()
        if (!ensureActiveSearch(trimmed)) return
        editor.searcher.gotoPrevious()
    }

    fun startSearch(query: String): Int {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return 0

        // Count matches in plain text
        val count = countOccurrences(editor.text.toString(), trimmed)

        ensureActiveSearch(trimmed)

        // Jump to the first match by moving caret to start.
        editor.setSelection(0, 0)
        editor.searcher.gotoNext()

        return count
    }

    private fun ensureActiveSearch(query: String): Boolean {
        if (query.isBlank()) return false
        if (activeSearchQuery != query) {
            activeSearchQuery = query
            editor.searcher.search(query, activeSearchOptions)
        }
        return true
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx == -1) break
            count++
            from = idx + needle.length
        }
        return count
    }

    override fun focusSearch(direction: Int): View? = editor.focusSearch(direction)

    fun focusEditor() {
        editor.requestFocus()
    }


    fun goToLine(line0Based: Int, column0Based: Int = 0) {
        val ln = line0Based.coerceAtLeast(0)
        val col = column0Based.coerceAtLeast(0)
        editor.post {
            runCatching {
                editor.setSelection(ln, col)
                editor.requestFocus()
            }
        }
    }


    fun toggleBold() {
        wrapSelectionWithTags("[b]", "[/b]")
    }

    fun toggleItalic() {
        wrapSelectionWithTags("[i]", "[/i]")
    }

    private fun wrapSelectionWithTags(openTag: String, closeTag: String) {
        editor.requestFocus()

        val cursor = editor.cursor
        val lLine = cursor.leftLine
        val lCol = cursor.leftColumn
        val rLine = cursor.rightLine
        val rCol = cursor.rightColumn

        // Determine ordered start/end
        val startIsLeft = (lLine < rLine) || (lLine == rLine && lCol <= rCol)
        val startLine = if (startIsLeft) lLine else rLine
        val startCol = if (startIsLeft) lCol else rCol
        val endLine = if (startIsLeft) rLine else lLine
        val endCol = if (startIsLeft) rCol else lCol

        val hasSelection = (startLine != endLine) || (startCol != endCol)

        runCatching {
            editor.text.beginBatchEdit()

            if (!hasSelection) {
                // Insert both tags at cursor and place caret between them
                editor.text.insert(startLine, startCol, openTag + closeTag)
                editor.setSelection(startLine, startCol + openTag.length)
            } else {
                // Insert close tag at end first so start position remains valid
                editor.text.insert(endLine, endCol, closeTag)
                editor.text.insert(startLine, startCol, openTag)

                // Place caret after the closing tag
                val shiftedEndCol = endCol + closeTag.length + if (endLine == startLine) openTag.length else 0
                editor.setSelection(endLine, shiftedEndCol)
            }
        }.also {
            editor.text.endBatchEdit()
        }
    }

}