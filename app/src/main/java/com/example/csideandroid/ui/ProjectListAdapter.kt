package com.example.csideandroid.ui.model

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.documentfile.provider.DocumentFile
import com.example.csideandroid.R
import java.io.File

private const val VIEW_TYPE_HEADER = 0
private const val VIEW_TYPE_FILE = 1
private const val PAYLOAD_ROW_SHAPE = "row_shape"

class ProjectListAdapter(
    private var groups: List<ProjectGroup>,
    private val isPinned: (File) -> Boolean,
    private val metaProvider: (File) -> Pair<String, String>,
    private val fileMetaProvider: (ProjectGroup, DocumentFile) -> Pair<String, String>,
    private val onOpen: (File) -> Unit,
    private val onTogglePin: (File) -> Unit,
    private val onShowProjectMenu: (File, View) -> Unit,
    private val onToggleExpand: (ProjectGroup) -> Unit,
    private val onReorderProjects: (List<ProjectGroup>) -> Unit,
    private val onReorderFiles: (ProjectGroup, List<DocumentFile>) -> Unit,
    private val onOpenFile: (ProjectGroup, DocumentFile) -> Unit,
    private val onFileMenu: (ProjectGroup, DocumentFile, View) -> Unit,
    private val onActionNew: (ProjectGroup) -> Unit,
    private val onActionPlay: (ProjectGroup) -> Unit,
    private val onActionTest: (ProjectGroup) -> Unit,
    private val onActionCompile: (ProjectGroup) -> Unit,
    private val onActionMore: (ProjectGroup, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<RowItem> = emptyList()

    init {
        rebuildRows()
    }

    private fun rebuildRows() {
        val out = ArrayList<RowItem>()
        for (g in groups) {
            out.add(RowItem.Header(g))
            if (g.expanded) {
                for (doc in g.files) out.add(RowItem.FileRow(g, doc))
            }
        }
        rows = out
    }

    fun update(list: List<ProjectGroup>) {
        groups = list
        rebuildRows()
        notifyDataSetChanged()
    }

    fun refreshGroup(group: ProjectGroup) {
        rebuildRows()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is RowItem.Header -> VIEW_TYPE_HEADER
            is RowItem.FileRow -> VIEW_TYPE_FILE
        }

    override fun getItemCount(): Int = rows.size

    fun rowAt(position: Int): RowItem? = rows.getOrNull(position)

    // Group owning the file row at position, or null if that row isn't a file row.
    fun fileGroupAt(position: Int): ProjectGroup? =
        (rows.getOrNull(position) as? RowItem.FileRow)?.group

    fun refreshRowShapes(group: ProjectGroup) {
        val first = rows.indexOfFirst { it is RowItem.FileRow && it.group === group }
        if (first == -1) return

        var count = 0
        var i = first
        while (i < rows.size) {
            val r = rows[i]
            if (r !is RowItem.FileRow || r.group !== group) break
            count++
            i++
        }
        if (count > 0) notifyItemRangeChanged(first, count, PAYLOAD_ROW_SHAPE)
    }

    // Returns true if a drag from `from` to `to` is a legal move.
    fun canMove(from: Int, to: Int): Boolean {
        val a = rows.getOrNull(from) ?: return false
        val b = rows.getOrNull(to) ?: return false
        return when {
            a is RowItem.Header && b is RowItem.Header -> true
            a is RowItem.FileRow && b is RowItem.FileRow -> a.group === b.group
            else -> false
        }
    }

    fun onMoved(from: Int, to: Int) {
        val a = rows[from]
        when (a) {
            is RowItem.Header -> {
                val list = groups.toMutableList()
                val fromIdx = list.indexOf(a.group)
                val toGroup = (rows[to] as RowItem.Header).group
                val toIdx = list.indexOf(toGroup)
                if (fromIdx == -1 || toIdx == -1) return
                val item = list.removeAt(fromIdx)
                list.add(toIdx, item)
                groups = list
                rebuildRows()
                notifyItemMoved(from, to)
                onReorderProjects(list)
            }
            is RowItem.FileRow -> {
                val g = a.group
                val fromIdx = g.files.indexOf(a.doc)
                val toDoc = (rows[to] as RowItem.FileRow).doc
                val toIdx = g.files.indexOf(toDoc)
                if (fromIdx == -1 || toIdx == -1) return
                val item = g.files.removeAt(fromIdx)
                g.files.add(toIdx, item)
                rebuildRows()
                notifyItemMoved(from, to)
                onReorderFiles(g, g.files)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_project_group_header, parent, false)
            HeaderVH(v)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_project_file_row, parent, false)
            FileVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is RowItem.Header -> {
                val hasVisibleRowsBelow = row.group.expanded &&
                        row.group.filesLoaded && row.group.files.isNotEmpty()
                (holder as HeaderVH).bind(
                    row.group, isPinned, metaProvider, onOpen, onTogglePin, onShowProjectMenu,
                    onToggleExpand, onActionNew, onActionPlay, onActionTest, onActionCompile, onActionMore,
                    hasVisibleRowsBelow
                )
            }
            is RowItem.FileRow -> {
                val nextRow = rows.getOrNull(position + 1)
                val isLast = nextRow !is RowItem.FileRow || nextRow.group !== row.group
                (holder as FileVH).bind(
                    row.group, row.doc, fileMetaProvider, onOpenFile, onFileMenu, isLast
                )
            }
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.txtCardName)
        private val meta1: TextView = v.findViewById(R.id.txtCardMeta1)
        private val meta2: TextView = v.findViewById(R.id.txtCardMeta2)
        private val pin: ImageButton = v.findViewById(R.id.btnPin)
        private val chevron: ImageView = v.findViewById(R.id.imgChevron)
        private val headerRow: View = v.findViewById(R.id.headerRow)
        private val actionRow: View = v.findViewById(R.id.actionRow)
        private val txtLoading: View = v.findViewById(R.id.txtFilesLoading)
        private val txtEmpty: View = v.findViewById(R.id.txtFilesEmpty)
        private val actionNew: View = v.findViewById(R.id.actionNew)
        private val actionPlay: View = v.findViewById(R.id.actionPlay)
        private val actionTest: View = v.findViewById(R.id.actionTest)
        private val actionCompile: View = v.findViewById(R.id.actionCompile)
        private val actionMore: View = v.findViewById(R.id.actionMore)

        fun bind(
            group: ProjectGroup,
            isPinned: (File) -> Boolean,
            metaProvider: (File) -> Pair<String, String>,
            onOpen: (File) -> Unit,
            onTogglePin: (File) -> Unit,
            onShowProjectMenu: (File, View) -> Unit,
            onToggleExpand: (ProjectGroup) -> Unit,
            onActionNew: (ProjectGroup) -> Unit,
            onActionPlay: (ProjectGroup) -> Unit,
            onActionTest: (ProjectGroup) -> Unit,
            onActionCompile: (ProjectGroup) -> Unit,
            onActionMore: (ProjectGroup, View) -> Unit,
            hasVisibleRowsBelow: Boolean
        ) {
            val dir = group.projectFile
            val ctx = itemView.context
            name.text = dir.name
            val (m1, m2) = metaProvider(dir)
            meta1.text = m1
            meta2.text = m2

            fun applyPinState(pinnedNow: Boolean) {
                pin.setImageResource(if (pinnedNow) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                val colorRes = if (pinnedNow) R.color.pb_gold else R.color.pb_text_tertiary
                ImageViewCompat.setImageTintList(
                    pin, ColorStateList.valueOf(ContextCompat.getColor(ctx, colorRes))
                )
            }
            applyPinState(isPinned(dir))

            pin.setOnClickListener {
                onTogglePin(dir)
                applyPinState(isPinned(dir))
            }

            chevron.rotation = if (group.expanded) 270f else 90f
            actionRow.visibility = if (group.expanded) View.VISIBLE else View.GONE
            txtLoading.visibility = if (group.expanded && group.loading) View.VISIBLE else View.GONE
            txtEmpty.visibility =
                if (group.expanded && !group.loading && group.filesLoaded && group.files.isEmpty()) View.VISIBLE else View.GONE
            itemView.setBackgroundResource(
                if (hasVisibleRowsBelow) R.drawable.bg_project_group_top else R.drawable.bg_project_group
            )
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = if (hasVisibleRowsBelow) 0 else itemView.resources.getDimensionPixelSize(R.dimen.project_card_margin)
                itemView.layoutParams = lp
            }

            // Tap header = expand/collapse
            headerRow.setOnClickListener {
                onToggleExpand(group)
            }
            headerRow.setOnLongClickListener {
                onShowProjectMenu(dir, pin)
                true
            }

            actionNew.setOnClickListener { onActionNew(group) }
            actionPlay.setOnClickListener { onActionPlay(group) }
            actionTest.setOnClickListener { onActionTest(group) }
            actionCompile.setOnClickListener { onActionCompile(group) }
            actionMore.setOnClickListener { onActionMore(group, it) }
        }
    }

    class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.txtFileName)
        private val meta: TextView = v.findViewById(R.id.txtFileMeta)
        private val more: ImageButton = v.findViewById(R.id.btnFileMore)

        fun bind(
            group: ProjectGroup,
            doc: DocumentFile,
            fileMetaProvider: (ProjectGroup, DocumentFile) -> Pair<String, String>,
            onOpenFile: (ProjectGroup, DocumentFile) -> Unit,
            onFileMenu: (ProjectGroup, DocumentFile, View) -> Unit,
            isLast: Boolean
        ) {
            name.text = doc.name
            val (m1, m2) = fileMetaProvider(group, doc)
            meta.text = if (m2.isNotBlank()) "$m1 · $m2" else m1

            itemView.setBackgroundResource(
                if (isLast) R.drawable.bg_project_file_last else R.drawable.bg_project_file_middle
            )
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = if (isLast) itemView.resources.getDimensionPixelSize(R.dimen.project_card_margin) else 0
                itemView.layoutParams = lp
            }

            itemView.setOnClickListener { onOpenFile(group, doc) }
            more.setOnClickListener { anchor -> onFileMenu(group, doc, anchor) }
        }
    }
}