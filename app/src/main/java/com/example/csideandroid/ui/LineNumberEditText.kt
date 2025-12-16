package com.example.csideandroid.ui

import android.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.ViewTreeObserver
import androidx.appcompat.widget.AppCompatEditText

// Monospace, wrapping code editor with a drawn line-number gutter.
//  Tiny undo/redo and indent/outdent helpers.

class LineNumberEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyle) {

    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        // Use the same text size as the main editor to align baselines for line numbers.
        // The size will be updated in onSizeChanged() and when text size changes.
        textSize = this@LineNumberEditText.textSize
        // Use the same typeface as the editor so the metrics (ascent/descent) match
        typeface = Typeface.MONOSPACE
    }
    private val gutterRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        strokeWidth = resources.displayMetrics.density
    }

    private val padDp = (8 * resources.displayMetrics.density).toInt()
    private var gutterWidth = (48 * resources.displayMetrics.density).toInt()

    private var selectionChangedListener: ((start: Int, end: Int) -> Unit)? = null

    fun setOnSelectionChangedListener(l: ((start: Int, end: Int) -> Unit)?) {
        selectionChangedListener = l
    }

    //  tiny undo/redo stack
    private data class Snap(val text: String, val selStart: Int, val selEnd: Int)
    private val undo = ArrayDeque<Snap>()
    private val redo = ArrayDeque<Snap>()
    private var suppressRecord = false
    private var suspendUndoRecording = false
    private val maxHistory = 100

    // snapshot of text/selection BEFORE a change
    private var lastBeforeChange: Snap? = null


    fun setUndoRecordingEnabled(enabled: Boolean) {
        suspendUndoRecording = !enabled
    }

    fun clearUndoHistory() {
        undo.clear()
        redo.clear()
    }


    init {
        typeface = Typeface.MONOSPACE
        setHorizontallyScrolling(false) // wrap

        setIncludeFontPadding(false)
        setPadding(gutterWidth + padDp, paddingTop, paddingRight, paddingBottom)

        // Record snapshots and adjust gutter width on text changes
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (suppressRecord || suspendUndoRecording) return
                // Capture state BEFORE this change so undo goes back to it
                lastBeforeChange = Snap(text?.toString() ?: "", selectionStart, selectionEnd)
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (suppressRecord || suspendUndoRecording) return

                // Push the before change state into undo, not the new state
                lastBeforeChange?.let { snap ->
                    pushUndo(snap)
                    lastBeforeChange = null
                }

                // When text changes, adjust gutter width to accommodate changed line counts
                computeGutterWidth()
                invalidate()
            }
        })

        viewTreeObserver.addOnGlobalLayoutListener(
            ViewTreeObserver.OnGlobalLayoutListener { post { computeGutterWidth() } }
        )
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        selectionChangedListener?.invoke(selStart, selEnd)
    }

    private fun pushUndo(snap: Snap) {
        // Avoid pushing duplicates for tiny edits
        if (undo.isEmpty() || undo.last() != snap) {
            undo.addLast(snap)
            while (undo.size > maxHistory) undo.removeFirst()
        }
        // Any new user edit kills redo history
        redo.clear()
    }

    fun undo() {
        if (undo.isEmpty()) return

        val current = Snap(text?.toString() ?: "", selectionStart, selectionEnd)
        val prev = undo.removeLast()          // previous state we want to go back to

        // current visible state goes to redo
        redo.addLast(current)
        applySnap(prev)
    }

    fun redo() {
        if (redo.isEmpty()) return

        val current = Snap(text?.toString() ?: "", selectionStart, selectionEnd)
        val next = redo.removeLast()          // state we want to move forward to

        // current visible state goes back to undo
        undo.addLast(current)
        applySnap(next)
    }

    private fun applySnap(s: Snap) {
        suppressRecord = true
        setText(s.text)
        val safe = s.text.length
        val a = s.selStart.coerceIn(0, safe)
        val b = s.selEnd.coerceIn(0, safe)
        try {
            setSelection(a, b)
        } catch (_: Throwable) {
            setSelection(safe)
        }
        suppressRecord = false
    }

    fun indent() = replaceSelection(prefix = "    ", removePrefix = null)
    fun outdent() = replaceSelection(prefix = null, removePrefix = "    ")

    private fun replaceSelection(prefix: String? = null, removePrefix: String? = null) {
        val start = selectionStart
        val end = selectionEnd
        val full = text ?: return
        val s = minOf(start, end)
        val e = maxOf(start, end)

        // keep the caret on the same visual line/column after indent/outdent.
        val caret = if (start == end) start else -1
        var caretNewPos = -1

        // operate line-by-line
        val content = full.toString()
        val sb = StringBuilder(content.length + 64)
        var i = 0
        var lineStart = 0
        while (i <= content.length) {
            val isBreak = (i == content.length) || (content[i] == '\n')
            if (isBreak) {
                val lineEnd = i
                val line = content.substring(lineStart, lineEnd)
                val lineSelStart = maxOf(lineStart, s)
                val lineSelEnd = minOf(lineEnd, e)
                val touches = lineSelStart <= lineSelEnd

                // remember where this line will start in the new buffer
                val newLineStart = sb.length

                if (touches && prefix != null) {
                    // indent
                    sb.append(prefix).append(line)
                } else if (touches && removePrefix != null && line.startsWith(removePrefix)) {
                    // outdent
                    sb.append(line.removePrefix(removePrefix))
                } else {
                    sb.append(line)
                }

                // If the caret was on this line, compute its new position
                if (caret >= 0 && caret in lineStart..lineEnd) {
                    val caretOffsetInLine = caret - lineStart
                    caretNewPos = when {
                        touches && prefix != null -> {
                            // added at the start of the line
                            newLineStart + prefix.length + caretOffsetInLine
                        }
                        touches && removePrefix != null && line.startsWith(removePrefix) -> {
                            // removed prefix from the start of the line
                            val adjustedOffset = (caretOffsetInLine - removePrefix.length).coerceAtLeast(0)
                            newLineStart + adjustedOffset
                        }
                        else -> {
                            newLineStart + caretOffsetInLine
                        }
                    }
                }

                if (i < content.length) sb.append('\n')
                lineStart = i + 1
            }
            i++
        }
        suppressRecord = true
        setText(sb.toString())

        if (caretNewPos >= 0) {
            val safe = caretNewPos.coerceIn(0, sb.length)
            setSelection(safe)
        } else {
            // keep cursor roughly at start line for multi-line selections
            val newPos = s.coerceIn(0, sb.length)
            setSelection(newPos)
        }

        suppressRecord = false
    }

    private fun computeGutterWidth() {
        val totalLines = text?.let { 1 + it.count { ch -> ch == '\n' } } ?: 1
        val digits = totalLines.toString().length
        val w = gutterPaint.measureText("9".repeat(digits)) + 2 * padDp
        gutterWidth = w.toInt().coerceAtLeast((36 * resources.displayMetrics.density).toInt())
        setPadding(gutterWidth + padDp, paddingTop, paddingRight, paddingBottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Ensure gutter text size is the same as the main editor text size for proper alignment.
        // Keep the gutter paint in sync with the editor's text size.
        gutterPaint.textSize = textSize
        gutterPaint.typeface = typeface

        val left = (scrollX).toFloat()
        val top = (scrollY).toFloat()
        val bottom = (scrollY + height).toFloat()
        val xRule = (left + gutterWidth - padDp / 2).toFloat()
        canvas.drawLine(xRule, top, xRule, bottom, gutterRulePaint)

        val lay: Layout? = layout
        if (lay != null) {
            val first = lay.getLineForVertical(scrollY)
            val last = lay.getLineForVertical(scrollY + height)
            val content = text?.toString() ?: ""
            var logical = 1
            val editorFm = paint.fontMetrics
            val gutterFm = gutterPaint.fontMetrics
            val baselineShift = editorFm.descent - gutterFm.descent
            val offsetY = totalPaddingTop.toFloat() - scrollY

            for (line in first..last) {
                val start = lay.getLineStart(line)
                if (start == 0 || (start - 1 in content.indices && content[start - 1] == '\n')) {
                    val baseline = lay.getLineBaseline(line).toFloat()
                    val y = baseline + baselineShift + offsetY
                    val t = logical.toString()
                    val x = (left + gutterWidth - padDp - gutterPaint.measureText(t))
                    canvas.drawText(t, x, y, gutterPaint)
                    logical++
                }
            }
        }

        super.onDraw(canvas)
    }

    // Request a re-measure and redraw of the line-number gutter.
    fun invalidateLineNumbers() {
        requestLayout()
        invalidate()
    }
}
