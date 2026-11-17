package com.animetv.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.animetv.R

/**
 * AniBolt loading state: the logo breathing above an orange bar sweeping across a zinc track,
 * with an optional title and caption. Used between screens (details, browse results, player start).
 * Animations only run while the view is shown and attached.
 */
class LoadingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val logo = ImageView(context).apply {
        setImageResource(R.drawable.ic_logo)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.tv_text))
        textSize = 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        visibility = GONE
    }
    private val caption = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.tv_muted))
        textSize = 16f
        gravity = Gravity.CENTER
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val bar = SweepBar(context)
    private var pulse: ObjectAnimator? = null
    private var fade: android.view.ViewPropertyAnimator? = null

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(logo, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(title, LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
            addView(bar, LinearLayout.LayoutParams(dp(200), dp(4)).apply { topMargin = dp(20) })
            addView(caption, LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        addView(column, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // Swallow clicks so nothing underneath reacts while loading.
        isClickable = true
    }

    /**
     * Shows the loader at once (no fade-in: it replaces a screen that is not ready yet).
     * With [takeFocus], it holds remote focus so D-pad presses cannot reach the controls hidden underneath.
     * A null [caption] and [title] give the icon-only form (logo + bar); [description] still tells screen readers what is loading.
     */
    fun show(caption: CharSequence?, title: CharSequence? = null, takeFocus: Boolean = false, description: CharSequence? = null) {
        fade?.cancel()
        fade = null
        animate().cancel()
        alpha = 1f
        this.caption.text = caption
        this.caption.visibility = if (caption.isNullOrBlank()) GONE else VISIBLE
        this.title.text = title
        this.title.visibility = if (title.isNullOrBlank()) GONE else VISIBLE
        contentDescription = description
            ?: listOfNotNull(title?.takeIf { it.isNotBlank() }, caption?.takeIf { it.isNotBlank() }).joinToString(". ")
        visibility = VISIBLE
        bringToFront()
        startAnimations()
        if (takeFocus) {
            isFocusable = true
            requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isShowing && isFocused) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Fades out, then stops the animations. */
    fun hide() {
        if (!isShowing) return
        fade = animate().alpha(0f).setDuration(220).withEndAction {
            fade = null
            isFocusable = false
            visibility = GONE
            stopAnimations()
        }.also { it.start() }
    }

    /** True from [show] until [hide] starts fading out. */
    val isShowing get() = visibility == VISIBLE && fade == null

    private fun startAnimations() {
        if (!isAttachedToWindow) return
        if (pulse == null) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                logo,
                PropertyValuesHolder.ofFloat(SCALE_X, 0.9f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0.9f, 1f),
                PropertyValuesHolder.ofFloat(ALPHA, 0.65f, 1f),
            ).apply {
                duration = 700
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        if (pulse?.isStarted != true) pulse?.start()
        bar.start()
    }

    private fun stopAnimations() {
        pulse?.cancel()
        logo.scaleX = 1f; logo.scaleY = 1f; logo.alpha = 1f
        bar.stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startAnimations()
    }

    /** Infinite animators keep ticking behind a covered or backgrounded screen; run them only while it can be seen. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) stopAnimations() else if (isShowing) startAnimations()
    }

    override fun onDetachedFromWindow() {
        fade?.cancel()
        fade = null
        stopAnimations()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Indeterminate bar: an amber-to-orange segment sweeping over a zinc track. */
    private class SweepBar(context: Context) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.tv_surface_high) }
        private val segment = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var progress = 0f
        private var animator: ValueAnimator? = null

        fun start() {
            if (animator?.isStarted == true) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            segment.shader = LinearGradient(0f, 0f, w * 0.4f, 0f,
                ContextCompat.getColor(context, R.color.tv_accent_amber), ContextCompat.getColor(context, R.color.tv_accent), Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, track)
            // Eased travel from fully left of the track to fully right, clipped to the track.
            val eased = (1 - Math.cos(progress * Math.PI).toFloat()) / 2
            val length = w * 0.4f
            val left = -length + (w + length) * eased
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            canvas.translate(left, 0f)
            rect.set(0f, 0f, length, h)
            canvas.drawRoundRect(rect, radius, radius, segment)
            canvas.restore()
        }
    }

    companion object {
        /** Adds a full-screen loader over an activity's content. */
        fun attachTo(activity: Activity, backgroundColor: Int = ContextCompat.getColor(activity, R.color.tv_background)): LoadingView {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            return LoadingView(activity).apply {
                setBackgroundColor(backgroundColor)
                visibility = GONE
                content.addView(this, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }
}
