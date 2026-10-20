package app.lawnchair.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Process
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.lawnchair.gestures.handlers.OpenAppGestureHandler
import app.lawnchair.launcherNullable
import com.android.launcher3.CellLayout
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import com.android.launcher3.util.Themes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ActivityShortcutWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var iconView: ImageView? = null
    private var labelView: TextView? = null
    private var boundWidgetId: Int = -1

    override fun onFinishInflate() {
        super.onFinishInflate()
        iconView = findViewById(R.id.activity_widget_icon)
        labelView = findViewById(R.id.activity_widget_label)

        val isDarkText = Themes.getAttrBoolean(context, R.attr.isWorkspaceDarkText)
        labelView?.apply {
            setTextColor(if (isDarkText) 0xDE000000.toInt() else 0xFFFFFFFF.toInt())
            if (!isDarkText) {
                setShadowLayer(4f, 0f, 1f, 0x80000000.toInt())
            } else {
                setShadowLayer(0f, 0f, 0f, 0)
            }
        }

        setOnClickListener {
            handleTap()
        }

        val forwardLongClick = OnLongClickListener {
            var p = parent
            while (p != null) {
                if (p is com.android.launcher3.widget.LauncherAppWidgetHostView) {
                    return@OnLongClickListener p.performLongClick()
                }
                p = p.parent
            }
            performLongClick()
        }
        setOnLongClickListener(forwardLongClick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reloadFromConfig()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateIconAndTextSizing(w, h)
    }

    private fun findAppWidgetId(): Int {
        var p = parent
        while (p != null) {
            if (p is AppWidgetHostView) {
                return p.appWidgetId
            }
            p = p.parent
        }
        return -1
    }

    fun reloadFromConfig() {
        val widgetId = findAppWidgetId()
        boundWidgetId = widgetId
        if (widgetId <= 0) {
            // Preview mode in widget picker
            iconView?.setImageResource(R.mipmap.ic_launcher_home)
            labelView?.setText(R.string.activity_shortcut_widget_label)
            labelView?.visibility = View.VISIBLE
            updateIconAndTextSizing(width, height)
            return
        }

        val config = ActivityShortcutWidgetProvider.loadConfig(context, widgetId) ?: return
        val displayTitle = config.displayLabel
        labelView?.text = displayTitle
        labelView?.visibility = if (config.showLabel && displayTitle.isNotBlank()) View.VISIBLE else View.GONE
        contentDescription = displayTitle

        MODEL_EXECUTOR.execute {
            val bitmap = ActivityShortcutWidgetProvider.loadShapedWidgetIconBitmap(context, widgetId, config)
            post {
                iconView?.setImageBitmap(bitmap)
                updateIconAndTextSizing(width, height)
            }
        }
    }

    private fun updateIconAndTextSizing(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val icon = iconView ?: return
        val label = labelView ?: return

        val launcher = context.launcherNullable
        val dp = launcher?.deviceProfile

        val parentHost = parent as? View
        val tagInfo = parentHost?.tag as? ItemInfo
        val spanX = tagInfo?.spanX ?: 1
        val spanY = tagInfo?.spanY ?: 1

        val showLabel = label.visibility == View.VISIBLE
        if (showLabel && dp != null) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.iconTextSizePx.toFloat())
        }

        val drawablePadding = dp?.iconDrawablePaddingPx ?: dpToPx(2f)
        val labelLp = label.layoutParams as? ViewGroup.MarginLayoutParams
        if (labelLp != null && labelLp.topMargin != drawablePadding) {
            labelLp.topMargin = drawablePadding
            label.layoutParams = labelLp
        }

        val fm = label.paint.fontMetrics
        val singleLineTextHeight = kotlin.math.ceil((fm.bottom - fm.top).toDouble()).toInt()
        val labelBlockHeight = if (showLabel) {
            drawablePadding + singleLineTextHeight
        } else {
            0
        }

        val isSingleCell = spanX == 1 && spanY == 1

        if (isSingleCell && dp != null) {
            // Match standard workspace BubbleTextView padding and icon size identically
            val noPaddingX = dp.cellLayoutBorderSpacePx.x > 0
            val cellPaddingX = if (noPaddingX) 0 else dp.workspaceCellPaddingXPx

            val topPad = if (!showLabel) {
                max(0, (h - dp.iconSizePx) / 2)
            } else if (dp.iconCenterVertically) {
                val cellContentH = dp.iconSizePx + labelBlockHeight
                max(0, (h - cellContentH) / 2)
            } else if (dp.cellYPaddingPx >= 0) {
                dp.cellYPaddingPx
            } else {
                val cHeight = min(h, dp.getCellContentHeight(CellLayout.WORKSPACE))
                max(0, (h - cHeight) / 2)
            }

            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (paddingLeft != cellPaddingX || paddingTop != topPad || paddingRight != cellPaddingX || paddingBottom != 0) {
                setPadding(cellPaddingX, topPad, cellPaddingX, 0)
            }

            val availableW = (w - cellPaddingX * 2).coerceAtLeast(1)
            val availableH = (h - topPad - labelBlockHeight).coerceAtLeast(1)
            val targetIconSize = min(dp.iconSizePx, min(availableW, availableH)).coerceAtLeast(1)

            val lp = icon.layoutParams
            if (lp != null && (lp.width != targetIconSize || lp.height != targetIconSize)) {
                lp.width = targetIconSize
                lp.height = targetIconSize
                icon.layoutParams = lp
            }
        } else {
            // Resized widget (e.g., 2x1, 1x2, 2x2, 3x3) or widget preview: center and fill the cell cleanly!
            gravity = Gravity.CENTER
            if (paddingLeft != 0 || paddingTop != 0 || paddingRight != 0 || paddingBottom != 0) {
                setPadding(0, 0, 0, 0)
            }

            val availableW = w.coerceAtLeast(1)
            val availableH = (h - labelBlockHeight).coerceAtLeast(1)
            val maxSide = min(availableW, availableH)
            val targetIconSize = if (boundWidgetId <= 0 && dp != null) {
                min(dp.iconSizePx, maxSide)
            } else {
                maxSide
            }

            val lp = icon.layoutParams
            if (lp != null && (lp.width != targetIconSize || lp.height != targetIconSize)) {
                lp.width = targetIconSize
                lp.height = targetIconSize
                icon.layoutParams = lp
            }
        }
    }

    private fun handleTap() {
        val widgetId = findAppWidgetId().takeIf { it > 0 } ?: boundWidgetId
        if (widgetId <= 0) return
        val config = ActivityShortcutWidgetProvider.loadConfig(context, widgetId) ?: return
        val cn = config.componentName ?: return

        val launcher = context.launcherNullable
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            component = cn
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        val hostView = (parent as? View) ?: this
        val itemInfo = hostView.tag as? ItemInfo

        if (launcher != null) {
            try {
                val options = launcher.getActivityLaunchOptions(this, itemInfo).toBundle()
                launcher.startActivity(launchIntent, options)
                return
            } catch (_: Exception) {
            }
        }
        OpenAppGestureHandler.launchComponent(context, ComponentKey(cn, Process.myUserHandle()))
    }

    fun getIconBoundsRelativeTo(targetView: View, outRect: Rect) {
        val icon = iconView
        if (icon != null && icon.width > 0 && icon.height > 0) {
            val locIcon = IntArray(2)
            val locTarget = IntArray(2)
            icon.getLocationInWindow(locIcon)
            targetView.getLocationInWindow(locTarget)
            val left = locIcon[0] - locTarget[0]
            val top = locIcon[1] - locTarget[1]
            outRect.set(left, top, left + icon.width, top + icon.height)
        } else {
            outRect.set(0, 0, targetView.width, targetView.height)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics,
        ).roundToInt()
    }
}
