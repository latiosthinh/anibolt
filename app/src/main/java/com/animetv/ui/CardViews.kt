package com.animetv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.animetv.R
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Keeps the height a fixed ratio of the width, like qfilm's padding-top boxes
 * (pt-[120%] posters, pt-[56.25%] wide cards). Clips children to the rounded background.
 */
class RatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var ratio: Float

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.RatioFrameLayout)
        ratio = a.getFloat(R.styleable.RatioFrameLayout_heightRatio, 1.2f)
        a.recycle()
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * ratio).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}

/** Scales to fill and anchors to the top edge (qfilm's object-top), keeping faces in wide crops. */
class TopCrop : BitmapTransformation() {
    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        if (toTransform.width == outWidth && toTransform.height == outHeight) return toTransform
        val scale = maxOf(outWidth.toFloat() / toTransform.width, outHeight.toFloat() / toTransform.height)
        val dx = (outWidth - toTransform.width * scale) / 2f
        val result = pool.get(outWidth, outHeight, toTransform.config ?: Bitmap.Config.ARGB_8888)
        result.setHasAlpha(toTransform.hasAlpha())
        val matrix = Matrix().apply { setScale(scale, scale); postTranslate(dx, 0f) }
        Canvas(result).drawBitmap(toTransform, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return result
    }

    override fun equals(other: Any?) = other is TopCrop
    override fun hashCode() = ID.hashCode()
    override fun updateDiskCacheKey(messageDigest: MessageDigest) = messageDigest.update(ID_BYTES)

    private companion object {
        const val ID = "com.animetv.ui.TopCrop"
        val ID_BYTES = ID.toByteArray(Charsets.UTF_8)
    }
}
