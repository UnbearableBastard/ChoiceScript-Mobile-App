package com.example.csideandroid.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import com.example.csideandroid.R
import com.google.android.material.textfield.TextInputEditText
import java.io.File

// Long-press popup for project cards with Rename/Delete.
object ProjectCardMenu {

    fun attach(
        anchorView: View,
        projectDir: File,
        onRenamedOrDeleted: () -> Unit
    ) {
        anchorView.setOnLongClickListener {
            showPopup(anchorView, projectDir, onRenamedOrDeleted)
            true
        }
    }

    private fun showPopup(anchor: View, projectDir: File, onChanged: () -> Unit) {
        val ctx = anchor.context
        val popup = PopupMenu(ctx, anchor)
        val idRename = 1
        val idDelete = 2
        popup.menu.add(0, idRename, 0, "Rename project")
        popup.menu.add(0, idDelete, 1, "Delete project")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                idRename -> {
                    showRenameDialog(ctx, projectDir) { onChanged() }
                    true
                }
                idDelete -> {
                    confirmDelete(ctx, projectDir) { onChanged() }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(context: Context, projectDir: File, onRenamed: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_project_rename, null)
        val input = view.findViewById<TextInputEditText>(R.id.inputProjectName)
        input.setText(projectDir.name)

        AlertDialog.Builder(context)
            .setTitle("Rename project")
            .setView(view) // keep Material TextInput
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && newName != projectDir.name) {
                    renameDir(projectDir, newName)?.let {
                        onRenamed()
                    }
                }
            }
            .show()
    }

    private fun confirmDelete(context: Context, projectDir: File, onDeleted: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Delete project?")
            .setMessage("This will permanently delete the project folder and its contents.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (projectDir.deleteRecursively()) {
                    onDeleted()
                }
            }
            .show()
    }

    private fun renameDir(dir: File, newName: String): File? {
        val dest = File(dir.parentFile, newName)
        return if (!dest.exists() && dir.renameTo(dest)) dest else null
    }
}
