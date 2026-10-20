package app.lawnchair.ui.preferences.destinations

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.gestures.handlers.OpenAppTarget
import app.lawnchair.ui.OverflowMenu
import app.lawnchair.ui.preferences.components.AppItemPlaceholder
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupItem
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceSearchScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.util.kotlinxJson
import com.android.launcher3.R
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ActivityEntryInfo(
    val label: String,
    val componentName: ComponentName,
    val exported: Boolean,
    val enabled: Boolean,
)

data class AppWithActivities(
    val appLabel: String,
    val packageName: String,
    val appIcon: Bitmap,
    val activities: List<ActivityEntryInfo>,
)

@Composable
fun PickAppActivityScreen(
    onSelectActivity: ((ActivityEntryInfo, AppWithActivities) -> Unit)? = null,
) {
    val context = LocalContext.current
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)
    val activity = context as? Activity

    var searchQuery by remember { mutableStateOf("") }
    var showUnexported by remember { mutableStateOf(false) }

    val allApps by produceState<List<AppWithActivities>>(initialValue = emptyList(), showUnexported) {
        value = withContext(Dispatchers.IO) {
            loadAppsWithActivities(context, showUnexported)
        }
    }

    val filteredApps by remember(allApps, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                allApps
            } else {
                val query = searchQuery.trim().lowercase(Locale.getDefault())
                allApps.mapNotNull { app ->
                    val appMatches = app.appLabel.lowercase(Locale.getDefault()).contains(query) ||
                        app.packageName.lowercase(Locale.getDefault()).contains(query)
                    val matchingActivities = app.activities.filter {
                        it.label.lowercase(Locale.getDefault()).contains(query) ||
                            it.componentName.className.lowercase(Locale.getDefault()).contains(query)
                    }
                    when {
                        appMatches && matchingActivities.isEmpty() -> app
                        matchingActivities.isNotEmpty() -> app.copy(activities = matchingActivities)
                        else -> null
                    }
                }
            }
        }
    }

    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }
    val state = rememberLazyListState()

    fun handleSelect(entry: ActivityEntryInfo, app: AppWithActivities) {
        if (onSelectActivity != null) {
            onSelectActivity(entry, app)
            return
        }
        val key = ComponentKey(entry.componentName, Process.myUserHandle())
        val displayLabel = entry.label.ifBlank { app.appLabel }
        val config: GestureHandlerConfig = GestureHandlerConfig.OpenApp(
            appName = displayLabel,
            target = OpenAppTarget.Activity(key),
        )
        val configString = kotlinxJson.encodeToString(config)
        val resultIntent = Intent().apply {
            putExtra("config", configString)
            putExtra("componentName", entry.componentName.flattenToString())
            putExtra("activityLabel", displayLabel)
        }
        activity?.setResult(Activity.RESULT_OK, resultIntent)
        activity?.finish()
    }

    PreferenceSearchScaffold(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = {
            Text(
                text = stringResource(id = R.string.pick_activity),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            OverflowMenu {
                DropdownMenuItem(
                    onClick = {
                        showUnexported = !showUnexported
                        hideMenu()
                    },
                    trailingIcon = {
                        if (showUnexported) Icon(Icons.Rounded.Check, null)
                    },
                    text = {
                        Text(text = stringResource(id = R.string.show_all_apps))
                    },
                )
            }
        },
    ) { paddingValues ->
        Crossfade(targetState = allApps.isNotEmpty(), label = "pick_activity_loading") { loaded ->
            if (loaded) {
                PreferenceLazyColumn(paddingValues, state = state) {
                    val count = filteredApps.size
                    items(
                        count = count,
                        key = { index -> filteredApps[index].packageName },
                    ) { index ->
                        val app = filteredApps[index]
                        val isSearching = searchQuery.isNotBlank() && app.activities.size <= 5
                        val isExpanded = expandedMap[app.packageName] ?: isSearching

                        PreferenceGroupItem(
                            cutTop = index > 0,
                            cutBottom = index < count - 1,
                        ) {
                            Column {
                                if (index > 0) {
                                    HorizontalDivider(
                                        thickness = 3.dp,
                                        color = MaterialTheme.colorScheme.surface,
                                    )
                                }
                                val rotation by animateFloatAsState(
                                    targetValue = if (isExpanded) 180f else 0f,
                                    label = "arrow_rotation",
                                )
                                PreferenceTemplate(
                                    title = {
                                        Text(
                                            text = app.appLabel,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    description = {
                                        Text(
                                            text = "${app.packageName} • " + pluralStringResource(
                                                id = R.plurals.activities_count,
                                                count = app.activities.size,
                                                app.activities.size,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    startWidget = {
                                        Image(
                                            bitmap = app.appIcon.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    },
                                    endWidget = {
                                        Icon(
                                            imageVector = Icons.Rounded.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.rotate(rotation),
                                        )
                                    },
                                    onClick = {
                                        mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                                        expandedMap[app.packageName] = !isExpanded
                                    },
                                )

                                AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(start = 24.dp),
                                    ) {
                                        app.activities.forEach { act ->
                                            HorizontalDivider(
                                                thickness = 1.dp,
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            )
                                            ActivityRowItem(
                                                entry = act,
                                                fallbackIcon = app.appIcon,
                                                onClick = {
                                                    mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                                                    handleSelect(act, app)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                PreferenceLazyColumn(paddingValues, enabled = false) {
                    preferenceGroupItems(
                        count = 20,
                        isFirstChild = true,
                    ) {
                        AppItemPlaceholder {
                            Spacer(Modifier.width(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRowItem(
    entry: ActivityEntryInfo,
    fallbackIcon: Bitmap,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val activityIcon by produceState(initialValue = fallbackIcon, entry.componentName) {
        value = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val info = pm.getActivityInfo(entry.componentName, 0)
                info.loadIcon(pm)?.toBitmap(96, 96) ?: fallbackIcon
            } catch (_: Exception) {
                fallbackIcon
            }
        }
    }
    PreferenceTemplate(
        title = {
            Text(
                text = entry.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        description = {
            Text(
                text = entry.componentName.shortClassName.ifEmpty { entry.componentName.className },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        startWidget = {
            Image(
                bitmap = activityIcon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        },
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        onClick = onClick,
    )
}

@Suppress("DEPRECATION")
private fun loadAppsWithActivities(
    context: Context,
    includeUnexported: Boolean,
): List<AppWithActivities> {
    val pm = context.packageManager
    val packages = try {
        pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
    } catch (e: Exception) {
        emptyList()
    }
    val result = ArrayList<AppWithActivities>(packages.size)
    for (pkg in packages) {
        val rawActivities = pkg.activities ?: continue
        if (rawActivities.isEmpty()) continue
        val appInfo = pkg.applicationInfo ?: continue

        val validActivities = rawActivities.mapNotNull { actInfo ->
            if (!actInfo.enabled) return@mapNotNull null
            if (!includeUnexported && !actInfo.exported) return@mapNotNull null
            val actLabel = try {
                actInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } ?: actInfo.name.substringAfterLast('.')

            ActivityEntryInfo(
                label = actLabel,
                componentName = ComponentName(actInfo.packageName, actInfo.name),
                exported = actInfo.exported,
                enabled = actInfo.enabled,
            )
        }
        if (validActivities.isEmpty()) continue

        val appLabel = try {
            appInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: pkg.packageName

        val appIcon = try {
            appInfo.loadIcon(pm).toBitmap(96, 96)
        } catch (_: Exception) {
            pm.defaultActivityIcon.toBitmap(96, 96)
        }

        result.add(
            AppWithActivities(
                appLabel = appLabel,
                packageName = pkg.packageName,
                appIcon = appIcon,
                activities = validActivities,
            ),
        )
    }
    result.sortBy { it.appLabel.lowercase(Locale.getDefault()) }
    return result
}

