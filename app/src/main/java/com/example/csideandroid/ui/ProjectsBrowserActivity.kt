package com.example.csideandroid.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
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
import kotlin.math.abs

class ProjectsBrowserActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("cside_prefs", MODE_PRIVATE) }

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

    // Drag helpers for each section
    private lateinit var pinnedTouchHelper: ItemTouchHelper
    private lateinit var recentTouchHelper: ItemTouchHelper
    private lateinit var allTouchHelper: ItemTouchHelper

    // Cache for project metadata (scenes + words) so we avoid heavy recomputation
    private val projectMetaCache = mutableMapOf<String, Pair<String, String>>()
    private val projectMetaInProgress = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Single background executor for project metadata / word counts
    private val projectMetaExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Current lists so background updates can safely rebind
    private var currentPinned: List<File> = emptyList()
    private var currentRecent: List<File> = emptyList()
    private var currentAll: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projects_browser)

        pinnedGrid = findViewById(R.id.recyclerPinned)
        recentGrid = findViewById(R.id.recyclerRecent)
        allGrid    = findViewById(R.id.recyclerAll)
        emptyView  = findViewById(R.id.txtEmptyProjects)

        val smallestWidthDp = resources.configuration.smallestScreenWidthDp
        val span = when {
            smallestWidthDp >= 900 -> 3
            smallestWidthDp >= 600 -> 2
            else -> 1
        }

        pinnedGrid.layoutManager = GridLayoutManager(this, span)
        recentGrid.layoutManager = GridLayoutManager(this, span)
        allGrid.layoutManager    = GridLayoutManager(this, span)

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
        allGrid.adapter    = allAdapter

        // Enable drag-to-reorder for all three sections without breaking long-press popup
        setupDragAndDrop()

        findViewById<View>(R.id.btnTutorial)?.setOnClickListener {
            startActivity(Intent(this, TutorialActivity::class.java))
        }

        findViewById<View>(R.id.btnNewProject)?.setOnClickListener { promptNewProject() }

        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanly stop background metadata work when this Activity is going away.
        projectMetaExecutor.shutdownNow()
    }

    // Create a ZIP file for the entire project directory
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

    private fun zipDocumentTree(doc: DocumentFile, basePath: String, zos: ZipOutputStream) {
        val name = doc.name ?: return
        val path = if (basePath.isEmpty()) name else "$basePath/$name"

        if (doc.isDirectory) {
            val dirEntry = ZipEntry("$path/")
            zos.putNextEntry(dirEntry)
            zos.closeEntry()

            for (child in doc.listFiles()) {
                zipDocumentTree(child, path, zos)
            }
        } else {
            val entry = ZipEntry(path)
            zos.putNextEntry(entry)
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

    private fun metaForProject(name: String): Pair<String, String> {
        // 1) cached? return immediately
        projectMetaCache[name]?.let { return it }

        val root = StorageAccess.getProjectsRoot(this) ?: return "—" to "—"
        val proj = root.findFile(name) ?: return "—" to "—"

        val scenesDir = proj.findFile("scenes")
            ?: proj.findFile("mygame")?.findFile("scenes")

        // 2) cheap scene count (shallow scan only)
        var scenes = 0
        scenesDir?.listFiles()?.forEach {
            if (it.isFile) {
                val n = it.name?.lowercase() ?: ""
                if (n.endsWith(".txt")) scenes++
            }
        }

        val t = getLastOpenedProject(name)
        val bottom =
            if (t > 0) "Last opened " + DateUtils.getRelativeTimeSpanString(
                t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            else "Never opened"

        val nf = NumberFormat.getIntegerInstance()
        val placeholderTop = "$scenes scenes — calculating words…"
        val baseMeta = placeholderTop to bottom

        // cache placeholder so binds are instant
        projectMetaCache[name] = baseMeta

        // 3) background word-count across project (once per project),
        //    using a single executor instead of spawning many threads.
        if (!projectMetaInProgress.contains(name)) {
            projectMetaInProgress.add(name)
            projectMetaExecutor.execute {
                try {
                    val totalWords = WordCountUtil.countWordsInProject(proj, contentResolver)
                    val wordsStr = nf.format(totalWords)
                    val finalTop = "$scenes scenes — $wordsStr words"
                    val finalMeta = finalTop to bottom
                    projectMetaCache[name] = finalMeta

                    // rebind adapters on UI thread with same lists but updated meta
                    runOnUiThread {
                        pinnedAdapter.update(currentPinned)
                        recentAdapter.update(currentRecent)
                        allAdapter.update(currentAll)
                    }
                } catch (_: Exception) {
                    // ignore, keep placeholder
                } finally {
                    projectMetaInProgress.remove(name)
                }
            }
        }

        return baseMeta
    }

    private fun reload() {
        val root = StorageAccess.getProjectsRoot(this) ?: run {
            emptyView.visibility = View.VISIBLE
            pinnedAdapter.update(emptyList())
            recentAdapter.update(emptyList())
            allAdapter.update(emptyList())
            return
        }

        val all = root.listFiles()
            .filter { it.isDirectory && !(it.name ?: "").startsWith(".") }
            .mapNotNull { it.name?.let(::File) }
            .sortedBy { it.name.lowercase() }

        val pinned = all.filter { isPinned(it.name) }
        val recent = all.filter { getLastOpenedProject(it.name) > 0 }
            .sortedByDescending { getLastOpenedProject(it.name) }
            .take(10)

        // store current lists so background meta updates can rebind safely
        currentAll = all
        currentPinned = pinned
        currentRecent = recent

        pinnedAdapter.update(pinned)
        recentAdapter.update(recent)
        allAdapter.update(all)

        emptyView.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showProjectMenu(name: String, anchor: View) {
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
                        // 1) Create ZIP of the project directory
                        val zipFile = createProjectZip(df)
                        val displayName = (df.name ?: "project") + ".zip"

                        // 2) Build a content:// URI via FileProvider
                        val uri = FileProvider.getUriForFile(
                            this,
                            "com.example.csideandroid.fileprovider", // must match manifest
                            zipFile
                        )

                        // 3) Share sheet (Drive / OneDrive / Gmail / etc.)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            putExtra(Intent.EXTRA_TITLE, displayName)
                        }

                        startActivity(
                            Intent.createChooser(
                                shareIntent,
                                "Upload project backup"
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            "Failed to prepare project for upload: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
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
                                } catch (_: Exception) {
                                }
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

    // Set up drag-and-drop reordering for pinned, recent, and all project grids.
    // Drag starts when the user presses and moves, so long-press is still free for the popup menu.

    private fun setupDragAndDrop() {

        // ---- PINNED SECTION ----
        val pinnedCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in currentPinned.indices || to !in currentPinned.indices) return false

                val list = currentPinned.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)

                currentPinned = list
                pinnedAdapter.update(list)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) { }
        }

        pinnedTouchHelper = ItemTouchHelper(pinnedCallback)
        pinnedTouchHelper.attachToRecyclerView(pinnedGrid)


        // ---- RECENT SECTION ----
        val recentCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in currentRecent.indices || to !in currentRecent.indices) return false

                val list = currentRecent.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)

                currentRecent = list
                recentAdapter.update(list)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) { }
        }

        recentTouchHelper = ItemTouchHelper(recentCallback)
        recentTouchHelper.attachToRecyclerView(recentGrid)


        // ---- ALL SECTION ----
        val allCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in currentAll.indices || to !in currentAll.indices) return false

                val list = currentAll.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)

                currentAll = list
                allAdapter.update(list)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) { }
        }

        allTouchHelper = ItemTouchHelper(allCallback)
        allTouchHelper.attachToRecyclerView(allGrid)
    }
}