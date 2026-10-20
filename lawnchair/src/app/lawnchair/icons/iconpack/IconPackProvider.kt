package app.lawnchair.icons.iconpack

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import app.lawnchair.icons.ClockMetadata
import app.lawnchair.icons.CustomAdaptiveIconDrawable
import app.lawnchair.icons.ExtendedBitmapDrawable
import app.lawnchair.icons.picker.IconEntry
import app.lawnchair.icons.picker.IconType
import app.lawnchair.icons.shouldTransparentBGIcons
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.icons.ClockDrawableWrapper
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import java.io.File
import javax.inject.Inject

@LauncherAppSingleton
class IconPackProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val iconPacks = mutableMapOf<String, IconPack?>()

    fun getIconPackOrSystem(packageName: String): IconPack? {
        if (packageName.isEmpty()) return SystemIconPack(context, packageName)
        return getIconPack(packageName)
    }

    fun getIconPack(packageName: String): IconPack? {
        if (packageName.isEmpty()) {
            return null
        }
        return iconPacks.getOrPut(packageName) {
            try {
                CustomIconPack(context, packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    fun getClockMetadata(iconEntry: IconEntry): ClockMetadata? {
        val iconPack = getIconPackOrSystem(iconEntry.packPackageName) ?: return null
        return iconPack.getClock(iconEntry)
    }

    fun getDrawable(iconEntry: IconEntry, iconDpi: Int, user: UserHandle): Drawable? {
        if (iconEntry.type == IconType.CustomImage) {
            return loadCustomImageDrawable(iconEntry.name)
        }
        val iconPack = getIconPackOrSystem(iconEntry.packPackageName) ?: return null
        iconPack.loadBlocking()
        val drawable = iconPack.getIcon(iconEntry, iconDpi) ?: return null
        val clockMetadata =
            if (user == Process.myUserHandle()) iconPack.getClock(iconEntry) else null
        try {
            if (clockMetadata != null) {
                val clockDrawable: ClockDrawableWrapper =
                    ClockDrawableWrapper.forMeta(Build.VERSION.SDK_INT, clockMetadata) {
                        drawable
                    }

                return if (context.shouldTransparentBGIcons()) {
                    clockDrawable.foreground
                } else {
                    CustomAdaptiveIconDrawable(
                        clockDrawable.background,
                        clockDrawable.foreground,
                    )
                }
            }
        } catch (t: Throwable) {
            // Ignore
        }

        return drawable
    }

    private fun loadCustomImageDrawable(rawSpec: String): Drawable? {
        val reshape = rawSpec.startsWith("adaptive:")
        val filePath = rawSpec.removePrefix("adaptive:").removePrefix("raw:")
        val file = File(filePath)
        if (!file.exists()) return null
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Throwable) {
            null
        } ?: return null

        return if (reshape) {
            CustomAdaptiveIconDrawable(
                BitmapDrawable(context.resources, bitmap),
                null,
            )
        } else {
            ExtendedBitmapDrawable(context.resources, bitmap, true)
        }
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    companion object {
        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getIconPackProvider)
    }
}
