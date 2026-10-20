package app.lawnchair.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Process
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.gestures.handlers.OpenAppGestureHandler
import app.lawnchair.launcherNullable
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import kotlin.math.min
import kotlin.math.roundToInt

class ActivityPillWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var pillContainer: LinearLayout? = null
    private var iconView: ImageView? = null
    private var labelView: TextView? = null
    private var boundWidgetId: Int = -1
    private var currentBackgroundAlpha: Float = 0.42f
    private var currentBackgroundColorOption: String = "default"
    private var currentMatchSearchBarStyle: Boolean = false
    private var pendingAppearanceUpdate: Boolean = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        pillContainer = findViewById(R.id.activity_pill_container)
        iconView = findViewById(R.id.activity_pill_icon)
        labelView = findViewById(R.id.activity_pill_label)

        setOnClickListener {
            handleTap()
        }
        pillContainer?.setOnClickListener {
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
        pillContainer?.setOnLongClickListener(forwardLongClick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activeViews.add(this)
        reloadFromConfig()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activeViews.remove(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePillAppearanceAndSizing(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (pendingAppearanceUpdate && width > 0 && height > 0) {
            pendingAppearanceUpdate = false
            updatePillAppearanceAndSizing(width, height)
        }
    }

    fun findAppWidgetId(): Int {
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
        val widgetId = findAppWidgetId().takeIf { it > 0 } ?: boundWidgetId
        boundWidgetId = widgetId
        if (widgetId <= 0) {
            // Preview mode in widget picker: show One UI Finder pill preview
            currentBackgroundAlpha = 0.42f
            currentBackgroundColorOption = "default"
            currentMatchSearchBarStyle = false
            iconView?.setImageResource(R.drawable.ic_finder_gradient)
            labelView?.setText(R.string.activity_pill_widget_default_title)
            labelView?.visibility = View.VISIBLE
            updatePillAppearanceAndSizing(width, height)
            return
        }

        val config = ActivityShortcutWidgetProvider.loadConfig(context, widgetId) ?: return
        currentBackgroundAlpha = config.backgroundAlpha.coerceIn(0f, 1f)
        currentBackgroundColorOption = config.backgroundColorOption
        currentMatchSearchBarStyle = config.matchSearchBarStyle

        val displayTitle = config.displayLabel
        labelView?.text = displayTitle
        labelView?.visibility = if (config.showLabel && displayTitle.isNotBlank()) View.VISIBLE else View.GONE
        contentDescription = displayTitle

        // Apply visual updates immediately on the main thread
        updatePillAppearanceAndSizing(width, height)

        MODEL_EXECUTOR.execute {
            val hasCustomIcon = IconOverrideRepository.INSTANCE.get(context)
                .overridesMap[config.getIconOverrideKey(widgetId)] != null
            if (config.useFinderIcon && !hasCustomIcon) {
                post {
                    iconView?.setImageResource(R.drawable.ic_finder_gradient)
                    updatePillAppearanceAndSizing(width, height)
                }
            } else {
                val bitmap = ActivityShortcutWidgetProvider.loadShapedWidgetIconBitmap(context, widgetId, config)
                post {
                    iconView?.setImageBitmap(bitmap)
                    updatePillAppearanceAndSizing(width, height)
                }
            }
        }
    }

    private data class PillStyle(
        val baseColor: Int,
        val alpha: Float,
        val strokeWidth: Int,
        val strokeColor: Int,
    )

    private fun updatePillAppearanceAndSizing(w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            pendingAppearanceUpdate = true
            return
        }
        val container = pillContainer ?: return
        val icon = iconView ?: return
        val label = labelView ?: return

        // Outer margins so the pill sits comfortably inside the workspace grid cells like One UI Finder
        val hInset = min(dpToPx(6f), (w * 0.04f).roundToInt())
        val vInset = min(dpToPx(8f), (h * 0.10f).roundToInt())
        val availW = (w - hInset * 2).coerceAtLeast(dpToPx(40f))
        val availH = (h - vInset * 2).coerceAtLeast(dpToPx(32f))

        // Sizing: matches standard search bar pill height (52dp)
        val targetPillH = dpToPx(52f)
        val pillH = availH.coerceIn(dpToPx(36f), min(availH, targetPillH))
        val pillW = availW

        val containerLp = container.layoutParams as? LayoutParams
        if (containerLp != null && (containerLp.width != pillW || containerLp.height != pillH || containerLp.gravity != Gravity.CENTER)) {
            containerLp.width = pillW
            containerLp.height = pillH
            containerLp.gravity = Gravity.CENTER
            container.layoutParams = containerLp
        }

        val showLabel = label.visibility == View.VISIBLE
        val iconSize = (pillH * 0.50f).roundToInt().coerceIn(dpToPx(20f), dpToPx(32f))
        val iconLp = icon.layoutParams as? LinearLayout.LayoutParams
        if (iconLp != null && (iconLp.width != iconSize || iconLp.height != iconSize)) {
            iconLp.width = iconSize
            iconLp.height = iconSize
            icon.layoutParams = iconLp
        }

        if (showLabel) {
            val hPad = (pillH * 0.30f).roundToInt().coerceAtLeast(dpToPx(12f))
            container.gravity = Gravity.CENTER
            container.setPaddingRelative(hPad, 0, hPad, 0)

            val labelLp = label.layoutParams as? LinearLayout.LayoutParams
            val labelGap = (pillH * 0.20f).roundToInt().coerceIn(dpToPx(8f), dpToPx(14f))
            if (labelLp != null && labelLp.marginStart != labelGap) {
                labelLp.marginStart = labelGap
                label.layoutParams = labelLp
            }

            val textPx = (pillH * 0.31f).coerceIn(dpToPx(13f).toFloat(), dpToPx(18f).toFloat())
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
            label.gravity = Gravity.CENTER
        } else {
            container.gravity = Gravity.CENTER
            container.setPaddingRelative(0, 0, 0, 0)
        }

        val prefs = app.lawnchair.preferences.PreferenceManager.getInstance(context)
        val prefs2 = app.lawnchair.preferences2.PreferenceManager2.getInstance(context)

        val style = if (currentMatchSearchBarStyle) {
            val themed = try {
                prefs2.themedHotseatQsb.firstCached()
            } catch (_: Exception) {
                true
            }
            val bg = app.lawnchair.qsb.getHotseatBackgroundColor(context, themed)
            val alpha = try {
                (prefs.hotseatQsbAlpha.get() / 100f).coerceIn(0f, 1f)
            } catch (_: Exception) {
                1f
            }
            val sWidth = try {
                dpToPx(prefs.hotseatQsbStrokeWidth.get())
            } catch (_: Exception) {
                0
            }
            val sColor = try {
                val strokeColorOption = prefs2.strokeColorStyle.firstCached()
                strokeColorOption.colorPreferenceEntry.lightColor.invoke(context)
            } catch (_: Exception) {
                com.android.launcher3.util.Themes.getColorAccent(context)
            }
            PillStyle(bg, alpha, sWidth, sColor)
        } else {
            val bg = resolveBaseColor(context, currentBackgroundColorOption)
            val alpha = currentBackgroundAlpha.coerceIn(0f, 1f)
            val isDarkBg = ColorUtils.calculateLuminance(bg) < 0.45
            val sWidth = if (alpha > 0f) dpToPx(1f).coerceAtLeast(1) else 0
            val sColor = if (isDarkBg) {
                ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (alpha * 60f).roundToInt().coerceIn(0, 100))
            } else {
                ColorUtils.setAlphaComponent(0xFF000000.toInt(), (alpha * 40f).roundToInt().coerceIn(0, 80))
            }
            PillStyle(bg, alpha, sWidth, sColor)
        }

        val effectiveColor = ColorUtils.setAlphaComponent(style.baseColor, (style.alpha * 255f).roundToInt().coerceIn(0, 255))
        val pillDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pillH / 2f
            setColor(effectiveColor)
            if (style.strokeWidth > 0) {
                setStroke(style.strokeWidth, style.strokeColor)
            }
        }
        container.background = pillDrawable

        // Label contrast calculation
        val isDarkBg = when {
            style.alpha < 0.20f -> !com.android.launcher3.util.Themes.getAttrBoolean(context, R.attr.isWorkspaceDarkText)
            ColorUtils.calculateLuminance(style.baseColor) < 0.45 -> true
            else -> false
        }
        label.setTextColor(if (isDarkBg) 0xF2FFFFFF.toInt() else 0xD91C1C1E.toInt())
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

    fun getPillBoundsRelativeTo(targetView: View, outRect: Rect) {
        val pill = pillContainer
        if (pill != null && pill.width > 0 && pill.height > 0) {
            val locPill = IntArray(2)
            val locTarget = IntArray(2)
            pill.getLocationInWindow(locPill)
            targetView.getLocationInWindow(locTarget)
            val left = locPill[0] - locTarget[0]
            val top = locPill[1] - locTarget[1]
            outRect.set(left, top, left + pill.width, top + pill.height)
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

    companion object {
        private val activeViews = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<ActivityPillWidgetView, Boolean>()
        )

        fun notifyConfigChanged(widgetId: Int) {
            activeViews.forEach { view ->
                if (view.boundWidgetId == widgetId || view.findAppWidgetId() == widgetId) {
                    view.post { view.reloadFromConfig() }
                }
            }
        }

        fun resolveBaseColor(context: Context, colorOptionString: String): Int {
            if (colorOptionString == "search_bar" || colorOptionString == "search_bar_surface") {
                val themed = try {
                    PreferenceManager2.getInstance(context).themedHotseatQsb.firstCached()
                } catch (_: Exception) {
                    true
                }
                return app.lawnchair.qsb.getHotseatBackgroundColor(context, themed)
            }
            if (colorOptionString.isBlank() || colorOptionString == "default") {
                val isDark = com.android.launcher3.util.Themes.getAttrBoolean(context, R.attr.isMainColorDark)
                return if (isDark) {
                    com.android.launcher3.util.Themes.getColorBackgroundFloating(context)
                } else {
                    0xFFF5F7FA.toInt()
                }
            }
            return try {
                val colorOption = app.lawnchair.theme.color.ColorOption.fromString(colorOptionString)
                val isDark = com.android.launcher3.util.Themes.getAttrBoolean(context, R.attr.isMainColorDark)
                if (isDark) {
                    colorOption.colorPreferenceEntry.darkColor(context)
                } else {
                    colorOption.colorPreferenceEntry.lightColor(context)
                }
            } catch (_: Exception) {
                0xFFF5F7FA.toInt()
            }
        }
    }
}
