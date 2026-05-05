package com.example.csideandroid.util

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

object WordCountUtil {

    data class WordCounts(val withoutCode: Int, val withCode: Int)

    // Count words excluding CS command/option lines (lines starting with * or #)
    fun countWordsInFile(file: DocumentFile, resolver: ContentResolver): Int =
        countWordsInFileBoth(file, resolver).withoutCode

    // Count words including all lines
    fun countWordsInFileWithCode(file: DocumentFile, resolver: ContentResolver): Int =
        countWordsInFileBoth(file, resolver).withCode

    // Count both in a single pass for efficiency
    fun countWordsInFileBoth(file: DocumentFile, resolver: ContentResolver): WordCounts {
        if (!file.isFile || file.name?.endsWith(".txt", ignoreCase = true) != true)
            return WordCounts(0, 0)

        var withoutCode = 0
        var withCode = 0

        resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine

                val wordCount = trimmed
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .size

                withCode += wordCount
                if (!trimmed.startsWith("*") && !trimmed.startsWith("#")) {
                    withoutCode += wordCount
                }
            }
        }

        return WordCounts(withoutCode, withCode)
    }

    // Count words (without code) in all .txt files under this project (recursively).
    fun countWordsInProject(root: DocumentFile, resolver: ContentResolver): Int =
        countWordsInProjectBoth(root, resolver).withoutCode

    // Count both with and without code for a whole project
    fun countWordsInProjectBoth(root: DocumentFile, resolver: ContentResolver): WordCounts {
        var totalWithout = 0
        var totalWith = 0

        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else if (child.name?.endsWith(".txt", ignoreCase = true) == true) {
                    val counts = countWordsInFileBoth(child, resolver)
                    totalWithout += counts.withoutCode
                    totalWith += counts.withCode
                }
            }
        }

        walk(root)
        return WordCounts(totalWithout, totalWith)
    }
}
