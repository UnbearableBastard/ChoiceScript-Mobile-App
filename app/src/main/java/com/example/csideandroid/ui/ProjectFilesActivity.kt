package com.example.csideandroid.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.EditText
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.csideandroid.R
import com.example.csideandroid.runner.RunnerActivity
import com.example.csideandroid.util.WordCountUtil
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.zip.CRC32

private const val REQUEST_CREATE_SINGLE_HTML = 2002

class ProjectFilesActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ProjectFilesAdapter

    private var projectTree: DocumentFile? = null
    private var scenesDir: DocumentFile? = null
    private var cache: List<DocumentFile> = emptyList()

    private val prefs by lazy { getSharedPreferences("cside_prefs", MODE_PRIVATE) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_project_files)

        val spacer = findViewById<android.view.View>(R.id.statusBarSpacer)
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updateLayoutParams<ViewGroup.LayoutParams> { height = top }
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.title = ""

        toolbar.inflateMenu(R.menu.menu_project_files)

        toolbar.menu.findItem(R.id.action_new_txt).actionView
            ?.findViewById<android.widget.Button>(R.id.btnNewFile)
            ?.setOnClickListener { promptNewFile() }

        toolbar.navigationIcon = AppCompatResources.getDrawable(
            this,
            androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )
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
                R.id.action_run -> {
                    copyProjectToRunner()
                    startActivity(Intent(this, RunnerActivity::class.java))
                    true
                }
                R.id.action_compile_html -> {
                    promptCompileToSingleHtml()
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
                initialRight,
                initialBottom + navInsets.bottom
            )
            insets
        }

        recycler.layoutManager = LinearLayoutManager(this)

        val uriStr = intent.getStringExtra("extra_project_uri") ?: run { finish(); return }
        projectTree = DocumentFile.fromTreeUri(this, Uri.parse(uriStr))
        scenesDir = resolveScenes(projectTree!!)

        adapter = ProjectFilesAdapter(
            metaProvider = { f -> metaFor(f) },
            onOpenTxt = { openTxt(it) },
            onRename = { rename(it) },
            onDelete = { delete(it) },
            isProtected = {
                it.name.equals("startup.txt", true) ||
                        it.name.equals("choicescript_stats.txt", true)
            }
        )
        recycler.adapter = adapter

        loadFiles()

        val touchHelper = ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            ) {

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

        adapter.submit(finalList)
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
            if (df.name?.endsWith(".txt", ignoreCase = true) == true)
                "$sizeStr – calculating words…" to whenStr
            else sizeStr to whenStr

        fileMetaCache[name] = baseMeta

        if (df.name?.endsWith(".txt", ignoreCase = true) == true &&
            !fileMetaInProgress.contains(name)
        ) {
            fileMetaInProgress.add(name)
            Thread {
                try {
                    val words = WordCountUtil.countWordsInFile(df, contentResolver)
                    val nf = NumberFormat.getIntegerInstance()
                    val wordsStr = nf.format(words)
                    val finalMeta = "$sizeStr – $wordsStr words" to whenStr
                    fileMetaCache[name] = finalMeta
                    runOnUiThread { adapter.notifyDataSetChanged() }
                } finally { fileMetaInProgress.remove(name) }
            }.start()
        }

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

    private fun openTxt(fake: File) {
        val doc = mapDoc(fake) ?: return
        startActivity(
            Intent(this, EditorActivityV3::class.java)
                .putExtra("extra_document_uri", doc.uri.toString())
                .putExtra("extra_display_name", doc.name)
        )
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
            // "*image foo.png" (no path), our engine will resolve that to images/foo.png.
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
    }
}