
package com.example.csideandroid.ui

import android.content.res.Resources
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView

private fun Int.dp(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

// Apply bottom padding = system navigation bar height + extraDp.
fun View.applyBottomInsetPadding(extraDp: Int = 16) {
    if (this is RecyclerView) this.clipToPadding = false

    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val extra = extraDp.dp()

    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPaddingRelative(start, top, end, sys.bottom + extra)
        insets
    }
    requestApplyInsets()
}
