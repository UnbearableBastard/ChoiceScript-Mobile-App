package com.example.csideandroid.core

import android.content.Context
import java.io.File

data class Project(val name: String, val dir: File, val scenesDir: File)

object ProjectRepository {
    const val LOCK_STARTUP = "startup.txt"
    const val LOCK_STATS = "choicescript_stats.txt"
    private const val SCENE_LIST = "scene_list.txt"
    private const val ROOT_FOLDER = "cs_projects"

    fun root(context: Context): File = File(context.filesDir, ROOT_FOLDER).apply { mkdirs() }

    fun createProject(context: Context, name: String): Project {
        val projectDir = File(root(context), sanitize(name)).apply { mkdirs() }
        val mygame = File(projectDir, "mygame").apply { mkdirs() }
        val scenes = File(mygame, "scenes").apply { mkdirs() }

        val startup = File(scenes, LOCK_STARTUP); if (!startup.exists()) startup.writeText("")
        val stats = File(scenes, LOCK_STATS); if (!stats.exists()) stats.writeText("")
        val sceneList = File(scenes, SCENE_LIST); if (!sceneList.exists()) sceneList.writeText("startup\n")

        return Project(name = name, dir = projectDir, scenesDir = scenes)
    }

    fun openExistingFolderAsProject(context: Context, folder: File): Project {
        val mygame = File(folder, "mygame").apply { mkdirs() }
        val scenes = File(mygame, "scenes").apply { mkdirs() }
        val sceneList = File(scenes, SCENE_LIST)
        if (!sceneList.exists()) {
            val files = FileOps.listTxt(scenes).map { it.nameWithoutExtension }
            val ensured = (listOf("startup") + files.filter { it != "startup" }).distinct()
            sceneList.writeText(ensured.joinToString("\n") + "\n")
            if (!File(scenes, LOCK_STARTUP).exists()) File(scenes, LOCK_STARTUP).writeText("")
            if (!File(scenes, LOCK_STATS).exists()) File(scenes, LOCK_STATS).writeText("")
        }
        return Project(name = folder.name, dir = folder, scenesDir = scenes)
    }

    fun listScenes(project: Project): List<File> {
        val listFile = File(project.scenesDir, SCENE_LIST)
        val ordered = if (listFile.exists()) listFile.readLines().mapNotNull { n ->
            val t = n.trim(); if (t.isEmpty()) null else File(project.scenesDir, "$t.txt")
        } else emptyList()
        val actual = FileOps.listTxt(project.scenesDir).associateBy { it.name }
        val combined = mutableListOf<File>()
        ordered.forEach { f -> actual[f.name]?.let(combined::add) }
        actual.values.filter { it !in combined }.sortedBy { it.name }.forEach(combined::add)
        return combined
    }

    fun addNewScene(project: Project, rawName: String): File {
        val name = sanitize(rawName).ifBlank { "scene" }
        var file = File(project.scenesDir, "$name.txt")
        var i = 1
        while (file.exists()) { file = File(project.scenesDir, "${name}($i).txt"); i++ }
        file.writeText("")
        appendToSceneList(project, file)
        return file
    }

    fun addFileToCurrentProject(project: Project, external: File): File {
        val copied = FileOps.copyToDir(external, project.scenesDir)
        appendToSceneList(project, copied)
        return copied
    }

    fun renameScene(project: Project, file: File, newRawName: String): File {
        if (isLocked(file)) throw IllegalStateException("Locked file cannot be renamed")
        var newFile = File(project.scenesDir, sanitize(newRawName) + ".txt")
        var i = 1
        while (newFile.exists()) { newFile = File(project.scenesDir, sanitize(newRawName) + "($i).txt"); i++ }
        if (!file.renameTo(newFile)) throw IllegalStateException("Rename failed")
        rewriteSceneList(project) { names -> names.map { if (it == file.nameWithoutExtension) newFile.nameWithoutExtension else it } }
        return newFile
    }

    fun deleteScene(project: Project, file: File) {
        if (isLocked(file)) throw IllegalStateException("Locked file cannot be deleted")
        file.delete()
        rewriteSceneList(project) { names -> names.filter { it != file.nameWithoutExtension } }
    }

    fun isLocked(file: File): Boolean =
        file.name.equals(LOCK_STARTUP, true) || file.name.equals(LOCK_STATS, true)

    private fun sanitize(name: String): String =
        name.trim().replace("[^A-Za-z0-9_\\- ]".toRegex(), "_").replace(" +".toRegex(), "_")

    private fun appendToSceneList(project: Project, file: File) {
        val listFile = File(project.scenesDir, SCENE_LIST)
        val names = listFile.takeIf { it.exists() }?.readLines()?.toMutableList() ?: mutableListOf()
        val stem = file.nameWithoutExtension
        if (stem !in names) {
            names.add(stem)
            listFile.writeText(names.joinToString("\n") + "\n")
        }
    }

    private fun rewriteSceneList(project: Project, mapFn: (List<String>) -> List<String>) {
        val listFile = File(project.scenesDir, SCENE_LIST)
        val names = listFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()
        val mapped = mapFn(names)
        listFile.writeText(mapped.joinToString("\n") + "\n")
    }
}
