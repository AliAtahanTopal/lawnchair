package app.lawnchair.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Process
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.gestures.handlers.OpenAppGestureHandler
import app.lawnchair.icons.iconpack.IconPackProvider
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.BuildConfig
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.util.ComponentKey
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ActivityShortcutWidgetConfig(
    val componentNameString: String,
    val customLabel: String = "",
    val defaultLabel: String = "",
    val showLabel: Boolean = true,
    val isPillWidget: Boolean = false,
    val useFinderIcon: Boolean = false,
    val backgroundAlpha: Float = 0.42f,
    val backgroundColorOption: String = "default",
    val matchSearchBarStyle: Boolean = false,
) {
    val componentName: ComponentName?
        get() = ComponentName.unflattenFromString(componentNameString)

    val displayLabel: String
        get() = customLabel.ifEmpty { defaultLabel }

    fun getIconOverrideKey(appWidgetId: Int): ComponentKey {
        return ComponentKey(
            ComponentName(BuildConfig.APPLICATION_ID, "ActivityWidget_$appWidgetId"),
            Process.myUserHandle(),
        )
    }
}

class ActivityPillWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            ActivityShortcutWidgetProvider.deleteConfig(context, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ActivityShortcutWidgetProvider.ACTION_LAUNCH_ACTIVITY) {
            val componentString = intent.getStringExtra(ActivityShortcutWidgetProvider.EXTRA_COMPONENT_NAME) ?: return
            val cn = ComponentName.unflattenFromString(componentString) ?: return
            OpenAppGestureHandler.launchComponent(
                context,
                ComponentKey(cn, Process.myUserHandle()),
            )
        }
    }

    companion object {
        @JvmField
        val componentName = ComponentName(
            BuildConfig.APPLICATION_ID,
            ActivityPillWidgetProvider::class.java.name,
        )

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val config = ActivityShortcutWidgetProvider.loadConfig(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.activity_pill_widget_remote)
            if (config != null) {
                val hasCustomIcon = IconOverrideRepository.INSTANCE.get(context)
                    .overridesMap[config.getIconOverrideKey(appWidgetId)] != null
                if (config.useFinderIcon && !hasCustomIcon) {
                    views.setImageViewResource(R.id.activity_pill_icon, R.drawable.ic_finder_gradient)
                } else {
                    val bitmap = ActivityShortcutWidgetProvider.loadShapedWidgetIconBitmap(context, appWidgetId, config)
                    views.setImageViewBitmap(R.id.activity_pill_icon, bitmap)
                }
                views.setTextViewText(R.id.activity_pill_label, config.displayLabel)
                views.setViewVisibility(
                    R.id.activity_pill_label,
                    if (config.showLabel && config.displayLabel.isNotBlank()) View.VISIBLE else View.GONE,
                )

                val bgBitmap = createPillBitmap(context, config)
                views.setImageViewBitmap(R.id.activity_pill_bg, bgBitmap)

                val isDarkBg = if (config.matchSearchBarStyle) {
                    true
                } else {
                    val rawColor = ActivityPillWidgetView.resolveBaseColor(context, config.backgroundColorOption)
                    if (config.backgroundAlpha < 0.20f) {
                        !com.android.launcher3.util.Themes.getAttrBoolean(context, R.attr.isWorkspaceDarkText)
                    } else {
                        ColorUtils.calculateLuminance(rawColor) < 0.45
                    }
                }
                views.setTextColor(
                    R.id.activity_pill_label,
                    if (isDarkBg) 0xF2FFFFFF.toInt() else 0xD91C1C1E.toInt(),
                )
                val launchIntent = Intent(context, ActivityPillWidgetProvider::class.java).apply {
                    action = ActivityShortcutWidgetProvider.ACTION_LAUNCH_ACTIVITY
                    putExtra(ActivityShortcutWidgetProvider.EXTRA_COMPONENT_NAME, config.componentNameString)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.activity_pill_widget_root, pendingIntent)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun createPillBitmap(context: Context, config: ActivityShortcutWidgetConfig): Bitmap {
            val density = context.resources.displayMetrics.density
            val widthPx = (240 * density).roundToInt()
            val heightPx = (52 * density).roundToInt()
            val bitmap = createBitmap(widthPx, heightPx)
            val canvas = Canvas(bitmap)

            val prefs = app.lawnchair.preferences.PreferenceManager.getInstance(context)
            val prefs2 = app.lawnchair.preferences2.PreferenceManager2.getInstance(context)

            val baseColor: Int
            val alpha: Float
            val strokeWidth: Int
            val strokeColor: Int

            if (config.matchSearchBarStyle) {
                val themed = try {
                    prefs2.themedHotseatQsb.firstCached()
                } catch (_: Exception) {
                    true
                }
                baseColor = app.lawnchair.qsb.getHotseatBackgroundColor(context, themed)
                alpha = try {
                    (prefs.hotseatQsbAlpha.get() / 100f).coerceIn(0f, 1f)
                } catch (_: Exception) {
                    1f
                }
                strokeWidth = try {
                    (prefs.hotseatQsbStrokeWidth.get() * density).roundToInt()
                } catch (_: Exception) {
                    0
                }
                strokeColor = try {
                    val strokeColorOption = prefs2.strokeColorStyle.firstCached()
                    strokeColorOption.colorPreferenceEntry.lightColor.invoke(context)
                } catch (_: Exception) {
                    com.android.launcher3.util.Themes.getColorAccent(context)
                }
            } else {
                baseColor = ActivityPillWidgetView.resolveBaseColor(context, config.backgroundColorOption)
                alpha = config.backgroundAlpha.coerceIn(0f, 1f)
                val isDarkBg = ColorUtils.calculateLuminance(baseColor) < 0.45
                strokeWidth = if (alpha > 0f) (1f * density).roundToInt().coerceAtLeast(1) else 0
                strokeColor = if (isDarkBg) {
                    ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (alpha * 60f).roundToInt().coerceIn(0, 100))
                } else {
                    ColorUtils.setAlphaComponent(0xFF000000.toInt(), (alpha * 40f).roundToInt().coerceIn(0, 80))
                }
            }

            val effectiveColor = ColorUtils.setAlphaComponent(
                baseColor,
                (alpha * 255f).roundToInt().coerceIn(0, 255),
            )
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = heightPx / 2f
                setColor(effectiveColor)
                if (strokeWidth > 0) {
                    setStroke(strokeWidth, strokeColor)
                }
            }
            drawable.setBounds(0, 0, widthPx, heightPx)
            drawable.draw(canvas)
            return bitmap
        }
    }
}

class ActivityShortcutWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            deleteConfig(context, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LAUNCH_ACTIVITY) {
            val componentString = intent.getStringExtra(EXTRA_COMPONENT_NAME) ?: return
            val cn = ComponentName.unflattenFromString(componentString) ?: return
            OpenAppGestureHandler.launchComponent(
                context,
                ComponentKey(cn, Process.myUserHandle()),
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "activity_shortcut_widgets"
        private const val KEY_PREFIX = "widget_"
        const val ACTION_LAUNCH_ACTIVITY = "app.lawnchair.widget.ACTION_LAUNCH_ACTIVITY"
        const val EXTRA_COMPONENT_NAME = "extra_component_name"

        @JvmField
        val componentName = ComponentName(
            BuildConfig.APPLICATION_ID,
            ActivityShortcutWidgetProvider::class.java.name,
        )

        fun saveConfig(context: Context, appWidgetId: Int, config: ActivityShortcutWidgetConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putString("$KEY_PREFIX$appWidgetId", Json.encodeToString(config))
            }
        }

        fun loadConfig(context: Context, appWidgetId: Int): ActivityShortcutWidgetConfig? {
            if (appWidgetId <= 0) return null
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("$KEY_PREFIX$appWidgetId", null) ?: return null
            return try {
                Json.decodeFromString<ActivityShortcutWidgetConfig>(json)
            } catch (_: Exception) {
                null
            }
        }

        fun deleteConfig(context: Context, appWidgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                remove("$KEY_PREFIX$appWidgetId")
            }
        }

        fun loadWidgetIconDrawable(
            context: Context,
            appWidgetId: Int,
            config: ActivityShortcutWidgetConfig,
        ): Drawable {
            val iconDpi = try {
                LauncherAppState.getIDP(context).fillResIconDpi
            } catch (_: Exception) {
                context.resources.displayMetrics.densityDpi
            }
            val overrideKey = config.getIconOverrideKey(appWidgetId)
            val overrideItem = IconOverrideRepository.INSTANCE.get(context).overridesMap[overrideKey]
            if (overrideItem != null) {
                val customDrawable = IconPackProvider.INSTANCE.get(context)
                    .getDrawable(overrideItem.toIconEntry(), iconDpi, overrideKey.user)
                if (customDrawable != null) {
                    return customDrawable
                }
            }

            val targetCn = config.componentName
            val pm = context.packageManager
            if (targetCn != null) {
                try {
                    val iconProvider = LauncherAppState.getInstance(context).iconCache.iconProvider
                    val actInfo = pm.getActivityInfo(targetCn, 0)
                    return iconProvider.getIcon(actInfo, iconDpi)
                } catch (_: Exception) {
                    try {
                        val actInfo = pm.getActivityInfo(targetCn, 0)
                        return actInfo.loadIcon(pm) ?: pm.getApplicationIcon(targetCn.packageName)
                    } catch (_: Exception) {
                        try {
                            return pm.getApplicationIcon(targetCn.packageName)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            return pm.defaultActivityIcon
        }

        fun loadShapedWidgetIconBitmap(
            context: Context,
            appWidgetId: Int,
            config: ActivityShortcutWidgetConfig,
        ): Bitmap {
            val drawable = loadWidgetIconDrawable(context, appWidgetId, config)
            return try {
                LauncherIcons.obtain(context).use { li ->
                    li.createBadgedIconBitmap(drawable).icon
                }
            } catch (_: Exception) {
                val w = drawable.intrinsicWidth.coerceAtLeast(96)
                val h = drawable.intrinsicHeight.coerceAtLeast(96)
                val bmp = createBitmap(w, h)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bmp
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val config = loadConfig(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.activity_shortcut_widget_remote)
            if (config != null) {
                val bitmap = loadShapedWidgetIconBitmap(context, appWidgetId, config)
                views.setImageViewBitmap(R.id.activity_widget_icon, bitmap)
                views.setTextViewText(R.id.activity_widget_label, config.displayLabel)
                views.setViewVisibility(
                    R.id.activity_widget_label,
                    if (config.showLabel && config.displayLabel.isNotBlank()) View.VISIBLE else View.GONE,
                )
                val launchIntent = Intent(context, ActivityShortcutWidgetProvider::class.java).apply {
                    action = ACTION_LAUNCH_ACTIVITY
                    putExtra(EXTRA_COMPONENT_NAME, config.componentNameString)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.activity_widget_root, pendingIntent)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

