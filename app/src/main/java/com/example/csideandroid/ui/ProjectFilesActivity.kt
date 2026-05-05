package com.example.csideandroid.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.csideandroid.R
import com.example.csideandroid.runner.RunnerActivity
import com.example.csideandroid.util.WordCountUtil
import com.google.android.material.appbar.MaterialToolbar
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.NumberFormat
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject
import android.view.Menu
import android.view.MenuItem

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val REQUEST_CREATE_SINGLE_HTML = 2002

class ProjectFilesActivity : AppCompatActivity() {

    private companion object {
        // Unique id for programmatically-added overflow menu item
        private const val MENU_UPLOAD_COGDEMOS = 16790001
    }


    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ProjectFilesAdapter
    private lateinit var projectTitleView: TextView

    private var projectTree: DocumentFile? = null
    private var scenesDir: DocumentFile? = null
    private var cache: List<DocumentFile> = emptyList()

    private val prefs by lazy { getSharedPreferences("cside_prefs", MODE_PRIVATE) }

    // Quicktest (ChoiceScript error checker)
    private var quicktestWebView: WebView? = null
    private var quicktestCallback: ((String?) -> Unit)? = null


    // Quicktest (collect-all) results
    private data class QuicktestError(
        val scene: String,
        val line: Int,
        val message: String
    )

    private var lastQuicktestErrors: List<QuicktestError>? = null


    private fun loadOrder(projectName: String): List<String> {
        val raw = prefs.getString("order_files_$projectName", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    private fun saveOrder(projectName: String, list: List<File>) {
        val joined = list.joinToString("|") { it.name }
        prefs.edit().putString("order_files_$projectName", joined).apply()
    }

    private fun applyOrder(raw: List<File>, saved: List<String>): List<File> {
        if (saved.isEmpty()) return raw
        val map = raw.associateBy { it.name }
        val ordered = saved.mapNotNull { map[it] }
        val leftovers = raw.filter { it.name !in saved }
        return ordered + leftovers
    }

    private val fileMetaCache = mutableMapOf<String, Pair<String, String>>()
    private val fileMetaInProgress = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Batch word-count once per refresh
    private val wordCountExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var wordCountGeneration: Int = 0
    @Volatile private var wordCountFuture: Future<*>? = null
    @Volatile private var needsWordCountRefreshOnResume: Boolean = false
    @Volatile private var lastSubmittedFiles: List<File> = emptyList()


    private fun promptCompileToSingleHtml() {
        val project = projectTree
        if (project == null) {
            Toast.makeText(this, "No project selected.", Toast.LENGTH_SHORT).show()
            return
        }

        val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_TITLE, "${project.name ?: "game"}.html")
        }

        startActivityForResult(createIntent, REQUEST_CREATE_SINGLE_HTML)
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
                // move startup to front
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
    private fun runQuicktest(callback: (String?) -> Unit) {
        val project = projectTree
        val scenes = scenesDir
        if (project == null || scenes == null) {
            callback("No project selected.")
            return
        }

        // Build { sceneNameWithoutExt : text }
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
                        val obj = JSONObject(resultJson)

                        val status = obj.optString("status")
                        if (status == "OK") {
                            lastQuicktestErrors = null
                            quicktestCallback?.invoke(null)
                        } else {
                            // New method returns an "errors" array; fall back to "message" if not present.
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
                                // Signal "there are errors" via non-null err, details are in lastQuicktestErrors
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

        webView.addJavascriptInterface(QuicktestBridge(), "QuicktestBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Pass payload as a JSON string to avoid quoting issues.
                val quoted = JSONObject.quote(payload.toString())
                view.evaluateJavascript("window.runQuicktestCollectAll($quoted);", null)
            }
        }

        // Load the checker wrapper HTML from assets
        webView.loadUrl("https://appassets.androidplatform.net/assets/choicescript/checker/quicktest_checker.html")
    }




    private fun showQuicktestErrorsDialog(errors: List<QuicktestError>) {
        // Clear stored list so future runs don't accidentally reuse it
        lastQuicktestErrors = null

        var dialog: androidx.appcompat.app.AlertDialog? = null

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

        val scroll = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        scroll.addView(container)

        errors.forEachIndexed { index, e ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val textBox = android.widget.TextView(this).apply {
                val sceneTitle = if (e.scene.endsWith(".txt", ignoreCase = true)) e.scene else "${e.scene}.txt"
                val linePart = if (e.line > 0) " — line ${e.line}" else ""
                text = """
                ${index + 1}) $sceneTitle$linePart
                ${e.message}
                """.trimIndent()
                setPadding(0, 0, dp(12), 0)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btn = android.widget.Button(this).apply {
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

            if (index != errors.lastIndex) {
                val divider = android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
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

        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Errors (${errors.size})")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()

        dialog?.show()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_project_files)

        projectTitleView = findViewById(R.id.projectTitle)

        val spacer = findViewById<android.view.View>(R.id.statusBarSpacer)
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updateLayoutParams<ViewGroup.LayoutParams> { height = top }
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.title = ""

        // Apply right navigation bar inset to toolbar for landscape mode
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, navInsets.right, v.paddingBottom)
            insets
        }

        toolbar.inflateMenu(R.menu.menu_project_files)

        // Add overflow menu item to open CoG Demos upload page
        toolbar.menu.add(Menu.NONE, MENU_UPLOAD_COGDEMOS, Menu.NONE, "Upload to CoG Demos")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)


        toolbar.menu.findItem(R.id.action_new_txt).actionView
            ?.findViewById<android.widget.Button>(R.id.btnNewFile)
            ?.setOnClickListener { promptNewFile() }

        AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back_24)?.let { d ->
            d.setTint(Color.WHITE)
            toolbar.navigationIcon = d
        }
        toolbar.navigationIcon?.setTint(Color.WHITE)
        toolbar.setNavigationOnClickListener { finish() }

        for (i in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(i).icon?.setTint(Color.WHITE)
        }
        toolbar.overflowIcon = toolbar.overflowIcon?.mutate()?.apply {
            setTint(Color.WHITE)
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_UPLOAD_COGDEMOS -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cogdemos.ink/")))
                    } catch (t: Throwable) {
                        Toast.makeText(this, "No browser found to open CoG Demos.", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_run -> {
                    copyProjectToRunner()
                    startActivity(Intent(this, RunnerActivity::class.java))
                    true
                }
                R.id.action_compile_html -> {
                    promptCompileToSingleHtml()
                    true
                }
                R.id.action_quicktest -> {
                    runQuicktest { err ->
                        // Cleanup hidden WebView
                        quicktestWebView?.destroy()
                        quicktestWebView = null


                        if (err == null) {
                            Toast.makeText(this, "Quicktest passed (no errors).", Toast.LENGTH_SHORT).show()
                        } else {
                            val errs = lastQuicktestErrors
                            if (errs != null && errs.isNotEmpty()) {
                                showQuicktestErrorsDialog(errs)
                            } else {
                                AlertDialog.Builder(this)
                                    .setTitle("Quicktest Error")
                                    .setMessage(err)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        recycler = findViewById(R.id.recyclerFiles)

        val initialLeft = recycler.paddingLeft
        val initialTop = recycler.paddingTop
        val initialRight = recycler.paddingRight
        val initialBottom = recycler.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(recycler) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                initialLeft,
                initialTop,
                initialRight + navInsets.right,
                initialBottom + navInsets.bottom
            )
            insets
        }

        val smallestWidthDp = resources.configuration.smallestScreenWidthDp
        val span = when {
            smallestWidthDp >= 900 -> 3
            smallestWidthDp >= 600 -> 2
            else -> 1
        }
        recycler.layoutManager = GridLayoutManager(this, span)

        val uriStr = intent.getStringExtra("extra_project_uri") ?: run { finish(); return }
        projectTree = DocumentFile.fromTreeUri(this, Uri.parse(uriStr))
        scenesDir = resolveScenes(projectTree!!)

        projectTitleView.text = projectTree?.name ?: ""

        adapter = ProjectFilesAdapter(
            metaProvider = { f -> metaFor(f) },
            onOpenTxt = { openTxt(it) },
            onRename = { rename(it) },
            onDelete = { delete(it) },
            isProtected = {
                isImageFile(it) ||
                        it.name.equals("startup.txt", true) ||
                        it.name.equals("choicescript_stats.txt", true)
            }
        )
        recycler.adapter = adapter

        loadFiles()

        val touchHelper = ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(0, 0) {


            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val lm = recyclerView.layoutManager
                val dragFlags =
                    if (lm is GridLayoutManager) {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    } else {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN
                    }
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, tgt: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = tgt.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

                val projectName = projectTree?.name ?: return false

                val current = adapter.currentList.toMutableList()
                if (from !in current.indices || to !in current.indices) return false

                val item = current.removeAt(from)
                current.add(to, item)

                adapter.submit(current)

                saveOrder(projectName, current)

                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(recycler)
    }

    private fun resolveScenes(project: DocumentFile): DocumentFile {
        project.findFile("scenes")?.let { return it }
        project.findFile("mygame")?.findFile("scenes")?.let { return it }
        return project
    }

    // Load files with restored order
    private fun loadFiles() {
        val list = scenesDir?.listFiles()?.filter { it.isFile } ?: emptyList()
        cache = list

        val rawFiles = list.map { File(it.name ?: "") }
        val projectName = projectTree?.name ?: ""

        val saved = loadOrder(projectName)
        val finalList = applyOrder(rawFiles, saved)

        lastSubmittedFiles = finalList
        adapter.submit(finalList)

        // Batch compute word counts once per refresh (never from bind)
        startBatchWordCount(finalList)
    }

    private fun startBatchWordCount(orderedFiles: List<File>) {
        // Cancel any previous batch
        wordCountFuture?.cancel(true)
        val gen = ++wordCountGeneration

        // Snapshot names to avoid adapter/list changes during counting
        val names = orderedFiles.mapNotNull { it.name }

        // Mark as in-progress so metaFor can show placeholder without spawning threads
        fileMetaInProgress.clear()
        fileMetaInProgress.addAll(names)

        wordCountFuture = wordCountExecutor.submit {
            val updates = mutableMapOf<String, Pair<String, String>>()

            // Build a quick lookup for DocumentFiles by name
            val byName = cache.associateBy { it.name ?: "" }

            for (name in names) {
                if (Thread.currentThread().isInterrupted) break
                val df = byName[name] ?: continue

                val sizeStr = try { Formatter.formatShortFileSize(this, df.length()) } catch (_: Exception) { "—" }
                val lm = try { df.lastModified() } catch (_: Exception) { 0L }
                val whenStr =
                    if (lm > 0)
                        DateUtils.getRelativeTimeSpanString(
                            lm,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString()
                    else "—"

                val meta: Pair<String, String> =
                    if (name.endsWith(".txt", ignoreCase = true)) {
                        val counts = try { WordCountUtil.countWordsInFileBoth(df, contentResolver) } catch (_: Exception) { null }
                        if (counts != null) {
                            val nf = NumberFormat.getIntegerInstance()
                            val withStr = nf.format(counts.withCode)
                            val withoutStr = nf.format(counts.withoutCode)
                            "$sizeStr – $withStr words\n$withoutStr words w/o code" to whenStr
                        } else {
                            sizeStr to whenStr
                        }
                    } else {
                        sizeStr to whenStr
                    }

                updates[name] = meta
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (gen != wordCountGeneration) return@runOnUiThread

                fileMetaCache.clear()
                fileMetaCache.putAll(updates)
                fileMetaInProgress.clear()

                // One UI refresh only (Option C)
                adapter.notifyDataSetChanged()
            }
        }
    }


    private fun metaFor(fake: File): Pair<String, String> {
        val name = fake.name ?: return "—" to "—"
        fileMetaCache[name]?.let { return it }
        val df = cache.firstOrNull { it.name == name } ?: return "—" to "—"

        val sizeStr = Formatter.formatShortFileSize(this, df.length())
        val lm = df.lastModified()
        val whenStr =
            if (lm > 0)
                DateUtils.getRelativeTimeSpanString(
                    lm,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
            else "—"

        val baseMeta: Pair<String, String> =
            if (df.name?.endsWith(".txt", ignoreCase = true) == true && fileMetaInProgress.contains(name))
                "$sizeStr – calculating words…" to whenStr
            else sizeStr to whenStr

        fileMetaCache[name] = baseMeta



        return baseMeta
    }

    private fun promptNewFile() {
        val input = EditText(this).apply {
            hint = "Filename (e.g., chapter1.txt)"
            setSingleLine(true)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
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
                if (scenesDir?.findFile(name) != null) {
                    input.error = "A file with that name already exists."
                    return@setOnClickListener
                }
                val created = scenesDir?.createFile("text/plain", name) != null
                if (!created) {
                    Toast.makeText(this, "Could not create file", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                loadFiles()
            }
        }
        dialog.show()
    }

    private fun mapDoc(fake: File) = cache.firstOrNull { it.name == fake.name }


    private fun isImageFile(f: File): Boolean {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "png","jpg","jpeg","webp","gif","bmp",
            "tga","tif","tiff","svg","heic","heif","avif"
        )
    }

    private fun openTxt(fake: File) {
        if (isImageFile(fake)) {
            android.widget.Toast.makeText(this, "Image files can\'t be opened in the editor.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val doc = mapDoc(fake) ?: return
        val projectUriStr = projectTree?.uri?.toString()
        needsWordCountRefreshOnResume = true
        startActivity(
            Intent(this, EditorActivityV3::class.java)
                .putExtra("extra_document_uri", doc.uri.toString())
                .putExtra("extra_display_name", doc.name)
                .apply {
                    // Needed so the editor's Quicktest (error checker) can run against the project.
                    // ProjectFilesActivity already has the selected SAF tree; pass it through.
                    if (!projectUriStr.isNullOrBlank()) {
                        putExtra("extra_project_uri", projectUriStr)
                    }
                }
        )
    }


    private fun openSceneAndJump(sceneName: String, lineOneBased: Int) {
        val proj = projectTree ?: return
        val scenes = scenesDir ?: resolveScenes(proj) ?: proj

        val wanted = if (sceneName.endsWith(".txt", ignoreCase = true)) sceneName else "$sceneName.txt"

        // Find the scene file under scenes directory first, fallback to anywhere under project tree.
        fun findIn(dir: DocumentFile): DocumentFile? {
            dir.listFiles().forEach { f ->
                if (f.isFile && f.name.equals(wanted, ignoreCase = true)) return f
            }
            return null
        }

        var doc: DocumentFile? = findIn(scenes)
        if (doc == null && scenes != proj) doc = findIn(proj)

        if (doc == null) {
            android.widget.Toast.makeText(this, "Couldn't find scene: $wanted", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val projectUriStr = proj.uri.toString()
        val intent = Intent(this, EditorActivityV3::class.java)
            .putExtra("extra_document_uri", doc!!.uri.toString())
            .putExtra("extra_display_name", doc!!.name)
            .putExtra("extra_project_uri", projectUriStr)

        if (lineOneBased > 0) {
            intent.putExtra("extra_goto_line_1based", lineOneBased)
        }
        startActivity(intent)
    }



    private fun rename(fake: File) {
        val doc = mapDoc(fake) ?: return
        val input = EditText(this).apply {
            setText(fake.name)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename file")
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotBlank()) {
                    try {
                        android.provider.DocumentsContract.renameDocument(
                            contentResolver,
                            doc.uri,
                            newName
                        )
                    } catch (_: Exception) { }
                    loadFiles()
                }
            }.show()
    }

    private fun delete(fake: File) {
        val doc = mapDoc(fake) ?: return
        if (!doc.delete()) {
            Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
        }
        loadFiles()
    }


    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (needsWordCountRefreshOnResume) {
            needsWordCountRefreshOnResume = false
            // Recount once when returning from the editor
            startBatchWordCount(lastSubmittedFiles)
        }
    }

    override fun onPause() {
        // Cancel any in-flight word-count batch so Back/exit is instant.
        wordCountFuture?.cancel(true)
        super.onPause()
    }

    override fun onDestroy() {
        // Ensure no background work survives the Activity.
        wordCountFuture?.cancel(true)
        try { wordCountExecutor.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CREATE_SINGLE_HTML && resultCode == Activity.RESULT_OK) {
            val outputUri = data?.data
            if (outputUri != null) {
                compileProjectToSingleHtml(outputUri)
            } else {
                Toast.makeText(this, "Export cancelled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun compileProjectToSingleHtml(outputUri: Uri) {
        val projectRoot = projectTree
        val scenesRoot = scenesDir

        if (projectRoot == null || scenesRoot == null) {
            Toast.makeText(this, "Project or scenes folder missing.", Toast.LENGTH_SHORT).show()
            return
        }

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
                '"'  -> sb.append("\\\"")
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

        val styleCss       = assetText("choicescript/web/style.css")
        val alertifyCss    = assetText("choicescript/web/alertify.css")
        val uiJs           = assetText("choicescript/web/ui.js")
        val sceneJs        = assetText("choicescript/web/scene.js")
        val navigatorJs    = assetText("choicescript/web/navigator.js")
        val persistJs      = assetText("choicescript/web/persist.js")
        val utilJs         = assetText("choicescript/web/util.js")
        val alertifyMinJs  = assetText("choicescript/web/alertify.min.js")

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

    private fun copyProjectToRunner() {
        val srcScenes = scenesDir ?: return
        val projectRoot = projectTree ?: return

        val destRoot = File(filesDir, "runner/mygame")
        val destScenes = File(destRoot, "scenes")
        val destImages = File(destRoot, "images")
        destRoot.deleteRecursively()
        destScenes.mkdirs()

        fun isImageFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg")
        }

        for (doc in srcScenes.listFiles().orEmpty()) {
            if (!doc.isFile) continue
            val name = doc.name ?: continue
            val outFile = File(destScenes, name)
            contentResolver.openInputStream(doc.uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }

            // Convenience: if authors store images alongside their scene files and reference them as
            // "*image example.png" (no path), the engine will resolve that to images/example.png.
            // Copy any images found in the scenes folder into runner/mygame/images as well.
            if (isImageFile(name)) {
                destImages.mkdirs()
                val imgOut = File(destImages, name)
                contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(imgOut).use { output -> input.copyTo(output) }
                }
            }
        }

        // Copy images folder if present (ChoiceScript expects images to be referenced relative to mygame/index.html,
        // commonly under "images/").
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
        // Auto-inject unique storeName based on projects folder name
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
}