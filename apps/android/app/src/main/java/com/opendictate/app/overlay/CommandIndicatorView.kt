package com.opendictate.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.opendictate.app.R
import com.opendictate.app.model.CommandIndicatorStage
import com.opendictate.app.model.CommandIndicatorSwipe
import com.opendictate.app.model.DictationButtonAppearance
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The indicator of a spoken command: the white dictation button, alone at the top of the screen.
 * Ripples spread from it while it listens; a tap finishes the command and a swipe away cancels it.
 */
@SuppressLint("ViewConstructor")
class CommandIndicatorView(
    context: Context,
    diameterDp: Int,
    private val onStop: () -> Unit,
    private val onSwipeAway: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val diameterPx = diameterDp * density
    private val rippleSpreadPx = RIPPLE_SPREAD_DP * density

    /** The square window that holds the button and its ripples. */
    val windowSizePx: Int = (diameterPx + 2 * rippleSpreadPx).roundToInt()

    private val stopIcon = icon(R.drawable.ic_stop_rounded_24)
    private val closeIcon = icon(R.drawable.ic_close_rounded_24)
    private val transcriptionIcon = icon(R.drawable.ic_speech_to_text_rounded_24)
    private val executionIcon = icon(R.drawable.ic_auto_fix_high_rounded_24)
    private val button = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RIPPLE_COLOR }
    private val spinner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density
        color = INK
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var swipeProgress = 0f
    private var swipeArmed = false

    var stage: CommandIndicatorStage = CommandIndicatorStage.LISTENING
        set(value) {
            if (field == value) return
            field = value
            updateContentDescription()
            invalidate()
        }

    init {
        elevation = 5f * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = ((view.width - diameterPx) / 2f).roundToInt()
                outline.setOval(inset, inset, view.width - inset, view.height - inset)
            }
        }
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        updateContentDescription()
    }

    fun appear() {
        alpha = 0f
        scaleX = APPEAR_SCALE
        scaleY = APPEAR_SCALE
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    fun disappear(then: () -> Unit) {
        animate().alpha(0f).scaleX(APPEAR_SCALE).scaleY(APPEAR_SCALE).setDuration(140)
            .withEndAction(then).start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = diameterPx / 2f
        if (stage == CommandIndicatorStage.LISTENING && swipeProgress == 0f) drawRipples(canvas, centerX, centerY, radius)
        button.color = blend(Color.WHITE, SWIPE_COLOR, swipeProgress)
        canvas.drawCircle(centerX, centerY, radius, button)
        when {
            swipeProgress > 0f -> drawIcon(canvas, closeIcon, if (swipeArmed) Color.WHITE else INK)
            stage == CommandIndicatorStage.LISTENING -> drawIcon(canvas, stopIcon, INK)
            else -> {
                drawSpinner(canvas, centerX, centerY)
                drawIcon(canvas, if (stage == CommandIndicatorStage.TRANSCRIBING) transcriptionIcon else executionIcon, INK)
            }
        }
    }

    private fun drawRipples(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val time = SystemClock.uptimeMillis()
        repeat(RIPPLES) { index ->
            val phase = ((time + index * RIPPLE_PERIOD_MS / RIPPLES) % RIPPLE_PERIOD_MS).toFloat() / RIPPLE_PERIOD_MS
            ripple.alpha = ((1f - phase) * RIPPLE_MAX_ALPHA).roundToInt()
            canvas.drawCircle(centerX, centerY, radius + phase * rippleSpreadPx, ripple)
        }
        postInvalidateOnAnimation()
    }

    private fun drawSpinner(canvas: Canvas, centerX: Float, centerY: Float) {
        val radius = diameterPx * 18f / DictationButtonAppearance.DEFAULT_DIAMETER_DP
        val rotation = SystemClock.uptimeMillis() % SPINNER_PERIOD_MS * 360f / SPINNER_PERIOD_MS
        canvas.drawArc(centerX - radius, centerY - radius, centerX + radius, centerY + radius,
            rotation, SPINNER_SWEEP_DEGREES, false, spinner)
        postInvalidateOnAnimation()
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, color: Int) {
        val size = (diameterPx * 24f / DictationButtonAppearance.DEFAULT_DIAMETER_DP).roundToInt()
        val left = width / 2 - size / 2
        val top = height / 2 - size / 2
        icon.setTint(color)
        icon.setBounds(left, top, left + size, top + size)
        icon.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val wasArmed = swipeArmed
                swipeArmed = CommandIndicatorSwipe.isArmed(dx, dy, density)
                swipeProgress = if (CommandIndicatorSwipe.distancePx(dx, dy) > touchSlop) {
                    CommandIndicatorSwipe.progress(dx, dy, density)
                } else 0f
                if (swipeArmed && !wasArmed) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                val armed = swipeArmed
                val isTap = abs(event.rawX - downX) < touchSlop && abs(event.rawY - downY) < touchSlop
                resetSwipe()
                when {
                    armed -> onSwipeAway()
                    isTap -> performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                resetSwipe()
            }
        }
        return true
    }

    private fun resetSwipe() {
        swipeArmed = false
        swipeProgress = 0f
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (stage.acceptsStop) onStop()
        return true
    }

    private fun updateContentDescription() {
        contentDescription = context.getString(when (stage) {
            CommandIndicatorStage.LISTENING -> R.string.command_indicator_listening
            CommandIndicatorStage.TRANSCRIBING -> R.string.command_indicator_transcribing
            CommandIndicatorStage.EXECUTING -> R.string.command_indicator_executing
        })
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!stage.acceptsStop) info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.isClickable = stage.acceptsStop
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.accessibility_action_dismiss_overlay,
            context.getString(if (stage == CommandIndicatorStage.EXECUTING) R.string.command_indicator_hide else R.string.command_indicator_cancel)))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.accessibility_action_dismiss_overlay) {
            onSwipeAway()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun icon(resourceId: Int): Drawable = requireNotNull(ContextCompat.getDrawable(context, resourceId)).mutate()

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        fun channel(start: Int, end: Int): Int = (start + (end - start) * fraction).roundToInt()
        return Color.rgb(channel(Color.red(from), Color.red(to)), channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to)))
    }

    private companion object {
        val INK = Color.rgb(8, 8, 8)
        val SWIPE_COLOR = Color.rgb(38, 38, 38)
        // A neutral gray stays visible over both light and dark apps.
        val RIPPLE_COLOR = Color.rgb(138, 138, 138)
        const val RIPPLE_MAX_ALPHA = 110
        const val RIPPLE_SPREAD_DP = 16f
        const val RIPPLE_PERIOD_MS = 1_600L
        const val RIPPLES = 2
        const val APPEAR_SCALE = 0.7f
        const val SPINNER_PERIOD_MS = 900L
        const val SPINNER_SWEEP_DEGREES = 270f
    }
}
