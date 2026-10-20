package app.lawnchair.gestures.handlers

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.widget.Toast
import androidx.core.content.getSystemService
import app.lawnchair.LawnchairLauncher
import app.lawnchair.util.ComponentKeySerializer
import app.lawnchair.util.IntentSerializer
import app.lawnchair.util.UserHandlerSerializer
import com.android.launcher3.R
import com.android.launcher3.util.ComponentKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class OpenAppGestureHandler(
    context: Context,
    private val target: OpenAppTarget,
) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        when (target) {
            is OpenAppTarget.App -> {
                val key = target.key
                launchComponent(launcher, key)
            }

            is OpenAppTarget.Activity -> {
                val key = target.key
                launchComponent(launcher, key)
            }

            is OpenAppTarget.Shortcut -> Unit
        }
    }

    companion object {
        fun launchComponent(context: Context, key: ComponentKey) {
            val launcherApps = context.getSystemService<LauncherApps>()
            val isMainUser = key.user == Process.myUserHandle()
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = key.componentName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            try {
                if (isMainUser) {
                    context.startActivity(intent)
                } else {
                    launcherApps?.startMainActivity(
                        key.componentName,
                        key.user,
                        null,
                        null,
                    ) ?: context.startActivity(intent)
                }
            } catch (e: Exception) {
                try {
                    launcherApps?.startMainActivity(
                        key.componentName,
                        key.user,
                        null,
                        null,
                    )
                } catch (e2: Exception) {
                    Toast.makeText(context, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Serializable
sealed class OpenAppTarget {
    @Serializable
    @SerialName("app")
    data class App(
        @Serializable(ComponentKeySerializer::class) val key: ComponentKey,
    ) : OpenAppTarget()

    @Serializable
    @SerialName("activity")
    data class Activity(
        @Serializable(ComponentKeySerializer::class) val key: ComponentKey,
    ) : OpenAppTarget()

    @Serializable
    @SerialName("shortcut")
    data class Shortcut(
        @Serializable(IntentSerializer::class) val intent: Intent,
        @Serializable(UserHandlerSerializer::class) val user: UserHandle,
        val packageName: String,
        val id: String,
    ) : OpenAppTarget()
}
