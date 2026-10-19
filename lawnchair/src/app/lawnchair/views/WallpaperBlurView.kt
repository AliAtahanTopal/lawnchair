package app.lawnchair.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import app.lawnchair.util.WallpaperBlurHelper
import com.android.launcher3.Launcher
import com.android.launcher3.util.Executors.MAIN_EXECUTOR

class WallpaperBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    private val blurListener = {
        MAIN_EXECUTOR.execute {
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
        WallpaperBlurHelper.init(context)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WallpaperBlurHelper.addListener(blurListener)
        if (WallpaperBlurHelper.cachedBlurredBitmap == null) {
            WallpaperBlurHelper.updateWallpaperBlur(context, force = true)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        WallpaperBlurHelper.removeListener(blurListener)
    }

    override fun onDraw(canvas: Canvas) {
        if (alpha <= 0.001f) {
            return
        }
        val bitmap = WallpaperBlurHelper.cachedBlurredBitmap ?: return
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
            return
        }

        // Calculate scroll offset if parallax is active
        var scrollRatio = 0f
        try {
            val launcher = Launcher.getLauncher(context)
            if (launcher != null) {
                val workspace = launcher.workspace
                if (workspace != null) {
                    val interpolator = workspace.wallpaperOffset
                    if (interpolator != null) {
                        scrollRatio = interpolator.wallpaperOffsetForScroll(workspace.scrollX).coerceIn(0f, 1f)
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        val viewAspect = width.toFloat() / height.toFloat()
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

        if (bitmapAspect > viewAspect) {
            // Bitmap is wider than view: use scrollRatio to crop horizontally
            val targetCropWidth = (bitmap.height * viewAspect).toInt().coerceAtMost(bitmap.width)
            val maxCropX = (bitmap.width - targetCropWidth).coerceAtLeast(0)
            val cropX = (maxCropX * scrollRatio).toInt().coerceIn(0, maxCropX)
            srcRect.set(cropX, 0, cropX + targetCropWidth, bitmap.height)
        } else {
            srcRect.set(0, 0, bitmap.width, bitmap.height)
        }

        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }
}
