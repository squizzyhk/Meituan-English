package com.mtenglish.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import com.mtenglish.app.MtEnglishApp
import com.mtenglish.app.overlay.TranslateAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether our live-translation AccessibilityService is switched on in system settings. */
object AccessibilityStatus {
    fun isEnabled(context: Context): Boolean {
        val ours = ComponentName(context, TranslateAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { entry ->
            val cn = ComponentName.unflattenFromString(entry.trim())
            cn != null && cn == ours
        }
    }

    fun openSettings(context: Context): Boolean = startSafely(
        context,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) || startSafely(context, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The official apps the live overlay works with, in order of preference. */
object OfficialApps {
    data class App(val packageName: String, val englishName: String)

    val APPS = listOf(
        App("com.sankuai.meituan", "Meituan"),
        App("com.sankuai.meituan.takeoutnew", "Meituan Food Delivery"),
        App("com.dianping.v1", "Dianping"),
    )

    const val DOWNLOAD_PAGE = "https://www.meituan.com/"

    fun installed(context: Context): List<App> =
        APPS.filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null }

    /** Launches the first installed official app; returns its name, or null if none could be opened. */
    fun launchFirstInstalled(context: Context): App? {
        for (app in installed(context)) {
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: continue
            if (startSafely(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return app
        }
        return null
    }

    fun openDownloadPage(context: Context): Boolean = startSafely(
        context,
        Intent(Intent.ACTION_VIEW, DOWNLOAD_PAGE.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Offline (ML Kit) model download, shared by the home screen and Settings. Runs in an app-wide
 * scope so it survives rotation and leaving the screen; UI observes [state].
 */
object OfflineModelDownload {
    sealed class State {
        object Unknown : State()
        object NotDownloaded : State()
        object Downloading : State()
        object Ready : State()
        object Failed : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Re-reads whether the model is on the device (does nothing while a download runs). */
    fun refresh() {
        if (_state.value == State.Downloading) return
        scope.launch {
            val ready = try {
                MtEnglishApp.instance.engine.isOfflineModelDownloaded()
            } catch (_: Exception) {
                false
            }
            if (_state.value != State.Downloading) {
                _state.value = when {
                    ready -> State.Ready
                    _state.value == State.Failed -> State.Failed
                    else -> State.NotDownloaded
                }
            }
        }
    }

    fun start() {
        if (_state.value == State.Downloading) return
        _state.value = State.Downloading
        scope.launch {
            val ok = try {
                MtEnglishApp.instance.engine.downloadOfflineModel()
            } catch (_: Exception) {
                false
            }
            _state.value = if (ok) State.Ready else State.Failed
        }
    }
}

/** Masks an API key for display, e.g. "sk-ant-…abcd". */
object KeyMask {
    fun mask(key: String?): String? {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return null
        val tail = if (k.length > 8) k.takeLast(4) else ""
        val head = when {
            k.startsWith("sk-ant-") -> "sk-ant-"
            k.length > 8 -> k.take(3)
            else -> ""
        }
        return "$head…$tail".ifEmpty { "…" }.let { if (it == "…") "••••" else it }
    }
}

internal fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
