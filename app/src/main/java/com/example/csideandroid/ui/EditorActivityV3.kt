package com.example.csideandroid.ui

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.example.csideandroid.R
import com.example.csideandroid.runner.RunnerActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import androidx.core.content.edit

class EditorActivityV3 : AppCompatActivity() {

    private val uiScope = MainScope()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var editor: SoraEditorView

    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnIndent: ImageButton
    private lateinit var btnOutdent: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSave: ImageButton
    private lateinit var btnFormat: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnBold: ImageButton
    private lateinit var btnItalic: ImageButton
    private var searchBar: MaterialCardView? = null
    private var searchInput: EditText? = null
    private var btnSearchPrev: ImageButton? = null
    private var btnSearchNext: ImageButton? = null
    private var btnSearchClose: ImageButton? = null
    private var currentSearchQuery: String = ""

    // Scene side menu (left-slide list of .txt scenes)
    private var scenePanel: MaterialCardView? = null
    private var sceneScrim: View? = null
    private var sceneListContainer: LinearLayout? = null
    private var sceneEdgeIndicator: MaterialCardView? = null
    private var isScenePanelOpen: Boolean = false
    private var scenePanelBackCallback: androidx.activity.OnBackPressedCallback? = null
    private val scenePanelWidthPx: Int by lazy { (240 * resources.displayMetrics.density).toInt() }


    private data class QuicktestError(
        val scene: String,
        val line: Int,
        val message: String
    )

    private var lastQuicktestErrors: List<QuicktestError>? = null


    private var documentUri: Uri? = null
    private var currentDisplayName: String? = null
    private var projectTreeUri: Uri? = null

    // If another screen opens the editor and wants us to jump to a specific line,
    // it can pass: extra_goto_line_1based (Int). We apply it after the document is loaded.
    private var pendingGotoLine0: Int? = null
    private var currentFontSp: Float = 18f
    private val MIN_FONT_SP: Float = 14f
    private val MAX_FONT_SP: Float = 32f
    private val FONT_STEP_SP: Float = 2f

    private var currentUiTint: Int = Color.WHITE

    // Quicktest (ChoiceScript error checker)
    private var quicktestWebView: WebView? = null
    private var quicktestCallback: ((String?) -> Unit)? = null
    private var quicktestTimeoutPosted: Boolean = false

    // ChoiceScript commands list for popup completion
    private val csCommands: List<String> = listOf(
        "achieve","achievement","check_achievements",
        "choice","fake_choice","disable_reuse","hide_reuse","allow_reuse","selectable_if","delay_break",
        "create","create_array","temp","temp_array","set","setref","delete","input_number","input_text","print","rand",
        "if","elseif","else","elsif","return","params",
        "label","goto","goto_scene","goto_random_scene","gosub","gosub_scene","finish","ending","redirect_scene","gotoref",
        "image","text_image","line_break","page_break","link","stat_chart","bold","italic","sound","script","scene_list","pause",
        "title","author","ifid","save_checkpoint","restore_checkpoint","comment","bug","looplimit","more_games",
        "share_this_game","show_password",
        "\${}", "\$!{}"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_editor_v3)

        toolbar = findViewById(R.id.toolbar)
        editor = findViewById(R.id.editor)

        editor.setCommands(csCommands)

        // Apply current app theme (templates + any user overrides) to Sora
        val currentTheme = EditorThemeManager.getCurrentTheme(this)
        val colors = EditorThemeManager.getColorsForSora(this, currentTheme)
        editor.applyThemeColors(colors)

        // Font size: default bigger on open + clamp zoom out
        val prefs = getSharedPreferences("editor_prefs", MODE_PRIVATE)
        currentFontSp = prefs.getFloat("font_sp", 18f).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        editor.setFontSizeSp(currentFontSp)

        // Apply visible indents setting
        editor.setVisibleIndents(prefs.getBoolean(SettingsActivity.KEY_VISIBLE_INDENTS, false))

        // Apply auto-close brackets/quotes setting
        editor.setAutoCloseBrackets(prefs.getBoolean(SettingsActivity.KEY_AUTO_CLOSE_BRACKETS, true))

        // Apply indentation-unit setting
        editor.setUseTabs(prefs.getBoolean(SettingsActivity.KEY_USE_TABS, false))

        // Bottom bar IDs
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnIndent = findViewById(R.id.btnIndent)
        btnOutdent = findViewById(R.id.btnOutdent)
        btnSearch = findViewById(R.id.btnSearchTop)
        btnPlay = findViewById(R.id.btnPlayTop)
        btnSave = findViewById(R.id.btnSave)

        // Scene side menu views
        scenePanel = findViewById(R.id.scenePanel)
        sceneScrim = findViewById(R.id.sceneScrim)
        sceneListContainer = findViewById(R.id.sceneListContainer)
        sceneEdgeIndicator = findViewById(R.id.sceneEdgeIndicator)
        btnFormat = findViewById(R.id.btnFormat)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnBold = findViewById(R.id.btnBold)
        btnItalic = findViewById(R.id.btnItalic)

        // Search bar views
        searchBar = findViewById<View>(R.id.searchBar) as? MaterialCardView
        searchInput = findViewById<View>(R.id.searchInput) as? EditText
        btnSearchPrev = findViewById<View>(R.id.btnSearchPrev) as? ImageButton
        btnSearchNext = findViewById<View>(R.id.btnSearchNext) as? ImageButton
        btnSearchClose = findViewById<View>(R.id.btnSearchClose) as? ImageButton
        if (searchBar != null && searchInput != null && btnSearchPrev != null && btnSearchNext != null && btnSearchClose != null) {
            wireUpSearchBar()
        }

        // Apply surrounding UI theme after buttons exist
        applySurroundingUiTheme(colors)

        btnUndo.setOnClickListener { editor.undo() }
        btnRedo.setOnClickListener { editor.redo() }
        btnIndent.setOnClickListener { editor.outdent() }
        btnOutdent.setOnClickListener { editor.indent() }
        btnSearch.setOnClickListener { onSearchButtonPressed() }
        btnPlay.setOnClickListener { playCurrentProject() }
        btnSave.setOnClickListener { saveDocument(showToast = true) }

        // Scene side menu: start closed, wire toggles.
        setScenePanelWidthPx(0)
        findViewById<View>(R.id.sceneEdgeGrabber)?.setOnClickListener { toggleScenePanel() }
        sceneEdgeIndicator?.setOnClickListener { toggleScenePanel() }
        sceneScrim?.setOnClickListener { setScenePanelOpen(false) }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (isScenePanelOpen) setScenePanelOpen(false)
            }
        }.also { cb ->
            // Keep the callback enabled only while the panel is open.
            scenePanelBackCallback = cb
        })

        // Error Checker button: Quicktest
        btnFormat.setOnClickListener {
            uiScope.launch(Dispatchers.Main) {
                runQuicktestFromEditorButton()
            }
        }

        btnBold.setOnClickListener { editor.toggleBold() }
        btnItalic.setOnClickListener { editor.toggleItalic() }

        btnZoomIn.setOnClickListener {
            currentFontSp = (currentFontSp + FONT_STEP_SP).coerceAtMost(MAX_FONT_SP)
            editor.setFontSizeSp(currentFontSp)
            getSharedPreferences("editor_prefs", MODE_PRIVATE).edit()
                .putFloat("font_sp", currentFontSp)
                .apply()
        }
        btnZoomOut.setOnClickListener {
            currentFontSp = (currentFontSp - FONT_STEP_SP).coerceAtLeast(MIN_FONT_SP)
            editor.setFontSizeSp(currentFontSp)
            getSharedPreferences("editor_prefs", MODE_PRIVATE).edit()
                .putFloat("font_sp", currentFontSp)
                .apply()
        }

        var displayName = intent.getStringExtra("extra_display_name")
        currentDisplayName = displayName
        val projStr = intent.getStringExtra("extra_project_uri")
        if (!projStr.isNullOrBlank()) projectTreeUri = Uri.parse(projStr)
        val uriStr = intent.getStringExtra("extra_document_uri")

        // Optional: jump to a specific line after loading (1-based line number).
        val gotoLine1 = intent.getIntExtra("extra_goto_line_1based", -1)
        if (gotoLine1 > 0) {
            pendingGotoLine0 = (gotoLine1 - 1).coerceAtLeast(0)
        }
        if (!uriStr.isNullOrBlank()) {
            documentUri = Uri.parse(uriStr)
            if (displayName.isNullOrBlank()) {
                DocumentFile.fromSingleUri(this, documentUri!!)?.name?.let {
                    displayName = it
                    currentDisplayName = it
                }
            }
            loadDocumentIntoEditor()
        }

        refreshSceneFileCache()

        toolbar.title = displayName ?: ""
        currentDisplayName = displayName
        toolbar.setTitleTextColor(currentUiTint)
        AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back_24)?.let { d ->
            d.setTint(currentUiTint)
            toolbar.navigationIcon = d
        }
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        setSupportActionBar(toolbar)

        // Long press toolbar area to edit current theme colors
        toolbar.setOnLongClickListener {
            showThemeEditor()
            true
        }

        // Insets padding (keeps bottom bar above keyboard)
        val spacer = findViewById<View>(R.id.statusBarSpacer)
        val scenePanelContent = findViewById<View>(R.id.scenePanelContent)
        val scenePanelBasePaddingTop = scenePanelContent?.paddingTop ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { v, ins ->
            val top = ins.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.layoutParams = v.layoutParams.apply { height = top }
            // The scene panel spans full height
            scenePanelContent?.setPadding(
                scenePanelContent.paddingLeft, scenePanelBasePaddingTop + top,
                scenePanelContent.paddingRight, scenePanelContent.paddingBottom
            )
            ins
        }
        val container = findViewById<View>(R.id.editor_container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, ins ->
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            val nav = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, max(ime.bottom, nav.bottom))
            ins
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.editor_menu, menu)
        // Ensure the theme icon matches current UI tint
        menu?.findItem(R.id.action_theme)?.let { item ->
            val icon = item.icon
            if (icon != null) {
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(icon)
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, currentUiTint)
                item.icon = wrapped
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme -> {
                showThemePicker()
                true
            }
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showThemePicker() {
        val themes = EditorTheme.values()
        val names = themes.map { it.name.replace('_', ' ') }.toTypedArray()
        val current = EditorThemeManager.getCurrentTheme(this)
        val checked = themes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val theme = themes[which]
                EditorThemeManager.setCurrentTheme(this, theme)
                val colors = EditorThemeManager.getColorsForSora(this, theme)
                editor.applyThemeColors(colors)
                applySurroundingUiTheme(colors)
                dialog.dismiss()
            }
            .setNeutralButton("Edit current") { _, _ -> showThemeEditor() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showThemeEditor() {
        val theme = EditorThemeManager.getCurrentTheme(this)
        val colors = EditorThemeManager.getColorsForSora(this, theme)

        val fields = arrayOf(
            "Background" to "background",
            "Text" to "text",
            "Line numbers" to "lineNumber",
            "Choice option (#)" to "optionColor",
            "Inline var (\${...})" to "inlineVarColor",
            "Default command (*cmd)" to "defaultCommandColor"
        )

        fun currentColorFor(field: String): Int = when (field) {
            "background" -> colors.background
            "text" -> colors.text
            "lineNumber" -> colors.lineNumber
            "optionColor" -> colors.optionColor
            "inlineVarColor" -> colors.inlineVarColor
            "defaultCommandColor" -> colors.defaultCommandColor
            else -> colors.text
        }

        val items = fields.map { (label, key) ->
            val c = currentColorFor(key)
            val hex = String.format("#%08X", c)
            "$label  ($hex)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Edit Theme: ${theme.name.replace('_', ' ')}")
            .setItems(items) { _, which ->
                val (label, key) = fields[which]
                showColorHexDialog(label, theme, key, currentColorFor(key))
            }
            .setNeutralButton("Reset") { _, _ ->
                EditorThemeManager.clearThemeOverrides(this, theme)
                val merged = EditorThemeManager.getColorsForSora(this, theme)
                editor.applyThemeColors(merged)
                applySurroundingUiTheme(merged)
                Toast.makeText(this, "Theme reset.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showColorHexDialog(label: String, theme: EditorTheme, field: String, initialColor: Int) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(String.format("#%08X", initialColor))
            setSelection(text.length)
            hint = "#AARRGGBB or #RRGGBB"
        }

        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                val parsed = parseColorSafe(raw)
                if (parsed == null) {
                    Toast.makeText(this, "Invalid color.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                EditorThemeManager.saveThemeOverride(this, theme, field, parsed)
                val merged = EditorThemeManager.getColorsForSora(this, theme)
                editor.applyThemeColors(merged)
                applySurroundingUiTheme(merged)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseColorSafe(input: String): Int? {
        val s = input.trim()
        if (!s.startsWith("#")) return null
        return try {
            when (s.length) {
                7 -> Color.parseColor(s) or (0xFF shl 24)
                9 -> Color.parseColor(s)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadDocumentIntoEditor() {
        val uri = documentUri ?: return
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }.onSuccess { content ->
            editor.setTextContent(content)

            // Apply any pending line-jump request (e.g., from the project error checker / Quicktest).
            pendingGotoLine0?.let { targetLine0 ->
                pendingGotoLine0 = null
                val delays = longArrayOf(0L, 40L, 120L, 250L, 450L, 700L, 1000L)
                for (d in delays) {
                    editor.postDelayed({ editor.goToLine(targetLine0) }, d)
                }
            }
        }.onFailure {
            Toast.makeText(this, "Failed to open file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onSearchButtonPressed() {
        // Prefer the in-layout find bar when present, fallback to the old dialog on older layouts.
        if (searchBar != null) {
            toggleSearchBar()
        } else {
            promptSearch()
        }
    }

    private fun wireUpSearchBar() {
        val bar = searchBar ?: return
        val input = searchInput ?: return
        val prev = btnSearchPrev ?: return
        val next = btnSearchNext ?: return
        val close = btnSearchClose ?: return

        bar.visibility = View.GONE
        currentSearchQuery = ""

        fun runNewSearch(q: String) {
            val trimmed = q.trim()
            currentSearchQuery = trimmed
            if (trimmed.isEmpty()) return
            editor.startSearch(trimmed)
        }

        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                runNewSearch(input.text?.toString().orEmpty())
                true
            } else false
        }

        next.setOnClickListener {
            val q = currentSearchQuery.ifBlank { input.text?.toString().orEmpty().trim() }
            if (q.isBlank()) return@setOnClickListener
            if (currentSearchQuery != q) runNewSearch(q)
            editor.focusEditor()
            editor.findNext(q)
        }
        prev.setOnClickListener {
            val q = currentSearchQuery.ifBlank { input.text?.toString().orEmpty().trim() }
            if (q.isBlank()) return@setOnClickListener
            if (currentSearchQuery != q) runNewSearch(q)
            editor.focusEditor()
            editor.findPrev(q)
        }

        close.setOnClickListener {
            // Clear highlight by switching to a query that won't match, without moving the caret.
            editor.findNext("\u0000")
            currentSearchQuery = ""
            input.setText("")
            bar.visibility = View.GONE
            hideKeyboard(input)
        }
    }

    private fun toggleSearchBar() {
        val bar = searchBar ?: return
        val input = searchInput ?: return
        if (bar.visibility == View.VISIBLE) {
            bar.visibility = View.GONE
            hideKeyboard(input)
        } else {
            bar.visibility = View.VISIBLE
            input.requestFocus()
            showKeyboard(input)
        }
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        view.post { imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun promptSearch() {
        val input = EditText(this).apply {
            hint = getString(R.string.find)
            setSingleLine(true)
        }

        val countView = TextView(this).apply {
            text = "0"
            setPadding(16, 0, 16, 0)
        }

        val btnPrev = Button(this).apply { text = "Prev" }
        val btnNext = Button(this).apply { text = getString(R.string.next) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnPrev)
            addView(countView)
            addView(btnNext)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 0)
            addView(input)
            addView(row)
        }

        var currentQuery = ""

        fun runNewSearch(q: String) {
            val trimmed = q.trim()
            currentQuery = trimmed
            if (trimmed.isEmpty()) {
                countView.text = "0"
                return
            }
            val total = editor.startSearch(trimmed)
            countView.text = total.toString()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.find)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        btnNext.setOnClickListener {
            if (currentQuery.isNotEmpty()) {
                editor.focusEditor()
                editor.findNext(currentQuery)
            } else {
                runNewSearch(input.text?.toString().orEmpty())
            }
        }
        btnPrev.setOnClickListener {
            if (currentQuery.isNotEmpty()) {
                editor.focusEditor()
                editor.findPrev(currentQuery)
            } else {
                runNewSearch(input.text?.toString().orEmpty())
            }
        }

        // Run search when the user presses Enter/Search on the keyboard
        input.setOnEditorActionListener { _, _, _ ->
            runNewSearch(input.text?.toString().orEmpty())
            true
        }

        dialog.show()
    }

    private fun saveDocument(showToast: Boolean) {
        val uri = documentUri ?: return
        uiScope.launch(Dispatchers.Main) {
            val text = editor.getTextContent()
            runCatching {
                contentResolver.openOutputStream(uri, "rwt")?.use { os ->
                    OutputStreamWriter(os).use { it.write(text) }
                }
            }.onSuccess {
                if (showToast) Toast.makeText(this@EditorActivityV3, R.string.saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                if (showToast) Toast.makeText(this@EditorActivityV3, R.string.failed_save, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Optional autosave
        saveDocument(showToast = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun applySurroundingUiTheme(colors: EditorThemeColors) {
        val bg = colors.background
        val bgLum = ColorUtils.calculateLuminance(bg)

        val chrome = if (bgLum >= 0.5) {
            ColorUtils.blendARGB(bg, Color.BLACK, 0.08f)
        } else {
            ColorUtils.blendARGB(bg, Color.WHITE, 0.08f)
        }

        // Bottom bar should stand out a bit more than the padding area.
        val bar = if (bgLum >= 0.5) {
            ColorUtils.blendARGB(bg, Color.BLACK, 0.12f)
        } else {
            ColorUtils.blendARGB(bg, Color.WHITE, 0.12f)
        }

        val tint = if (ColorUtils.calculateLuminance(chrome) >= 0.5) Color.BLACK else Color.WHITE
        currentUiTint = tint

        findViewById<View>(R.id.root)?.setBackgroundColor(chrome)
        findViewById<View>(R.id.statusBarSpacer)?.setBackgroundColor(chrome)
        toolbar.setBackgroundColor(chrome)
        findViewById<View>(R.id.editor_container)?.setBackgroundColor(chrome)
        findViewById<MaterialCardView>(R.id.bottomBar)?.setCardBackgroundColor(bar)

        toolbar.setTitleTextColor(tint)
        toolbar.navigationIcon?.setTint(tint)

        // Bottom bar icon tint
        listOf(btnUndo, btnRedo, btnIndent, btnOutdent, btnBold, btnItalic, btnFormat, btnZoomOut, btnZoomIn, btnSearch, btnSave).forEach { b ->
            b.setColorFilter(tint)
        }
        if (::btnPlay.isInitialized) btnPlay.setColorFilter(tint)

        // Scene side menu: match the chrome/tint used elsewhere.
        scenePanel?.setCardBackgroundColor(bar)
        // Edge grab-handle: chrome-colored card with a tinted chevron.
        sceneEdgeIndicator?.setCardBackgroundColor(bar)
        (sceneEdgeIndicator?.getChildAt(0) as? ImageView)?.setColorFilter(tint)
        findViewById<TextView>(R.id.scenePanelHeader)?.setTextColor(tint)
        // Re-tint any scene rows already inflated.
        sceneListContainer?.let { c ->
            for (i in 0 until c.childCount) (c.getChildAt(i) as? TextView)?.setTextColor(tint)
        }

        // Find bar tint
        searchBar?.setCardBackgroundColor(bar)
        searchInput?.setTextColor(tint)
        searchInput?.setHintTextColor(ColorUtils.setAlphaComponent(tint, 160))
        btnSearchPrev?.setColorFilter(tint)
        btnSearchNext?.setColorFilter(tint)
        btnSearchClose?.setColorFilter(tint)

        // Update the theme icon tint if the menu already exists
        invalidateOptionsMenu()
    }


    // Quicktest (ChoiceScript error checker)


    private fun showQuicktestErrorsDialog(errors: List<QuicktestError>) {
        // Clear stored list so future runs don't accidentally reuse it
        lastQuicktestErrors = null
        var dialog: AlertDialog? = null


        val scroll = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
        }
        scroll.addView(container)

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

        errors.forEachIndexed { index, e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val textBox = android.widget.TextView(this).apply {
                val sceneTitle = if (e.scene.endsWith(".txt", ignoreCase = true)) e.scene else "${e.scene}.txt"
                val linePart = if (e.line > 0) " — line ${e.line}" else ""
                text = "${index + 1}) $sceneTitle$linePart\n${e.message}"
                setPadding(0, 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btn = Button(this).apply {
                text = "Go to"
                isAllCaps = false
                setOnClickListener {
                    dialog?.dismiss()
                    openSceneAndJump(e.scene, e.line)
                }
            }

            row.addView(textBox)
            row.addView(btn)
            container.addView(row)

            // Divider
            if (index != errors.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply { topMargin = dp(10); bottomMargin = dp(10) }
                    setBackgroundColor(Color.parseColor("#22000000"))
                }
                container.addView(divider)
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Quicktest Errors (${errors.size})")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .create()
        dialog.show()
    }

    private fun openSceneAndJump(sceneName: String, lineOneBased: Int) {
        val targetLine0 = (lineOneBased - 1).coerceAtLeast(0)

        // If the error didn't include a line, just open the file (if we can) without jumping.
        val hasLine = lineOneBased > 0

        val projUri = projectTreeUri
        if (projUri == null) {
            if (hasLine) editor.goToLine(targetLine0)
            return
        }

        val project = DocumentFile.fromTreeUri(this, projUri)
        if (project == null) {
            if (hasLine) editor.goToLine(targetLine0)
            return
        }

        val scenesDir = resolveScenes(project)

        // Normalize to a .txt file name
        val wanted = if (sceneName.endsWith(".txt", ignoreCase = true)) sceneName else "$sceneName.txt"

        // If already open, just jump
        val current = currentDisplayName
        if (!current.isNullOrBlank() && current.equals(wanted, ignoreCase = true)) {
            if (hasLine) editor.goToLine(targetLine0)
            return
        }

        val targetFile = scenesDir.listFiles().firstOrNull { it.isFile && (it.name?.equals(wanted, ignoreCase = true) == true) }
        if (targetFile == null) {
            Toast.makeText(this, "Couldn't open $wanted", Toast.LENGTH_SHORT).show()
            return
        }

        documentUri = targetFile.uri
        currentDisplayName = targetFile.name
        toolbar.title = currentDisplayName ?: ""

        loadDocumentIntoEditor()

        if (hasLine) {
            // When switching files, Sora applies text/layout asynchronously. A single post() can run too early:
            // it selects the line but doesn't scroll if the target is off-screen. Retry a few times with small delays.
            val delays = longArrayOf(0L, 40L, 120L, 250L, 450L, 700L, 1000L)
            for (d in delays) {
                editor.postDelayed({ editor.goToLine(targetLine0) }, d)
            }
        }
    }
    private fun resolveScenes(project: DocumentFile): DocumentFile {
        project.findFile("scenes")?.let { return it }
        project.findFile("mygame")?.findFile("scenes")?.let { return it }
        return project
    }

    // Scene side menu
    // Cached list of .txt scene files
    private var cachedSceneFiles: List<DocumentFile>? = null

    private fun setScenePanelWidthPx(widthPx: Int) {
        val panel = scenePanel ?: return
        val w = widthPx.coerceIn(0, scenePanelWidthPx)
        panel.layoutParams = panel.layoutParams.apply { width = w }
        sceneScrim?.visibility = if (w > 0) View.VISIBLE else View.GONE
        // Hide the edge hint while the panel is open so it doesn't sit on top of the list.
        sceneEdgeIndicator?.visibility = if (w > 0) View.GONE else View.VISIBLE
    }

    private fun setScenePanelOpen(open: Boolean) {
        // Refresh the file list only when the user actually opens the panel.
        if (open) {
            // Clear any editor text-selection popup and hide the keyboard so they don't overlap the panel.
            editor.clearFocus()
            hideKeyboard(editor)
            refreshSceneFileCache()
            renderSceneList()
        }
        setScenePanelWidthPx(if (open) scenePanelWidthPx else 0)
        isScenePanelOpen = open
        scenePanelBackCallback?.isEnabled = open
    }

    private fun toggleScenePanel() = setScenePanelOpen(!isScenePanelOpen)

    private fun currentScenesDir(): DocumentFile? {
        val projUri = projectTreeUri ?: return null
        val project = DocumentFile.fromTreeUri(this, projUri) ?: return null
        return resolveScenes(project)
    }

    // SAF I/O — call sparingly
    private fun refreshSceneFileCache() {
        val scenesDir = currentScenesDir()
        val txt = scenesDir?.listFiles()
            ?.filter { it.isFile && (it.name?.endsWith(".txt", ignoreCase = true) == true) }
            ?.sortedBy { it.name?.lowercase() ?: "" }
            ?: emptyList()
        // Honor any user-defined order saved from either the editor or the project browser.
        cachedSceneFiles = applySceneOrder(txt, loadSceneOrder())
    }

    // Order persistence — shares cside_prefs + the "order_files_<project>" key with the
    // project browser, so a reorder in one surface shows up in the other.
    private fun sceneOrderProjectName(): String? {
        val projUri = projectTreeUri ?: return null
        return DocumentFile.fromTreeUri(this, projUri)?.name
    }

    private fun loadSceneOrder(): List<String> {
        val proj = sceneOrderProjectName() ?: return emptyList()
        val raw = getSharedPreferences("cside_prefs", MODE_PRIVATE)
            .getString("order_files_$proj", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    private fun saveSceneOrder(files: List<DocumentFile>) {
        val proj = sceneOrderProjectName() ?: return
        val joined = files.joinToString("|") { it.name ?: "" }
        getSharedPreferences("cside_prefs", MODE_PRIVATE)
            .edit { putString("order_files_$proj", joined) }
    }

    private fun applySceneOrder(raw: List<DocumentFile>, saved: List<String>): List<DocumentFile> {
        if (saved.isEmpty()) return raw
        val map = raw.associateBy { it.name ?: "" }
        val ordered = saved.mapNotNull { map[it] }
        val leftovers = raw.filter { (it.name ?: "") !in saved }
        return ordered + leftovers
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderSceneList() {
        val container = sceneListContainer ?: return
        container.removeAllViews()

        val files = cachedSceneFiles ?: emptyList()
        val pad = (12 * resources.displayMetrics.density).toInt()
        val padH = (20 * resources.displayMetrics.density).toInt()
        val handleSize = (24 * resources.displayMetrics.density).toInt()
        val activeName = currentDisplayName

        for (file in files) {
            val name = file.name ?: continue

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(padH, pad, padH, pad)
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@EditorActivityV3, android.R.drawable.list_selector_background
                )
                // Identify this row by filename for reorder persistence.
                tag = name
            }

            val handle = ImageView(this).apply {
                setImageResource(R.drawable.ic_drag_handle_24)
                val lp = LinearLayout.LayoutParams(handleSize, handleSize)
                lp.marginEnd = (12 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setColorFilter(currentUiTint)
                alpha = 0.6f
                contentDescription = "Reorder"
            }

            val label = TextView(this).apply {
                text = name
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                val isActive = !activeName.isNullOrBlank() && activeName.equals(name, ignoreCase = true)
                setTextColor(currentUiTint)
                alpha = if (isActive) 1.0f else 0.7f
                setTypeface(typeface, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            row.addView(handle)
            row.addView(label)

            // Tap anywhere on the row opens the scene.
            row.setOnClickListener { requestSwitchToScene(file) }

            // Long-press anywhere on the row starts a drag.
            row.setOnLongClickListener {
                val data = android.content.ClipData.newPlainText("sceneRow", name)
                val shadow = View.DragShadowBuilder(row)
                @Suppress("DEPRECATION")
                row.startDrag(data, shadow, row, 0)
                true
            }

            // Touching the handle starts a drag immediately
            handle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    val data = android.content.ClipData.newPlainText("sceneRow", name)
                    val shadow = View.DragShadowBuilder(row)
                    @Suppress("DEPRECATION")
                    row.startDrag(data, shadow, row, 0)
                    true
                } else false
            }

            row.setOnDragListener(sceneRowDragListener)

            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // Handles reordering as a dragged row hovers over its siblings, and persists on drop.
    private val sceneRowDragListener = View.OnDragListener { view, event ->
        val container = sceneListContainer ?: return@OnDragListener false
        val dragged = event.localState as? View ?: return@OnDragListener false
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> {
                if (view === dragged) dragged.alpha = 0.4f
                true
            }
            android.view.DragEvent.ACTION_DRAG_ENTERED -> true
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                val over = view
                if (over !== dragged && over.parent === container) {
                    val fromIdx = container.indexOfChild(dragged)
                    val toIdx = container.indexOfChild(over)
                    if (fromIdx != -1 && toIdx != -1) {
                        container.removeView(dragged)
                        container.addView(dragged, toIdx)
                    }
                }
                true
            }
            android.view.DragEvent.ACTION_DROP -> true
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                dragged.alpha = 1.0f
                dragged.visibility = View.VISIBLE
                if (view === dragged) persistSceneOrderFromViews()
                true
            }
            else -> true
        }
    }

    private fun persistSceneOrderFromViews() {
        val container = sceneListContainer ?: return
        val byName = (cachedSceneFiles ?: emptyList()).associateBy { it.name ?: "" }
        val ordered = ArrayList<DocumentFile>()
        for (i in 0 until container.childCount) {
            val tag = container.getChildAt(i).tag as? String ?: continue
            byName[tag]?.let { ordered.add(it) }
        }
        if (ordered.isNotEmpty()) {
            cachedSceneFiles = ordered
            saveSceneOrder(ordered)
        }
    }

    private fun requestSwitchToScene(file: DocumentFile) {
        val name = file.name ?: return

        // Tapping the file that's already open just closes the panel
        val current = currentDisplayName
        if (!current.isNullOrBlank() && current.equals(name, ignoreCase = true)) {
            setScenePanelOpen(false)
            return
        }

        // Auto-save the current file then switch
        val oldUri = documentUri
        uiScope.launch(Dispatchers.Main) {
            if (oldUri != null) {
                val text = editor.getTextContent()
                runCatching {
                    contentResolver.openOutputStream(oldUri, "rwt")?.use { os ->
                        OutputStreamWriter(os).use { it.write(text) }
                    }
                }
            }
            switchToScene(file)
        }
    }

    private fun switchToScene(file: DocumentFile) {
        documentUri = file.uri
        currentDisplayName = file.name
        toolbar.title = currentDisplayName ?: ""
        loadDocumentIntoEditor()
        renderSceneList()
        setScenePanelOpen(false)
    }

    // Play Button - launch the game in the runner webview

    private fun playCurrentProject() {
        val projUri = projectTreeUri
        if (projUri == null) {
            Toast.makeText(this, "No project to run", Toast.LENGTH_SHORT).show()
            return
        }
        val project = DocumentFile.fromTreeUri(this, projUri)
        if (project == null) {
            Toast.makeText(this, "No project to run", Toast.LENGTH_SHORT).show()
            return
        }

        // Save the open file first so the game runs the latest text, then copy + launch
        // The write is async; the copy must wait for it to finish
        val uri = documentUri
        uiScope.launch(Dispatchers.Main) {
            if (uri != null) {
                val text = editor.getTextContent()
                runCatching {
                    contentResolver.openOutputStream(uri, "rwt")?.use { os ->
                        OutputStreamWriter(os).use { it.write(text) }
                    }
                }
            }
            val scenes = resolveScenes(project)
            val ok = runCatching { copyProjectToRunner(project, scenes) }.isSuccess
            if (ok) {
                startActivity(Intent(this@EditorActivityV3, RunnerActivity::class.java))
            } else {
                Toast.makeText(this@EditorActivityV3, "Couldn't prepare the game", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Ported from ProjectsBrowserActivity so the editor launches the game identically.
    private fun copyProjectToRunner(projectRoot: DocumentFile, srcScenes: DocumentFile) {
        val destRoot = File(filesDir, "runner/mygame")
        val destScenes = File(destRoot, "scenes")
        val destImages = File(destRoot, "images")
        destRoot.deleteRecursively()
        destScenes.mkdirs()

        for (doc in srcScenes.listFiles().orEmpty()) {
            if (!doc.isFile) continue
            val name = doc.name ?: continue
            val outFile = File(destScenes, name)
            contentResolver.openInputStream(doc.uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }

            if (isImageFile(name)) {
                destImages.mkdirs()
                val imgOut = File(destImages, name)
                contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(imgOut).use { output -> input.copyTo(output) }
                }
            }
        }

        val imagesCandidates = listOfNotNull(
            projectRoot.findFile("images"),
            projectRoot.findFile("mygame")?.findFile("images")
        )
        val srcImages = imagesCandidates.firstOrNull { it.isDirectory }
        if (srcImages != null) {
            destImages.mkdirs()
            for (doc in srcImages.listFiles().orEmpty()) {
                if (!doc.isFile) continue
                val name = doc.name ?: continue
                val outFile = File(destImages, name)
                contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
        }

        val mygameCandidates = listOfNotNull(
            projectRoot.findFile("mygame.js"),
            projectRoot.findFile("mygame")?.findFile("mygame.js")
        )
        for (doc in mygameCandidates) {
            if (doc != null && doc.isFile) {
                val outFile = File(destRoot, "mygame.js")
                contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                break
            }
        }

        val indexSrc = assets.open("choicescript/web/mygame/index.html")
        val indexText = indexSrc.bufferedReader().readText()
        val safeName = (projectRoot.name ?: "unnamed")
            .replace("\"", "")
            .replace("\\", "")
        val patched = indexText.replace(
            "window.storeName = null;",
            "window.storeName = \"CS APP_${safeName}\";"
        )
        File(destRoot, "index.html").writeText(patched)
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    private fun readText(df: DocumentFile): String {
        val ins: InputStream = contentResolver.openInputStream(df.uri) ?: return ""
        return ins.use { String(it.readBytes()) }
    }

    private fun parseSceneListFromStartup(startupText: String): List<String> {
        val lines = startupText.replace("\r", "").split("\n")
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].trimStart().startsWith("*scene_list", ignoreCase = true)) {
                idx = i
                break
            }
        }
        if (idx == -1) return emptyList()

        val scenes = mutableListOf<String>()
        var expectedIndent: Int? = null
        for (i in (idx + 1) until lines.size) {
            val raw = lines[i]
            if (raw.trim().isEmpty()) continue
            val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
            if (indent == 0) break
            if (expectedIndent == null) expectedIndent = indent
            if (indent != expectedIndent) continue

            var line = raw.trim()
            // support "$product sceneName" format; we only need the sceneName
            if (line.startsWith("$")) {
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) line = parts[1].trim()
            }
            if (line.isNotBlank()) scenes.add(line)
        }

        // Ensure startup is first if scene_list omits it
        val normalized = scenes.map { it.removeSuffix(".txt") }.toMutableList()
        if (normalized.isEmpty()) return emptyList()
        if (!normalized.first().equals("startup", ignoreCase = true)) {
            if (normalized.none { it.equals("startup", ignoreCase = true) }) {
                normalized.add(0, "startup")
            } else {
                normalized.removeAll { it.equals("startup", ignoreCase = true) }
                normalized.add(0, "startup")
            }
        }
        return normalized
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runQuicktest(openFileName: String?, openFileText: String, callback: (String?) -> Unit) {
        val projUri = projectTreeUri
        if (projUri == null) {
            callback("No project selected.")
            return
        }

        val project = DocumentFile.fromTreeUri(this, projUri)
        if (project == null) {
            callback("No project selected.")
            return
        }

        val scenesDir = resolveScenes(project)

        // Build { sceneNameWithoutExt : text }
        val sceneMap = org.json.JSONObject()
        val files = scenesDir.listFiles().filter { it.isFile && (it.name?.endsWith(".txt", true) == true) }
        for (f in files) {
            val nameWithExt = f.name ?: continue
            val name = nameWithExt.removeSuffix(".txt")

            // If this is the currently-open file, inject the editor's current text (including unsaved edits)
            if (!openFileName.isNullOrBlank() && nameWithExt.equals(openFileName, ignoreCase = true)) {
                sceneMap.put(name, openFileText)
            } else {
                sceneMap.put(name, readText(f))
            }
        }

        val startup = sceneMap.optString("startup", "")
        val sceneList = parseSceneListFromStartup(startup)
        val sceneListJson = org.json.JSONArray()
        for (s in sceneList) sceneListJson.put(s)

        val payload = org.json.JSONObject().apply {
            put("scenes", sceneMap)
            put("sceneList", sceneListJson)
        }

        quicktestCallback = callback

        val webView = WebView(this)
        quicktestWebView = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        class QuicktestBridge {
            @JavascriptInterface
            fun onResult(resultJson: String) {
                runOnUiThread {
                    try {
                        val obj = org.json.JSONObject(resultJson)
                        val status = obj.optString("status")
                        if (status == "OK") {
                            quicktestCallback?.invoke(null)
                        } else {
                            // If the checker returns an errors array, keep them structured so the UI can render per-error actions.
                            val errorsArr = obj.optJSONArray("errors")
                            if (errorsArr != null && errorsArr.length() > 0) {
                                val list = ArrayList<QuicktestError>(errorsArr.length())
                                val sb = StringBuilder()
                                for (i in 0 until errorsArr.length()) {
                                    val er = errorsArr.optJSONObject(i)
                                    val scene = er?.optString("scene")?.takeIf { it.isNotBlank() } ?: "(unknown)"
                                    val line = er?.optInt("line", -1) ?: -1 // 1-based from checker (usually)
                                    val msg = er?.optString("message")?.takeIf { it.isNotBlank() } ?: "(no message)"
                                    list.add(QuicktestError(scene = scene, line = line, message = msg))

                                    // Keep a readable fallback string too
                                    sb.append(i + 1).append(") ").append(scene)
                                    if (line > 0) sb.append(" (line ").append(line).append(")")
                                    sb.append("\n").append(msg)
                                    if (i != errorsArr.length() - 1) sb.append("\n\n")
                                }
                                lastQuicktestErrors = list
                                quicktestCallback?.invoke(sb.toString())
                            } else {
                                quicktestCallback?.invoke(obj.optString("message"))
                            }
                        }
                    } catch (e: Exception) {
                        quicktestCallback?.invoke(resultJson)
                    }
                }
            }
        }

        webView.addJavascriptInterface(QuicktestBridge(), "QuicktestBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                val quoted = org.json.JSONObject.quote(payload.toString())
                view.evaluateJavascript("window.runQuicktestCollectAll($quoted);", null)
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/choicescript/checker/quicktest_checker.html")
    }

    // Called by btnFormat (Error Checker) in the editor bottom bar
    private suspend fun runQuicktestFromEditorButton() {
        val openName = currentDisplayName
        val openText = editor.getTextContent()

        // Non-cancelable loading indicator while the checker runs
        val loadingView = android.widget.ProgressBar(this@EditorActivityV3)
        val loadingDialog = AlertDialog.Builder(this@EditorActivityV3)
            .setTitle("Running error checker")
            .setMessage("Quicktest is running across the whole project. Don’t leave this page until it finishes.")
            .setView(loadingView)
            .setCancelable(false)
            .create()
        loadingDialog.show()

        runQuicktest(openName, openText) { err ->
            loadingDialog.dismiss()

            // Cleanup hidden WebView
            quicktestWebView?.destroy()
            quicktestWebView = null

            if (err == null) {
                Toast.makeText(this@EditorActivityV3, "Quicktest passed (no errors).", Toast.LENGTH_SHORT).show()
            } else {
                val errs = lastQuicktestErrors
                if (!errs.isNullOrEmpty()) {
                    showQuicktestErrorsDialog(errs)
                } else {
                    AlertDialog.Builder(this@EditorActivityV3)
                        .setTitle("Quicktest Error")
                        .setMessage(err)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
}