package com.example.csideandroid.ui

import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.example.csideandroid.R
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import kotlin.math.max

class EditorActivityV3 : AppCompatActivity() {

    // ---- syntax data for highlighting / autocomplete ----
    private val csCommands: Set<String> = setOf(
        "achieve","achievement","check_achievements",
        "choice","fake_choice","disable_reuse","hide_reuse","allow_reuse","selectable_if",
        "create","create_array","temp","temp_array","set","setref","delete","input_number","input_text","print","rand",
        "if","elseif","else","elsif","return","params",
        "label","goto","goto_scene","goto_random_scene","gosub","gosub_scene","finish","ending","redirect_scene",
        "image","line_break","page_break","link","stat_chart","bold","italic","sound","script","scene_list","pause",
        "title","author","ifid","save_checkpoint","restore_checkpoint","comment","bug","looplimit","more_games",
        "share_this_game","show_password"
    )

    // theme colors
    private lateinit var themeColors: EditorThemeColors

    private val reCommandLine = Regex("(?m)(^|\\n)\\s*\\*[a-z_][^\\r\\n]*")
    private val reOption      = Regex("(?m)^\\s*#.*$")
    private val reInlineVar   = Regex("\\$!?\\{[^}\\n]*\\}")

    // --- Coroutine Debouncing State ---
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)
    private var autocompleteJob: Job? = null
    private var highlightJob: Job? = null

    private var caretRecenterRequested = false

    // Variable to store raw cursor position immediately on text change
    private var lastCursorPosition: Int = 0

    private val HIGHLIGHT_DELAY_MS = 300L
    private val AUTOCOMPLETE_DELAY_MS = 250L

    // UI
    private lateinit var editor: LineNumberEditText
    private lateinit var toolbar: MaterialToolbar

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


    private lateinit var validationWebView: WebView
    private var validationReady: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_editor_v3)

        toolbar = findViewById(R.id.toolbar)
        editor = findViewById(R.id.editor)
        currentTextSizeSp = editor.textSize / resources.displayMetrics.scaledDensity

        // load and apply current theme
        val currentTheme = EditorThemeManager.getCurrentTheme(this)
        applyTheme(currentTheme)

        inflateDefaultMenu()

        // Load file
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

        // status bar spacer and bottom padding for editor_container
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

        // Bottom bar
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
        btnSave.setOnClickListener { saveDocument() }
        btnFormat.setOnClickListener { confirmFormatDocument() }

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


        // Autocomplete
        autoCompleteAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        autoCompletePopup = ListPopupWindow(this).apply {
            anchorView = editor
            isModal = false
            inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED
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

                // Store cursor position immediately.
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

        initValidationWebView()

        val root = findViewById<View>(android.R.id.content)
        setupKeyboardCentering(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }


    // ScheduleAutoComplete() no longer takes a 'line' parameter

    private fun scheduleAutoComplete() {
        autocompleteJob?.cancel()

        autocompleteJob = mainScope.launch {
            delay(AUTOCOMPLETE_DELAY_MS)
            maybeShowAutoComplete()
        }
    }

    override fun onPause() {
        super.onPause()
        highlightJob?.cancel()
        autocompleteJob?.cancel()
        saveDocument(showToast = true)
    }

    // ---- theme ----
    private fun applyTheme(theme: EditorTheme) {
        themeColors = EditorThemeManager.colorsFor(theme)
        editor.setBackgroundColor(themeColors.background)
        editor.setTextColor(themeColors.text)
        editor.invalidate()
        scheduleHighlight()
    }

    private fun showThemePicker() {
        val themes = arrayOf(
            EditorTheme.CLASSIC,
            EditorTheme.DARK,
            EditorTheme.SOLARIZED,
            EditorTheme.NORD,
            EditorTheme.MONOKAI,
            EditorTheme.DRACULA
        )

        val labels = arrayOf(
            "Classic (White + Rainbow)",
            "Dark",
            "Solarized",
            "Nord",
            "Monokai",
            "Dracula"
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

    // ---- hidden WebView setup for ChoiceScript validation ----
    private fun initValidationWebView() {
        validationWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            visibility = View.GONE
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    injectValidationScript()
                }
            }
        }
        // Load the ChoiceScript runner HTML from assets
        validationWebView.loadUrl("file:///android_asset/choicescript/web/index.html")
    }

    private fun injectValidationScript() {
        val js = """
        (function() {
          window.validateChoiceScript = function(text) {
            try {
              if (typeof Scene === 'undefined') {
                return { ok: false, errors: ["ChoiceScript engine not loaded."] };
              }
              if (typeof SceneNavigator === 'undefined') {
                return { ok: false, errors: ["SceneNavigator not available."] };
              }
              if (typeof nav === 'undefined' || !nav) {
                nav = new SceneNavigator(["startup"]);
              }
              if (typeof stats === 'undefined' || !stats) {
                stats = {};
              }
              var scene = new Scene("editor_validate", stats, nav, { debugMode: true });
              var lines = String(text).split(/\r?\n/);
              scene.loadLinesFast(0, lines, {});
              scene.quicktest = true;
              scene.randomtest = false;
              var safetyCounter = 0;
              while (!scene.finished && safetyCounter < 100000) {
                scene.execute();
                safetyCounter++;
              }
              return { ok: true, errors: [] };
            } catch (e) {
              var msg = (e && e.message) ? String(e.message) : String(e);
              return { ok: false, errors: [msg] };
            }
          };
        })();
    """.trimIndent()

        validationWebView.evaluateJavascript(js) {
            validationReady = true
        }
    }



    // ---- menu helpers ----
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

        // Swap toolbar to text actions menu while there is a selection
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_text_actions)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_all -> {
                    editor.selectAll()
                    true
                }
                R.id.action_cut -> {
                    editor.onTextContextMenuItem(android.R.id.cut)
                    true
                }
                R.id.action_copy -> {
                    editor.onTextContextMenuItem(android.R.id.copy)
                    true
                }
                R.id.action_paste -> {
                    editor.onTextContextMenuItem(android.R.id.paste)
                    true
                }
                R.id.action_bold -> {
                    wrapSelectionWithChoiceScriptTag("[b]", "[/b]")
                    true
                }
                R.id.action_italic -> {
                    wrapSelectionWithChoiceScriptTag("[i]", "[/i]")
                    true
                }
                else -> false
            }
        }
    }
    private fun exitSelectionMode()  { isSelectionMode = false; inflateDefaultMenu() }

    // ---- ChoiceScript error checking (selection only) ----

    private fun confirmFormatDocument() {
        val selStart = editor.selectionStart
        val selEnd   = editor.selectionEnd

        if (selStart == selEnd || selStart < 0 || selEnd < 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.format_confirm_title)
                .setMessage(getString(R.string.format_select_prompt))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.format_confirm_title)
            .setMessage("Check the selected text for ChoiceScript errors?")
            .setPositiveButton(android.R.string.ok) { _, _ -> formatSelectedBlock() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun lineStart(text: CharSequence, index: Int): Int {
        if (index <= 0) return 0
        val i = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0))
        return if (i == -1) 0 else i + 1
    }
    private fun lineEnd(text: CharSequence, index: Int): Int {
        val s = index.coerceAtMost(text.length)
        val i = text.indexOf('\n', s)
        return if (i == -1) text.length else i + 1
    }



    private fun formatSelectedBlock() {
        val editable = editor.text ?: return
        val selStart = editor.selectionStart
        val selEnd   = editor.selectionEnd

        if (selStart < 0 || selEnd < 0 || selStart == selEnd) {
            AlertDialog.Builder(this)
                .setTitle(R.string.format_confirm_title)
                .setMessage(getString(R.string.format_select_prompt))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val blockStart = lineStart(editable, selStart)
        val blockEnd   = lineEnd(editable, selEnd)
        val slice = editable.subSequence(blockStart, blockEnd).toString()

        if (!validationReady) {
            AlertDialog.Builder(this)
                .setTitle("Validator not ready")
                .setMessage("The ChoiceScript validator is still initializing. Please try again in a moment.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        if (slice.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.format_confirm_title)
                .setMessage("Selected text is blank.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val escaped = JSONObject.quote(slice)
        val js = "window.validateChoiceScript(" + escaped + ")"

        validationWebView.evaluateJavascript(js) { json ->
            try {
                val obj = JSONObject(json ?: "{}")
                val ok = obj.optBoolean("ok", false)
                val errorsJson = obj.optJSONArray("errors")

                if (ok || errorsJson == null || errorsJson.length() == 0) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.format_confirm_title)
                            .setMessage("No errors found in selection.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } else {
                    val msgs = mutableListOf<String>()
                    for (i in 0 until errorsJson.length()) {
                        msgs += errorsJson.getString(i)
                    }
                    val message = msgs.joinToString("\n\n")
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("ChoiceScript errors")
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Validation error")
                        .setMessage(e.message ?: "Error while checking selection.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
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

    private fun visibleCharRange(): IntRange {
        val layout = editor.layout ?: return 0..(editor.text?.length ?: 0)
        val first = layout.getLineForVertical(editor.scrollY)
        val last  = layout.getLineForVertical(editor.scrollY + editor.height)
        val pad   = (last - first).coerceAtMost(3)
        val start = layout.getLineStart((first - pad).coerceAtLeast(0))
        val end   = layout.getLineEnd((last + pad).coerceAtMost(layout.lineCount - 1))
        return start..end
    }

    private inner class CSSpan(color: Int) : ForegroundColorSpan(color)

    private fun clearOurSpans(text: Spannable, range: IntRange) {
        val start = range.first
        val endExclusive = range.last + 1
        text.getSpans(start, endExclusive, CSSpan::class.java).forEach { span ->
            text.removeSpan(span)
        }
    }

    // ----------------------------------------------------------------
    // Syntax highlighting for ChoiceScript commands / options / vars
    // ----------------------------------------------------------------
    private fun rehighlight() {
        val editable = editor.text as? Spannable ?: return

        val length = editable.length
        if (length == 0) return

        // For correctness, highlight the whole document.
        val startOffset = 0
        val endOffset = length
        val targetRange = startOffset until endOffset

        // Remove our existing spans in this range
        clearOurSpans(editable, targetRange)

        val slice: CharSequence = editable.subSequence(startOffset, endOffset)

        val optionColor = themeColors.optionColor
        val inlineVarColor = themeColors.inlineVarColor
        val defaultCommandColor = themeColors.defaultCommandColor
        val commandColors = themeColors.commandColors

        // 1. Options (# lines)
        reOption.findAll(slice).forEach { m ->
            editable.setSpan(
                CSSpan(optionColor),
                startOffset + m.range.first,
                startOffset + m.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        // 2. ChoiceScript commands
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
                while (i < lineText.length && (lineText[i].isLetterOrDigit() || lineText[i] == '_')) {
                    i++
                }
                val cmdEnd = i

                if (cmdEnd > cmdStart) {
                    val cmd = lineText.substring(cmdStart, cmdEnd).lowercase()
                    val color = commandColors[cmd] ?: defaultCommandColor

                    val absoluteStart = startOffset + lineStartInSlice + starIndex
                    val absoluteEnd = startOffset + lineStartInSlice + cmdEnd

                    editable.setSpan(
                        CSSpan(color),
                        absoluteStart,
                        absoluteEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }

            indexInSlice = if (newlineIndex == -1) slice.length else newlineIndex + 1
        }

        // 3. Inline variables (${...})
        reInlineVar.findAll(slice).forEach { m ->
            editable.setSpan(
                CSSpan(inlineVarColor),
                startOffset + m.range.first,
                startOffset + m.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    // ---- typing helpers ----
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

        // 1. Logic for increasing indent
        if (trimmed.startsWith("*choice", true) ||
            trimmed.startsWith("*fake_choice", true) ||
            trimmed.startsWith("*if", true) ||
            trimmed.startsWith("#")
        ) {
            indentSpaces = leadingSpaces + 4
        }

        // If the previous line was a *goto or *return, reset indent to 0.
        if (trimmed.startsWith("*goto", true) ||
            trimmed.startsWith("*return", true) ||
            trimmed.startsWith("*finish", true) // Added *finish as it's a similar flow control command
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

        while (start > 0 && text[start - 1].isLetterOrDigit()) {
            start--
        }

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
