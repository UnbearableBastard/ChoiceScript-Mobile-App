package com.example.csideandroid.util

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

object WordCountUtil {

    // Count words in a single ChoiceScript file, ignoring lines that start with *.
    fun countWordsInFile(file: DocumentFile, resolver: ContentResolver): Int {
        if (!file.isFile || file.name?.endsWith(".txt", ignoreCase = true) != true) return 0

        var count = 0

        resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine
                if (trimmed.startsWith("*")) return@forEachLine  // skip *commands

                count += trimmed
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .size
            }
        }

        return count
    }

    //  Count words in all .txt files under this project (recursively).
    fun countWordsInProject(root: DocumentFile, resolver: ContentResolver): Int {
        var total = 0

        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else if (child.name?.endsWith(".txt", ignoreCase = true) == true) {
                    total += countWordsInFile(child, resolver)
                }
            }
        }

        walk(root)
        return total
    }
}
