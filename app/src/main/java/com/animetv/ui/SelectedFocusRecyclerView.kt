package com.animetv.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A tab strip RecyclerView: when focus enters it (D-pad or nextFocus*), the selected item takes focus
 * instead of whichever child happens to be first, so Up/Down always return to the current tab.
 */
class SelectedFocusRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : RecyclerView(context, attrs) {
    var selectedPosition = 0

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        findViewHolderForAdapterPosition(selectedPosition)?.itemView?.let { if (it.requestFocus()) return true }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}
