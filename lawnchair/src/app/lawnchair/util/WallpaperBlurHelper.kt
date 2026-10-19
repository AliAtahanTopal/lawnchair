package app.lawnchair.util

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR
import com.hoko.blur.HokoBlur
import java.util.concurrent.CopyOnWriteArrayList

object WallpaperBlurHelper {
    private const val TAG = "WallpaperBlurHelper"

    @Volatile
    var cachedBlurredBitmap: Bitmap? = null
        private set

    @Volatile
    private var isGenerating = false
    private var registeredReceiver = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun init(context: Context) {
        if (!registeredReceiver) {
            registeredReceiver = true
            try {
                val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        updateWallpaperBlur(context.applicationContext, force = true)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.applicationContext.registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_EXPORTED,
                    )
                } else {
                    context.applicationContext.registerReceiver(
                        receiver,
                        filter,
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to register wallpaper change receiver", e)
            }
        }
        updateWallpaperBlur(context.applicationContext, force = false)
    }

    fun updateWallpaperBlur(context: Context, force: Boolean = false) {
        if (cachedBlurredBitmap != null && !force) {
            return
        }
        if (isGenerating) {
            return
        }
        isGenerating = true

        UI_HELPER_EXECUTOR.execute {
            try {
                val wm = WallpaperManager.getInstance(context)
                var drawable: Drawable? = null
                try {
                    val wallpaperInfo = wm.wallpaperInfo
                    if (wallpaperInfo != null) {
                        drawable = wallpaperInfo.loadThumbnail(context.packageManager)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Cannot load wallpaperInfo thumbnail", t)
                }
                if (drawable == null) {
                    try {
                        drawable = wm.drawable
                    } catch (t: Throwable) {
                        Log.w(TAG, "Cannot get wm.drawable", t)
                    }
                }

                if (drawable != null) {
                    val dm = context.resources.displayMetrics
                    val screenWidth = dm.widthPixels
                    val screenHeight = dm.heightPixels

                    val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: screenWidth
                    val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: screenHeight

                    val sampleHeight = (screenHeight / 3).coerceAtLeast(100)
                    val aspect = intrinsicWidth.toFloat() / intrinsicHeight.toFloat()
                    val minWidth = (sampleHeight * (screenWidth.toFloat() / screenHeight.toFloat())).toInt()
                    val sampleWidth = ((sampleHeight * aspect).toInt()).coerceAtLeast(minWidth)

                    val scaledBitmap = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(scaledBitmap)
                    drawable.setBounds(0, 0, sampleWidth, sampleHeight)
                    drawable.draw(canvas)

                    var blurred: Bitmap? = null
                    try {
                        blurred = HokoBlur.with(context)
                            .forceCopy(true)
                            .scheme(HokoBlur.SCHEME_NATIVE)
                            .sampleFactor(2f)
                            .radius(25)
                            .blur(scaledBitmap)
                    } catch (e: Throwable) {
                        try {
                            blurred = HokoBlur.with(context)
                                .forceCopy(true)
                                .scheme(HokoBlur.SCHEME_OPENGL)
                                .sampleFactor(2f)
                                .radius(25)
                                .blur(scaledBitmap)
                        } catch (e2: Throwable) {
                            Log.e(TAG, "HokoBlur failed", e2)
                        }
                    }

                    if (blurred != null) {
                        cachedBlurredBitmap = blurred
                        for (listener in listeners) {
                            listener()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error generating blurred wallpaper", t)
            } finally {
                isGenerating = false
            }
        }
    }
}
