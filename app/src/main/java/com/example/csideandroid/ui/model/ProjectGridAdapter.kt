package com.example.csideandroid.ui

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.csideandroid.R
import java.io.File

class ProjectGridAdapter(
    private var projects: List<File>,
    private val isPinned: (File) -> Boolean,
    private val metaProvider: (File) -> Pair<String, String>,
    private val onOpen: (File) -> Unit,
    private val onTogglePin: (File) -> Unit,
    private val onLongPress: (File, View) -> Unit
) : RecyclerView.Adapter<ProjectGridAdapter.CardVH>() {

    fun submit(list: List<File>) {
        projects = list
        notifyDataSetChanged()
    }

    fun update(list: List<File>) {
        projects = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project_card, parent, false)
        return CardVH(v)
    }

    override fun onBindViewHolder(holder: CardVH, position: Int) {
        holder.bind(projects[position], isPinned, metaProvider, onOpen, onTogglePin, onLongPress)
    }

    override fun getItemCount(): Int = projects.size

    class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.txtCardName)
        private val meta1: TextView = v.findViewById(R.id.txtCardMeta1)
        private val meta2: TextView = v.findViewById(R.id.txtCardMeta2)
        private val path: TextView = v.findViewById(R.id.txtCardPath)
        private val pin: ImageButton = v.findViewById(R.id.btnPin)

        fun bind(
            dir: File,
            isPinned: (File) -> Boolean,
            metaProvider: (File) -> Pair<String, String>,
            onOpen: (File) -> Unit,
            onToggle: (File) -> Unit,
            onLongPress: (File, View) -> Unit
        ) {
            name.text = dir.name
            val (m1, m2) = metaProvider(dir)
            meta1.text = m1
            meta2.text = m2
            path.text = dir.absolutePath

            fun applyPinState(pinnedNow: Boolean) {
                val ctx = itemView.context
                val nightMode = (ctx.resources.configuration.uiMode
                        and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

                // Filled when pinned, outline when not
                if (pinnedNow) {
                    pin.setImageResource(R.drawable.ic_star_filled)
                } else {
                    pin.setImageResource(R.drawable.ic_star_outline)
                }

                // Light mode: black stars; Dark mode: white stars
                val tintColor = if (nightMode) Color.WHITE else Color.BLACK
                ImageViewCompat.setImageTintList(pin, ColorStateList.valueOf(tintColor))
            }

            val pinned = isPinned(dir)
            applyPinState(pinned)

            itemView.setOnClickListener { onOpen(dir) }
            itemView.setOnLongClickListener { onLongPress(dir, itemView); true }
            pin.setOnClickListener {
                onToggle(dir)
                applyPinState(isPinned(dir))
            }
        }
    }
}
