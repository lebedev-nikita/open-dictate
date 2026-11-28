package com.opendictate.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import com.opendictate.app.model.StatusBanner
import com.opendictate.app.model.StatusBannerSwipeDirection
import kotlin.math.roundToInt

/**
 * The accessibility overlay window that holds one [StatusBannerView] below the status bar. It
 * never takes focus, so the app underneath keeps its keyboard and field.
 */
class StatusBannerWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    onStop: () -> Unit,
    onSwipe: (StatusBannerSwipeDirection) -> Unit,
    onOpen: () -> Unit = {},
) {
    private val view = StatusBannerView(context, onStop, onSwipe, onOpen)
    private var attached = false

    var content: StatusBanner
        get() = view.content
        set(value) {
            view.content = value
        }

    /** Adds the window; false when Android refuses the overlay. */
    fun show(content: StatusBanner): Boolean {
        if (attached) return true
        view.content = content
        val density = context.resources.displayMetrics.density
        val maxWidth = ((StatusBannerView.MAX_CARD_WIDTH_DP + 2 * StatusBannerView.SHADOW_SIDE_DP) * density).roundToInt()
        val params = WindowManager.LayoutParams(
            minOf(displayWidthPx(), maxWidth),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topInsetPx() + ((TOP_MARGIN_DP - StatusBannerView.SHADOW_TOP_DP) * density).roundToInt()
        }
        return runCatching { windowManager.addView(view, params) }
            .onFailure { Log.w(TAG, "Could not add the status banner", it) }
            .onSuccess {
                attached = true
                view.appear()
            }.isSuccess
    }

    fun dismiss() {
        if (!attached) return
        attached = false
        view.disappear { runCatching { windowManager.removeView(view) } }
    }

    fun removeNow() {
        if (!attached) return
        attached = false
        view.animate().cancel()
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun displayWidthPx(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.currentWindowMetrics.bounds.width()
        else context.resources.displayMetrics.widthPixels

    private fun topInsetPx(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return (FALLBACK_STATUS_BAR_DP * context.resources.displayMetrics.density).roundToInt()
        return windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
    }

    private companion object {
        const val TAG = "OpenDictateBanner"
        const val TOP_MARGIN_DP = 8
        const val FALLBACK_STATUS_BAR_DP = 24
    }
}
