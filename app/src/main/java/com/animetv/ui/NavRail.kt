package com.animetv.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.animetv.CategoryActivity
import com.animetv.MainActivity
import com.animetv.R
import com.animetv.SearchActivity

/**
 * The left menu (layout/nav_rail) on home and the listing screens: Khám phá, Mới cập nhật, Tìm kiếm, Thể loại.
 * [current] (a nav_* id) is marked in orange; choosing it again runs [onCurrent] (usually: focus the content).
 * The rail shows icons only and widens with labels while it has focus.
 */
class NavRail(private val activity: Activity, private val current: Int, private val onCurrent: () -> Unit) {
    val view: View = activity.findViewById(R.id.nav_rail)
    private val items: List<View>
    private var animator: ValueAnimator? = null
    private var expanded = false

    init {
        activity.findViewById<TextView>(R.id.rail_brand).text = SpannableString("AniBolt").apply {
            setSpan(StyleSpan(Typeface.BOLD), 3, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun item(id: Int, icon: Int, label: Int): View = activity.findViewById<View>(id).apply {
            findViewById<ImageView>(R.id.rail_icon).setImageResource(icon)
            findViewById<TextView>(R.id.rail_label).setText(label)
            contentDescription = activity.getString(label)
            isSelected = id == current
            setOnClickListener { open(id) }
        }
        items = listOf(
            item(R.id.nav_home, R.drawable.ic_nav_home, R.string.nav_home),
            item(R.id.nav_updated, R.drawable.ic_nav_updated, R.string.nav_updated),
            item(R.id.nav_search, R.drawable.ic_nav_search, R.string.nav_search),
            item(R.id.nav_genres, R.drawable.ic_nav_category, R.string.nav_genres),
        )
        // Up and Down stay inside the menu at its ends.
        items.first().nextFocusUpId = items.first().id
        items.last().nextFocusDownId = items.last().id
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus -> setExpanded(newFocus != null && contains(newFocus)) }
    }

    /** Moves focus to the item of the current screen. */
    fun focus() = activity.findViewById<View>(current).requestFocus()

    fun contains(target: View): Boolean {
        var node: View? = target
        while (node != null) {
            if (node === view) return true
            node = node.parent as? View
        }
        return false
    }

    fun release() = animator?.cancel()

    private fun open(id: Int) {
        if (id == current) {
            onCurrent()
            return
        }
        val intent = when (id) {
            // Back to the home already underneath instead of stacking a second one.
            R.id.nav_home -> Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            R.id.nav_updated -> Intent(activity, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_LISTING, SearchActivity.LISTING_UPDATED)
            R.id.nav_search -> Intent(activity, SearchActivity::class.java)
            else -> Intent(activity, CategoryActivity::class.java)
        }
        activity.startActivity(intent)
        // Screens reached from the menu replace each other; only home stays underneath, so Back always leads home.
        if (activity !is MainActivity) activity.finish()
    }

    /** Collapsed: logo and icons only. Focused: qfilm's full sidebar with labels. */
    private fun setExpanded(value: Boolean) {
        if (value == expanded) return
        expanded = value
        activity.findViewById<View>(R.id.rail_brand).visibility = if (value) View.VISIBLE else View.GONE
        items.forEach { it.findViewById<View>(R.id.rail_label).visibility = if (value) View.VISIBLE else View.GONE }
        val target = (activity.resources.displayMetrics.density * if (value) EXPANDED_DP else COLLAPSED_DP).toInt()
        animator?.cancel()
        animator = ValueAnimator.ofInt(view.width, target).apply {
            duration = 180
            addUpdateListener { view.layoutParams = view.layoutParams.apply { width = it.animatedValue as Int } }
            start()
        }
    }

    companion object {
        const val COLLAPSED_DP = 80
        const val EXPANDED_DP = 232
    }
}
