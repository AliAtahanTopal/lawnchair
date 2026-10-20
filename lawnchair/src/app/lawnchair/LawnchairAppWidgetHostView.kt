package app.lawnchair

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.RemoteViews
import app.lawnchair.smartspace.SmartspaceAppWidgetProvider
import app.lawnchair.widget.ActivityPillWidgetProvider
import app.lawnchair.widget.ActivityPillWidgetView
import app.lawnchair.widget.ActivityShortcutWidgetProvider
import app.lawnchair.widget.ActivityShortcutWidgetView
import com.android.launcher3.R
import com.android.launcher3.util.Themes
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.launcher3.widget.ListenableHostView

class LawnchairAppWidgetHostView @JvmOverloads constructor(
    context: Context,
    private var previewMode: Boolean = false,
) : ListenableHostView(context) {

    private var customView: ViewGroup? = null

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo) {
        super.setAppWidget(appWidgetId, info)
        inflateCustomView(info)
        (customView as? ActivityShortcutWidgetView)?.reloadFromConfig()
        (customView as? ActivityPillWidgetView)?.reloadFromConfig()
    }

    fun disablePreviewMode() {
        previewMode = false
        inflateCustomView(appWidgetInfo)
    }

    private fun inflateCustomView(info: AppWidgetProviderInfo?) {
        if (info == null) return
        customView = inflateCustomView(context, info, previewMode)
        if (customView == null) {
            return
        }
        customView!!.setOnLongClickListener(this)
        removeAllViews()
        addView(customView, MATCH_PARENT, MATCH_PARENT)
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        (customView as? ActivityShortcutWidgetView)?.reloadFromConfig()
        (customView as? ActivityPillWidgetView)?.reloadFromConfig()
        if (customView != null) {
            notifyUpdateListeners()
            return
        }
        super.updateAppWidget(remoteViews)
    }

    override fun getDefaultView(): View {
        if (customView != null) return getEmptyView()
        return super.getDefaultView()
    }

    override fun getErrorView(): View {
        if (customView != null) return getEmptyView()
        return super.getErrorView()
    }

    private fun getEmptyView(): View {
        return View(context)
    }

    companion object {

        private val customLayouts = mapOf(
            SmartspaceAppWidgetProvider.componentName to R.layout.smartspace_widget,
            ActivityShortcutWidgetProvider.componentName to R.layout.activity_shortcut_widget_custom,
            ActivityPillWidgetProvider.componentName to R.layout.activity_pill_widget_custom,
        )

        @JvmStatic
        fun inflateCustomView(context: Context, info: AppWidgetProviderInfo, previewMode: Boolean): ViewGroup? {
            val layoutId = customLayouts[info.provider] ?: return null

            val inflationContext = if (previewMode) Themes.createWidgetPreviewContext(context) else context
            return LayoutInflater.from(inflationContext)
                .inflate(layoutId, null, false) as ViewGroup
        }
    }
}
