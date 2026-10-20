package app.lawnchair.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.lawnchair.BlankActivity
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.launcherNullable
import app.lawnchair.override.CustomizeDialog
import app.lawnchair.preferences.observeAsState
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.asState
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.qsb.getHotseatBackgroundColor
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.components.colorpreference.ColorPreference
import app.lawnchair.ui.preferences.components.colorpreference.StandaloneColorSelection
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.destinations.PickAppActivityScreen
import app.lawnchair.ui.preferences.navigation.SelectIcon
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.ui.util.addIf
import app.lawnchair.util.ProvideLifecycleState
import com.android.launcher3.R
import com.android.launcher3.util.Themes
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class ActivityWidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    open val isPillMode: Boolean = false

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val initialConfig = ActivityShortcutWidgetProvider.loadConfig(this, appWidgetId)
        val isPillWidget = initialConfig?.isPillWidget ?: isPillMode

        setContent {
            LawnchairTheme {
                EdgeToEdge()
                ProvideLifecycleState {
                    val windowSizeClass = calculateWindowSizeClass(this)
                    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact ||
                        windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .addIf(!isCompact) {
                                requiredWidth(600.dp)
                            },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ConfigureActivityWidgetScreen(
                            appWidgetId = appWidgetId,
                            initialConfig = initialConfig,
                            isPillWidget = isPillWidget,
                            onSave = { finalConfig ->
                                saveAndFinish(finalConfig)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun saveAndFinish(config: ActivityShortcutWidgetConfig) {
        ActivityShortcutWidgetProvider.saveConfig(this, appWidgetId, config)
        val appWidgetManager = AppWidgetManager.getInstance(this)
        if (config.isPillWidget) {
            ActivityPillWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)
        } else {
            ActivityShortcutWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)
        }

        // Notify active views directly so they update immediately
        ActivityPillWidgetView.notifyConfigChanged(appWidgetId)

        // Also refresh any live LauncherAppWidgetHostView on the workspace immediately
        val launcher = app.lawnchair.LawnchairLauncher.instance ?: applicationContext.launcherNullable
        launcher?.workspace?.mapOverItems { _, view ->
            if (view is LauncherAppWidgetHostView && view.appWidgetId == appWidgetId) {
                (view.findViewById<ActivityPillWidgetView>(R.id.activity_pill_widget_root))?.reloadFromConfig()
                (view.findViewById<ActivityShortcutWidgetView>(R.id.activity_widget_root))?.reloadFromConfig()
                view.requestLayout()
                view.invalidate()
            }
            false
        }

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}

class ActivityPillWidgetConfigureActivity : ActivityWidgetConfigureActivity() {
    override val isPillMode: Boolean = true
}

@Composable
private fun ConfigureActivityWidgetScreen(
    appWidgetId: Int,
    initialConfig: ActivityShortcutWidgetConfig?,
    isPillWidget: Boolean,
    onSave: (ActivityShortcutWidgetConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()

    var currentConfig by remember { mutableStateOf(initialConfig) }
    var isPickingActivity by remember { mutableStateOf(initialConfig == null) }
    var isPickingColor by remember { mutableStateOf(false) }
    var iconReloadTrigger by remember { mutableIntStateOf(0) }

    if (isPickingActivity) {
        BackHandler(enabled = currentConfig != null) {
            isPickingActivity = false
        }
        PickAppActivityScreen(
            onSelectActivity = { entry, app ->
                val defaultLabel = entry.label.ifBlank { app.appLabel }
                currentConfig = ActivityShortcutWidgetConfig(
                    componentNameString = entry.componentName.flattenToString(),
                    customLabel = defaultLabel,
                    defaultLabel = defaultLabel,
                    showLabel = currentConfig?.showLabel ?: true,
                    isPillWidget = isPillWidget,
                    useFinderIcon = currentConfig?.useFinderIcon ?: isPillWidget,
                    backgroundAlpha = currentConfig?.backgroundAlpha ?: 0.42f,
                    backgroundColorOption = currentConfig?.backgroundColorOption ?: "default",
                    matchSearchBarStyle = currentConfig?.matchSearchBarStyle ?: false,
                )
                isPickingActivity = false
                iconReloadTrigger++
            },
        )
    } else if (isPickingColor) {
        BackHandler {
            isPickingColor = false
        }
        val config = currentConfig ?: return
        val currentColorOption = remember(config.backgroundColorOption) {
            ColorOption.fromString(config.backgroundColorOption)
        }
        val dynamicEntries = remember(context) {
            listOf(
                ColorOption.SearchBarSurface,
                ColorOption.SystemAccent,
                ColorOption.WallpaperPrimary,
                ColorOption.Default,
            ).filter { it.isSupported }.map { it.colorPreferenceEntry }
        }
        StandaloneColorSelection(
            label = stringResource(id = R.string.hotseat_bg_color_label),
            selectedColorOption = currentColorOption,
            dynamicEntries = dynamicEntries,
            onColorSelected = { newOption ->
                currentConfig = config.copy(backgroundColorOption = newOption.toString())
                isPickingColor = false
            },
        )
    } else {
        val config = currentConfig ?: return
        var labelText by remember(config.componentNameString) {
            mutableStateOf(config.customLabel.ifEmpty { config.defaultLabel })
        }
        var showLabel by remember(config.componentNameString) {
            mutableStateOf(config.showLabel)
        }
        var useFinderIcon by remember(config.componentNameString) {
            mutableStateOf(config.useFinderIcon)
        }
        var matchSearchBarStyle by remember(config.componentNameString) {
            mutableStateOf(config.matchSearchBarStyle)
        }
        var backgroundAlpha by remember(config.componentNameString) {
            mutableFloatStateOf(config.backgroundAlpha)
        }
        val selectedColorOption = remember(config.backgroundColorOption) {
            ColorOption.fromString(config.backgroundColorOption)
        }

        val iconDrawable by produceState<Drawable>(
            initialValue = context.packageManager.defaultActivityIcon,
            config.componentNameString,
            useFinderIcon,
            iconReloadTrigger,
        ) {
            value = withContext(Dispatchers.IO) {
                val hasCustomIcon = IconOverrideRepository.INSTANCE.get(context)
                    .overridesMap[config.getIconOverrideKey(appWidgetId)] != null
                if (isPillWidget && useFinderIcon && !hasCustomIcon) {
                    ContextCompat.getDrawable(context, R.drawable.ic_finder_gradient)
                        ?: ActivityShortcutWidgetProvider.loadWidgetIconDrawable(context, appWidgetId, config)
                } else {
                    ActivityShortcutWidgetProvider.loadWidgetIconDrawable(context, appWidgetId, config)
                }
            }
        }

        val overrideKey = remember(appWidgetId) { config.getIconOverrideKey(appWidgetId) }
        val openIconPicker = {
            val route = SelectIcon(overrideKey.toString())
            val intent = PreferenceActivity.createIntent(context, route)
            scope.launch {
                val result = BlankActivity.startBlankActivityForResult(context as Activity, intent)
                if (result.resultCode == Activity.RESULT_OK) {
                    iconReloadTrigger++
                }
            }
            Unit
        }

        PreferenceLayout(
            label = stringResource(id = R.string.customize_activity_widget),
            backArrowVisible = true,
        ) {
            CustomizeDialog(
                icon = iconDrawable,
                title = labelText,
                onTitleChange = { labelText = it },
                defaultTitle = config.defaultLabel,
                launchSelectIcon = openIconPicker,
            ) {
                // Live preview for Pill widget
                if (isPillWidget) {
                    val themedQsb by prefs2.themedHotseatQsb.asState()
                    val qsbAlpha by prefs.hotseatQsbAlpha.observeAsState()
                    val qsbStrokeWidth by prefs.hotseatQsbStrokeWidth.observeAsState()

                    val previewBgColor = if (matchSearchBarStyle) {
                        getHotseatBackgroundColor(context, themedQsb)
                    } else {
                        remember(config.backgroundColorOption) {
                            ActivityPillWidgetView.resolveBaseColor(context, config.backgroundColorOption)
                        }
                    }
                    val previewAlpha = if (matchSearchBarStyle) {
                        (qsbAlpha / 100f).coerceIn(0f, 1f)
                    } else {
                        backgroundAlpha.coerceIn(0f, 1f)
                    }
                    val previewStrokeWidth = if (matchSearchBarStyle) {
                        qsbStrokeWidth.dp
                    } else {
                        if (previewAlpha > 0f) 1.dp else 0.dp
                    }
                    val isDarkPreview = when {
                        previewAlpha < 0.20f -> !Themes.getAttrBoolean(context, R.attr.isWorkspaceDarkText)
                        ColorUtils.calculateLuminance(previewBgColor) < 0.45 -> true
                        else -> false
                    }
                    val previewTextColor = if (isDarkPreview) ComposeColor.White else ComposeColor(0xD91C1C1E)
                    val previewStrokeColor = if (isDarkPreview) ComposeColor.White.copy(alpha = 0.25f) else ComposeColor.Black.copy(alpha = 0.15f)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val pillShape = RoundedCornerShape(26.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(pillShape)
                                .background(
                                    ComposeColor(previewBgColor).copy(alpha = previewAlpha),
                                    pillShape,
                                )
                                .then(
                                    if (previewStrokeWidth > 0.dp) {
                                        Modifier.border(previewStrokeWidth, previewStrokeColor, pillShape)
                                    } else Modifier,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Image(
                                painter = rememberDrawablePainter(iconDrawable),
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                            )
                            if (showLabel && labelText.isNotBlank()) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = labelText,
                                    color = previewTextColor,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                PreferenceGroup(
                    description = config.componentNameString,
                    showDescription = true,
                ) {
                    SwitchPreference(
                        checked = showLabel,
                        label = stringResource(id = R.string.show_label),
                        onCheckedChange = { showLabel = it },
                    )
                    if (isPillWidget) {
                        SwitchPreference(
                            checked = useFinderIcon,
                            label = stringResource(id = R.string.use_finder_search_icon),
                            description = stringResource(id = R.string.use_finder_search_icon_description),
                            onCheckedChange = { useFinderIcon = it },
                        )
                        SwitchPreference(
                            checked = matchSearchBarStyle,
                            label = stringResource(id = R.string.match_search_bar_style),
                            description = stringResource(id = R.string.match_search_bar_style_desc),
                            onCheckedChange = { matchSearchBarStyle = it },
                        )
                        if (!matchSearchBarStyle) {
                            ColorPreference(
                                label = stringResource(id = R.string.hotseat_bg_color_label),
                                selectedColor = selectedColorOption,
                                onClick = { isPickingColor = true },
                            )
                            SliderPreference(
                                label = stringResource(id = R.string.background_opacity),
                                value = backgroundAlpha,
                                onValueChange = { backgroundAlpha = it },
                                onValueChangeFinished = { backgroundAlpha = it },
                                valueRange = 0f..1f,
                                step = 0.05f,
                                showAsPercentage = true,
                            )
                        }
                    }
                    ClickablePreference(
                        label = stringResource(id = R.string.change_activity),
                        subtitle = config.defaultLabel,
                        onClick = { isPickingActivity = true },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        onSave(
                            config.copy(
                                customLabel = labelText.ifEmpty { config.defaultLabel },
                                showLabel = showLabel,
                                isPillWidget = isPillWidget,
                                useFinderIcon = useFinderIcon,
                                backgroundAlpha = backgroundAlpha,
                                backgroundColorOption = config.backgroundColorOption,
                                matchSearchBarStyle = matchSearchBarStyle,
                            ),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(text = stringResource(id = R.string.apply_label))
                }
            }
        }
    }
}
