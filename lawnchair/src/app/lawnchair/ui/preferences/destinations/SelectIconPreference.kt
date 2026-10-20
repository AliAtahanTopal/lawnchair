package app.lawnchair.ui.preferences.destinations

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.icons.picker.IconPickerItem
import app.lawnchair.icons.picker.IconType
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.LocalPreferenceInteractor
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.navigation.IconPicker
import app.lawnchair.ui.util.OnResult
import app.lawnchair.util.requireSystemService
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SelectIconPreference"

@Composable
fun SelectIconPreference(componentKey: ComponentKey) {
    val context = LocalContext.current
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)
    val label = remember(componentKey) {
        resolveAppLabel(context.requireSystemService(), componentKey)
    }
    val iconPacks by LocalPreferenceInteractor.current.iconPacks.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val launcherAppState = LauncherAppState.getInstance(context)
    val model = launcherAppState.model

    val repo = IconOverrideRepository.INSTANCE.get(context)

    val applyIconPickerItem: (IconPickerItem) -> Unit = { item ->
        scope.launch {
            repo.setOverride(componentKey, item)
            // Refresh package icons while the model is still loaded; forceReload alone can
            // skip PackageUpdatedTask and leave icon-cache entries that look "fresh".
            // onAppIconChanged is @WorkerThread (blocking ShortcutManager query).
            withContext(Dispatchers.IO) {
                model.onAppIconChanged(componentKey.componentName.packageName, componentKey.user)
            }
            (context as? Activity)?.let {
                it.setResult(Activity.RESULT_OK)
                it.finish()
            }
        }
    }

    OnResult<IconPickerItem> { item ->
        applyIconPickerItem(item)
    }

    val overrideItem by repo.observeTarget(componentKey).collectAsStateWithLifecycle(initialValue = null)
    val hasOverride = overrideItem != null

    var reshapeCustomImage by rememberSaveable { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val savedSpec = withContext(Dispatchers.IO) {
                saveCustomIconImage(context, componentKey, uri, reshapeCustomImage)
            }
            if (savedSpec != null) {
                applyIconPickerItem(
                    IconPickerItem(
                        packPackageName = "",
                        drawableName = savedSpec,
                        label = context.getString(R.string.custom_image_heading),
                        type = IconType.CustomImage,
                    ),
                )
            } else {
                Toast.makeText(context, R.string.icon_picker_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    PreferenceLayoutLazyColumn(label = label) {
        if (hasOverride) {
            preferenceGroupItems(1, isFirstChild = true) {
                ClickablePreference(
                    label = stringResource(id = R.string.icon_picker_reset_to_default),
                    onClick = {
                        scope.launch {
                            repo.deleteOverride(componentKey)
                            withContext(Dispatchers.IO) {
                                model.onAppIconChanged(
                                    componentKey.componentName.packageName,
                                    componentKey.user,
                                )
                            }
                            (context as? Activity)?.let {
                                it.setResult(Activity.RESULT_OK)
                                it.finish()
                            }
                        }
                    },
                )
            }
        }
        preferenceGroupItems(
            count = 2,
            isFirstChild = !hasOverride,
            heading = { stringResource(id = R.string.custom_image_heading) },
        ) { index ->
            if (index == 0) {
                ClickablePreference(
                    label = stringResource(id = R.string.pick_image_from_gallery),
                    onClick = {
                        mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                        imagePickerLauncher.launch("image/*")
                    },
                )
            } else {
                SwitchPreference(
                    checked = reshapeCustomImage,
                    onCheckedChange = { reshapeCustomImage = it },
                    label = stringResource(id = R.string.pick_image_reshape_label),
                    description = stringResource(id = R.string.pick_image_reshape_description),
                )
            }
        }
        preferenceGroupItems(
            items = iconPacks,
            isFirstChild = false,
            heading = { stringResource(id = R.string.pick_icon_from_label) },
        ) { _, iconPack ->
            AppItem(
                label = iconPack.name,
                icon = remember(iconPack) { iconPack.icon.toBitmap() },
                onClick = {
                    mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    if (iconPack.packageName.isEmpty()) {
                        navController.navigate(IconPicker())
                    } else {
                        navController.navigate(IconPicker(iconPack.packageName))
                    }
                },
            )
        }
    }
}

private fun saveCustomIconImage(
    context: Context,
    componentKey: ComponentKey,
    uri: Uri,
    reshapeToMask: Boolean,
): String? {
    return try {
        val maxSidePx = 512
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while (max(rawW, rawH) / (sampleSize * 2) >= maxSidePx) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val processedBitmap = if (reshapeToMask) {
            // Center-crop to a square so it fills the adaptive icon mask cleanly without borders
            val side = min(decoded.width, decoded.height).coerceAtMost(maxSidePx)
            val cropSide = min(decoded.width, decoded.height)
            val srcLeft = (decoded.width - cropSide) / 2
            val srcTop = (decoded.height - cropSide) / 2
            val outBmp = createBitmap(side, side)
            val canvas = Canvas(outBmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                decoded,
                Rect(srcLeft, srcTop, srcLeft + cropSide, srcTop + cropSide),
                Rect(0, 0, side, side),
                paint,
            )
            if (outBmp !== decoded) decoded.recycle()
            outBmp
        } else {
            // Preserve aspect ratio within a square canvas so transparent/full PNGs aren't cropped
            val maxDim = max(decoded.width, decoded.height)
            val scale = if (maxDim > maxSidePx) maxSidePx.toFloat() / maxDim else 1f
            val targetW = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            val canvasSide = max(targetW, targetH)
            val outBmp = createBitmap(canvasSide, canvasSide)
            val canvas = Canvas(outBmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val dstLeft = (canvasSide - targetW) / 2
            val dstTop = (canvasSide - targetH) / 2
            canvas.drawBitmap(
                decoded,
                null,
                Rect(dstLeft, dstTop, dstLeft + targetW, dstTop + targetH),
                paint,
            )
            if (outBmp !== decoded) decoded.recycle()
            outBmp
        }

        val dir = File(context.filesDir, "custom_icons").apply { mkdirs() }
        val keyHash = componentKey.toString().hashCode().toUInt().toString(16)
        // Clean up any previous custom icon files for this componentKey
        dir.listFiles()?.forEach { existing ->
            if (existing.name.startsWith("icon_${keyHash}_")) {
                existing.delete()
            }
        }
        val outFile = File(dir, "icon_${keyHash}_${System.currentTimeMillis()}.png")
        FileOutputStream(outFile).use { fos ->
            processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        processedBitmap.recycle()

        val prefix = if (reshapeToMask) "adaptive:" else "raw:"
        "$prefix${outFile.absolutePath}"
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to save custom icon image", t)
        null
    }
}

/**
 * Resolves a display label for [componentKey], including work/private profiles.
 *
 * [LauncherApps.resolveActivity] can return null or throw for non-current users; fall back to
 * [LauncherApps.getActivityList] and finally the activity class name.
 */
private fun resolveAppLabel(launcherApps: LauncherApps, componentKey: ComponentKey): String {
    val componentName = componentKey.componentName
    val user = componentKey.user
    try {
        val intent = Intent().setComponent(componentName)
        launcherApps.resolveActivity(intent, user)?.label?.toString()?.let { return it }
    } catch (t: Throwable) {
        Log.w(TAG, "resolveActivity failed for $componentKey", t)
    }
    try {
        val activities = launcherApps.getActivityList(componentName.packageName, user)
        activities
            .firstOrNull { it.componentName == componentName }
            ?.label
            ?.toString()
            ?.let { return it }
        activities
            .firstOrNull()
            ?.label
            ?.toString()
            ?.let { return it }
    } catch (t: Throwable) {
        Log.w(TAG, "getActivityList failed for $componentKey", t)
    }
    return componentName.shortClassName?.trimStart('.') ?: componentName.packageName
}
