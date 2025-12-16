package com.example.csideandroid.ui

import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import com.example.csideandroid.R
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import kotlin.math.max
import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.JavascriptInterface
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class EditorActivityV3 : AppCompatActivity() {

    // ---- syntax data for highlighting / autocomplete ----
    private val csCommands: Set<String> = setOf(
        "achieve","achievement","check_achievements",
        "choice","fake_choice","disable_reuse","disable_reuse","allow_reuse","selectable_if",
        "create","create_array","temp","temp_array","set","setref","delete","input_number","input_text","print","rand",
        "if","elseif","else","elsif","return","params",
        "label","goto","goto_scene","goto_random_scene","gosub","gosub_scene","finish","ending","redirect_scene",
        "image","line_break","page_break","link","stat_chart","bold","italic","sound","script","scene_list","pause",
        "title","author","ifid","save_checkpoint","restore_checkpoint","comment","bug","looplimit","more_games",
        "share_this_game","show_password"
    )

    // theme colors
    private lateinit var themeColors: EditorThemeColors

    private val reOption      = Regex("(?m)^\\s*#.*$")
    private val reInlineVar   = Regex("\\$!?\\{[^}\\n]*\\}")

    // Coroutine Debouncing State
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)
    private var autocompleteJob: Job? = null
    private var highlightJob: Job? = null

    private val HIGHLIGHT_DELAY_MS = 300L
    private val AUTOCOMPLETE_DELAY_MS = 250L

    // UI
    private lateinit var editor: LineNumberEditText
    private lateinit var toolbar: MaterialToolbar

    // UI components (defined later for brevity)
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnIndent: ImageButton
    private lateinit var btnOutdent: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnSave: ImageButton
    private lateinit var btnFormat: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton

    // Autocomplete
    private lateinit var autoCompletePopup: ListPopupWindow
    private lateinit var autoCompleteAdapter: ArrayAdapter<String>

    // State
    private var documentUri: Uri? = null
    private var lastQuery: String? = null
    private var lastIndex: Int = -1
    private var isSelectionMode: Boolean = false
    private var currentTextSizeSp: Float = 0f
    private var caretRecenterRequested = false
    private var lastCursorPosition: Int = 0


    var isCheckerReady: Boolean = false // Flag kept but unused
    private var isChecking = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_editor_v3)

        toolbar = findViewById(R.id.toolbar)
        editor = findViewById(R.id.editor)

        editor.inputType = editor.inputType or (
                InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                )
        currentTextSizeSp = editor.textSize / resources.displayMetrics.scaledDensity

        val currentTheme = EditorThemeManager.getCurrentTheme(this)
        applyTheme(currentTheme)
        inflateDefaultMenu()

        var displayName = intent.getStringExtra("extra_display_name")
        val uriStr = intent.getStringExtra("extra_document_uri")
        if (!uriStr.isNullOrEmpty()) {
            documentUri = Uri.parse(uriStr)
            if (displayName.isNullOrBlank()) {
                DocumentFile.fromSingleUri(this, documentUri!!)?.name?.let { displayName = it }
            }
            runCatching {
                contentResolver.openInputStream(documentUri!!)?.bufferedReader()?.use { r ->
                    val content = r.readText()
                    editor.setUndoRecordingEnabled(false)
                    editor.setText(content)
                    editor.clearUndoHistory()
                    editor.setUndoRecordingEnabled(true)
                }
            }
        }

        toolbar.title = displayName ?: ""
        toolbar.setTitleTextColor(Color.WHITE)
        AppCompatResources.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            ?.let { d -> d.setTint(Color.WHITE); toolbar.navigationIcon = d }
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val spacer = findViewById<View>(R.id.statusBarSpacer)
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { v, ins ->
            val top = ins.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.layoutParams = v.layoutParams.apply { height = top }
            ins
        }
        val container = findViewById<View>(R.id.editor_container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, ins ->
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            val nav = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, max(ime.bottom, nav.bottom))
            ins
        }

        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnIndent = findViewById(R.id.btnIndent)
        btnOutdent = findViewById(R.id.btnOutdent)
        btnSearch = findViewById(R.id.btnSearch)
        btnSave = findViewById(R.id.btnSave)
        btnFormat = findViewById(R.id.btnFormat)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)

        btnUndo.setOnClickListener { editor.undo() }
        btnRedo.setOnClickListener { editor.redo() }
        btnIndent.setOnClickListener { editor.outdent() }
        btnOutdent.setOnClickListener { editor.indent() }
        btnSearch.setOnClickListener { promptSearch() }
        // Old Format button and now Error Checker button
        btnFormat.setOnClickListener {
            val text = editor.text?.toString() ?: ""

            checkChoiceScriptWithEngine(text) { engineError ->
                if (engineError != null) {
                    AlertDialog.Builder(this)
                        .setTitle("ChoiceScript Engine Error")
                        .setMessage(engineError)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@checkChoiceScriptWithEngine
                }

                AlertDialog.Builder(this)
                    .setTitle("ChoiceScript Check")
                    .setMessage("No errors found in this file.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }

        btnZoomIn.setOnClickListener {
            currentTextSizeSp = (currentTextSizeSp + 2f).coerceAtMost(36f)
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSizeSp)
            editor.invalidateLineNumbers()
        }

        btnZoomOut.setOnClickListener {
            currentTextSizeSp = (currentTextSizeSp - 2f).coerceAtLeast(10f)
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSizeSp)
            editor.invalidateLineNumbers()
        }

        autoCompleteAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        autoCompletePopup = ListPopupWindow(this).apply {
            anchorView = editor
            isModal = false
            inputMethodMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setAdapter(autoCompleteAdapter)
            setOnItemClickListener { _, _, position, _ ->
                val choice = autoCompleteAdapter.getItem(position) ?: return@setOnItemClickListener
                val editable = editor.text ?: return@setOnItemClickListener
                val cursor = editor.selectionStart
                if (cursor < 0) return@setOnItemClickListener
                var start = cursor
                while (start > 0 && editable[start - 1].isLetterOrDigit()) start--
                if (start > 0 && editable[start - 1] == '*') start--
                editable.replace(start, cursor, choice)
                editor.setSelection(start + choice.length)
                dismiss()
            }
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                caretRecenterRequested = true
                lastCursorPosition = editor.selectionStart
                scheduleHighlight()
                scheduleAutoComplete()
            }
        })

        editor.setOnSelectionChangedListener { start, end ->
            val hasSel = start != end
            if (hasSel && !isSelectionMode) enterSelectionMode()
            else if (!hasSel && isSelectionMode) exitSelectionMode()
        }

        editor.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                editor.post { handleAutoIndent() }
            }
            false
        }

        editor.post { scheduleHighlight() }
        val root = findViewById<View>(android.R.id.content)
        setupKeyboardCentering(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        // Removed WebView cleanup
    }

    override fun onPause() {
        super.onPause()
        highlightJob?.cancel()
        autocompleteJob?.cancel()
        saveDocument(showToast = true)
    }

    private fun scheduleAutoComplete() {
        autocompleteJob?.cancel()
        autocompleteJob = mainScope.launch {
            delay(AUTOCOMPLETE_DELAY_MS)
            maybeShowAutoComplete()
        }
    }

    private fun applyTheme(theme: EditorTheme) {
        themeColors = EditorThemeManager.colorsFor(theme)
        editor.setBackgroundColor(themeColors.background)
        editor.setTextColor(themeColors.text)
        editor.invalidate()
        scheduleHighlight()
    }

    private fun showThemePicker() {
        // Theme Picker
        val themes = arrayOf(
            EditorTheme.CLASSIC, EditorTheme.DARK, EditorTheme.SOLARIZED,
            EditorTheme.NORD, EditorTheme.MONOKAI, EditorTheme.DRACULA,
            EditorTheme.STAR_WARS_EMPIRE, EditorTheme.DUNE_ARRAKIS,
            EditorTheme.CYBERPUNK_NIGHTCITY, EditorTheme.JEDI_ORDER,
            EditorTheme.FALLOUT_PIPBOY,
            EditorTheme.MASS_EFFECT_N7, EditorTheme.HP_GRYFFINDOR,
            EditorTheme.HP_HUFFLEPUFF, EditorTheme.HP_RAVENCLAW,
            EditorTheme.HP_SLYTHERIN, EditorTheme.DESERT_DUNES,
            EditorTheme.NO_MANS_SKY
        )

        val labels = arrayOf(
            "Classic (White + Rainbow)", "Dark", "Solarized", "Nord", "Monokai", "Dracula",
            "Star Wars — Imperial Terminal", "Dune — Arrakis", "Cyberpunk 2077 — Night City",
            "Jedi Order — Temple Archives", "Fallout — Pip-Boy 3000", "Mass Effect — N7",
            "Gryffindor", "Hufflepuff", "Ravenclaw", "Slytherin", "Desert Dunes",
            "No Man's Sky"
        )

        val current = EditorThemeManager.getCurrentTheme(this)
        val currentIndex = themes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Editor theme")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selected = themes[which]
                EditorThemeManager.setCurrentTheme(this, selected)
                applyTheme(selected)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun inflateDefaultMenu() {
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.editor_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_theme -> {
                    showThemePicker()
                    true
                }
                else -> false
            }
        }
    }

    private fun wrapSelectionWithChoiceScriptTag(openTag: String, closeTag: String) {
        val text = editor.text ?: return
        val start = editor.selectionStart
        val end = editor.selectionEnd
        if (start < 0 || end <= start) return

        val selected = text.subSequence(start, end).toString()
        val wrapped = buildString {
            append(openTag)
            append(selected)
            append(closeTag)
        }

        text.replace(start, end, wrapped)
        editor.setSelection(start + wrapped.length)
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_text_actions)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_all -> { editor.selectAll(); true }
                R.id.action_cut -> { editor.onTextContextMenuItem(android.R.id.cut); true }
                R.id.action_copy -> { editor.onTextContextMenuItem(android.R.id.copy); true }
                R.id.action_paste -> { editor.onTextContextMenuItem(android.R.id.paste); true }
                R.id.action_bold -> { wrapSelectionWithChoiceScriptTag("[b]", "[/b]"); true }
                R.id.action_italic -> { wrapSelectionWithChoiceScriptTag("[i]", "[/i]"); true }
                else -> false
            }
        }
    }
    private fun exitSelectionMode()  { isSelectionMode = false; inflateDefaultMenu() }

    private fun goToLine(lineNumber: Int) {
        val editable = editor.text ?: return
        val text = editable.toString()
        if (text.isEmpty()) return

        val targetLine = lineNumber.coerceAtLeast(1)
        var currentLine = 1
        var index = 0

        while (index < text.length && currentLine < targetLine) {
            if (text[index] == '\n') currentLine++
            index++
        }

        val targetIndex = index.coerceAtMost(text.length)
        editor.requestFocus()
        editor.setSelection(targetIndex)

        editor.post {
            val layout = editor.layout ?: return@post
            try {
                // Ensure we don't access an out-of-bounds offset
                val safeIndex = targetIndex.coerceAtMost(max(0, text.length - 1))
                val line = layout.getLineForOffset(safeIndex)
                val y = layout.getLineTop(line)
                val targetY = y - (editor.height / 2)
                editor.scrollTo(0, targetY.coerceAtLeast(0))
            } catch (e: Exception) {
                // Fallback scroll if layout calculation fails
            }
        }
    }

    private fun promptSearch() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.find); setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.find)
            .setView(input)
            .setPositiveButton(R.string.next) { _, _ -> findNext(input.text?.toString() ?: "") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun findNext(query: String) {
        if (query.isEmpty()) return
        val text = editor.text?.toString() ?: ""
        val startPos = if (query == lastQuery && lastIndex >= 0) {
            (lastIndex + 1).coerceAtMost(text.length)
        } else editor.selectionEnd.coerceAtLeast(0)

        val idx = text.indexOf(query, startPos, ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf(query, 0, ignoreCase = true)

        if (idx >= 0) {
            editor.setSelection(idx, idx + query.length)
            lastQuery = query; lastIndex = idx
        } else {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show()
            lastIndex = -1
        }
    }

    private fun saveDocument(showToast: Boolean = true) {
        val uri = documentUri ?: return
        val text = editor.text?.toString() ?: ""
        runCatching {
            contentResolver.openOutputStream(uri, "rwt")?.use { os ->
                OutputStreamWriter(os).use { it.write(text) }
            }
        }.onSuccess {
            if (showToast) Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }.onFailure {
            if (showToast) Toast.makeText(this, R.string.failed_save, Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleHighlight() {
        highlightJob?.cancel()
        highlightJob = mainScope.launch {
            delay(HIGHLIGHT_DELAY_MS)
            rehighlight()
        }
    }

    private inner class CSSpan(color: Int) : ForegroundColorSpan(color)

    private fun clearOurSpans(text: Spannable, range: IntRange) {
        val start = range.first
        val endExclusive = range.last + 1
        text.getSpans(start, endExclusive, CSSpan::class.java).forEach { span ->
            text.removeSpan(span)
        }
    }

    private fun rehighlight() {
        val editable = editor.text as? Spannable ?: return
        val length = editable.length
        if (length == 0) return

        val startOffset = 0
        val endOffset = length
        val targetRange = startOffset until endOffset

        clearOurSpans(editable, targetRange)

        val slice: CharSequence = editable.subSequence(startOffset, endOffset)
        val optionColor = themeColors.optionColor
        val inlineVarColor = themeColors.inlineVarColor
        val defaultCommandColor = themeColors.defaultCommandColor
        val commandColors = themeColors.commandColors

        reOption.findAll(slice).forEach { m ->
            editable.setSpan(CSSpan(optionColor), startOffset + m.range.first, startOffset + m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        var indexInSlice = 0
        while (indexInSlice < slice.length) {
            val lineStartInSlice = indexInSlice
            val newlineIndex = slice.indexOf('\n', indexInSlice)
            val lineEndInSlice = if (newlineIndex == -1) slice.length else newlineIndex
            val lineText = slice.subSequence(lineStartInSlice, lineEndInSlice).toString()

            val firstNonSpace = lineText.indexOfFirst { !it.isWhitespace() }
            if (firstNonSpace >= 0 && lineText[firstNonSpace] == '*') {
                val starIndex = firstNonSpace
                var i = starIndex + 1
                while (i < lineText.length && lineText[i].isWhitespace()) i++
                val cmdStart = i
                while (i < lineText.length && (lineText[i].isLetterOrDigit() || lineText[i] == '_')) { i++ }
                val cmdEnd = i

                if (cmdEnd > cmdStart) {
                    val cmd = lineText.substring(cmdStart, cmdEnd).lowercase()
                    val color = commandColors[cmd] ?: defaultCommandColor
                    val absoluteStart = startOffset + lineStartInSlice + starIndex
                    val absoluteEnd = startOffset + lineStartInSlice + cmdEnd
                    editable.setSpan(CSSpan(color), absoluteStart, absoluteEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            indexInSlice = if (newlineIndex == -1) slice.length else newlineIndex + 1
        }

        reInlineVar.findAll(slice).forEach { m ->
            editable.setSpan(CSSpan(inlineVarColor), startOffset + m.range.first, startOffset + m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun handleAutoIndent() {
        val editable = editor.text ?: return
        val pos = editor.selectionStart
        if (pos < 1) return

        val prevNewline = editable.lastIndexOf('\n', pos - 2)
        val prevStart = if (prevNewline >= 0) prevNewline + 1 else 0
        val prevLine = editable.subSequence(prevStart, pos - 1).toString()

        val leadingSpaces = prevLine.takeWhile { it == ' ' }.length
        val trimmed = prevLine.trimStart()
        var indentSpaces = leadingSpaces

        if (trimmed.startsWith("*choice", true) ||
            trimmed.startsWith("*fake_choice", true) ||
            trimmed.startsWith("*if", true) ||
            trimmed.startsWith("#")
        ) {
            indentSpaces = leadingSpaces + 4
        }

        if (trimmed.startsWith("*goto", true) ||
            trimmed.startsWith("*return", true) ||
            trimmed.startsWith("*finish", true)
        ) {
            indentSpaces = 0
        }

        if (indentSpaces > 0) {
            var ahead = 0
            while (pos + ahead < editable.length && editable[pos + ahead] == ' ') ahead++
            if (ahead == 0) {
                val spaces = " ".repeat(indentSpaces)
                editable.insert(pos, spaces)
                editor.setSelection((pos + indentSpaces).coerceAtMost(editable.length))
            }
        } else if (indentSpaces == 0) {
            val spaces = " ".repeat(0)
            editable.insert(pos, spaces)
            editor.setSelection(pos)
        }
    }

    private fun maybeShowAutoComplete() {
        val editable = editor.text ?: return
        val cursor = lastCursorPosition

        if (cursor <= 0) {
            autoCompletePopup.dismiss()
            return
        }

        val text = editable.toString()
        var start = cursor

        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        if (start == 0 || text[start - 1] != '*') {
            autoCompletePopup.dismiss()
            return
        }
        if (cursor <= start) {
            autoCompletePopup.dismiss()
            return
        }

        val prefix = text.substring(start, cursor)
        if (prefix.isEmpty()) {
            autoCompletePopup.dismiss()
            return
        }

        val matches = csCommands.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
        if (matches.isEmpty()) {
            autoCompletePopup.dismiss()
            return
        }

        autoCompleteAdapter.clear()
        autoCompleteAdapter.addAll(matches.map { "*$it" })

        val layout = editor.layout ?: return
        val lineNum = layout.getLineForOffset(cursor)
        val caretX = layout.getPrimaryHorizontal(cursor).toInt()
        val caretY = layout.getLineBottom(lineNum)

        val minWidth = (200 * resources.displayMetrics.density).toInt()
        val width = max(minWidth, editor.width / 2)
        autoCompletePopup.width = width
        autoCompletePopup.horizontalOffset = caretX - editor.scrollX - width / 2
        autoCompletePopup.verticalOffset = caretY - editor.scrollY + (editor.lineHeight * 0.1f).toInt()
        autoCompletePopup.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun checkChoiceScriptWithEngine(text: String, callback: (String?) -> Unit) {
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        class ErrorBridge {
            @JavascriptInterface
            fun onResult(result: String) {
                runOnUiThread { callback(if (result == "OK") null else result) }
            }
        }

        webView.addJavascriptInterface(ErrorBridge(), "ErrorBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val safeText = JSONObject.quote(text)
                webView.evaluateJavascript("validateScene($safeText)", null)
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/checker.html")
    }

    private fun setupKeyboardCentering(root: View) {
        root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (!editor.isFocused) return

                    val r = Rect()
                    root.getWindowVisibleDisplayFrame(r)
                    val visibleHeight = r.height()
                    val totalHeight = root.rootView.height
                    val heightDiff = totalHeight - visibleHeight

                    val keyboardVisible = heightDiff > totalHeight * 0.15f
                    if (!keyboardVisible) {
                        caretRecenterRequested = false
                        return
                    }

                    if (!caretRecenterRequested) return

                    val layout = editor.layout ?: return
                    val sel = editor.selectionStart
                    if (sel < 0) {
                        caretRecenterRequested = false
                        return
                    }

                    val line = layout.getLineForOffset(sel)
                    val lineTop = layout.getLineTop(line)
                    val lineBottom = layout.getLineBottom(line)

                    val loc = IntArray(2)
                    editor.getLocationOnScreen(loc)
                    val caretTopOnScreen = loc[1] + lineTop - editor.scrollY
                    val caretBottomOnScreen = loc[1] + lineBottom - editor.scrollY

                    val keyboardTop = r.bottom

                    if (caretBottomOnScreen <= keyboardTop) {
                        caretRecenterRequested = false
                        return
                    }

                    val lineHeight = lineBottom - lineTop
                    val targetY = keyboardTop - lineHeight * 2
                    val caretMid = (caretTopOnScreen + caretBottomOnScreen) / 2
                    val delta = caretMid - targetY

                    if (delta != 0) {
                        editor.scrollBy(0, delta)
                    }
                    caretRecenterRequested = false
                }
            }
        )
    }
}