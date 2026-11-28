package com.opendictate.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.opendictate.app.R
import com.opendictate.app.model.StatusBanner
import com.opendictate.app.model.StatusBannerKind
import com.opendictate.app.model.StatusBannerStage
import com.opendictate.app.model.StatusBannerSwipe
import com.opendictate.app.model.StatusBannerSwipeAction
import com.opendictate.app.model.StatusBannerSwipeDirection
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The banner at the top of the screen: a white card with the session's badge, its stage and, for a
 * spoken command, the command's words and then its outcome. A tap finishes the recording or opens
 * the outcome; what a swipe up or aside does depends on the stage ([StatusBanner.onSwipe]).
 */
@SuppressLint("ViewConstructor")
class StatusBannerView(
    context: Context,
    private val onStop: () -> Unit,
    private val onSwipe: (StatusBannerSwipeDirection) -> Unit,
    private val onOpen: () -> Unit = {},
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val badge = StatusBadgeView(context)
    private val title = TextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val body = TextView(context).apply {
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(2f * density, 1f)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        visibility = GONE
    }
    private val stopButton = ImageView(context).apply {
        setImageDrawable(icon(R.drawable.ic_stop_rounded_24).apply { setTint(INK) })
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(STOP_BACKGROUND)
        }
        val inset = dp(7)
        setPadding(inset, inset, inset, inset)
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = CORNER_DP * density
        }
        elevation = 6f * density
        minimumHeight = dp(MIN_HEIGHT_DP)
        setPadding(dp(4), dp(4), dp(12), dp(4))
    }
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var swipeArmed = false

    var content: StatusBanner = StatusBanner(StatusBannerKind.COMMAND, StatusBannerStage.LISTENING)
        set(value) {
            if (field == value) return
            field = value
            render()
        }

    init {
        clipChildren = false
        clipToPadding = false
        setPadding(dp(SHADOW_SIDE_DP), dp(SHADOW_TOP_DP), dp(SHADOW_SIDE_DP), dp(SHADOW_BOTTOM_DP))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(2) })
        }
        card.addView(badge, LinearLayout.LayoutParams(dp(BADGE_DP), dp(BADGE_DP)))
        card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        card.addView(stopButton, LinearLayout.LayoutParams(dp(STOP_DP), dp(STOP_DP)).apply { marginStart = dp(10) })
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        for (child in listOf(card, badge, title, body, stopButton)) child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        render()
    }

    fun appear() {
        card.alpha = 0f
        card.translationY = -dp(16).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    fun disappear(then: () -> Unit) {
        card.animate().alpha(0f).translationY(card.translationY - dp(16)).setDuration(150).withEndAction(then).start()
    }

    private fun render() {
        val text = content.text
        title.text = titleText()
        title.setTextColor(if (text == null) INK else MUTED)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (text == null) 15f else 12.5f)
        title.typeface = if (text == null) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        body.text = text
        // An outcome reads from its start, so it gets a line more than a command's latest words.
        body.maxLines = if (content.stage == StatusBannerStage.RESULT) 4 else 3
        body.visibility = if (text == null) GONE else VISIBLE
        stopButton.visibility = if (content.acceptsStop) VISIBLE else GONE
        badge.render(content)
        updateContentDescription()
    }

    private fun updateContentDescription() {
        val hint = when {
            content.acceptsStop -> R.string.status_banner_hint_stop
            content.stage == StatusBannerStage.RESULT -> R.string.status_banner_hint_result
            content.onSwipe(StatusBannerSwipeDirection.UP) == StatusBannerSwipeAction.TO_NOTIFICATION -> R.string.status_banner_hint_to_notification
            else -> R.string.status_banner_hint_cancel
        }
        // Words that change while the command is spoken would interrupt TalkBack, so only final ones are read.
        val words = content.text?.takeUnless { content.stage == StatusBannerStage.LISTENING }
        contentDescription = listOfNotNull(titleText(), words, context.getString(hint)).joinToString(". ")
    }

    private fun titleText(): String = content.resultTitle ?: context.getString(titleOf(content))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                card.animate().cancel()
                card.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE).setDuration(70).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && StatusBannerSwipe.distancePx(dx, dy) > touchSlop) dragging = true
                if (dragging) followFinger(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                val armed = swipeArmed
                val isTap = !dragging && abs(event.rawX - downX) < touchSlop && abs(event.rawY - downY) < touchSlop
                if (armed) flingAway(event.rawX - downX, event.rawY - downY) else settle()
                if (isTap) performClick()
            }
            MotionEvent.ACTION_CANCEL -> settle()
        }
        return true
    }

    private fun followFinger(dx: Float, dy: Float) {
        val sideways = StatusBannerSwipe.isSideways(dx, dy)
        card.translationX = if (sideways) dx else 0f
        card.translationY = if (sideways) 0f else dy.coerceAtMost(0f)
        val progress = StatusBannerSwipe.progress(dx, dy, density)
        card.alpha = 1f - FADED_ALPHA * progress
        val wasArmed = swipeArmed
        swipeArmed = StatusBannerSwipe.isArmed(dx, dy, density)
        if (swipeArmed && !wasArmed) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun flingAway(dx: Float, dy: Float) {
        swipeArmed = false
        dragging = false
        val animation = card.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(140)
        if (StatusBannerSwipe.isSideways(dx, dy)) animation.translationX(if (dx < 0) -width.toFloat() else width.toFloat())
        else animation.translationY(-card.height.toFloat())
        animation.start()
        onSwipe(StatusBannerSwipe.direction(dx, dy))
    }

    private fun settle() {
        swipeArmed = false
        dragging = false
        card.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (content.acceptsStop) onStop() else if (content.stage == StatusBannerStage.RESULT) onOpen()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val clickable = content.acceptsStop || content.stage == StatusBannerStage.RESULT
        if (!clickable) info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.isClickable = clickable
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.accessibility_action_dismiss_overlay,
            context.getString(actionLabel(content.onSwipe(StatusBannerSwipeDirection.UP)))))
        if (content.onSwipe(StatusBannerSwipeDirection.SIDEWAYS) != content.onSwipe(StatusBannerSwipeDirection.UP)) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.accessibility_action_close_banner,
                context.getString(actionLabel(content.onSwipe(StatusBannerSwipeDirection.SIDEWAYS)))))
        }
    }

    private fun actionLabel(action: StatusBannerSwipeAction): Int = when (action) {
        StatusBannerSwipeAction.CANCEL -> R.string.status_banner_cancel
        StatusBannerSwipeAction.TO_NOTIFICATION -> R.string.status_banner_to_notification
        StatusBannerSwipeAction.CLOSE -> R.string.status_banner_close
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            R.id.accessibility_action_dismiss_overlay -> onSwipe(StatusBannerSwipeDirection.UP)
            R.id.accessibility_action_close_banner -> onSwipe(StatusBannerSwipeDirection.SIDEWAYS)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun icon(resourceId: Int): Drawable = requireNotNull(ContextCompat.getDrawable(context, resourceId)).mutate()

    companion object {
        /** A phone-wide card; wider screens keep it at a readable width. */
        const val MAX_CARD_WIDTH_DP = 480
        const val SHADOW_SIDE_DP = 12
        const val SHADOW_TOP_DP = 4
        const val SHADOW_BOTTOM_DP = 14
        private const val MIN_HEIGHT_DP = 52
        private const val CORNER_DP = 22f
        private const val BADGE_DP = 44
        private const val STOP_DP = 32
        private const val PRESSED_SCALE = 0.98f
        private const val FADED_ALPHA = 0.6f
        val INK = Color.rgb(8, 8, 8)
        private val MUTED = Color.rgb(110, 110, 110)
        private val STOP_BACKGROUND = Color.rgb(238, 238, 238)

        fun titleOf(content: StatusBanner): Int = when (content.kind) {
            StatusBannerKind.COMMAND -> when (content.stage) {
                StatusBannerStage.LISTENING -> R.string.status_banner_command_listening
                StatusBannerStage.TRANSCRIBING -> R.string.status_banner_command_transcribing
                StatusBannerStage.PROCESSING -> R.string.assistant_running
                StatusBannerStage.RESULT -> R.string.assistant_channel
            }
            StatusBannerKind.DICTATION -> when (content.stage) {
                StatusBannerStage.LISTENING -> R.string.status_banner_dictation_listening
                StatusBannerStage.TRANSCRIBING -> R.string.status_banner_transcribing
                StatusBannerStage.PROCESSING, StatusBannerStage.RESULT -> R.string.status_banner_postprocessing
            }
            StatusBannerKind.VOICE_EDIT -> when (content.stage) {
                StatusBannerStage.LISTENING -> R.string.status_banner_edit_listening
                StatusBannerStage.TRANSCRIBING -> R.string.status_banner_transcribing
                StatusBannerStage.PROCESSING, StatusBannerStage.RESULT -> R.string.status_banner_editing
            }
        }
    }
}

/** A black disc with the session's icon: it pulses while listening, spins while working, and rests on an outcome. */
private class StatusBadgeView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StatusBannerView.INK }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StatusBannerView.INK }
    private val spinner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val icons = mutableMapOf<Int, Drawable>()
    private var iconResource = R.drawable.ic_agent_command_24
    private var listening = true
    private var working = false

    fun render(content: StatusBanner) {
        listening = content.stage == StatusBannerStage.LISTENING
        working = content.stage == StatusBannerStage.TRANSCRIBING || content.stage == StatusBannerStage.PROCESSING
        iconResource = when {
            content.stage == StatusBannerStage.TRANSCRIBING -> R.drawable.ic_speech_to_text_rounded_24
            content.kind == StatusBannerKind.COMMAND -> R.drawable.ic_agent_command_24
            content.kind == StatusBannerKind.DICTATION && listening -> R.drawable.ic_mic_chatgpt_24
            else -> R.drawable.ic_auto_fix_high_rounded_24
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = DISC_DP * density / 2f
        val time = SystemClock.uptimeMillis()
        if (listening) {
            val phase = (time % PULSE_PERIOD_MS).toFloat() / PULSE_PERIOD_MS
            halo.alpha = ((1f - phase) * HALO_MAX_ALPHA).roundToInt()
            canvas.drawCircle(centerX, centerY, radius + phase * (width / 2f - radius), halo)
        }
        canvas.drawCircle(centerX, centerY, radius, disc)
        val iconSize = ((if (working) 14 else 18) * density).roundToInt()
        val icon = icons.getOrPut(iconResource) { requireNotNull(ContextCompat.getDrawable(context, iconResource)).mutate() }
        icon.setTint(Color.WHITE)
        val left = (centerX - iconSize / 2f).roundToInt()
        val top = (centerY - iconSize / 2f).roundToInt()
        icon.setBounds(left, top, left + iconSize, top + iconSize)
        icon.draw(canvas)
        if (working) {
            val ring = SPINNER_DP * density / 2f
            val rotation = time % SPINNER_PERIOD_MS * 360f / SPINNER_PERIOD_MS
            canvas.drawArc(centerX - ring, centerY - ring, centerX + ring, centerY + ring, rotation, SPINNER_SWEEP_DEGREES, false, spinner)
        }
        if (isAttachedToWindow && (listening || working)) postInvalidateOnAnimation()
    }

    private companion object {
        const val DISC_DP = 34f
        const val SPINNER_DP = 24f
        const val HALO_MAX_ALPHA = 46
        const val PULSE_PERIOD_MS = 1_400L
        const val SPINNER_PERIOD_MS = 900L
        const val SPINNER_SWEEP_DEGREES = 270f
    }
}
