package com.example.csideandroid.core

import java.io.File

object FileOps {
    fun readUtf8(file: File): String = file.readText(Charsets.UTF_8)
    fun writeUtf8(file: File, text: String) = file.writeText(text, Charsets.UTF_8)

    fun normalizeNewlines(s: String): String =
        s.replace("\r\n", "\n").replace("\r", "\n")

    fun listTxt(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", true) }?.toList() ?: emptyList()

    fun copyToDir(src: File, destDir: File): File {
        destDir.mkdirs()
        val out = File(destDir, src.name)
        src.inputStream().use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }
}
