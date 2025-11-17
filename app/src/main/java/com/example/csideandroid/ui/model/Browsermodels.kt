package com.example.csideandroid.ui.model

import com.example.csideandroid.core.Project
import java.io.File

// One collapsible group per project
data class ProjectGroup(
    val project: Project,
    val scenes: List<File>,
    var expanded: Boolean = false
)

// Rows for the list: a project header or a scene entry
sealed class RowItem {
    data class Header(val group: ProjectGroup) : RowItem()
    data class Scene(val group: ProjectGroup, val file: File) : RowItem()
}
