package com.example.csideandroid.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
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
import androidx.core.content.edit

class ProjectsBrowserActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("cside_prefs", MODE_PRIVATE) }

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

    private val projectMetaExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentPinned: List<File> = emptyList()
    private var currentRecent: List<File> = emptyList()
    private var currentAll: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projects_browser)

        pinnedGrid = findViewById(R.id.recyclerPinned)
        recentGrid = findViewById(R.id.recyclerRecent)
        allGrid = findViewById(R.id.recyclerAll)
        emptyView = findViewById(R.id.txtEmptyProjects)

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
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        projectMetaExecutor.shutdownNow()
    }

    // META SYSTEM

    private fun metaForProject(name: String): Pair<String, String> {
        projectMetaCache[name]?.let { return it }

        val root = StorageAccess.getProjectsRoot(this) ?: return "—" to "—"
        val proj = root.findFile(name) ?: return "—" to "—"

        val scenesDir = proj.findFile("scenes")
            ?: proj.findFile("mygame")?.findFile("scenes")

        var scenes = 0
        scenesDir?.listFiles()?.forEach {
            if (it.isFile && (it.name?.lowercase()?.endsWith(".txt") == true)) scenes++
        }

        val t = getLastOpenedProject(name)
        val bottom =
            if (t > 0) "Last opened " + DateUtils.getRelativeTimeSpanString(
                t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            else "Never opened"

        val nf = NumberFormat.getIntegerInstance()
        val baseMeta = "$scenes scenes — calculating words…" to bottom

        projectMetaCache[name] = baseMeta

        if (!projectMetaInProgress.contains(name)) {
            projectMetaInProgress.add(name)
            projectMetaExecutor.execute {
                try {
                    val totalWords = WordCountUtil.countWordsInProject(proj, contentResolver)
                    val wordsStr = nf.format(totalWords)
                    val finalTop = "$scenes scenes — $wordsStr words"
                    val finalMeta = finalTop to bottom
                    projectMetaCache[name] = finalMeta

                    runOnUiThread {
                        pinnedAdapter.update(currentPinned)
                        recentAdapter.update(currentRecent)
                        allAdapter.update(currentAll)
                    }
                } finally {
                    projectMetaInProgress.remove(name)
                }
            }
        }
        return baseMeta
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

        currentAll = all
        currentPinned = pinned
        currentRecent = recent

        pinnedAdapter.update(pinned)
        recentAdapter.update(recent)
        allAdapter.update(all)

        emptyView.visibility = if (rawAll.isEmpty()) View.VISIBLE else View.GONE
    }

    // DRAG & DROP WITH SAVE

    private fun setupDragAndDrop() {

        // ---- PINNED ----
        val pinnedCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled() = true

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in currentPinned.indices || to !in currentPinned.indices) return false

                val list = currentPinned.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)

                currentPinned = list
                pinnedAdapter.update(list)
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

                val list = currentRecent.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)

                currentRecent = list
                recentAdapter.update(list)
                saveOrder("order_recent", list) // SAVE
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

                val list = currentAll.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)

                currentAll = list
                allAdapter.update(list)
                saveOrder("order_all", list) // SAVE
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
}
