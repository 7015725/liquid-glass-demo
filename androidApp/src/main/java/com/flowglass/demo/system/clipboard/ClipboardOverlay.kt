package com.flowglass.demo.system.clipboard

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.flowglass.demo.ui.resolveDark
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.system.exec.StepStatus
import com.flowglass.demo.system.exec.TextInputTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object ClipboardOverlay {
    private var window: OverlayWindow? = null
    private val insertScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return false
        val existing = window
        if (existing != null) {
            existing.refresh()
            return true
        }
        val created = OverlayWindow(
            app,
            canInsert = TextInputTask.hasFocusedEditable() || Root.findSu() != null,
        )
        return if (created.show()) {
            window = created
            true
        } else {
            false
        }
    }

    fun hide() {
        window?.hide()
        window = null
    }

    /**
     * Selection closes the picker so the previous app can regain input focus, then inserts the
     * entry at its caret. If no field accepts the text, preserve the old copy behavior.
     */
    fun insertText(context: Context, text: String) {
        val app = context.applicationContext
        hide()
        insertScope.launch {
            delay(250)
            val root = resolveRoot()
            if (!TextInputTask.hasFocusedInput(root)) {
                copyToClipboard(app, text)
                Toast.makeText(app, "未找到输入框，已复制", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val outcome = TextInputTask.run(app, text, root)
            if (outcome.status == StepStatus.SUCCESS) {
                Toast.makeText(app, "已输入到当前输入框", Toast.LENGTH_SHORT).show()
            } else {
                copyToClipboard(app, text)
                Toast.makeText(app, "未找到输入框，已复制", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun resolveRoot(): RootInfo? =
        runCatching { Root.check(request = false) }.getOrNull()?.takeIf { it.granted }

    private fun copyToClipboard(context: Context, text: String) {
        ClipboardArchive.suppressNext(text)
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("流光剪贴板", text))
    }
}

private class OverlayWindow(
    private val context: Context,
    private val canInsert: Boolean,
) :
    LifecycleOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val backDispatcher = OnBackPressedDispatcher()
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private val refreshTick = mutableStateOf(0)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

    fun refresh() {
        refreshTick.value += 1
    }

    fun show(): Boolean {
        val wm = windowManager ?: return false
        if (view != null) return true
        savedStateController.performRestore(null)
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        com.flowglass.demo.ui.C.dark = com.flowglass.demo.ui.AppearanceSettings.read(context)
            .resolveDark(
                (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES,
            )
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setViewTreeOnBackPressedDispatcherOwner(this@OverlayWindow)
            setContent {
                val store = androidx.compose.runtime.remember {
                    ClipboardHistoryStore(context)
                }
                ClipboardOverlayScreen(
                    store = store,
                    refreshTick = refreshTick.value,
                    canInsert = canInsert,
                    onClose = { ClipboardOverlay.hide() },
                    onCopy = { entry ->
                        ClipboardArchive.suppressNext(entry.content)
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(
                                android.content.ClipData.newPlainText("流光剪贴板", entry.content),
                            )
                    },
                    onInsert = { entry -> ClipboardOverlay.insertText(context, entry.content) },
                )
            }
        }
        return try {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            wm.addView(compose, layout)
            view = compose
            params = layout
            true
        } catch (_: Throwable) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            false
        }
    }

    fun hide() {
        val wm = windowManager ?: return
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
