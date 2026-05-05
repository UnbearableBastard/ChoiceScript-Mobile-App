package com.example.csideandroid.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import android.net.Uri
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import com.example.csideandroid.R
import com.example.csideandroid.storage.StorageAccess
import com.example.csideandroid.util.WordCountUtil
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import android.transition.TransitionManager
import android.view.ViewGroup
import android.provider.Settings
import com.example.csideandroid.ui.model.ProjectGridAdapter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
class ProjectsBrowserActivity : AppCompatActivity() {

    companion object {
        // Ensures the automatic update check runs only once per cold app process start.
        private var didBootUpdateCheck: Boolean = false
    }


    private val prefs by lazy { getSharedPreferences("cside_prefs", MODE_PRIVATE) }


    private var helpDialog: AlertDialog? = null
    private val PREF_HIDE_HELP_POPUP = "hide_help_popup"

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

    // pin logic
    private fun isPinned(name: String): Boolean =
        prefs.getStringSet("pinned_projects", emptySet())!!.contains(name)

    private fun togglePin(name: String) {
        val set = prefs.getStringSet("pinned_projects", emptySet())!!.toMutableSet()
        if (!set.add(name)) set.remove(name)
        prefs.edit().putStringSet("pinned_projects", set).apply()
    }

    private fun setLastOpenedProject(name: String, t: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("last_opened_project_$name", t).apply()
    }

    private fun getLastOpenedProject(name: String): Long =
        prefs.getLong("last_opened_project_$name", 0L)

    private lateinit var pinnedGrid: RecyclerView
    private lateinit var recentGrid: RecyclerView
    private lateinit var allGrid: RecyclerView
    private lateinit var emptyView: TextView

    private lateinit var pinnedAdapter: ProjectGridAdapter
    private lateinit var recentAdapter: ProjectGridAdapter
    private lateinit var allAdapter: ProjectGridAdapter

    private lateinit var pinnedTouchHelper: ItemTouchHelper
    private lateinit var recentTouchHelper: ItemTouchHelper
    private lateinit var allTouchHelper: ItemTouchHelper

    private val projectMetaCache = mutableMapOf<String, Pair<String, String>>()
    private val projectMetaInProgress = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Word-count refresh runs once per menu open (onResume), in background, and only re-counts
    // projects whose .txt fingerprint changed since last count. UI uses cached values immediately.
    private val wordRefreshLock = Any()
    @Volatile private var wordRefreshRunning: Boolean = false
    @Volatile private var wordRefreshPending: Boolean = false

    private val projectMetaExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // --- GitHub updates (set these to your repo) ---
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

        // Show scrim whenever the menu is visible (even partially during drag).
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

        // Side menu scrim + edge drag / tap-to-close handling
        menuScrim = findViewById(R.id.menuScrim)
        val rootTouchTarget = findViewById<View>(R.id.rootProjectsBrowser)
        val menuTouchListener = View.OnTouchListener { _, ev -> handleSideMenuTouch(ev) }
        rootTouchTarget.setOnTouchListener(menuTouchListener)
        menuScrim.setOnTouchListener(menuTouchListener)
        findViewById<View>(R.id.leftEdgeGrabber).setOnTouchListener(menuTouchListener)
        // Keep scrim in sync with the initial menu state
        setSideMenuWidthPx(findViewById<View>(R.id.sideMenu).layoutParams.width, animate = false)


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
        pinnedGrid = findViewById(R.id.recyclerPinned)
        recentGrid = findViewById(R.id.recyclerRecent)
        allGrid = findViewById(R.id.recyclerAll)
        emptyView = findViewById(R.id.txtEmptyProjects)

        // Apply navigation bar insets for landscape mode (right side cutoff)
        val projectsScroll = findViewById<View>(R.id.projectsScroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(projectsScroll) { v, insets ->
            val navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, navInsets.right, navInsets.bottom)
            insets
        }

        // Section header buttons: tap to collapse/expand each list
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

        val smallestWidthDp = resources.configuration.smallestScreenWidthDp
        val span = when {
            smallestWidthDp >= 900 -> 3
            smallestWidthDp >= 600 -> 2
            else -> 1
        }

        pinnedGrid.layoutManager = GridLayoutManager(this, span)
        recentGrid.layoutManager = GridLayoutManager(this, span)
        allGrid.layoutManager = GridLayoutManager(this, span)

        if (!StorageAccess.hasBase(this)) {
            startActivity(Intent(this, FirstRunActivity::class.java))
            finish()
            return
        }

        val meta: (File) -> Pair<String, String> = { f -> metaForProject(f.name) }
        val isPinnedCheck: (File) -> Boolean = { f -> isPinned(f.name) }

        pinnedAdapter = ProjectGridAdapter(
            emptyList(), isPinnedCheck, meta,
            onOpen = { openProject(it.name) },
            onTogglePin = { togglePin(it.name); reload() },
            onLongPress = { f, anchor -> showProjectMenu(f.name, anchor) }
        )

        recentAdapter = ProjectGridAdapter(
            emptyList(), isPinnedCheck, meta,
            onOpen = { openProject(it.name) },
            onTogglePin = { togglePin(it.name); reload() },
            onLongPress = { f, anchor -> showProjectMenu(f.name, anchor) }
        )

        allAdapter = ProjectGridAdapter(
            emptyList(), isPinnedCheck, meta,
            onOpen = { openProject(it.name) },
            onTogglePin = { togglePin(it.name); reload() },
            onLongPress = { f, anchor -> showProjectMenu(f.name, anchor) }
        )

        pinnedGrid.adapter = pinnedAdapter
        recentGrid.adapter = recentAdapter
        allGrid.adapter = allAdapter

        setupDragAndDrop()
        findViewById<View>(R.id.btnTutorial)?.setOnClickListener {
            startActivity(Intent(this, TutorialActivity::class.java))
        }

        findViewById<View>(R.id.btnHelp)?.setOnClickListener {
            // Always allow opening Help, even if the user previously selected "Do not show again".
            showHelpPopup(force = true)
            setSideMenuOpen(false)
        }

        findViewById<View>(R.id.btnNewProject)?.setOnClickListener {
            promptNewProject()
        }
        reload()
        // Populate/refresh cached word counts in the background on first open.
        refreshWordCountsIfNeeded()

        // Automatic update check: run only once per cold app process start (cold boot).
        if (!didBootUpdateCheck) {
            didBootUpdateCheck = true
            checkForUpdates(userInitiated = false)
        }
    }



    override fun onResume() {
        super.onResume()
        // Show the help popup every time unless the user opted out.
        showHelpPopup(force = false)
        // Refresh lists (including "Recent") when returning to this screen.
        reload()
        // One background pass per menu open; UI stays instant because cached meta is shown immediately.
        refreshWordCountsIfNeeded()

        // Automatic update check: run only once per cold app process start (cold boot).
        if (!didBootUpdateCheck) {
            didBootUpdateCheck = true
            checkForUpdates(userInitiated = false)
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        projectMetaExecutor.shutdownNow()
        updateExecutor.shutdownNow()
    }

    private fun checkForUpdates(userInitiated: Boolean = true) {
        // Requires INTERNET permission in AndroidManifest.xml
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

            // Find the first SemVer-ish token inside the string:
            //   v1.2.3, 1.2, release-1.2.3, 1.2.3-rc.1, 1.2.3+build
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

            // Core equal: prerelease is LOWER precedence than normal.
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
                    aNum != null && bNum == null -> return -1 // numeric < non-numeric
                    aNum == null && bNum != null -> return 1
                    else -> {
                        val c = ai.compareTo(bi) // ASCII lexical
                        if (c != 0) return c
                    }
                }
            }
            // Shorter prerelease set has lower precedence if all previous identifiers equal.
            return aPre.size.compareTo(bPre.size)
        }

        val a = parseSemVerLike(latest) ?: return false
        val b = parseSemVerLike(current) ?: return false

        return compareSemVer(a, b) > 0
    }


    private fun showHelpPopup(force: Boolean) {
        if (!force && prefs.getBoolean(PREF_HIDE_HELP_POPUP, false)) return
        if (helpDialog?.isShowing == true) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(8))
        }

        val message = """Welcome to the ChoiceScript Android Editor!

This app helps you manage your ChoiceScript projects and playtest them before publishing.

Getting started
    • Use the hamburger (side menu) to create a new project or paste an existing project into the newly created "ChoiceScript Projects" folder.
    
    • Within the menu, there is a basic tutorial on ChoiceScript for beginners. Just click the "Tutorial" button.
    
    • If you've reinstalled this and accidentally selected the wrong section or folder. Tap the "Reselect" button.
    
    • From the project browser menu, you can delete, rename and upload your project to your preferred cloud storage.
    
    • If you've selected the "Do not show again" prompt at the bottom of this menu and want to view it again, select the "Help" button.


Project sections: These sections are all collapsible to help reduce clutter. Just tap them and if you want to rearrange the project cards within, just hold and drag them.

    • Pinned: pin your most-used projects so they stay at the top.
    
    • Recent: shows projects you opened most recently.
    
    • All Projects: everything in your ChoiceScript Project folder.


Editor & Error Checker
    • Use the Quicktest/Error Checker buttons to find any errors in your code. It can be used within the editor by selecting the button with the paper icon and also in the file browser menu by selecting the three dots.
    
    • Important: The Error Checker collects errors from the files listed in *scene_list, and because it scans across many files, it can sometimes report false positives.
 
    • Best practice is to fix the errors in the order they appear in the Error menu as later errors often disappear after the earlier ones are corrected if they were false positives.
    
    • For example, if your *choice is spelt incorrectly or the indent is wrong, then all of the nested text and choices within the *choice will throw errors at the Quicktest.
    
    • The themes in the editor are customizable, you can tap and hold the top bar above the editor to open the "Edit Theme" menu.
  
""".trimIndent()

        val tv = TextView(this).apply {
            text = message
            textSize = 15f
        }

        val scroll = ScrollView(this).apply {
            addView(
                tv,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val cb = CheckBox(this).apply {
            text = "Do not show again"
            isChecked = prefs.getBoolean(PREF_HIDE_HELP_POPUP, false)
            setPadding(0, dp(12), 0, 0)
        }

        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        container.addView(cb)

        helpDialog = AlertDialog.Builder(this)
            .setTitle("Help")
            .setView(container)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .setOnDismissListener {
                // Persist the checkbox state every time the dialog closes.
                prefs.edit { putBoolean(PREF_HIDE_HELP_POPUP, cb.isChecked) }
            }
            .create()

        helpDialog?.show()
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
                            pinnedAdapter.update(currentPinned)
                            recentAdapter.update(currentRecent)
                            allAdapter.update(currentAll)
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

// RELOAD WITH ORDER PERSISTENCE

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

        pinnedAdapter.update(currentPinned)
        recentAdapter.update(currentRecent)
        allAdapter.update(currentAll)

        emptyView.visibility = if (rawAll.isEmpty()) View.VISIBLE else View.GONE
    }

    // DRAG & DROP WITH SAVE

    private fun setupDragAndDrop() {

        // PINNED
        val pinnedCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled() = true

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in currentPinned.indices || to !in currentPinned.indices) return false

                val list = (currentPinned as? MutableList<File>)
                    ?: ArrayList(currentPinned).also { currentPinned = it; pinnedAdapter.update(it) }

                val item = list.removeAt(from)
                list.add(to, item)

                pinnedAdapter.notifyItemMoved(from, to)
                saveOrder("order_pinned", list) // SAVE ORDER
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        }
        pinnedTouchHelper = ItemTouchHelper(pinnedCallback)
        pinnedTouchHelper.attachToRecyclerView(pinnedGrid)

        // RECENT
        val recentCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled() = true

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, tgt: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = tgt.bindingAdapterPosition
                if (from !in currentRecent.indices || to !in currentRecent.indices) return false

                val list = (currentRecent as? MutableList<File>)
                    ?: ArrayList(currentRecent).also { currentRecent = it; recentAdapter.update(it) }

                val item = list.removeAt(from)
                list.add(to, item)

                recentAdapter.notifyItemMoved(from, to)
                saveOrder("order_recent", list) // SAVE ORDER
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        }
        recentTouchHelper = ItemTouchHelper(recentCallback)
        recentTouchHelper.attachToRecyclerView(recentGrid)


        val allCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled() = true

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, tgt: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = tgt.bindingAdapterPosition
                if (from !in currentAll.indices || to !in currentAll.indices) return false

                val list = (currentAll as? MutableList<File>)
                    ?: ArrayList(currentAll).also { currentAll = it; allAdapter.update(it) }

                val item = list.removeAt(from)
                list.add(to, item)

                allAdapter.notifyItemMoved(from, to)
                saveOrder("order_all", list) // SAVE ORDER
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        }
        allTouchHelper = ItemTouchHelper(allCallback)
        allTouchHelper.attachToRecyclerView(allGrid)
    }

    // menu, zip, new project, open project remain unchanged

    private fun showProjectMenu(name: String, anchor: View) {
        // unchanged
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add("Upload entire folder")
        popup.menu.add("Rename")
        popup.menu.add("Delete")

        popup.setOnMenuItemClickListener { item ->
            val root = StorageAccess.getProjectsRoot(this) ?: return@setOnMenuItemClickListener true
            val df = root.findFile(name) ?: return@setOnMenuItemClickListener true
            when (item.title.toString()) {
                "Upload entire folder" -> {
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
                "Rename" -> {
                    val input = android.widget.EditText(this).apply {
                        setText(name)
                        setSelection(text.length)
                    }
                    val wrap = android.widget.FrameLayout(this).apply {
                        val pad = (20 * resources.displayMetrics.density).toInt()
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
                "Delete" -> {
                    df.delete()
                    reload()
                }
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
            val pad = (20 * resources.displayMetrics.density).toInt()
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

    private fun openProject(name: String) {
        val root = StorageAccess.getProjectsRoot(this) ?: return
        val df = root.findFile(name) ?: return

        setLastOpenedProject(name)

        startActivity(
            Intent(this, ProjectFilesActivity::class.java)
                .putExtra("extra_project_uri", df.uri.toString())
        )
    }


    private fun countTxtFilesRecursive(dir: DocumentFile): Int {
        if (!dir.exists()) return 0
        var count = 0
        val children = dir.listFiles() ?: return 0
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