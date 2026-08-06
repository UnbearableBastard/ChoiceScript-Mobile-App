package com.example.csideandroid.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.WebViewAssetLoader
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import com.example.csideandroid.R
import com.example.csideandroid.runner.RunnerActivity
import com.example.csideandroid.storage.StorageAccess
import com.example.csideandroid.util.WordCountUtil
import com.example.csideandroid.ui.model.ProjectGroup
import com.example.csideandroid.ui.model.ProjectListAdapter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import android.transition.TransitionManager
import android.view.ViewGroup
import android.provider.Settings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.switchmaterial.SwitchMaterial

private const val REQUEST_CREATE_SINGLE_HTML = 2002

class ProjectsBrowserActivity : AppCompatActivity() {

    companion object {
        // Ensures the automatic update check runs only once per cold app process start.
        private var didBootUpdateCheck: Boolean = false
        // Same, but for the "what's new" popup shown once after an update is installed.
        private var didBootWhatsNewCheck: Boolean = false
        // Set once the popup has actually been displayed in this process.
        private var didShowWhatsNew: Boolean = false
        private const val KEY_LAST_SEEN_VERSION = "last_seen_app_version"

        // Survives Activity recreation (rotation, theme toggle, etc.) so recreation never
        // has to re-fetch every project's DocumentFile one at a time on the main thread.
        private val groupCache = mutableMapOf<String, ProjectGroup>()
        private val projectMetaCache = mutableMapOf<String, Pair<String, String>>()
        private val fileWordCountCache = mutableMapOf<String, Int>()
    }


    private val prefs by lazy { getSharedPreferences("cside_prefs", MODE_PRIVATE) }


    private var helpDialog: AlertDialog? = null
    private val PREF_HIDE_HELP_POPUP = "hide_help_popup"

    // Release notes fetched but not yet displayed (queued behind the help popup on first run).
    private var pendingWhatsNew: Pair<String, String>? = null

    private val pickBase =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {

                // Same persistable-permission approach as FirstRunActivity
                val flags =
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: Exception) {
                    // Some OEMs may throw even when permission is effectively OK — ignore
                }

                val picked = DocumentFile.fromTreeUri(this, uri)

                // If user picked the "Choicescript Projects" folder itself, use it directly
                val projects = if (picked?.name == "Choicescript Projects") {
                    picked
                } else {
                    picked?.findFile("Choicescript Projects")
                        ?: picked?.createDirectory("Choicescript Projects")
                }

                if (projects != null) {
                    StorageAccess.setProjectsRoot(this, projects.uri)
                    reload()
                } else {
                    Toast.makeText(this, "Unable to access selected folder.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    // ORDER PERSISTENCE HELPERS

    private fun loadOrder(key: String): List<String> {
        val raw = prefs.getString(key, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    private fun saveOrder(key: String, list: List<File>) {
        val joined = list.joinToString("|") { it.name }
        prefs.edit { putString(key, joined) }
    }

    // Applies saved order; unknown items go to the bottom
    private fun applyOrder(rawList: List<File>, savedNames: List<String>): List<File> {
        if (savedNames.isEmpty()) return rawList
        val nameMap = rawList.associateBy { it.name }
        val ordered = savedNames.mapNotNull { nameMap[it] }
        val leftovers = rawList.filter { it.name !in savedNames }
        return ordered + leftovers
    }

    // File order persistence
    private fun loadFileOrder(projectName: String): List<String> {
        val raw = prefs.getString("order_files_$projectName", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    private fun saveFileOrder(projectName: String, list: List<DocumentFile>) {
        val joined = list.joinToString("|") { it.name ?: "" }
        prefs.edit { putString("order_files_$projectName", joined) }
    }

    private fun applyFileOrder(raw: List<DocumentFile>, saved: List<String>): List<DocumentFile> {
        if (saved.isEmpty()) return raw
        val map = raw.associateBy { it.name ?: "" }
        val ordered = saved.mapNotNull { map[it] }
        val leftovers = raw.filter { (it.name ?: "") !in saved }
        return ordered + leftovers
    }

    // pin logic
    private fun isPinned(name: String): Boolean =
        prefs.getStringSet("pinned_projects", emptySet())!!.contains(name)

    private fun togglePin(name: String) {
        val set = prefs.getStringSet("pinned_projects", emptySet())!!.toMutableSet()
        if (!set.add(name)) set.remove(name)
        prefs.edit { putStringSet("pinned_projects", set) }
    }

    private fun setLastOpenedProject(name: String, t: Long = System.currentTimeMillis()) {
        prefs.edit { putLong("last_opened_project_$name", t) }
    }

    private fun getLastOpenedProject(name: String): Long =
        prefs.getLong("last_opened_project_$name", 0L)

    private lateinit var pinnedGrid: RecyclerView
    private lateinit var recentGrid: RecyclerView
    private lateinit var allGrid: RecyclerView
    private lateinit var emptyView: TextView

    private lateinit var pinnedAdapter: ProjectListAdapter
    private lateinit var recentAdapter: ProjectListAdapter
    private lateinit var allAdapter: ProjectListAdapter

    private lateinit var pinnedTouchHelper: ItemTouchHelper
    private lateinit var recentTouchHelper: ItemTouchHelper
    private lateinit var allTouchHelper: ItemTouchHelper

    private val projectMetaInProgress = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val fileWordCountInProgress = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Word count refresh runs once per menu open in the background, and only recounts
    // projects whose .txt files have changed since last count.
    private val wordRefreshLock = Any()
    @Volatile private var wordRefreshRunning: Boolean = false
    @Volatile private var wordRefreshPending: Boolean = false

    private val projectMetaExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val fileMetaExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Quicktest & compile state.
    private var quicktestWebView: WebView? = null
    private var quicktestCallback: ((String?) -> Unit)? = null
    private var quicktestGroup: ProjectGroup? = null
    private var pendingCompileGroup: ProjectGroup? = null

    private data class QuicktestError(
        val scene: String,
        val line: Int,
        val message: String
    )

    private var lastQuicktestErrors: List<QuicktestError>? = null

    // --- GitHub updates
    private val GITHUB_OWNER = "UnbearableBastard"
    private val GITHUB_REPO = "ChoiceScript-Mobile-App"


    private var currentPinned: List<File> = emptyList()
    private var currentRecent: List<File> = emptyList()
    private var currentAll: List<File> = emptyList()


    private var isSideMenuOpen: Boolean = false
    private var pendingCloseOnUp: Boolean = false
    private var isDraggingMenu: Boolean = false
    private var dragStartX: Float = 0f
    private var dragStartWidth: Int = 0
    private var dragMoved: Boolean = false

    private val sideMenuMaxWidthPx: Int by lazy { dp(220) }
    private val sideMenuEdgePx: Int by lazy { dp(24) }
    private val sideMenuDragSlopPx: Int by lazy { dp(4) }

    private lateinit var menuScrim: View

    private fun toggleSideMenu() {
        setSideMenuOpen(!isSideMenuOpen)
    }

    private fun setSideMenuWidthPx(widthPx: Int, animate: Boolean) {
        val root = findViewById<ViewGroup>(R.id.rootProjectsBrowser)
        val menu = findViewById<View>(R.id.sideMenu)

        if (animate) {
            TransitionManager.beginDelayedTransition(root)
        }

        val lp = menu.layoutParams
        lp.width = widthPx.coerceIn(0, sideMenuMaxWidthPx)
        menu.layoutParams = lp

        // Keep the scrim *outside* the menu so menu buttons remain clickable.
        (menuScrim.layoutParams as? ViewGroup.MarginLayoutParams)?.let { slp ->
            slp.marginStart = lp.width
            menuScrim.layoutParams = slp
        }

        // Show scrim whenever the menu is visible.
        menuScrim.visibility = if (lp.width > 0) View.VISIBLE else View.GONE
    }

    private fun setSideMenuOpen(open: Boolean) {
        setSideMenuWidthPx(if (open) sideMenuMaxWidthPx else 0, animate = true)
        isSideMenuOpen = open
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun handleSideMenuTouch(ev: MotionEvent): Boolean {
        val root = findViewById<View>(R.id.rootProjectsBrowser)
        val menu = findViewById<View>(R.id.sideMenu)

        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val x = ev.rawX - loc[0]

        val currentWidth = menu.layoutParams.width.coerceAtLeast(0)
        val menuVisible = currentWidth > 0

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = x
                dragStartWidth = currentWidth
                dragMoved = false
                pendingCloseOnUp = false
                isDraggingMenu = false

                // If menu is closed, only start gesture from the left edge.
                if (!menuVisible) {
                    if (x <= sideMenuEdgePx) {
                        isDraggingMenu = true
                        // Make scrim visible immediately so taps/drags are captured.
                        menuScrim.visibility = View.VISIBLE
                        return true
                    }
                    return false
                }

                // Menu is visible: tap outside closes; drag can start from menu or edge.
                if (x > currentWidth) {
                    pendingCloseOnUp = true
                    return true
                }

                // Start drag from within menu (or edge) to allow closing by swiping left.
                isDraggingMenu = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingMenu) return pendingCloseOnUp

                val dx = x - dragStartX
                if (!dragMoved && kotlin.math.abs(dx) >= sideMenuDragSlopPx) {
                    dragMoved = true
                    pendingCloseOnUp = false
                }

                val newWidth = (dragStartWidth + dx).toInt()
                setSideMenuWidthPx(newWidth, animate = false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingMenu) {
                    val w = menu.layoutParams.width.coerceAtLeast(0)
                    val shouldOpen = w >= (sideMenuMaxWidthPx / 2)
                    setSideMenuOpen(shouldOpen)
                    isDraggingMenu = false
                    pendingCloseOnUp = false
                    return true
                }

                if (pendingCloseOnUp) {
                    setSideMenuOpen(false)
                    pendingCloseOnUp = false
                    return true
                }

                return false
            }
        }

        return false
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projects_browser)

        // Side menu scrim + edge drag / tap-to-close handling.
        menuScrim = findViewById(R.id.menuScrim)
        val rootTouchTarget = findViewById<View>(R.id.rootProjectsBrowser)
        val menuTouchListener = View.OnTouchListener { _, ev -> handleSideMenuTouch(ev) }
        rootTouchTarget.setOnTouchListener(menuTouchListener)
        menuScrim.setOnTouchListener(menuTouchListener)
        findViewById<View>(R.id.leftEdgeGrabber).setOnTouchListener(menuTouchListener)
        // The side menu always starts closed on launch.
        setSideMenuWidthPx(0, animate = false)


        // Side menu (collapsible) toggle
        findViewById<View>(R.id.btnMenuToggle).setOnClickListener {
            toggleSideMenu()
        }



        findViewById<View>(R.id.btnRelocate).setOnClickListener {
            pickBase.launch(null)
        }


        findViewById<View>(R.id.btnCheckUpdates)?.setOnClickListener {
            checkForUpdates()
            setSideMenuOpen(false)
        }

        findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            setSideMenuOpen(false)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val appPrefs = getSharedPreferences(com.example.csideandroid.CSApp.PREFS_NAME, MODE_PRIVATE)
        val switchDarkMode = findViewById<SwitchMaterial>(R.id.switchDarkMode)
        val savedMode = appPrefs.getInt(
            com.example.csideandroid.CSApp.KEY_NIGHT_MODE,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
        val systemIsDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        switchDarkMode.isChecked = when (savedMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> systemIsDark
        }
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            appPrefs.edit { putInt(com.example.csideandroid.CSApp.KEY_NIGHT_MODE, newMode) }
            AppCompatDelegate.setDefaultNightMode(newMode)
        }
        pinnedGrid = findViewById(R.id.recyclerPinned)
        recentGrid = findViewById(R.id.recyclerRecent)
        allGrid = findViewById(R.id.recyclerAll)
        emptyView = findViewById(R.id.txtEmptyProjects)

        // Right nav bar inset (landscape).
        val projectsScroll = findViewById<View>(R.id.projectsScroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(projectsScroll) { v, insets ->
            val navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, navInsets.right, navInsets.bottom)
            insets
        }

        // Status bar
        val topFillBar = findViewById<View>(R.id.topFillBar)
        val sideMenuContent = findViewById<View>(R.id.sideMenuContent)
        val projectsContent = findViewById<View>(R.id.projectsContent)
        val sideMenuBasePaddingTop = sideMenuContent.paddingTop
        val sideMenuBasePaddingBottom = sideMenuContent.paddingBottom
        val projectsContentBasePaddingTop = projectsContent.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.rootProjectsBrowser)) { _, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()
                        or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            ).top

            val navBottomInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            ).bottom

            topFillBar.layoutParams = topFillBar.layoutParams.apply { height = topInset }
            topFillBar.requestLayout()

            sideMenuContent.setPadding(
                sideMenuContent.paddingLeft, sideMenuBasePaddingTop + topInset,
                sideMenuContent.paddingRight, sideMenuBasePaddingBottom + navBottomInset
            )
            projectsContent.setPadding(
                projectsContent.paddingLeft, projectsContentBasePaddingTop + topInset,
                projectsContent.paddingRight, projectsContent.paddingBottom
            )
            insets
        }

        // Section headers
        val btnPinnedHeader = findViewById<View>(R.id.txtPinnedHeader)
        val btnRecentHeader = findViewById<View>(R.id.txtRecentHeader)
        val btnAllHeader = findViewById<View>(R.id.txtAllHeader)

        fun applyCollapsed(prefKey: String, header: View, list: View) {
            val collapsed = prefs.getBoolean(prefKey, false)
            list.visibility = if (collapsed) View.GONE else View.VISIBLE
            header.alpha = if (collapsed) 0.72f else 1.0f
        }

        fun wireCollapse(prefKey: String, header: View, list: View) {
            header.setOnClickListener {
                val collapseNow = list.visibility == View.VISIBLE
                list.visibility = if (collapseNow) View.GONE else View.VISIBLE
                header.alpha = if (collapseNow) 0.72f else 1.0f
                prefs.edit { putBoolean(prefKey, collapseNow) }
            }
        }

        applyCollapsed("collapse_pinned", btnPinnedHeader, pinnedGrid)
        applyCollapsed("collapse_recent", btnRecentHeader, recentGrid)
        applyCollapsed("collapse_all", btnAllHeader, allGrid)

        wireCollapse("collapse_pinned", btnPinnedHeader, pinnedGrid)
        wireCollapse("collapse_recent", btnRecentHeader, recentGrid)
        wireCollapse("collapse_all", btnAllHeader, allGrid)

        pinnedGrid.layoutManager = LinearLayoutManager(this)
        recentGrid.layoutManager = LinearLayoutManager(this)
        allGrid.layoutManager = LinearLayoutManager(this)

        if (!StorageAccess.hasBase(this)) {
            startActivity(Intent(this, FirstRunActivity::class.java))
            finish()
            return
        }

        val meta: (File) -> Pair<String, String> = { f -> metaForProject(f.name) }
        val isPinnedCheck: (File) -> Boolean = { f -> isPinned(f.name) }
        val fileMeta: (ProjectGroup, DocumentFile) -> Pair<String, String> = { g, f -> metaForFile(g, f) }

        pinnedAdapter = ProjectListAdapter(
            emptyList(), isPinnedCheck, meta, fileMeta,
            onOpen = { toggleExpandByName("pinned", it.name) },
            onTogglePin = { togglePin(it.name); reload() },
            onShowProjectMenu = { f, anchor -> showProjectMenu(f.name, anchor) },
            onToggleExpand = { g -> toggleExpand("pinned", g, pinnedAdapter) },
            onReorderProjects = { list -> currentPinned = list.map { it.projectFile }; saveOrder("order_pinned", currentPinned) },
            onReorderFiles = { g, files -> saveFileOrder(g.projectFile.name, files) },
            onOpenFile = { g, f -> openFile(g, f) },
            onFileMenu = { g, f, anchor -> showFileMenu(g, f, anchor, pinnedAdapter) },
            onActionNew = { g -> promptNewFile(g, pinnedAdapter) },
            onActionPlay = { g -> playProject(g) },
            onActionTest = { g -> runQuicktestForGroup(g) },
            onActionCompile = { g -> promptCompileToSingleHtml(g) },
            onActionMore = { g, anchor -> showGroupMoreMenu(g, anchor) }
        )

        recentAdapter = ProjectListAdapter(
            emptyList(), isPinnedCheck, meta, fileMeta,
            onOpen = { toggleExpandByName("recent", it.name) },
            onTogglePin = { togglePin(it.name); reload() },
            onShowProjectMenu = { f, anchor -> showProjectMenu(f.name, anchor) },
            onToggleExpand = { g -> toggleExpand("recent", g, recentAdapter) },
            onReorderProjects = { list -> currentRecent = list.map { it.projectFile }; saveOrder("order_recent", currentRecent) },
            onReorderFiles = { g, files -> saveFileOrder(g.projectFile.name, files) },
            onOpenFile = { g, f -> openFile(g, f) },
            onFileMenu = { g, f, anchor -> showFileMenu(g, f, anchor, recentAdapter) },
            onActionNew = { g -> promptNewFile(g, recentAdapter) },
            onActionPlay = { g -> playProject(g) },
            onActionTest = { g -> runQuicktestForGroup(g) },
            onActionCompile = { g -> promptCompileToSingleHtml(g) },
            onActionMore = { g, anchor -> showGroupMoreMenu(g, anchor) }
        )

        allAdapter = ProjectListAdapter(
            emptyList(), isPinnedCheck, meta, fileMeta,
            onOpen = { toggleExpandByName("all", it.name) },
            onTogglePin = { togglePin(it.name); reload() },
            onShowProjectMenu = { f, anchor -> showProjectMenu(f.name, anchor) },
            onToggleExpand = { g -> toggleExpand("all", g, allAdapter) },
            onReorderProjects = { list -> currentAll = list.map { it.projectFile }; saveOrder("order_all", currentAll) },
            onReorderFiles = { g, files -> saveFileOrder(g.projectFile.name, files) },
            onOpenFile = { g, f -> openFile(g, f) },
            onFileMenu = { g, f, anchor -> showFileMenu(g, f, anchor, allAdapter) },
            onActionNew = { g -> promptNewFile(g, allAdapter) },
            onActionPlay = { g -> playProject(g) },
            onActionTest = { g -> runQuicktestForGroup(g) },
            onActionCompile = { g -> promptCompileToSingleHtml(g) },
            onActionMore = { g, anchor -> showGroupMoreMenu(g, anchor) }
        )

        pinnedGrid.adapter = pinnedAdapter
        recentGrid.adapter = recentAdapter
        allGrid.adapter = allAdapter

        setupDragAndDrop()
        findViewById<View>(R.id.btnTutorial)?.setOnClickListener {
            startActivity(Intent(this, TutorialActivity::class.java))
        }

        findViewById<View>(R.id.btnHelp)?.setOnClickListener {
            showHelpPopup(force = true)
            setSideMenuOpen(false)
        }

        findViewById<View>(R.id.btnNewProject)?.setOnClickListener {
            promptNewProject()
        }
        reload()
        // Populate/refresh cached word counts in the background on first open.
        refreshWordCountsIfNeeded()

        // Automatic update check: run only once per cold app process start.
        if (!didBootUpdateCheck) {
            didBootUpdateCheck = true
            checkForUpdates(userInitiated = false)
        }

        if (!didBootWhatsNewCheck) {
            didBootWhatsNewCheck = true
            checkWhatsNew()
        }
    }


    override fun onResume() {
        super.onResume()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        showHelpPopup(force = false)
        reload()
        refreshWordCountsIfNeeded()
        if (!didBootUpdateCheck) {
            didBootUpdateCheck = true
            checkForUpdates(userInitiated = false)
        }

        if (!didBootWhatsNewCheck) {
            didBootWhatsNewCheck = true
            checkWhatsNew()
        }

        // Notes may have arrived while the activity was paused.
        flushPendingWhatsNew()
    }


    override fun onDestroy() {
        super.onDestroy()
        projectMetaExecutor.shutdownNow()
        fileMetaExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        quicktestWebView?.destroy()
        quicktestWebView = null
    }

    private fun checkWhatsNew() {
        val current = getCurrentVersionName()
        if (current.isBlank()) return
        if (didShowWhatsNew) return

        val lastSeen = prefs.getString(KEY_LAST_SEEN_VERSION, null)

        // lastSeen == null is a fresh install, which should see the notes once.
        // Only an exact match means this version's notes have already been shown.
        if (lastSeen == current) return

        updateExecutor.execute {
            val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            try {
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "$GITHUB_OWNER-$GITHUB_REPO-Android")
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val respBody = BufferedReader(InputStreamReader(stream)).use { it.readText() }

                // Network/API failure: leave the stored version alone so the next
                // launch retries instead of silently burning the notes.
                if (code !in 200..299) return@execute

                val json = JSONObject(respBody)
                val tag = json.optString("tag_name", current).trim()
                val notes = markdownToPlain(json.optString("body", "").trim())

                // Nothing worth showing — mark seen so we stop asking for this version.
                if (notes.isBlank()) {
                    prefs.edit { putString(KEY_LAST_SEEN_VERSION, current) }
                    return@execute
                }

                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    pendingWhatsNew = tag to notes
                    flushPendingWhatsNew()
                }
            } catch (_: Throwable) {
            }
        }
    }


    private fun flushPendingWhatsNew() {
        if (didShowWhatsNew) {
            pendingWhatsNew = null
            return
        }
        val pending = pendingWhatsNew ?: return
        if (isDestroyed || isFinishing) return

        // Help popup is on screen. Wait for its dismiss listener to call us back.
        if (helpDialog?.isShowing == true) return

        // Help popup is due but hasn't been created yet (fresh install, onResume
        // not reached). Wait rather than stacking on top of it.
        if (helpDialog == null && !prefs.getBoolean(PREF_HIDE_HELP_POPUP, false)) return

        pendingWhatsNew = null
        didShowWhatsNew = true

        val current = getCurrentVersionName()
        if (current.isNotBlank()) {
            prefs.edit { putString(KEY_LAST_SEEN_VERSION, current) }
        }
        showWhatsNewPopup(pending.first, pending.second)
    }

    private fun markdownToPlain(text: String): String {
        return text
            .lines()
            .joinToString("\n") { line ->
                line.trim()
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .let { Regex("^#+\\s*").replace(it, "") }
            }
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("__(.*?)__"), "$1")
            .replace(Regex("`(.*?)`"), "$1")
            .trim()
    }

    private fun showWhatsNewPopup(version: String, notes: String) {
        val view = layoutInflater.inflate(R.layout.dialog_whats_new, null)
        view.findViewById<TextView>(R.id.txtWhatsNewTitle).text = "What's New in $version"
        view.findViewById<TextView>(R.id.txtWhatsNewBody).text = notes

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        view.findViewById<View>(R.id.btnWhatsNewClose).setOnClickListener { dialog.dismiss() }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.8).toInt()
        )
        dialog.show()
    }

    private fun checkForUpdates(userInitiated: Boolean = true) {
        if (userInitiated) {
            Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        }
        updateExecutor.execute {
            val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            try {
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "$GITHUB_OWNER-$GITHUB_REPO-Android")
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                if (code !in 200..299) {
                    if (userInitiated) {
                        runOnUiThread {
                            Toast.makeText(this, "Update check failed ($code).", Toast.LENGTH_LONG).show()
                        }
                    }
                    return@execute
                }

                val json = JSONObject(body)
                val latestTag = json.optString("tag_name", "").trim()
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var apkName: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        val url = a.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                            apkUrl = url
                            apkName = name
                            break
                        }
                    }
                }

                val current = getCurrentVersionName()
                val latest = latestTag

                val isNewer = isVersionNewer(latest, current)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (!isNewer) {
                        if (userInitiated) {
                            Toast.makeText(this, "You have the latest version ($current).", Toast.LENGTH_LONG).show()
                        }
                    } else if (apkUrl.isNullOrBlank()) {
                        AlertDialog.Builder(this)
                            .setTitle("Update available")
                            .setMessage("A newer version ($latestTag) is available, but no APK asset was found in the latest GitHub release.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Update available")
                            .setMessage("A newer version ($latestTag) is available. Download and install now?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Download") { _, _ ->
                                downloadAndInstallApk(apkUrl!!, apkName ?: "update.apk")
                            }
                            .show()
                    }
                }
            } catch (t: Throwable) {
                if (userInitiated) {
                    runOnUiThread {
                        if (!isDestroyed) {
                            Toast.makeText(this, "Update check failed: ${t.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun downloadAndInstallApk(downloadUrl: String, suggestedName: String) {
        // If the user hasn't allowed installs from this app, prompt the setting.
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow installs")
                .setMessage("To install updates, allow this app to install unknown apps in Android settings.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .show()
            return
        }

        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
        updateExecutor.execute {
            try {
                val fileName = suggestedName.ifBlank { "update.apk" }
                val outFile = File(getExternalFilesDir(null), fileName)
                val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "$GITHUB_OWNER-$GITHUB_REPO-Android")
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    BufferedInputStream(input).use { bis ->
                        FileOutputStream(outFile).use { fos ->
                            BufferedOutputStream(fos).use { bos ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val r = bis.read(buf)
                                    if (r <= 0) break
                                    bos.write(buf, 0, r)
                                }
                                bos.flush()
                            }
                        }
                    }
                }

                runOnUiThread {
                    if (!isDestroyed) promptInstallApk(outFile)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (!isDestroyed) {
                        Toast.makeText(this, "Download failed: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    private fun setPendingUpdateRelaunch(pending: Boolean) {
        getSharedPreferences("update_relaunch", MODE_PRIVATE)
            .edit()
            .putBoolean("pending", pending)
            .apply()
    }

    private fun promptInstallApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setPendingUpdateRelaunch(true)
            startActivity(intent)
        } catch (t: Throwable) {
            setPendingUpdateRelaunch(false)
            Toast.makeText(this, "Could not launch installer: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }


    private fun getCurrentVersionName(): String {
        // Use PackageManager so this works regardless of BuildConfig generation / module boundaries.
        return try {
            @Suppress("DEPRECATION")
            val pi = packageManager.getPackageInfo(packageName, 0)
            (pi.versionName ?: "").trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        // Robust SemVer-ish compare (handles: v1.2.3, 1.2, release-1.2.3, 1.2.3-rc.1, 1.2.3+build).
        // If we can't parse BOTH, return false to avoid false-positive "update available".
        data class SemVer(
            val major: Int,
            val minor: Int,
            val patch: Int,
            val pre: List<String> = emptyList()
        )

        fun parseSemVerLike(raw: String): SemVer? {
            val s = raw.trim()
            if (s.isBlank()) return null

            val m = Regex(
                """(?i)v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?"""
            ).find(s) ?: return null

            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val patch = m.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0

            val preRaw = m.groupValues[4].takeIf { it.isNotBlank() }
            val pre = preRaw?.split('.')?.filter { it.isNotBlank() } ?: emptyList()

            return SemVer(major, minor, patch, pre)
        }

        fun compareSemVer(a: SemVer, b: SemVer): Int {
            if (a.major != b.major) return a.major.compareTo(b.major)
            if (a.minor != b.minor) return a.minor.compareTo(b.minor)
            if (a.patch != b.patch) return a.patch.compareTo(b.patch)

            val aPre = a.pre
            val bPre = b.pre
            val aHasPre = aPre.isNotEmpty()
            val bHasPre = bPre.isNotEmpty()

            if (!aHasPre && !bHasPre) return 0
            if (!aHasPre && bHasPre) return 1
            if (aHasPre && !bHasPre) return -1

            val n = minOf(aPre.size, bPre.size)
            for (i in 0 until n) {
                val ai = aPre[i]
                val bi = bPre[i]
                if (ai == bi) continue

                val aNum = ai.toIntOrNull()
                val bNum = bi.toIntOrNull()

                when {
                    aNum != null && bNum != null -> {
                        if (aNum != bNum) return aNum.compareTo(bNum)
                    }
                    aNum != null && bNum == null -> return -1
                    aNum == null && bNum != null -> return 1
                    else -> {
                        val c = ai.compareTo(bi)
                        if (c != 0) return c
                    }
                }
            }
            return aPre.size.compareTo(bPre.size)
        }

        val a = parseSemVerLike(latest) ?: return false
        val b = parseSemVerLike(current) ?: return false

        return compareSemVer(a, b) > 0
    }


    private fun showHelpPopup(force: Boolean) {
        if (!force && prefs.getBoolean(PREF_HIDE_HELP_POPUP, false)) return
        if (helpDialog?.isShowing == true) return

        val view = layoutInflater.inflate(R.layout.dialog_help, null)
        val cb = view.findViewById<CheckBox>(R.id.checkHideHelp)
        cb.isChecked = prefs.getBoolean(PREF_HIDE_HELP_POPUP, false)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener {
                prefs.edit { putBoolean(PREF_HIDE_HELP_POPUP, cb.isChecked) }
                flushPendingWhatsNew()
            }
            .create()

        view.findViewById<View>(R.id.btnHelpOk).setOnClickListener { dialog.dismiss() }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        helpDialog = dialog
        dialog.show()
    }


    // META SYSTEM

    private fun metaForProject(name: String): Pair<String, String> {
        val t = getLastOpenedProject(name)
        val bottom =
            if (t > 0) "Last opened " + DateUtils.getRelativeTimeSpanString(
                t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            else "Never opened"

        // Always return immediately to keep the menu snappy
        // Word counts are refreshed in the background once per menu open
        projectMetaCache[name]?.let { return it.first to bottom }

        // Show last cached value instantly
        prefs.getString("wc_top2_$name", null)?.takeIf { it.isNotBlank() }?.let { top ->
            val meta = top to bottom
            projectMetaCache[name] = meta
            return meta
        }

        val base = "Calculating…" to bottom
        projectMetaCache[name] = base
        return base
    }

    private fun metaForFile(group: ProjectGroup, doc: DocumentFile): Pair<String, String> {
        val projectName = group.projectFile.name
        val fname = doc.name ?: return "—" to ""
        val key = "$projectName/$fname"

        val sizeStr = try { Formatter.formatShortFileSize(this, doc.length()) } catch (_: Exception) { "—" }
        val lm = try { doc.lastModified() } catch (_: Exception) { 0L }
        val whenStr = if (lm > 0)
            DateUtils.getRelativeTimeSpanString(lm, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        else ""

        val cachedWords = fileWordCountCache[key]
        if (cachedWords == null) {
            requestFileWordCount(key, doc)
        }
        val bottom = if (cachedWords != null) {
            val label = if (cachedWords == 1) "word" else "words"
            "$whenStr · $cachedWords $label"
        } else {
            whenStr
        }

        return sizeStr to bottom
    }

    private fun requestFileWordCount(key: String, doc: DocumentFile) {
        if (!fileWordCountInProgress.add(key)) return
        fileMetaExecutor.execute {
            val words = try {
                WordCountUtil.countWordsInFileBoth(doc, contentResolver).withoutCode
            } catch (_: Throwable) {
                0
            }
            fileWordCountCache[key] = words
            fileWordCountInProgress.remove(key)
            runOnUiThread {
                if (!isDestroyed) {
                    pinnedAdapter.notifyDataSetChanged()
                    recentAdapter.notifyDataSetChanged()
                    allAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun refreshWordCountsIfNeeded() {
        synchronized(wordRefreshLock) {
            if (wordRefreshRunning) {
                // A refresh is already in-flight (e.g., user navigated away and came back quickly)
                // Mark pending so we run exactly one more pass right after the current one finishes
                wordRefreshPending = true
                return
            }
            wordRefreshRunning = true
        }

        val root = StorageAccess.getProjectsRoot(this)
        if (root == null) {
            synchronized(wordRefreshLock) { wordRefreshRunning = false }
            return
        }

        // Snapshot the currently displayed project names.
        val names = LinkedHashSet<String>().apply {
            currentPinned.forEach { add(it.name) }
            currentRecent.forEach { add(it.name) }
            currentAll.forEach { add(it.name) }
        }

        if (names.isEmpty()) {
            synchronized(wordRefreshLock) { wordRefreshRunning = false }
            return
        }

        projectMetaExecutor.execute {
            var anyUiChange = false
            val nf = NumberFormat.getIntegerInstance()

            try {
                for (name in names) {
                    val proj = root.findFile(name) ?: continue

                    // Fast fingerprint (metadata-only): max lastModified + txt file count.
                    val stats = scanTxtStats(proj)
                    val fp = "${stats.maxLastModified}|${stats.txtCount}"

                    val fpKey = "wc_fp2_$name"
                    val topKey = "wc_top2_$name"
                    val prevFp = prefs.getString(fpKey, null)
                    val cachedTop = prefs.getString(topKey, null)

                    if (prevFp == fp) {
                        // Unchanged since last count: ensure cache is filled from prefs if needed.
                        if (projectMetaCache[name] == null && !cachedTop.isNullOrBlank()) {
                            projectMetaCache[name] = (cachedTop to "")
                            anyUiChange = true
                        }
                        continue
                    }

                    // Changed: re-count words and update cache + prefs.
                    val counts = WordCountUtil.countWordsInProjectBoth(proj, contentResolver)
                    val withStr = nf.format(counts.withCode)
                    val withoutStr = nf.format(counts.withoutCode)
                    val top = "${stats.txtCount} scenes — $withStr words\n$withoutStr words w/o code"

                    prefs.edit {
                        putString(fpKey, fp)
                        putString(topKey, top)
                    }

                    projectMetaCache[name] = (top to "")
                    anyUiChange = true
                }

                if (anyUiChange) {
                    runOnUiThread {
                        if (!isDestroyed) {
                            pinnedAdapter.update(groupsFor("pinned", currentPinned))
                            recentAdapter.update(groupsFor("recent", currentRecent))
                            allAdapter.update(groupsFor("all", currentAll))
                        }
                    }
                }
            } finally {
                var runAgain = false
                synchronized(wordRefreshLock) {
                    wordRefreshRunning = false
                    if (wordRefreshPending) {
                        wordRefreshPending = false
                        runAgain = true
                    }
                }
                if (runAgain) {
                    // Queue exactly one follow-up pass; keeps UI instant and avoids constant updates.
                    runOnUiThread { if (!isDestroyed) refreshWordCountsIfNeeded() }
                }
            }
        }
    }

    private data class TxtStats(val txtCount: Int, val maxLastModified: Long)

    // Metadata-only scan of .txt files under the project folder
    private fun scanTxtStats(projectDir: DocumentFile): TxtStats {
        var count = 0
        var maxMod = 0L

        val stack = ArrayDeque<DocumentFile>()
        stack.add(projectDir)

        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                emptyArray<DocumentFile>()
            }

            for (c in children) {
                if (c.isDirectory) {
                    stack.add(c)
                } else if (c.isFile) {
                    val n = c.name ?: continue
                    if (n.endsWith(".txt", ignoreCase = true)) {
                        count++
                        val lm = try { c.lastModified() } catch (_: Throwable) { 0L }
                        if (lm > maxMod) maxMod = lm
                    }
                }
            }
        }

        return TxtStats(count, maxMod)
    }

    // GROUP CONSTRUCTION

    private fun groupsFor(sectionKey: String, files: List<File>): List<ProjectGroup> {
        val root = StorageAccess.getProjectsRoot(applicationContext) ?: return emptyList()
        return files.mapNotNull { f ->
            val cacheKey = "$sectionKey/${f.name}"
            val existing = groupCache[cacheKey]
            if (existing != null) {
                existing
            } else {
                val doc = root.findFile(f.name) ?: return@mapNotNull null
                val g = ProjectGroup(projectFile = f, projectDoc = doc)
                groupCache[cacheKey] = g
                g
            }
        }
    }

    private fun toggleExpandByName(sectionKey: String, name: String) {
        val cacheKey = "$sectionKey/$name"
        val g = groupCache[cacheKey] ?: return
        val adapter = when (sectionKey) {
            "pinned" -> pinnedAdapter
            "recent" -> recentAdapter
            else -> allAdapter
        }
        toggleExpand(sectionKey, g, adapter)
    }

    private fun toggleExpand(sectionKey: String, group: ProjectGroup, adapter: ProjectListAdapter) {
        if (!group.expanded) {
            group.expanded = true
            setLastOpenedProject(group.projectFile.name)
            if (!group.filesLoaded) {
                loadFilesForGroup(group, adapter)
            } else {
                adapter.refreshGroup(group)
            }
        } else {
            group.expanded = false
            adapter.refreshGroup(group)
        }
    }

    private fun resolveScenes(project: DocumentFile): DocumentFile {
        project.findFile("scenes")?.let { return it }
        project.findFile("mygame")?.findFile("scenes")?.let { return it }
        return project
    }

    private fun loadFilesForGroup(group: ProjectGroup, adapter: ProjectListAdapter) {
        group.loading = true
        adapter.refreshGroup(group)

        fileMetaExecutor.execute {
            val scenes = resolveScenes(group.projectDoc)
            val raw = try {
                scenes.listFiles().filter { it.isFile }
            } catch (_: Throwable) {
                emptyList()
            }
            val saved = loadFileOrder(group.projectFile.name)
            val ordered = applyFileOrder(raw, saved)

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                group.scenesDoc = scenes
                group.files = ordered.toMutableList()
                group.filesLoaded = true
                group.loading = false
                adapter.refreshGroup(group)
            }
        }
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp",
            "tga", "tif", "tiff", "svg", "heic", "heif", "avif"
        )
    }

    private fun openFile(group: ProjectGroup, doc: DocumentFile) {
        val name = doc.name ?: return
        if (isImageFile(name)) {
            Toast.makeText(this, "Image files can't be opened in the editor.", Toast.LENGTH_SHORT).show()
            return
        }
        val projectUriStr = group.projectDoc.uri.toString()
        startActivity(
            Intent(this, EditorActivityV3::class.java)
                .putExtra("extra_document_uri", doc.uri.toString())
                .putExtra("extra_display_name", doc.name)
                .putExtra("extra_project_uri", projectUriStr)
        )
    }

    private fun openSceneAndJump(group: ProjectGroup, sceneName: String, lineOneBased: Int) {
        val scenes = group.scenesDoc ?: resolveScenes(group.projectDoc)
        val wanted = if (sceneName.endsWith(".txt", ignoreCase = true)) sceneName else "$sceneName.txt"

        fun findIn(dir: DocumentFile): DocumentFile? {
            dir.listFiles().forEach { f ->
                if (f.isFile && f.name.equals(wanted, ignoreCase = true)) return f
            }
            return null
        }

        var doc: DocumentFile? = findIn(scenes)
        if (doc == null && scenes != group.projectDoc) doc = findIn(group.projectDoc)

        if (doc == null) {
            Toast.makeText(this, "Couldn't find scene: $wanted", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, EditorActivityV3::class.java)
            .putExtra("extra_document_uri", doc.uri.toString())
            .putExtra("extra_display_name", doc.name)
            .putExtra("extra_project_uri", group.projectDoc.uri.toString())

        if (lineOneBased > 0) {
            intent.putExtra("extra_goto_line_1based", lineOneBased)
        }
        startActivity(intent)
    }

    private fun promptNewFile(group: ProjectGroup, adapter: ProjectListAdapter) {
        val scenes = group.scenesDoc ?: resolveScenes(group.projectDoc).also { group.scenesDoc = it }
        val input = android.widget.EditText(this).apply {
            hint = "Filename (e.g., chapter1.txt)"
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("New text file")
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                var name = input.text?.toString()?.trim().orEmpty()
                if (!name.endsWith(".txt", ignoreCase = true)) name += ".txt"
                if (name.isBlank()) {
                    input.error = "Please enter a name."
                    return@setOnClickListener
                }
                if (scenes.findFile(name) != null) {
                    input.error = "A file with that name already exists."
                    return@setOnClickListener
                }
                val created = scenes.createFile("text/plain", name) != null
                if (!created) {
                    Toast.makeText(this, "Could not create file", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                group.filesLoaded = false
                loadFilesForGroup(group, adapter)
            }
        }
        dialog.show()
    }

    private fun showFileMenu(group: ProjectGroup, doc: DocumentFile, anchor: View, adapter: ProjectListAdapter) {
        val name = doc.name ?: return
        val protectedFile = isImageFile(name) ||
                name.equals("startup.txt", true) ||
                name.equals("choicescript_stats.txt", true)

        val popup = android.widget.PopupMenu(this, anchor)
        if (!protectedFile) {
            popup.menu.add("Rename")
            popup.menu.add("Delete")
        } else {
            popup.menu.add("Protected")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Rename" -> {
                    val input = android.widget.EditText(this).apply {
                        setText(name)
                        setSelection(text.length)
                    }
                    val wrap = android.widget.FrameLayout(this).apply {
                        val pad = dp(20)
                        setPadding(pad, pad, pad, pad)
                        addView(input)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Rename file")
                        .setView(wrap)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val newName = input.text?.toString()?.trim().orEmpty()
                            if (newName.isNotBlank()) {
                                try {
                                    android.provider.DocumentsContract.renameDocument(
                                        contentResolver, doc.uri, newName
                                    )
                                } catch (_: Exception) {}
                                group.filesLoaded = false
                                loadFilesForGroup(group, adapter)
                            }
                        }.show()
                }
                "Delete" -> {
                    if (!doc.delete()) {
                        Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                    group.filesLoaded = false
                    loadFilesForGroup(group, adapter)
                }
            }
            true
        }
        popup.show()
    }

    private fun uploadProjectFolder(name: String) {
        val root = StorageAccess.getProjectsRoot(this) ?: return
        val df = root.findFile(name) ?: return
        try {
            val zipFile = createProjectZip(df)
            val displayName = (df.name ?: "project") + ".zip"
            val uri = FileProvider.getUriForFile(
                this,
                "com.example.csideandroid.fileprovider",
                zipFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_TITLE, displayName)
            }
            startActivity(Intent.createChooser(shareIntent, "Upload project"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun promptRenameProject(name: String) {
        val root = StorageAccess.getProjectsRoot(this) ?: return
        val df = root.findFile(name) ?: return
        val input = android.widget.EditText(this).apply {
            setText(name)
            setSelection(text.length)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename project")
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val nn = input.text?.toString()?.trim().orEmpty()
                if (nn.isNotEmpty()) {
                    try {
                        android.provider.DocumentsContract.renameDocument(
                            contentResolver, df.uri, nn
                        )
                    } catch (_: Exception) {}
                    reload()
                }
            }.show()
    }

    private fun deleteProject(name: String) {
        val root = StorageAccess.getProjectsRoot(this) ?: return
        val df = root.findFile(name) ?: return
        df.delete()
        reload()
    }

    private fun showGroupMoreMenu(group: ProjectGroup, anchor: View) {
        val name = group.projectFile.name
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add("Upload to CoG Demos")
        popup.menu.add("Upload entire folder")
        popup.menu.add("Rename project")
        popup.menu.add("Delete project")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Upload to CoG Demos" -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cogdemos.ink/")))
                    } catch (_: Throwable) {
                        Toast.makeText(this, "No browser found to open CoG Demos.", Toast.LENGTH_SHORT).show()
                    }
                }
                "Upload entire folder" -> uploadProjectFolder(name)
                "Rename project" -> promptRenameProject(name)
                "Delete project" -> deleteProject(name)
            }
            true
        }
        popup.show()
    }

    // RUN / PLAY

    private fun playProject(group: ProjectGroup) {
        val scenes = group.scenesDoc ?: resolveScenes(group.projectDoc).also { group.scenesDoc = it }
        copyProjectToRunner(group.projectDoc, scenes)
        startActivity(Intent(this, RunnerActivity::class.java))
    }

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

    // Quicktest/Error Checker
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
            if (line.startsWith("$")) {
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) line = parts[1].trim()
            }
            if (line.isNotBlank()) scenes.add(line)
        }

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

    private fun readText(df: DocumentFile): String {
        val ins: InputStream = contentResolver.openInputStream(df.uri) ?: return ""
        return ins.use { String(it.readBytes()) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runQuicktest(group: ProjectGroup, callback: (String?) -> Unit) {
        val scenes = group.scenesDoc ?: resolveScenes(group.projectDoc).also { group.scenesDoc = it }

        val sceneMap = JSONObject()
        val files = scenes.listFiles().filter { it.isFile && (it.name?.endsWith(".txt", true) == true) }
        for (f in files) {
            val name = (f.name ?: continue).removeSuffix(".txt")
            sceneMap.put(name, readText(f))
        }

        val startup = sceneMap.optString("startup", "")
        val sceneList = parseSceneListFromStartup(startup)
        val sceneListJson = JSONArray()
        for (s in sceneList) sceneListJson.put(s)

        val payload = JSONObject().apply {
            put("scenes", sceneMap)
            put("sceneList", sceneListJson)
        }

        quicktestCallback = callback
        quicktestGroup = group

        val webView = WebView(this)
        quicktestWebView = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val bridge = object {
            @JavascriptInterface
            fun onResult(resultJson: String) {
                runOnUiThread {
                    try {
                        val obj = JSONObject(resultJson)
                        val status = obj.optString("status")
                        if (status == "OK") {
                            lastQuicktestErrors = null
                            quicktestCallback?.invoke(null)
                        } else {
                            val arr = obj.optJSONArray("errors")
                            if (arr != null) {
                                val list = ArrayList<QuicktestError>(arr.length())
                                for (i in 0 until arr.length()) {
                                    val e = arr.optJSONObject(i) ?: continue
                                    list.add(
                                        QuicktestError(
                                            scene = e.optString("scene"),
                                            line = e.optInt("line", 0),
                                            message = e.optString("message")
                                        )
                                    )
                                }
                                lastQuicktestErrors = list
                                quicktestCallback?.invoke("ERRORS")
                            } else {
                                lastQuicktestErrors = null
                                quicktestCallback?.invoke(obj.optString("message"))
                            }
                        }
                    } catch (e: Exception) {
                        quicktestCallback?.invoke(resultJson)
                    }
                }
            }
        }

        webView.addJavascriptInterface(bridge, "QuicktestBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                val quoted = JSONObject.quote(payload.toString())
                view.evaluateJavascript("window.runQuicktestCollectAll($quoted);", null)
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/choicescript/checker/quicktest_checker.html")
    }

    private fun runQuicktestForGroup(group: ProjectGroup) {
        runQuicktest(group) { err ->
            quicktestWebView?.destroy()
            quicktestWebView = null

            if (err == null) {
                Toast.makeText(this, "Quicktest passed (no errors).", Toast.LENGTH_SHORT).show()
            } else {
                val errs = lastQuicktestErrors
                if (errs != null && errs.isNotEmpty()) {
                    showQuicktestErrorsDialog(group, errs)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Quicktest Error")
                        .setMessage(err)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun showQuicktestErrorsDialog(group: ProjectGroup, errors: List<QuicktestError>) {
        lastQuicktestErrors = null

        var dialog: AlertDialog? = null

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        scroll.addView(container)

        errors.forEachIndexed { index, e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val textBox = TextView(this).apply {
                val sceneTitle = if (e.scene.endsWith(".txt", ignoreCase = true)) e.scene else "${e.scene}.txt"
                val linePart = if (e.line > 0) " — line ${e.line}" else ""
                text = """
                ${index + 1}) $sceneTitle$linePart
                ${e.message}
                """.trimIndent()
                setPadding(0, 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btn = android.widget.Button(this).apply {
                text = "Go to"
                isAllCaps = false
                setOnClickListener {
                    dialog?.dismiss()
                    openSceneAndJump(group, e.scene, e.line)
                }
            }

            row.addView(textBox)
            row.addView(btn)
            container.addView(row)

            if (index != errors.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply {
                        topMargin = dp(10)
                        bottomMargin = dp(10)
                    }
                    setBackgroundColor(android.graphics.Color.parseColor("#22000000"))
                }
                container.addView(divider)
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Errors (${errors.size})")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()

        dialog.show()
    }

    // Compile to a HTML file

    private fun promptCompileToSingleHtml(group: ProjectGroup) {
        pendingCompileGroup = group
        val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_TITLE, "${group.projectFile.name}.html")
        }
        startActivityForResult(createIntent, REQUEST_CREATE_SINGLE_HTML)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CREATE_SINGLE_HTML && resultCode == Activity.RESULT_OK) {
            val outputUri = data?.data
            val group = pendingCompileGroup
            pendingCompileGroup = null
            if (outputUri != null && group != null) {
                compileProjectToSingleHtml(group, outputUri)
            } else {
                Toast.makeText(this, "Export cancelled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun compileProjectToSingleHtml(group: ProjectGroup, outputUri: Uri) {
        val projectRoot = group.projectDoc
        val scenesRoot = group.scenesDoc ?: resolveScenes(projectRoot).also { group.scenesDoc = it }

        try {
            val projectName = projectRoot.name ?: "ChoiceScript Game"
            val allScenesJs = buildAllScenesJs(scenesRoot)

            val indexTemplate = assets.open("choicescript/web/mygame/index.html")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }

            val finalHtml = inlineEngineAndScenes(
                template = indexTemplate,
                projectName = projectName,
                allScenesJs = allScenesJs,
                projectRoot = projectRoot
            )

            contentResolver.openOutputStream(outputUri)?.use { out ->
                out.write(finalHtml.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            Toast.makeText(this, "Compiled to single HTML.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Compile failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildAllScenesJs(scenesRoot: DocumentFile): String {
        val builder = StringBuilder()
        builder.append("var allScenes = {")
        var firstScene = true

        for (doc in scenesRoot.listFiles().orEmpty()) {
            if (!doc.isFile) continue
            val name = doc.name ?: continue
            if (!name.endsWith(".txt", ignoreCase = true)) continue

            val sceneName = name.substringBeforeLast('.')

            val text = contentResolver.openInputStream(doc.uri)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() } ?: ""
            val lines = text.split(Regex("\r?\n"))

            val crc = CRC32().apply {
                update(text.toByteArray(Charsets.UTF_8))
            }.value

            val labels = linkedMapOf<String, Int>()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*label ")) {
                    val labelName = trimmed.removePrefix("*label ").trim()
                    if (labelName.isNotEmpty()) {
                        labels[labelName] = index
                    }
                }
            }

            if (!firstScene) builder.append(",")
            firstScene = false

            builder.append("\n  ")
            builder.append(jsString(sceneName))
            builder.append(":{\"crc\":")
            builder.append(crc)
            builder.append(",\"lines\":[")
            lines.forEachIndexed { i, ln ->
                if (i > 0) builder.append(',')
                builder.append(jsString(ln))
            }
            builder.append("],\"labels\":{")
            var firstLabel = true
            for ((label, idx) in labels) {
                if (!firstLabel) builder.append(',')
                firstLabel = false
                builder.append(jsString(label))
                builder.append(":")
                builder.append(idx)
            }
            builder.append("}}")
        }

        builder.append("\n};\n")
        builder.append("Scene.generatedFast = true;\n")
        return builder.toString()
    }

    private fun jsString(value: String): String {
        val sb = StringBuilder()
        sb.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> {}
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch < ' ') {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun inlineEngineAndScenes(
        template: String,
        projectName: String,
        allScenesJs: String,
        projectRoot: DocumentFile
    ): String {
        fun assetText(path: String): String =
            assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

        val styleCss = assetText("choicescript/web/style.css")
        val alertifyCss = assetText("choicescript/web/alertify.css")
        val uiJs = assetText("choicescript/web/ui.js")
        val sceneJs = assetText("choicescript/web/scene.js")
        val navigatorJs = assetText("choicescript/web/navigator.js")
        val persistJs = assetText("choicescript/web/persist.js")
        val utilJs = assetText("choicescript/web/util.js")
        val alertifyMinJs = assetText("choicescript/web/alertify.min.js")

        val mygameFromProject = listOfNotNull(
            projectRoot.findFile("mygame.js"),
            projectRoot.findFile("mygame")?.findFile("mygame.js")
        ).firstOrNull()

        val mygameJs = if (mygameFromProject != null && mygameFromProject.isFile) {
            contentResolver.openInputStream(mygameFromProject.uri)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() } ?: ""
        } else {
            assetText("choicescript/web/mygame/mygame.js")
        }

        var html = template

        html = html.replace("<script src=\"version.js\"></script>", "")

        html = html.replace("<script src=\"../persist.js\"></script>",
            "<script>\n$persistJs\n</script>")
        html = html.replace("<script src=\"../alertify.min.js\"></script>",
            "<script>\n$alertifyMinJs\n</script>")
        html = html.replace("<script src=\"../util.js\"></script>",
            "<script>\n$utilJs\n</script>")
        html = html.replace("<script src=\"../ui.js\"></script>",
            "<script>\n$uiJs\n</script>")
        html = html.replace("<script src=\"../scene.js\"></script>",
            "<script>\n$sceneJs\n</script>")
        html = html.replace("<script src=\"../navigator.js\"></script>",
            "<script>\n$navigatorJs\n</script>")
        html = html.replace("<script src=\"mygame.js\"></script>",
            "<script>\n$mygameJs\n</script>")

        html = html.replace(
            "<link href=\"../style.css\" rel=\"stylesheet\" type=\"text/css\">",
            "<style>\n$styleCss\n</style>"
        )
        html = html.replace(
            "<link href=\"../alertify.css\" rel=\"stylesheet\" type=\"text/css\">",
            "<style>\n$alertifyCss\n</style>"
        )

        html = html.replace("var rootDir = \"../\";", "var rootDir = \".\";")

        run {
            val titleRegex = Regex("<title>.*?</title>", RegexOption.DOT_MATCHES_ALL)
            val match = titleRegex.find(html)
            if (match != null) {
                html = html.replace(titleRegex, "<title>${projectName}</title>")
            }
        }

        val injectBlock = """
            <script>
            $allScenesJs
            if (typeof window.nav === "undefined" || !window.nav) {
              window.nav = new SceneNavigator(["startup"]);
            }
            if (typeof window.stats === "undefined" || !window.stats) {
              window.stats = {};
            }
            if (typeof generatedFast === "undefined") window.generatedFast = true;
            </script>
        """.trimIndent()

        html = html.replace("</head>", "$injectBlock\n</head>")

        return html
    }

    private fun reload() {
        val root = StorageAccess.getProjectsRoot(this) ?: run {
            emptyView.visibility = View.VISIBLE
            pinnedAdapter.update(emptyList())
            recentAdapter.update(emptyList())
            allAdapter.update(emptyList())
            return
        }

        val rawAll = root.listFiles()
            .filter { it.isDirectory && !(it.name ?: "").startsWith(".") }
            .mapNotNull { it.name?.let(::File) }

        // load saved orders
        val savedPinned = loadOrder("order_pinned")
        val savedRecent = loadOrder("order_recent")
        val savedAll = loadOrder("order_all")

        // reconstruct sections
        val pinned = applyOrder(
            rawAll.filter { isPinned(it.name) },
            savedPinned
        )

        val recentRaw = rawAll.filter { getLastOpenedProject(it.name) > 0 }
            .sortedByDescending { getLastOpenedProject(it.name) }

        val recent = applyOrder(recentRaw, savedRecent)

        val all = applyOrder(rawAll, savedAll)

        currentAll = ArrayList(all)
        currentPinned = ArrayList(pinned)
        currentRecent = ArrayList(recent)

        pinnedAdapter.update(groupsFor("pinned", currentPinned))
        recentAdapter.update(groupsFor("recent", currentRecent))
        allAdapter.update(groupsFor("all", currentAll))

        emptyView.visibility = if (rawAll.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun attachDragDrop(recycler: RecyclerView, adapter: ProjectListAdapter): ItemTouchHelper {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = true
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (!adapter.canMove(from, to)) return false
                adapter.onMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                adapter.fileGroupAt(pos)?.let { adapter.refreshRowShapes(it) }
            }
        }
        val helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(recycler)
        return helper
    }

    private fun setupDragAndDrop() {
        pinnedTouchHelper = attachDragDrop(pinnedGrid, pinnedAdapter)
        recentTouchHelper = attachDragDrop(recentGrid, recentAdapter)
        allTouchHelper = attachDragDrop(allGrid, allAdapter)
    }


    private fun showProjectMenu(name: String, anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add("Upload entire folder")
        popup.menu.add("Rename")
        popup.menu.add("Delete")

        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Upload entire folder" -> uploadProjectFolder(name)
                "Rename" -> promptRenameProject(name)
                "Delete" -> deleteProject(name)
            }
            true
        }
        popup.show()
    }


    private fun createProjectZip(projectDir: DocumentFile): File {
        val baseName = projectDir.name ?: "project"
        val zipFile = File.createTempFile(baseName, ".zip", cacheDir)
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (child in projectDir.listFiles()) {
                zipDocumentTree(child, baseName, zos)
            }
        }
        return zipFile
    }

    private fun zipDocumentTree(doc: DocumentFile, base: String, zos: ZipOutputStream) {
        val name = doc.name ?: return
        val path = "$base/$name"
        if (doc.isDirectory) {
            zos.putNextEntry(ZipEntry("$path/"))
            zos.closeEntry()
            for (child in doc.listFiles()) {
                zipDocumentTree(child, path, zos)
            }
        } else {
            zos.putNextEntry(ZipEntry(path))
            contentResolver.openInputStream(doc.uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    zos.write(buffer, 0, len)
                }
            }
            zos.closeEntry()
        }
    }

    private fun promptNewProject() {
        val root = StorageAccess.getProjectsRoot(this) ?: return
        val input = android.widget.EditText(this)
        val wrap = android.widget.FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("New project")
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    var created = root.findFile(name)
                    if (created == null) created = root.createDirectory(name)

                    if (created != null) {
                        val mygame = created.findFile("mygame") ?: created.createDirectory("mygame")
                        val scenes = mygame?.findFile("scenes")
                            ?: mygame?.createDirectory("scenes") ?: created

                        val startup = scenes.findFile("startup.txt")
                            ?: scenes.createFile("text/plain", "startup.txt")
                        scenes.findFile("choicescript_stats.txt")
                            ?: scenes.createFile("text/plain", "choicescript_stats.txt")

                        startup?.let { df ->
                            contentResolver.openOutputStream(df.uri, "rwt")?.use { os ->
                                OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                                    val four = "    "
                                    w.write(
                                        "*title $name\n" +
                                                "*author \n" +
                                                "*comment your code goes here\n" +
                                                "*scene_list\n" +
                                                four + "startup\n" +
                                                "*finish\n"
                                    )
                                }
                            }
                        }
                    }
                }
                reload()
            }.show()
    }

    private fun countTxtFilesRecursive(dir: DocumentFile): Int {
        if (!dir.exists()) return 0
        var count = 0
        val children = dir.listFiles()
        for (f in children) {
            if (f.isDirectory) {
                count += countTxtFilesRecursive(f)
            } else if (f.isFile && f.name?.endsWith(".txt", ignoreCase = true) == true) {
                count++
            }
        }
        return count
    }

}