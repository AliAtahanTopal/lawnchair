package app.lawnchair.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import app.lawnchair.launcherNullable
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.views.overlay.FullScreenOverlayMode
import com.android.launcher3.Utilities
import com.android.launcher3.views.FloatingIconView
import com.android.systemui.shared.system.QuickStepContract
import kotlin.math.roundToInt

class FullScreenOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var endX = 0f
    private var endY = 0f
    private val targetIconRect = RectF()
    private val startColor = ColorTokens.ColorBackground.resolveColor(context)
    private val endColor = Color.TRANSPARENT

    private var cornerRadius = 0f
    private val currentOutlineRect = Rect()

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setBackgroundColor(startColor)
        clipToOutline = true
    }

    fun pointyEndView(view: View) {
        val launcher = context.launcherNullable
        if (launcher != null) {
            val iconBounds = RectF()
            FloatingIconView.getLocationBoundsForView(launcher, view, false, iconBounds, Rect())
            val dragLayerPos = IntArray(2)
            launcher.dragLayer.getLocationOnScreen(dragLayerPos)
            iconBounds.offset(dragLayerPos[0].toFloat(), dragLayerPos[1].toFloat())
            targetIconRect.set(iconBounds)
            endX = iconBounds.centerX()
            endY = iconBounds.centerY()
        } else {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            targetIconRect.set(
                location[0].toFloat(),
                location[1].toFloat(),
                (location[0] + view.width).toFloat(),
                (location[1] + view.height).toFloat(),
            )
            endX = targetIconRect.centerX()
            endY = targetIconRect.centerY()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (currentOutlineRect.isEmpty) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                } else {
                    outline.setRoundRect(currentOutlineRect, cornerRadius)
                }
            }
        }
    }

    fun suckAnimation(duration: Long = 380, onEnd: (() -> Unit)? = null) {
        post {
            if (!isAttachedToWindow) return@post

            val w = width.coerceAtLeast(1).toFloat()
            val h = height.coerceAtLeast(1).toFloat()

            val viewPos = IntArray(2)
            getLocationOnScreen(viewPos)

            val iconRect = RectF(targetIconRect)
            if (iconRect.isEmpty) {
                val defaultSize = Utilities.dpToPx(56f).toFloat()
                val cx = if (endX != 0f) endX - viewPos[0] else w / 2f
                val cy = if (endY != 0f) endY - viewPos[1] else h / 2f
                iconRect.set(
                    cx - defaultSize / 2f,
                    cy - defaultSize / 2f,
                    cx + defaultSize / 2f,
                    cy + defaultSize / 2f,
                )
            } else {
                iconRect.offset(-viewPos[0].toFloat(), -viewPos[1].toFloat())
            }

            val startCornerRadius = QuickStepContract.getWindowCornerRadius(context)
                .coerceAtLeast(Utilities.dpToPx(24f).toFloat())
            val endIconRadius = iconRect.width().coerceAtMost(iconRect.height()) / 2f

            // Shrink uniformly in the phone screen's scale/aspect ratio down to the icon width,
            // while smoothly cropping the vertical bounds into the icon's exact bounds at the end
            val endScale = (iconRect.width() / w).coerceIn(0.05f, 1f)
            val startCenterX = w / 2f
            val startCenterY = h / 2f
            val targetCenterX = iconRect.centerX()
            val targetCenterY = iconRect.centerY()

            val morphInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = morphInterpolator
                addUpdateListener { va ->
                    val p = va.animatedValue as Float
                    val currentScale = Utilities.mapRange(p, 1f, endScale)
                    scaleX = currentScale
                    scaleY = currentScale

                    translationX = Utilities.mapRange(p, 0f, targetCenterX - startCenterX)
                    translationY = Utilities.mapRange(p, 0f, targetCenterY - startCenterY)

                    // Keep the full phone screen aspect ratio for the main shrink, then smoothly
                    // round into the exact icon square in the final third of the animation
                    val cropProgress = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    val unscaledIconHeight = (iconRect.height() / currentScale).coerceAtMost(h)
                    val currentUnscaledHeight = Utilities.mapRange(cropProgress, h, unscaledIconHeight)
                    val topCrop = ((h - currentUnscaledHeight) / 2f).roundToInt()
                    currentOutlineRect.set(0, topCrop, w.roundToInt(), h.roundToInt() - topCrop)

                    val unscaledEndRadius = endIconRadius / currentScale
                    cornerRadius = Utilities.mapRange(p, startCornerRadius, unscaledEndRadius)
                    invalidateOutline()

                    // Smoothly cross-fade into the app icon as the window lands on it
                    alpha = if (p < 0.35f) {
                        1f
                    } else {
                        1f - ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        (parent as? ViewGroup)?.removeView(this@FullScreenOverlayView)
                        onEnd?.invoke()
                    }
                })
            }
            anim.start()
        }
    }

    fun animateIn(duration: Long = 400, onEnd: (() -> Unit)? = null) {
        post {
            if (!isAttachedToWindow) return@post

            val colorAnimator =
                ValueAnimator.ofObject(ArgbEvaluator(), startColor, startColor).apply {
                    this.duration = duration
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animation ->
                        setBackgroundColor(animation.animatedValue as Int)
                    }
                }

            val fadeAnimator = ObjectAnimator.ofFloat(this, ALPHA, 0f, 1f).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
            }

            AnimatorSet().apply {
                playTogether(colorAnimator, fadeAnimator)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onEnd?.invoke()
                    }
                })
                start()
            }
        }
    }

    fun animateOut(duration: Long = 1200, onEnd: (() -> Unit)? = null) {
        post {
            if (!isAttachedToWindow) return@post

            val colorAnimator =
                ValueAnimator.ofObject(ArgbEvaluator(), startColor, endColor).apply {
                    this.duration = duration
                    interpolator = AccelerateInterpolator()
                    addUpdateListener { animation ->
                        setBackgroundColor(animation.animatedValue as Int)
                    }
                }

            val fadeAnimator = ObjectAnimator.ofFloat(this, ALPHA, 1f, 0f).apply {
                this.duration = duration
                interpolator = AccelerateInterpolator()
            }

            AnimatorSet().apply {
                playTogether(colorAnimator, fadeAnimator)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = INVISIBLE
                        (parent as? ViewGroup)?.removeView(this@FullScreenOverlayView)
                        onEnd?.invoke()
                    }
                })
                start()
            }
        }
    }
}

fun Activity.showFullScreenOverlay(
    durationIn: Long = 200,
    durationOut: Long = 380,
    rootView: ViewGroup? = null,
    endView: View,
    onOverlayReady: () -> Unit,
) {
    val pref2 = PreferenceManager2.getInstance(this)
    val animationMode = pref2.closingAppOverlay.firstCached()
    val overlayView = FullScreenOverlayView(this)
    val targetRootView = rootView ?: window.decorView.findViewById<ViewGroup>(android.R.id.content)

    overlayView.pointyEndView(endView)
    targetRootView?.addView(overlayView)

    when (animationMode) {
        FullScreenOverlayMode.FADE_IN -> {
            overlayView.animateIn(durationIn) {
                overlayView.animateOut(durationOut, onOverlayReady)
            }
        }

        FullScreenOverlayMode.SUCK_IN -> {
            overlayView.suckAnimation(durationOut) {
                onOverlayReady()
            }
        }

        FullScreenOverlayMode.NONE -> {
            targetRootView?.removeView(overlayView)
            onOverlayReady()
        }
    }
}
