package com.example.csideandroid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListPopupWindow
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.webkit.WebViewAssetLoader
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeEclipse
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.schemes.SchemeNotepadXX
import io.github.rosemoe.sora.widget.schemes.SchemeVS2019
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

class SoraEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {

    private val editor: CodeEditor = CodeEditor(context)
    private var language: ChoiceScriptLanguage = ChoiceScriptLanguage(emptyList())

    private var commandsList: List<String> = emptyList()
    private var isApplyingAutoIndent: Boolean = false
    private var lastKnownCursorLine: Int = -1
    private var activeSearchQuery: String? = null
    private val activeSearchOptions = EditorSearcher.SearchOptions(false, false)

    private val popupAnchor: View = View(context).apply {
        layoutParams = LayoutParams(1, 1)
        visibility = View.INVISIBLE
    }

    private val popupAdapter: ArrayAdapter<String> = object : ArrayAdapter<String>(
        context, android.R.layout.simple_list_item_1, mutableListOf()
    ) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            view.findViewById<android.widget.TextView>(android.R.id.text1).setTextColor(Color.WHITE)
            return view
        }
    }

    private val commandPopup: ListPopupWindow = ListPopupWindow(context).apply {
        anchorView = popupAnchor
        setAdapter(popupAdapter)
        isModal = false
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#424242")))
        width = dp(220)
        height = dp(200)
        inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED
        softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        setOnItemClickListener { _, _, position, _ ->
            val selected = popupAdapter.getItem(position) ?: return@setOnItemClickListener
            applySelectedCommand(selected)
            dismiss()
        }
    }


    // Visible Indents
    private var visibleIndentsEnabled: Boolean = false
    private val indentPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    // Harper Spell/Grammar Checker
    private data class SpellError(
        val line: Int,
        val startCol: Int,
        val endCol: Int,
        val word: String,
        val message: String,
        val suggestions: List<String>
    )

    private val spellErrors = mutableListOf<SpellError>()
    private val spellErrorLock = Any()

    private val underlinePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val handler = Handler(Looper.getMainLooper())
    private val spellCheckRunnable = Runnable { runHarperCheck() }
    private val SPELL_DEBOUNCE_MS = 700L

    private var harperReady = false
    private var harperWebView: WebView? = null
    private var pendingCheck = false

    // Toggle state — read from prefs, refreshed on each check
    private var spellCheckEnabled = true

    // Personal dictionary
    private val personalDictionary: MutableSet<String> by lazy {
        context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)
            .getStringSet("personal_dict", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    // Ignored errors — stored as "lineIndex:word" so they are location-specific
    private val ignoredErrors: MutableSet<String> by lazy {
        context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)
            .getStringSet("ignored_errors", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun ignoredKey(lineIndex: Int, word: String) = "$lineIndex:${word.lowercase()}"

    private fun isIgnored(lineIndex: Int, word: String) =
        ignoredErrors.contains(ignoredKey(lineIndex, word)) ||
                ignoredErrors.contains("*:${word.lowercase()}")

    private fun ignoreError(lineIndex: Int, word: String) {
        if (word.isBlank()) return
        val key = ignoredKey(lineIndex, word)
        ignoredErrors.add(key)
        context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("ignored_errors", ignoredErrors).apply()
        synchronized(spellErrorLock) {
            spellErrors.removeAll { it.line == lineIndex && it.word.lowercase() == word.lowercase() }
        }
        invalidate()
    }

    // Spell suggestion popup
    private val spellSuggestionAdapter: ArrayAdapter<String> = object : ArrayAdapter<String>(
        context, android.R.layout.simple_list_item_1, mutableListOf()
    ) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val item = getItem(position) ?: ""
            val tv = if (convertView is android.widget.TextView) convertView
            else android.widget.TextView(context)
            tv.text = item
            tv.setTextColor(Color.WHITE)
            tv.setPadding(dp(16), dp(12), dp(16), dp(12))
            if (item.startsWith("⚠")) {
                tv.maxLines = 6
                tv.isSingleLine = false
                tv.ellipsize = null
                tv.textSize = 13f
            } else {
                tv.maxLines = 1
                tv.isSingleLine = true
                tv.textSize = 15f
            }
            return tv
        }
    }

    private val spellSuggestionAnchor: View = View(context).apply {
        layoutParams = LayoutParams(1, 1)
        visibility = View.INVISIBLE
    }

    private var tappedSpellError: SpellError? = null

    private val spellSuggestionPopup: ListPopupWindow = ListPopupWindow(context).apply {
        setAdapter(spellSuggestionAdapter)
        isModal = true
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#424242")))
        height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        setOnItemClickListener { _, _, position, _ ->
            val item = spellSuggestionAdapter.getItem(position) ?: return@setOnItemClickListener
            val err = tappedSpellError ?: return@setOnItemClickListener
            when {
                item == ADD_TO_DICT_LABEL -> { dismiss(); addToPersonalDictionary(err.word) }
                item == IGNORE_LABEL -> { dismiss(); ignoreError(err.line, err.word) }
                item.startsWith("⚠") -> dismiss()
                else -> { dismiss(); replaceWordInEditor(err, item) }
            }
        }
        setOnDismissListener { tappedSpellError = null }
    }

    // Harper JavaScript bridge
    inner class HarperBridge {
        @JavascriptInterface
        fun onReady() {
            Log.d("Harper", "Harper is ready")
            handler.post {
                harperReady = true
                if (pendingCheck) {
                    pendingCheck = false
                    scheduleSpellCheck()
                }
            }
        }

        @JavascriptInterface
        fun onResults(json: String) {
            Log.d("Harper", "Results received: $json")
            handler.post { processHarperResults(json) }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            Log.e("Harper", "Error: $msg")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initHarperWebView() {
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(HarperBridge(), "HarperBridge")

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                Log.d("HarperJS", "${msg.sourceId()}:${msg.lineNumber()} - ${msg.message()}")
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                Log.d("Harper", "WebView fetching: ${request.url}")
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        wv.loadUrl("https://appassets.androidplatform.net/assets/harper_checker/harper_checker.html")
        harperWebView = wv
    }

    private fun runHarperCheckDirect() {
        if (!harperReady) { pendingCheck = true; return }
        runHarperCheckImpl()
    }



    private fun processHarperResults(json: String) {
        val errors = mutableListOf<SpellError>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val lineIndex = obj.getInt("lineIndex")
                val startColRaw = obj.getInt("startCol")
                val endColRaw = obj.getInt("endCol")
                val message = obj.getString("message")
                val suggArr = obj.getJSONArray("suggestions")
                val suggestions = mutableListOf<String>()
                for (j in 0 until suggArr.length()) suggestions.add(suggArr.getString(j))

                val lineText = runCatching {
                    editor.text.getLine(lineIndex).toString()
                }.getOrElse { "" }

                val indent = lineText.length - lineText.trimStart().length
                val startCol = startColRaw + indent
                val endCol = endColRaw + indent

                val word = runCatching { lineText.substring(startCol, endCol.coerceAtMost(lineText.length)) }.getOrElse { "" }

                // Skip if this error overlaps with a CS expression in the original line
                if (isInsideCsExpression(lineText, startCol, endCol)) continue

                // Skip if this specific error has been ignored
                if (isIgnored(lineIndex, word)) continue

                // If suggestions are empty or only contain bad splits, try contraction fix
                val contractionFix = contractionSuggestion(word)
                val finalSuggestions = if (suggestions.isEmpty() || suggestions.all { it.contains(' ') }) {
                    contractionFix?.let { listOf(it) } ?: suggestions
                } else suggestions
                val finalMessage = if (contractionFix != null && (suggestions.isEmpty() || suggestions.all { it.contains(' ') }))
                    "Did you mean ‘$contractionFix’?" else message

                errors.add(SpellError(lineIndex, startCol, endCol, word, finalMessage, finalSuggestions))
            }
        } catch (_: Throwable) {}

        synchronized(spellErrorLock) {
            spellErrors.clear()
            spellErrors.addAll(errors)
        }
        invalidate()
    }


    private fun contractionSuggestion(word: String): String? {
        val contractions = mapOf(
            "shes" to "she's", "hes" to "he's", "its" to "it's",
            "theyre" to "they're", "youre" to "you're", "were" to "we're",
            "theyll" to "they'll", "youll" to "you'll", "hell" to "he'll",
            "shell" to "she'll", "well" to "we'll", "itll" to "it'll",
            "theyve" to "they've", "youve" to "you've", "weve" to "we've",
            "ive" to "I've", "theyare" to "they are",
            "wont" to "won't", "cant" to "can't", "dont" to "don't",
            "doesnt" to "doesn't", "didnt" to "didn't", "isnt" to "isn't",
            "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
            "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
            "wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
            "mustnt" to "mustn't", "mightnt" to "mightn't", "neednt" to "needn't",
            "im" to "I'm", "id" to "I'd", "ill" to "I'll"
        )
        val lower = word.lowercase()
        val suggestion = contractions[lower] ?: return null
        // Preserve capitalisation of first letter
        return if (word[0].isUpperCase()) suggestion.replaceFirstChar { it.uppercase() } else suggestion
    }

    private fun isInsideCsExpression(line: String, startCol: Int, endCol: Int): Boolean {
        val regex = Regex("[\\$@]!?\\{[^}]*\\}")
        for (match in regex.findAll(line)) {
            if (startCol < match.range.last + 1 && endCol > match.range.first) return true
        }
        return false
    }

    private fun scheduleSpellCheck() {
        val prefs = context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)
        spellCheckEnabled = prefs.getBoolean("spell_check_enabled", true)
        if (!spellCheckEnabled) {
            synchronized(spellErrorLock) { spellErrors.clear() }
            invalidate()
            return
        }
        handler.removeCallbacks(spellCheckRunnable)
        handler.postDelayed(spellCheckRunnable, SPELL_DEBOUNCE_MS)
    }

    private fun runHarperCheck() {
        if (!spellCheckEnabled) return
        if (!harperReady) { pendingCheck = true; return }
        runHarperCheckImpl()
    }


    private fun runHarperCheckImpl() {
        val lineCount = editor.lineCount
        if (lineCount == 0) return
        val firstVisible = editor.firstVisibleLine.coerceAtLeast(0)
        val lastVisible = editor.lastVisibleLine.coerceAtMost(lineCount - 1)
        val sb = StringBuilder()
        val lineOffsets = JSONArray()
        var charOffset = 0
        for (lineIndex in firstVisible..lastVisible) {
            val lineText = runCatching { editor.text.getLine(lineIndex).toString() }.getOrNull() ?: continue
            val trimmed = lineText.trimStart()
            if (trimmed.startsWith("*") || trimmed.startsWith("#")) continue
            if (trimmed.isEmpty()) continue
            // Strip leading spaces before sending to Harper for spell check since it flags indents as errors
            val indent = lineText.length - trimmed.length
            val cleaned = stripCsExpressions(trimmed)
            val offsetObj = JSONObject()
            offsetObj.put("lineIndex", lineIndex)
            offsetObj.put("startCharOffset", charOffset)
            offsetObj.put("lineIndent", indent) // so results can be shifted back
            lineOffsets.put(offsetObj)
            sb.append(cleaned).append("\n"); charOffset += cleaned.length + 1
        }
        if (sb.isEmpty()) { synchronized(spellErrorLock) { spellErrors.clear() }; invalidate(); return }
        val text = sb.toString()
        val personalDictJson = JSONArray(personalDictionary.toList()).toString()
        val escapedText = JSONObject.quote(text)
        val escapedOffsets = JSONObject.quote(lineOffsets.toString())
        val escapedDict = JSONObject.quote(personalDictJson)
        harperWebView?.evaluateJavascript("window.runHarperCheck($escapedText, $escapedOffsets, $escapedDict);", null)
    }



    private fun stripCsExpressions(text: String): String {
        val sb = StringBuilder(text)
        // Match $!{...}, ${...}, @!{...}, @{...} — replace with spaces preserving length
        val regex = Regex("[\\$@]!?\\{[^}]*\\}")
        var offset = 0
        for (match in regex.findAll(text)) {
            val start = match.range.first
            val end = match.range.last
            for (j in start..end) sb.setCharAt(j, ' ')
        }
        return sb.toString()
    }

    private fun addToPersonalDictionary(word: String) {
        if (word.isBlank()) return
        personalDictionary.add(word.lowercase())
        context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)
            .edit { putStringSet("personal_dict", personalDictionary) }
        // Also add to ignored so Harper can't re-flag it
        val prefs = context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)
        val allIgnored = prefs.getStringSet("ignored_errors", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        allIgnored.add("*:${word.lowercase()}") // * means ignore on all lines
        ignoredErrors.add("*:${word.lowercase()}")
        prefs.edit { putStringSet("ignored_errors", allIgnored) }
        synchronized(spellErrorLock) {
            spellErrors.removeAll { it.word.equals(word, ignoreCase = true) }
        }
        invalidate()
    }

    private fun replaceWordInEditor(err: SpellError, replacement: String) {
        runCatching {
            editor.text.beginBatchEdit()
            val lineText = editor.text.getLine(err.line).toString()
            val safeEnd = err.endCol.coerceAtMost(lineText.length)

            val isPunctuationOnly = replacement.all { !it.isLetterOrDigit() && !it.isWhitespace() }
            val isInsertionFix = isPunctuationOnly && replacement.length < (err.endCol - err.startCol)

            if (isInsertionFix) {
                // Harper Bug - For Oxford comma: find "and" or "or" after startCol and insert before it
                val lineText2 = lineText.substring(err.startCol)
                val andIdx = lineText2.indexOfFirst { it == ' ' } // end of the flagged word
                // Insert after the flagged word before the space+and
                val insertAt = err.startCol + (andIdx.takeIf { it > 0 } ?: (err.endCol - err.startCol))
                editor.text.insert(err.line, insertAt, replacement)
            } else {
                editor.text.delete(err.line, err.startCol, err.line, safeEnd)
                editor.text.insert(err.line, err.startCol, replacement)
            }
            editor.text.endBatchEdit()
        }
        synchronized(spellErrorLock) { spellErrors.remove(err) }
        invalidate()
    }

    private var touchDownX = 0f
    private var touchDownY = 0f

    private fun setupSpellTouchListener() {
        editor.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.x - touchDownX)
                    val dy = Math.abs(event.y - touchDownY)
                    if (dx < dp(10) && dy < dp(10)) {
                        val tappedErr = findSpellErrorAt(event.x, event.y)
                        if (tappedErr != null) {
                            showSpellSuggestions(tappedErr, event.x.toInt(), event.y.toInt())
                        }
                    }
                }
            }
            false
        }
    }

    private fun findSpellErrorAt(touchX: Float, touchY: Float): SpellError? {
        val errors: List<SpellError>
        synchronized(spellErrorLock) { errors = spellErrors.toList() }
        for (err in errors) {
            val x1 = runCatching { editor.getCharOffsetX(err.line, err.startCol) }.getOrNull() ?: continue
            val x2 = runCatching { editor.getCharOffsetX(err.line, err.endCol) }.getOrNull() ?: continue
            val yBottom = runCatching { editor.getCharOffsetY(err.line, err.startCol) }.getOrNull() ?: continue
            val yTop = yBottom - editor.rowHeight
            if (touchX in x1..x2 && touchY in yTop..(yBottom + dp(8))) return err
        }
        return null
    }

    private fun showSpellSuggestions(err: SpellError, anchorX: Int, anchorY: Int) {
        tappedSpellError = err

        val lp = spellSuggestionAnchor.layoutParams as LayoutParams
        lp.leftMargin = anchorX
        lp.topMargin = anchorY
        spellSuggestionAnchor.layoutParams = lp
        spellSuggestionPopup.anchorView = spellSuggestionAnchor

        spellSuggestionAdapter.clear()
        // Show the Harper message at the top so the user knows why it's flagged
        if (err.message.isNotBlank()) spellSuggestionAdapter.add("⚠ ${err.message}")
        spellSuggestionAdapter.addAll(err.suggestions)
        spellSuggestionAdapter.add(ADD_TO_DICT_LABEL)
        spellSuggestionAdapter.add(IGNORE_LABEL)
        spellSuggestionAdapter.notifyDataSetChanged()

        // Use screen width minus 32dp padding, is capped at 420dp
        val screenWidth = resources.displayMetrics.widthPixels
        spellSuggestionPopup.width = minOf(screenWidth - dp(32), dp(420))
        if (!spellSuggestionPopup.isShowing) spellSuggestionPopup.show()
    }


    init {
        setWillNotDraw(false)
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(popupAnchor)
        addView(spellSuggestionAnchor)

        editor.setColorScheme(SchemeDarcula())
        editor.isLineNumberEnabled = true
        editor.isHighlightCurrentLine = true
        editor.isWordwrap = true
        editor.props.symbolPairAutoCompletion = true
        // Fix: keep the whole file addressable by the IME. Past ~32,768 chars limit
        editor.props.maxIPCTextLength = 5_000_000
        editor.setEditorLanguage(language)

        editor.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE

        editor.getComponent(
            io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java
        ).isEnabled = false

        setupSpellTouchListener()
        initHarperWebView()
        // WebView must be attached to the view hierarchy for JS module loading to work
        harperWebView?.let { wv ->
            wv.visibility = View.GONE
            addView(wv, LayoutParams(1, 1))
        }

        editor.subscribeAlways(ScrollEvent::class.java) {
            scheduleSpellCheck()
        }

        editor.subscribeAlways(ContentChangeEvent::class.java) { event ->
            if (event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                commandPopup.dismiss()
                scheduleSpellCheck()
                return@subscribeAlways
            }
            updateCommandPopup()
            scheduleSpellCheck()

            val changedText = event.changedText.toString()
            if (!isApplyingAutoIndent && event.action == ContentChangeEvent.ACTION_INSERT) {
                val newlineCount = changedText.count { it == '\n' }
                val looksLikeSingleNewlineInsert = newlineCount == 1 &&
                        (changedText == "\n" || changedText == "\r\n" ||
                                changedText.startsWith("\n") || changedText.startsWith("\r\n"))
                if (looksLikeSingleNewlineInsert) editor.post { autoIndentOnNewline() }
            }
        }

        // When the cursor jumps to a different line,force the keyboard to drop its composing span from the old position and start fresh.
        editor.subscribeAlways(SelectionChangeEvent::class.java) {
            val newLine = editor.cursor.leftLine
            if (lastKnownCursorLine != -1 && newLine != lastKnownCursorLine) {
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.restartInput(editor)
            }
            lastKnownCursorLine = newLine
        }
    }


    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (visibleIndentsEnabled) drawIndentMarkers(canvas)
        drawSpellErrors(canvas)
    }

    private fun drawSpellErrors(canvas: Canvas) {
        val errors: List<SpellError>
        synchronized(spellErrorLock) { errors = spellErrors.toList() }
        if (errors.isEmpty()) return
        for (err in errors) {
            val x1 = runCatching { editor.getCharOffsetX(err.line, err.startCol) }.getOrNull() ?: continue
            val x2 = runCatching { editor.getCharOffsetX(err.line, err.endCol) }.getOrNull() ?: continue
            val y  = runCatching { editor.getCharOffsetY(err.line, err.startCol) }.getOrNull() ?: continue
            drawWavyLine(canvas, x1, x2, y + 4f)
        }
    }

    private fun drawWavyLine(canvas: Canvas, x1: Float, x2: Float, y: Float) {
        val amplitude = 3f
        val wavelength = 8f
        var x = x1
        var up = true
        val path = Path()
        path.moveTo(x, y)
        while (x < x2) {
            val nextX = (x + wavelength / 2f).coerceAtMost(x2)
            val cy = if (up) y - amplitude else y + amplitude
            path.quadTo(x + (nextX - x) / 2f, cy, nextX, y)
            x = nextX
            up = !up
        }
        canvas.drawPath(path, underlinePaint)
    }

    private fun drawIndentMarkers(canvas: Canvas) {
        val lineCount = editor.lineCount
        if (lineCount == 0) return
        indentPaint.textSize = editor.textSizePx
        val textColor = editor.colorScheme.getColor(EditorColorScheme.TEXT_NORMAL)
        indentPaint.color = Color.argb(100, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        val safeFirst = editor.firstVisibleLine.coerceAtLeast(0)
        val safeLast = editor.lastVisibleLine.coerceAtMost(lineCount - 1)
        for (lineIndex in safeFirst..safeLast) {
            val lineText = runCatching { editor.text.getLine(lineIndex).toString() }.getOrNull() ?: continue
            var actualCol = 0
            while (actualCol < lineText.length) {
                when (lineText[actualCol]) {
                    ' ' -> {
                        val x = runCatching { editor.getCharOffsetX(lineIndex, actualCol) }.getOrNull() ?: break
                        val y = runCatching { editor.getCharOffsetY(lineIndex, actualCol) }.getOrNull() ?: break
                        canvas.drawText("-", x, y - indentPaint.descent(), indentPaint)
                        actualCol++
                    }
                    '\t' -> {
                        val xStart = runCatching { editor.getCharOffsetX(lineIndex, actualCol) }.getOrNull() ?: break
                        val xEnd = runCatching { editor.getCharOffsetX(lineIndex, actualCol + 1) }.getOrNull() ?: break
                        val y = runCatching { editor.getCharOffsetY(lineIndex, actualCol) }.getOrNull() ?: break
                        val baseline = y - indentPaint.descent()
                        val step = (xEnd - xStart) / 4f
                        for (i in 0 until 4) canvas.drawText("-", xStart + step * i, baseline, indentPaint)
                        actualCol++
                    }
                    else -> break
                }
            }
        }
    }



    fun setVisibleIndents(enabled: Boolean) {
        visibleIndentsEnabled = enabled
        invalidate()
    }

    fun setTextContent(text: String?) {
        editor.post {
            editor.setText(text ?: "")
            scheduleSpellCheck()
        }
    }

    suspend fun getTextContent(): String = withContext(Dispatchers.Main) {
        editor.text.toString()
    }

    fun setCommands(commands: List<String>) {
        commandsList = commands
        language = ChoiceScriptLanguage(commands)
        editor.setEditorLanguage(language)
        editor.post { updateCommandPopup() }
    }

    fun setAutoCloseBrackets(enabled: Boolean) {
        editor.props.symbolPairAutoCompletion = enabled
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
        val customColorBaseId = 512
        val scheme = object : EditorColorScheme() {
            init {
                setColor(WHOLE_BACKGROUND, colors.background)
                setColor(TEXT_NORMAL, colors.text)
                setColor(LINE_NUMBER, colors.lineNumber)
                setColor(LINE_NUMBER_BACKGROUND, colors.background)
                val currentLine = (colors.optionColor and 0x00FFFFFF) or (0x1F shl 24)
                setColor(CURRENT_LINE, currentLine)
                val selectionBg = (colors.optionColor and 0x00FFFFFF) or (0x3D shl 24)
                fun setColorIfExists(fieldName: String, color: Int) {
                    try { val f = EditorColorScheme::class.java.getField(fieldName); setColor(f.getInt(null), color) } catch (_: Throwable) {}
                }
                setColor(SELECTION_INSERT, selectionBg)
                setColor(SELECTION_HANDLE, colors.optionColor)
                setColorIfExists("SELECTION_BACKGROUND", selectionBg)
                setColorIfExists("SELECTION_BG", selectionBg)
                setColorIfExists("SELECTED_TEXT_BACKGROUND", selectionBg)
                setColorIfExists("SELECTION", selectionBg)
                setColor(KEYWORD, colors.defaultCommandColor)
                setColor(ANNOTATION, colors.optionColor)
                setColor(LITERAL, colors.inlineVarColor)
                val lowerMap = colors.commandColors.entries.associate { it.key.lowercase() to it.value }
                val keys = lowerMap.keys.distinct().sorted()
                for ((idx, key) in keys.withIndex()) {
                    val c = lowerMap[key] ?: continue
                    setColor(customColorBaseId + idx, c)
                }
            }
        }
        editor.setColorScheme(scheme)
        runCatching {
            val m = editor.editorLanguage.javaClass.getMethod("setThemeColors", EditorThemeColors::class.java)
            m.invoke(editor.editorLanguage, colors)
        }
        editor.invalidate()
        invalidate()
    }

    fun setFontSizeSp(sp: Float) { editor.setTextSizePx(spToPx(sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP))) }
    fun getFontSizeSp(): Float = runCatching { pxToSp(editor.textSizePx) }.getOrElse { MIN_FONT_SP }
    fun setFontSizePx(px: Int) { editor.setTextSizePx(px.coerceIn(spToPx(MIN_FONT_SP).toInt(), spToPx(MAX_FONT_SP).toInt()).toFloat()) }

    fun undo() = editor.undo()
    fun redo() = editor.redo()

    fun indent() {
        editor.requestFocus()
        val cursor = editor.cursor
        val leftLine = cursor.leftLine; val rightLine = cursor.rightLine
        val leftCol = cursor.leftColumn; val rightCol = cursor.rightColumn
        val hasSelection = (leftLine != rightLine) || (leftCol != rightCol)
        var startLine = minOf(leftLine, rightLine); var endLine = maxOf(leftLine, rightLine)
        if (hasSelection) { val sel = (rightCol == 0 && rightLine > leftLine) || (leftCol == 0 && leftLine > rightLine); if (sel) endLine -= 1 }
        if (endLine < startLine) { startLine = minOf(leftLine, rightLine); endLine = startLine }
        runCatching { editor.text.beginBatchEdit(); for (ln in startLine..endLine) editor.text.insert(ln, 0, "    ") }.also { editor.text.endBatchEdit() }
    }

    fun outdent() {
        editor.requestFocus()
        val cursor = editor.cursor
        val leftLine = cursor.leftLine; val rightLine = cursor.rightLine
        val leftCol = cursor.leftColumn; val rightCol = cursor.rightColumn
        val hasSelection = (leftLine != rightLine) || (leftCol != rightCol)
        var startLine = minOf(leftLine, rightLine); var endLine = maxOf(leftLine, rightLine)
        if (hasSelection) { val sel = (rightCol == 0 && rightLine > leftLine) || (leftCol == 0 && leftLine > rightLine); if (sel) endLine -= 1 }
        if (endLine < startLine) { startLine = minOf(leftLine, rightLine); endLine = startLine }
        runCatching {
            editor.text.beginBatchEdit()
            for (ln in startLine..endLine) {
                val lineText = editor.text.getLine(ln).toString()
                var toRemove = 0
                while (toRemove < 4 && toRemove < lineText.length && lineText[toRemove] == ' ') toRemove++
                if (toRemove > 0) editor.text.delete(ln, 0, ln, toRemove)
            }
        }.also { editor.text.endBatchEdit() }
    }

    fun findNext(query: String) { if (!ensureActiveSearch(query.trim())) return; editor.searcher.gotoNext() }
    fun findPrev(query: String) { if (!ensureActiveSearch(query.trim())) return; editor.searcher.gotoPrevious() }

    fun startSearch(query: String): Int {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return 0
        val count = countOccurrences(editor.text.toString(), trimmed)
        ensureActiveSearch(trimmed)
        editor.setSelection(0, 0)
        editor.searcher.gotoNext()
        return count
    }

    fun goToLine(line0Based: Int, column0Based: Int = 0) {
        val ln = line0Based.coerceAtLeast(0); val col = column0Based.coerceAtLeast(0)
        editor.post { runCatching { editor.setSelection(ln, col); editor.requestFocus() } }
    }

    fun focusEditor() = editor.requestFocus()
    override fun focusSearch(direction: Int): View? = editor.focusSearch(direction)
    fun toggleBold() = wrapSelectionWithTags("[b]", "[/b]")
    fun toggleItalic() = wrapSelectionWithTags("[i]", "[/i]")







    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
    private fun pxToSp(px: Float): Float = px / resources.displayMetrics.scaledDensity
    private val MIN_FONT_SP = 14f
    private val MAX_FONT_SP = 32f

    private fun updateCommandPopup() {
        if (commandsList.isEmpty()) { commandPopup.dismiss(); return }
        val cursor = editor.cursor
        val line = cursor.leftLine; val col = cursor.leftColumn
        val lineText = editor.text.getLine(line).toString()
        val safeCol = col.coerceIn(0, lineText.length)
        val beforeCursor = lineText.substring(0, safeCol)
        val lastBoundary = maxOf(beforeCursor.lastIndexOf(' '), beforeCursor.lastIndexOf('\t'), beforeCursor.lastIndexOf('"'))
        val token = beforeCursor.substring(lastBoundary + 1)

        // Check if we're inside a *stat_chart block and suggest opposed_pair in popup menu
        if (!token.startsWith("*") && !token.startsWith("$") && !token.startsWith("@")) {
            if (isInsideStatChart(line) && "opposed_pair".startsWith(token.lowercase()) && token.isNotEmpty()) {
                val matches = listOf("opposed_pair")
                showPopupMatches(matches, line, safeCol)
                return
            }
            commandPopup.dismiss(); return
        }

        val isStar = token.startsWith("*"); val isDollar = token.startsWith("$")
        val typed = token.substring(1).lowercase()
        val matches: List<String> = if (isStar) commandsList.asSequence().filter { typed.isEmpty() || it.startsWith(typed) }.take(200).map { "*$it" }.toList()
        else if (isDollar) listOf("\${}", "\$!{}").filter { typed.isEmpty() || it.substring(1).startsWith(typed) }
        else listOf("@{}", "@!{}").filter { typed.isEmpty() || it.substring(1).startsWith(typed) }
        if (matches.isEmpty()) { commandPopup.dismiss(); return }
        showPopupMatches(matches, line, safeCol)
    }

    private fun isInsideStatChart(line: Int): Boolean {
        val lineText = runCatching { editor.text.getLine(line).toString() }.getOrElse { return false }
        val indent = lineText.length - lineText.trimStart().length
        if (indent == 0) return false // not indented, can't be inside stat_chart
        for (i in (line - 1) downTo 0) {
            val prev = runCatching { editor.text.getLine(i).toString() }.getOrElse { continue }
            val prevTrimmed = prev.trimStart()
            if (prevTrimmed.isEmpty()) continue
            val prevIndent = prev.length - prevTrimmed.length
            if (prevIndent < indent) {
                return prevTrimmed.startsWith("*stat_chart", ignoreCase = true)
            }
        }
        return false
    }

    private fun showPopupMatches(matches: List<String>, line: Int, safeCol: Int) {
        var x: Float; var yBottom: Float
        try { x = editor.getCharOffsetX(line, safeCol); yBottom = editor.getCharOffsetY(line, safeCol) }
        catch (_: Throwable) { x = 0f; yBottom = (editor.getRowHeight() * line - editor.offsetY).toFloat() }
        val lp = popupAnchor.layoutParams as LayoutParams
        lp.leftMargin = x.toInt().coerceAtLeast(0)
        val rowHeight = try { editor.getRowHeight() } catch (_: Throwable) { dp(18) }
        lp.topMargin = (yBottom + rowHeight * 0.15f).toInt().coerceAtLeast(0)
        popupAnchor.layoutParams = lp
        popupAdapter.clear(); popupAdapter.addAll(matches); popupAdapter.notifyDataSetChanged()
        commandPopup.anchorView = popupAnchor
        if (!commandPopup.isShowing) commandPopup.show()
    }

    private fun countLeadingSpaces(s: String): Int { var i = 0; while (i < s.length && s[i] == ' ') i++; return i }

    private fun isBlockOpener(trimmedLine: String): Boolean {
        if (trimmedLine.startsWith("#")) return true
        // opposed_pair inside stat_chart also triggers indent for sub-label
        val lowerTrimmed = trimmedLine.lowercase()
        if (lowerTrimmed.startsWith("opposed_pair")) return true
        if (!trimmedLine.startsWith("*")) return false
        val afterStar = trimmedLine.substring(1).lowercase()
        return afterStar.startsWith("choice") || afterStar.startsWith("fake_choice") ||
                afterStar.startsWith("if ") || afterStar.startsWith("if\t") || afterStar == "if" ||
                afterStar.startsWith("elseif ") || afterStar.startsWith("elseif\t") ||
                afterStar == "elseif" || afterStar.startsWith("else") || afterStar.startsWith("selectable_if") ||
                afterStar.startsWith("stat_chart")
    }

    private fun isReturn(trimmedLine: String): Boolean {
        if (!trimmedLine.startsWith("*")) return false
        val afterStar = trimmedLine.substring(1).lowercase()
        return afterStar.startsWith("return ") || afterStar.startsWith("return\t") || afterStar == "return"
    }

    private fun autoIndentOnNewline() {
        if (isApplyingAutoIndent) return
        val cursor = editor.cursor; val line = cursor.leftLine
        if (line <= 0) return
        val prevLineText = editor.text.getLine(line - 1).toString()
        val prevTrimmed = prevLineText.trimStart()
        val targetIndent = if (isReturn(prevTrimmed)) 0
        else countLeadingSpaces(prevLineText) + if (isBlockOpener(prevTrimmed)) 4 else 0
        val currentLineText = editor.text.getLine(line).toString()
        val currentIndent = countLeadingSpaces(currentLineText)
        if (currentIndent == targetIndent && currentLineText.substring(currentIndent).isEmpty()) return
        isApplyingAutoIndent = true
        try {
            editor.text.beginBatchEdit()
            editor.text.delete(line, 0, line, currentIndent)
            if (targetIndent > 0) editor.text.insert(line, 0, " ".repeat(targetIndent))
            editor.setSelection(line, targetIndent)
            editor.text.endBatchEdit()
        } finally { isApplyingAutoIndent = false }

    }

    private fun applySelectedCommand(selectedWithStar: String) {
        val cursor = editor.cursor; val line = cursor.leftLine; val col = cursor.leftColumn
        val lineText = editor.text.getLine(line).toString()
        val safeCol = col.coerceIn(0, lineText.length)
        val beforeCursor = lineText.substring(0, safeCol)
        val lastDelim = run {
            var i = beforeCursor.length - 1
            while (i >= 0) {
                when (beforeCursor[i]) { ' ', '\t', '"', '\'', '(', ')', '[', ']', '{', '}', ',', ';', ':', '=', '+', '-', '/', '\\', '<', '>', '|', '&', '!', '?', '\n', '\r' -> break }
                i--
            }
            i
        }
        val token = beforeCursor.substring((lastDelim + 1).coerceIn(0, beforeCursor.length))
        if (token.isEmpty()) return
        // Handle opposed_pair (no prefix)
        if (selectedWithStar == "opposed_pair") {
            val typed = token.lowercase()
            if ("opposed_pair".startsWith(typed)) {
                // Delete and reinsert command in lowercased
                val cur = editor.cursor
                val lineIdx = cur.leftLine
                val colEnd = cur.leftColumn
                val colStart = colEnd - token.length
                if (colStart >= 0) {
                    editor.text.beginBatchEdit()
                    editor.text.delete(lineIdx, colStart, lineIdx, colEnd)
                    editor.text.insert(lineIdx, colStart, "opposed_pair")
                    editor.text.endBatchEdit()
                }
            }
            return
        }
        if (!(token.startsWith("*") || token.startsWith("$") || token.startsWith("@"))) return
        if (token.startsWith("*")) {
            val chosen = selectedWithStar.removePrefix("*")
            val already = token.substring(1)
            val suffix = if (chosen.startsWith(already, ignoreCase = true)) chosen.substring(already.length) else chosen
            if (suffix.isNotEmpty()) editor.insertText(suffix.lowercase(), suffix.length)
            // Fix any capitalization in the already-typed portion (e.g. Gboard Auto Caps)
            if (already.isNotEmpty() && already != already.lowercase()) {
                val cur = editor.cursor
                val lineIdx = cur.leftLine
                val colEnd = cur.leftColumn
                val colStart = colEnd - suffix.length - already.length
                if (colStart >= 0) {
                    editor.text.beginBatchEdit()
                    editor.text.delete(lineIdx, colStart, lineIdx, colStart + already.length)
                    editor.text.insert(lineIdx, colStart, already.lowercase())
                    editor.text.endBatchEdit()
                }
            }
        } else {
            val trigger = token[0].toString(); val chosen = selectedWithStar
            val chosenAfter = chosen.removePrefix(trigger); val alreadyAfter = token.substring(1)
            val suffix = if (chosenAfter.startsWith(alreadyAfter, ignoreCase = true)) chosenAfter.substring(alreadyAfter.length) else chosenAfter
            if (suffix.isNotEmpty()) {
                editor.insertText(suffix, suffix.length)
                // If the inserted text ends with {}, move cursor inside the braces
                if (suffix.endsWith("{}")) {
                    val cur = editor.cursor
                    editor.setSelection(cur.leftLine, cur.leftColumn - 1)
                }
            }
        }
    }

    private fun ensureActiveSearch(query: String): Boolean {
        if (query.isBlank()) return false
        if (activeSearchQuery != query) { activeSearchQuery = query; editor.searcher.search(query, activeSearchOptions) }
        return true
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0; var count = 0; var from = 0
        while (true) { val idx = haystack.indexOf(needle, from); if (idx == -1) break; count++; from = idx + needle.length }
        return count
    }

    private fun wrapSelectionWithTags(openTag: String, closeTag: String) {
        editor.requestFocus()
        val cursor = editor.cursor
        val lLine = cursor.leftLine; val lCol = cursor.leftColumn
        val rLine = cursor.rightLine; val rCol = cursor.rightColumn
        val startIsLeft = (lLine < rLine) || (lLine == rLine && lCol <= rCol)
        val startLine = if (startIsLeft) lLine else rLine; val startCol = if (startIsLeft) lCol else rCol
        val endLine = if (startIsLeft) rLine else lLine; val endCol = if (startIsLeft) rCol else lCol
        val hasSelection = (startLine != endLine) || (startCol != endCol)
        runCatching {
            editor.text.beginBatchEdit()
            if (!hasSelection) {
                editor.text.insert(startLine, startCol, openTag + closeTag)
                editor.setSelection(startLine, startCol + openTag.length)
            } else {
                editor.text.insert(endLine, endCol, closeTag)
                editor.text.insert(startLine, startCol, openTag)
                val shiftedEndCol = endCol + closeTag.length + if (endLine == startLine) openTag.length else 0
                editor.setSelection(endLine, shiftedEndCol)
            }
        }.also { editor.text.endBatchEdit() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(spellCheckRunnable)
        harperWebView?.destroy()
        harperWebView = null
    }

    companion object {
        private const val ADD_TO_DICT_LABEL = "Add to Dictionary"
        private const val IGNORE_LABEL = "Ignore"
    }
}