package app.lawnchair.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import app.lawnchair.LawnchairLauncher
import app.lawnchair.launcher
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.GestureNavContract
import com.android.launcher3.Insettable
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.util.Executors
import com.android.launcher3.util.window.RefreshRateTracker.Companion.getSingleFrameMs
import com.android.launcher3.views.FloatingIconView.getLocationBoundsForView
import com.android.launcher3.views.FloatingIconViewCompanion.setPropertiesVisible
import kotlin.math.roundToInt

/**
 * Similar to [com.android.launcher3.views.FloatingSurfaceView] but tailored for [LawnchairLauncher].
 * Displays a surface with the target icon and passes its position and [android.view.SurfaceControl]
 * handle to the [GestureNavContract] so the system Recents provider (e.g., Samsung One UI Home)
 * can natively animate the closing app window directly into its icon on the launcher.
 */
class LawnchairFloatingSurfaceView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractFloatingView(context, attrs, defStyleAttr),
    OnGlobalLayoutListener,
    Insettable,
    SurfaceHolder.Callback2 {
    private val mTmpPosition = RectF()

    private val mLauncher: LawnchairLauncher = context!!.launcher
    private val mIconPosition = RectF()
    private val mScreenPosition = RectF()
    private val mDragLayerLocation = IntArray(2)

    private val mIconBounds: Rect = Rect()
    private val mPicture = Picture()
    private var mPictureRecorded = false
    private val mRemoveViewRunnable = Runnable { this.removeViewFromParent() }
    private val mTimeoutRunnable = Runnable {
        close(false)
        removeViewImmediate()
    }

    private val mSurfaceView: SurfaceView = SurfaceView(context)

    private var mIcon: View? = null
    private var mContract: GestureNavContract? = null

    init {
        mSurfaceView.setZOrderOnTop(true)

        mSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        mSurfaceView.holder.addCallback(this)
        mSurfaceView.visibility = View.INVISIBLE

        mIsOpen = true
        addView(mSurfaceView)
    }

    fun reset() {
        removeCallbacks(mTimeoutRunnable)
        setCurrentIconVisible(true)
        mContract = null
        mIcon = null
        mIsOpen = false
        mPictureRecorded = false
        mIconPosition.setEmpty()
        mTmpPosition.setEmpty()
        mIconBounds.setEmpty()
        mSurfaceView.visibility = View.INVISIBLE
        mPicture.beginRecording(1, 1)
        mPicture.endRecording()
    }

    override fun handleClose(animate: Boolean) {
        removeCallbacks(mTimeoutRunnable)
        mSurfaceView.visibility = View.INVISIBLE
        setCurrentIconVisible(true)
        mLauncher.viewCache.recycleView(R.layout.floating_surface_view, this)
        mContract = null
        mIcon = null
        mIsOpen = false
        mPictureRecorded = false
        mIconPosition.setEmpty()
        mTmpPosition.setEmpty()
        mIconBounds.setEmpty()
        mPicture.beginRecording(1, 1)
        mPicture.endRecording()

        // Remove after some time, to avoid flickering
        Executors.MAIN_EXECUTOR.handler.postDelayed(
            mRemoveViewRunnable,
            mLauncher.getSingleFrameMs().toLong(),
        )
    }

    private fun removeViewFromParent() {
        removeCallbacks(mTimeoutRunnable)
        mSurfaceView.visibility = View.INVISIBLE
        mPicture.beginRecording(1, 1)
        mPicture.endRecording()
        mLauncher.dragLayer.removeViewInLayout(this)
    }

    private fun removeViewImmediate() {
        // Cancel any pending remove
        Executors.MAIN_EXECUTOR.handler.removeCallbacks(mRemoveViewRunnable)
        removeCallbacks(mTimeoutRunnable)
        if (isAttachedToWindow) {
            removeViewFromParent()
        }
    }

    override fun isOfType(type: Int): Boolean {
        return (type and TYPE_ICON_SURFACE) != 0
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent?): Boolean {
        close(false)
        removeViewImmediate()
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        getViewTreeObserver().addOnGlobalLayoutListener(this)
        updateIconLocation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(mTimeoutRunnable)
        getViewTreeObserver().removeOnGlobalLayoutListener(this)
        setCurrentIconVisible(true)
    }

    override fun onGlobalLayout() {
        updateIconLocation()
    }

    fun getIcon(): View? {
        val contract = mContract ?: return null
        val pkg = contract.componentName.packageName
        val user = contract.user

        // Check if the exact view that launched the app is still attached and visible
        val launchedView = mLauncher.lastLaunchedViewRef?.get()
        if (launchedView != null && launchedView.isAttachedToWindow && launchedView.isAggregatedVisible) {
            if (launchedView is app.lawnchair.widget.ActivityShortcutWidgetView ||
                launchedView is app.lawnchair.widget.ActivityPillWidgetView
            ) {
                return launchedView
            }
            val info = launchedView.tag as? com.android.launcher3.model.data.ItemInfo
            if (info != null && info.user == user && info.targetPackage == pkg) {
                return launchedView
            }
        }

        return mLauncher.getFirstVisibleElementForAppClose(
            mLauncher.lastLaunchedStableViewInfo,
            pkg,
            user,
        ) ?: findActivityWidgetForPackage(pkg)
    }

    private fun findActivityWidgetForPackage(packageName: String): View? {
        val result = arrayOfNulls<View>(1)
        mLauncher.workspace?.forEachVisiblePage { page ->
            if (result[0] != null) return@forEachVisiblePage
            val cellLayout = page as? com.android.launcher3.CellLayout ?: return@forEachVisiblePage
            val container = cellLayout.shortcutsAndWidgets
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is app.lawnchair.LawnchairAppWidgetHostView && child.childCount > 0) {
                    val inner = child.getChildAt(0)
                    if (inner is app.lawnchair.widget.ActivityShortcutWidgetView ||
                        inner is app.lawnchair.widget.ActivityPillWidgetView
                    ) {
                        val cfg = app.lawnchair.widget.ActivityShortcutWidgetProvider.loadConfig(
                            mLauncher,
                            child.appWidgetId,
                        )
                        if (cfg?.componentName?.packageName == packageName) {
                            result[0] = inner
                            break
                        }
                    }
                }
            }
        }
        return result[0]
    }

    override fun setInsets(insets: Rect?) {}

    private fun isValidIconPosition(pos: RectF, icon: View): Boolean {
        if (!icon.isAttachedToWindow || !icon.isLaidOut || icon.width <= 0 || icon.height <= 0) {
            return false
        }
        if (pos.isEmpty || pos.width() <= 0f || pos.height() <= 0f) {
            return false
        }
        // Launcher app icons are never placed at (0, 0) due to insets/margins.
        // A (0, 0) position indicates unmeasured or unpositioned ancestor view.
        if (pos.left <= 0f && pos.top <= 0f) {
            return false
        }
        return true
    }

    private fun updateIconLocation() {
        val contract = mContract ?: return

        val icon = getIcon()
        if (icon == null) {
            // No matching icon on home screen, dismiss immediately to prevent floating artifacts
            sendIconInfo()
            post {
                close(false)
                removeViewImmediate()
            }
            return
        }

        val iconChanged = mIcon !== icon
        if (iconChanged) {
            setCurrentIconVisible(true)
            mIcon = icon
            mPictureRecorded = false
            setCurrentIconVisible(false)
        }

        var positionValid = false
        if (icon.isAttachedToWindow) {
            getLocationBoundsForView(mLauncher, icon, false, mTmpPosition, mIconBounds)
            if (isValidIconPosition(mTmpPosition, icon)) {
                positionValid = true
                if (mTmpPosition != mIconPosition) {
                    mIconPosition.set(mTmpPosition)

                    val lp = mSurfaceView.layoutParams as LayoutParams
                    lp.width = mIconPosition.width().roundToInt()
                    lp.height = mIconPosition.height().roundToInt()
                    lp.leftMargin = mIconPosition.left.roundToInt()
                    lp.topMargin = mIconPosition.top.roundToInt()
                    mSurfaceView.requestLayout()
                }
                mSurfaceView.visibility = View.VISIBLE
            } else {
                mSurfaceView.visibility = View.INVISIBLE
            }
        }

        if (positionValid) {
            sendIconInfo()

            if (!mPictureRecorded && !mIconBounds.isEmpty && mIconBounds.width() > 0 && mIconBounds.height() > 0) {
                setCurrentIconVisible(true)
                val c = mPicture.beginRecording(mIconBounds.width(), mIconBounds.height())
                c.translate(-mIconBounds.left.toFloat(), -mIconBounds.top.toFloat())
                mIcon?.draw(c)
                mPicture.endRecording()
                mPictureRecorded = true
                setCurrentIconVisible(false)
                drawOnSurface()
            }
        }
    }

    private fun sendIconInfo() {
        val contract = mContract ?: return
        mLauncher.dragLayer.getLocationOnScreen(mDragLayerLocation)
        mScreenPosition.set(mIconPosition)
        if (!mScreenPosition.isEmpty) {
            mScreenPosition.offset(
                mDragLayerLocation[0].toFloat(),
                mDragLayerLocation[1].toFloat(),
            )
        }
        if (Utilities.ATLEAST_Q) {
            contract.sendEndPosition(mScreenPosition, mLauncher, mSurfaceView.surfaceControl)
        } else {
            contract.sendEndPosition(mScreenPosition, mLauncher, null)
        }
    }

    override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
        drawOnSurface()
        sendIconInfo()
    }

    override fun surfaceChanged(
        surfaceHolder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        drawOnSurface()
    }

    override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {}

    override fun surfaceRedrawNeeded(surfaceHolder: SurfaceHolder) {
        drawOnSurface()
    }

    private fun drawOnSurface() {
        if (mSurfaceView.visibility != View.VISIBLE || !mPictureRecorded) return
        val surfaceHolder = mSurfaceView.holder
        if (!surfaceHolder.surface.isValid) return

        val c = surfaceHolder.lockHardwareCanvas()
        if (c != null) {
            try {
                mPicture.draw(c)
            } finally {
                surfaceHolder.unlockCanvasAndPost(c)
            }
        }
    }

    private fun setCurrentIconVisible(isVisible: Boolean) {
        if (mIcon != null) {
            setPropertiesVisible(mIcon, isVisible)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 1000L

        /**
         * Shows the surfaceView for the provided contract
         */
        @JvmStatic
        fun show(launcher: LawnchairLauncher, contract: GestureNavContract?) {
            val view: LawnchairFloatingSurfaceView =
                launcher.viewCache.getView(
                    R.layout.floating_surface_view,
                    launcher,
                    launcher.dragLayer,
                )
            view.reset()
            view.mContract = contract
            view.mIsOpen = true

            view.removeViewImmediate()
            launcher.dragLayer.addView(view)

            view.removeCallbacks(view.mTimeoutRunnable)
            view.postDelayed(view.mTimeoutRunnable, TIMEOUT_MS)
        }
    }
}
