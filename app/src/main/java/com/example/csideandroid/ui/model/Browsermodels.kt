package com.example.csideandroid.ui.model

import androidx.documentfile.provider.DocumentFile
import java.io.File

// One collapsible group per project.
data class ProjectGroup(
    val projectFile: File,
    val projectDoc: DocumentFile,
    var scenesDoc: DocumentFile? = null,
    var files: MutableList<DocumentFile> = mutableListOf(),
    var expanded: Boolean = false,
    var filesLoaded: Boolean = false,
    var loading: Boolean = false
)

sealed class RowItem {
    data class Header(val group: ProjectGroup) : RowItem()
    data class FileRow(val group: ProjectGroup, val doc: DocumentFile) : RowItem()
}
