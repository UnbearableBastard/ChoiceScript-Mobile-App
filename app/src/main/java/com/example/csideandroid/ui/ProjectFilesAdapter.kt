package com.example.csideandroid.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.csideandroid.R
import java.io.File

class ProjectFilesAdapter(
    private val metaProvider: (File) -> Pair<String,String>,
    private val onOpenTxt: (File) -> Unit,
    private val onRename: (File) -> Unit,
    private val onDelete: (File) -> Unit,
    private val isProtected: (File) -> Boolean
) : ListAdapter<File, ProjectFilesAdapter.VH>(DIFF) {

    private fun <T: android.view.View> View.safeFind(vararg ids: Int): T {
        for (id in ids) {
            try { return findViewById(id) } catch (_: Exception) {}
            val v = findViewById<T?>(id)
            if (v != null) return v
        }
        throw IllegalStateException("None of the provided IDs exist in this layout")
    }



    fun submit(list: List<File>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val f = getItem(pos)
        h.title.text = f.name
        val (m1, m2) = metaProvider(f)
        h.meta1.text = m1
        h.meta2.text = m2

        h.itemView.setOnClickListener { onOpenTxt(f) }

        h.more.setOnClickListener { anchor ->
            val p = android.widget.PopupMenu(anchor.context, anchor)
            if (!isProtected(f)) {
                p.menu.add("Rename")
                p.menu.add("Delete")
            } else {
                p.menu.add("Protected")
            }
            p.setOnMenuItemClickListener { mi ->
                when (mi.title.toString()) {
                    "Rename" -> onRename(f)
                    "Delete" -> onDelete(f)
                }
                true
            }
            p.show()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtTitle)
        val meta1: TextView = v.findViewById(R.id.txtMeta1)
        val meta2: TextView = v.findViewById(R.id.txtMeta2)
        val icon: ImageView = v.findViewById(R.id.imgIcon)
        val more: ImageButton = v.findViewById(R.id.btnMore)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<File>() {
            override fun areItemsTheSame(oldItem: File, newItem: File) = oldItem.name == newItem.name
            override fun areContentsTheSame(oldItem: File, newItem: File) = oldItem == newItem
        }
    }
}
