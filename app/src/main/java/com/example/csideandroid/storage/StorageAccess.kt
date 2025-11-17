package com.example.csideandroid.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
// Select a location and create a project directory along with startup.txt, choicescript_stats.txt
object StorageAccess {
    private const val PREFS = "cside_storage"
    private const val KEY_ROOT = "projects_root_uri"

    fun hasBase(ctx: Context): Boolean = getBaseUri(ctx) != null

    fun setProjectsRoot(ctx: Context, uri: Uri) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROOT, uri.toString()).apply()
    }

    private fun getBaseUri(ctx: Context): Uri? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROOT, null)
        return s?.let { Uri.parse(it) }
    }

    fun getProjectsRoot(ctx: Context): DocumentFile? {
        val uri = getBaseUri(ctx) ?: return null
        return DocumentFile.fromTreeUri(ctx, uri)
    }

    fun createProject(ctx: Context, name: String): DocumentFile? {
        val root = getProjectsRoot(ctx) ?: return null
        // Avoid collisions
        if (root.findFile(name) != null) return null

        val project = root.createDirectory(name) ?: return null
        val mygame = project.createDirectory("mygame") ?: project
        val scenes = mygame.createDirectory("scenes") ?: project

        val startup = scenes.findFile("startup.txt") ?: scenes.createFile("text/plain", "startup.txt")
        scenes.findFile("choicescript_stats.txt") ?: scenes.createFile("text/plain", "choicescript_stats.txt")

        if (startup != null) {
            try {
                ctx.contentResolver.openOutputStream(startup.uri, "rwt")?.use { os ->
                    os.writer().use { w ->
                        val four = "    "
                        w.write(
                            "*title $name\n" +
                                    "*author \n" +
                                    "*comment your code goes here\n" +
                                    "*scene_list\n" +
                                    "${four}startup\n" +
                                    "*finish\n"
                        )
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }
        return project
    }
}
