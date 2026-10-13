package com.opendictate.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.opendictate.app.R
import com.opendictate.app.service.DictationOperation
import com.opendictate.app.service.DictationPhase
import com.opendictate.app.service.DictationState
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class DictationOverlayView(
    context: Context,
    onDictationClick: () -> Unit,
    onOperationCancel: () -> Unit,
    onDismiss: () -> Unit,
    onMenuToggle: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val dictationButton = OverlayPrimaryActionView(
        context = context,
        onClick = onDictationClick,
        onCancel = onOperationCancel,
        onDismiss = onDismiss,
        onMenuToggle = onMenuToggle,
    )

    init {
        gravity = Gravity.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setPadding(
            OverlayMotion.horizontalShadowInsetPx(density),
            OverlayMotion.verticalShadowInsetPx(density),
            OverlayMotion.horizontalShadowInsetPx(density),
            OverlayMotion.verticalShadowInsetPx(density),
        )
        clipChildren = false
        clipToPadding = false
        addView(
            dictationButton,
            LayoutParams(OverlayMotion.widthPx(density), OverlayMotion.actionHeightPx(density)),
        )
        setMenuState(expanded = false)
    }

    fun render(state: DictationState) = dictationButton.render(state)

    fun setMenuState(expanded: Boolean) {
        dictationButton.setMenuState(available = true, expanded = expanded)
    }
}

@SuppressLint("ViewConstructor")
class DictationOverlayMenuView(
    context: Context,
    onTransformationClick: () -> Unit,
    onAddToDictionaryClick: () -> Unit,
    onPasteLastClick: () -> Unit,
    onLastDictationClick: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var transformationAvailable: Boolean? = null
    private var dictionaryAvailable: Boolean? = null
    private val transformationButton = OverlayMenuActionView(
        context = context,
        onClick = onTransformationClick,
        iconResource = R.drawable.ic_auto_fix_high_rounded_24,
        labelResource = R.string.overlay_edit_text,
        descriptionResource = R.string.overlay_start_transformation,
    )
    private val pasteLastButton = OverlayMenuActionView(
        context = context,
        onClick = onPasteLastClick,
        iconResource = R.drawable.ic_content_paste_rounded_24,
        labelResource = R.string.overlay_paste_last,
        descriptionResource = R.string.overlay_paste_last_description,
    )
    private val dictionaryButton = OverlayMenuActionView(
        context = context,
        onClick = onAddToDictionaryClick,
        iconResource = R.drawable.ic_menu_book_24,
        labelResource = R.string.overlay_add_to_dictionary,
        descriptionResource = R.string.dictionary_add_action,
    )
    private val lastDictationButton = OverlayMenuActionView(
        context = context,
        onClick = onLastDictationClick,
        iconResource = R.drawable.ic_mic_chatgpt_24,
        labelResource = R.string.overlay_last_dictation,
        descriptionResource = R.string.overlay_last_dictation_description,
    )
    init {
        orientation = VERTICAL
        gravity = Gravity.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val horizontalShadowInset = OverlayMotion.horizontalShadowInsetPx(density)
        val verticalShadowInset = OverlayMotion.verticalShadowInsetPx(density)
        setPadding(
            horizontalShadowInset,
            verticalShadowInset,
            horizontalShadowInset,
            verticalShadowInset,
        )
        clipChildren = false
        clipToPadding = false
        addView(
            lastDictationButton,
            LayoutParams(
                OverlayMotion.widthPx(density, showMenu = true),
                OverlayMotion.actionHeightPx(density),
            ).apply { bottomMargin = OverlayMotion.gapPx(density) },
        )
        addView(
            pasteLastButton,
            LayoutParams(
                OverlayMotion.widthPx(density, showMenu = true),
                OverlayMotion.actionHeightPx(density),
            ),
        )
        addView(
            transformationButton,
            LayoutParams(
                OverlayMotion.widthPx(density, showMenu = true),
                OverlayMotion.actionHeightPx(density),
            ),
        )
        addView(
            dictionaryButton,
            LayoutParams(
                OverlayMotion.widthPx(density, showMenu = true),
                OverlayMotion.actionHeightPx(density),
            ),
        )
        setTransformationAvailable(false)
        setDictionaryAvailable(false)
    }

    fun render(state: DictationState) {
        lastDictationButton.render(state)
        transformationButton.render(state)
        pasteLastButton.render(state)
        dictionaryButton.render(state)
    }

    fun setTransformationAvailable(available: Boolean) {
        if (transformationAvailable == available) return
        transformationAvailable = available
        transformationButton.visibility = if (available) VISIBLE else GONE
        updateTrailingButtons()
    }

    fun setDictionaryAvailable(available: Boolean) {
        if (dictionaryAvailable == available) return
        dictionaryAvailable = available
        dictionaryButton.visibility = if (available) VISIBLE else GONE
        updateTrailingButtons()
    }

    private fun updateTrailingButtons() {
        (pasteLastButton.layoutParams as LayoutParams).apply {
            bottomMargin = if (transformationAvailable == true || dictionaryAvailable == true) {
                OverlayMotion.gapPx(density)
            } else 0
            pasteLastButton.layoutParams = this
        }
        (transformationButton.layoutParams as LayoutParams).apply {
            bottomMargin = if (transformationAvailable == true && dictionaryAvailable == true) {
                OverlayMotion.gapPx(density)
            } else 0
            transformationButton.layoutParams = this
        }
    }
}

@SuppressLint("ViewConstructor")
private class OverlayPrimaryActionView(
    context: Context,
    private val onClick: () -> Unit,
    private val onCancel: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onMenuToggle: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val idleIcon = icon(R.drawable.ic_mic_chatgpt_24)
    private val stopIcon = icon(R.drawable.ic_stop_rounded_24)
    private val closeIcon = icon(R.drawable.ic_close_rounded_24)
    private val dictationColor = Color.rgb(8, 8, 8)
    private val transformationColor = Color.rgb(8, 8, 8)
    private val idleBackgroundColor = Color.WHITE
    private val cancelBackgroundColor = Color.rgb(38, 38, 38)
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = idleBackgroundColor }
    private val spinner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density
    }
    private val bounds = RectF()
    private var phase = DictationPhase.IDLE
    private var operation = DictationOperation.DICTATION
    private var menuAvailable = false
    private var menuExpanded = false
    private var downX = 0f
    private var downY = 0f
    private var cancelDragOffsetPx = 0f
    private var dismissDragOffsetPx = 0f
    private var dragStartedActive = false
    private var cancelArmed = false
    private var dismissArmed = false
    private var longPressTriggered = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var dragReturnAnimator: ValueAnimator? = null
    private val longPressRunnable = Runnable {
        if (!isActionActive() && menuAvailable) {
            longPressTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            performLongClick()
        }
    }

    init {
        elevation = 5f * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateContentDescription()
    }

    fun render(state: DictationState) {
        if (state.isActive) {
            phase = state.phase
            operation = state.operation
        } else {
            phase = DictationPhase.IDLE
            operation = DictationOperation.DICTATION
        }
        if (!isActionActive() && cancelDragOffsetPx > 0f) settleDrags()
        updateContentDescription()
        invalidate()
    }

    fun setMenuState(available: Boolean, expanded: Boolean) {
        menuAvailable = available
        menuExpanded = available && expanded
        isLongClickable = menuAvailable
        updateContentDescription()
        invalidate()
    }

    private fun updateContentDescription() {
        contentDescription = context.getString(
            when {
                phase.isProcessing &&
                    operation == DictationOperation.TRANSFORMATION ->
                    R.string.overlay_transforming_description
                phase.isProcessing -> R.string.overlay_processing_description
                isActionActive() && operation == DictationOperation.TRANSFORMATION ->
                    R.string.overlay_active_transformation_description
                isActionActive() -> R.string.overlay_active_dictation_description
                menuExpanded -> R.string.overlay_close_menu
                menuAvailable -> R.string.overlay_start_dictation_with_actions
                else -> R.string.overlay_start_dictation_with_dismiss
            },
        )
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        dragReturnAnimator?.cancel()
        dragReturnAnimator = null
        resetGesture()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cancelProgress = OverlaySwipeToCancel.progress(cancelDragOffsetPx, density)
        background.color = blendColor(idleBackgroundColor, cancelBackgroundColor, cancelProgress)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, height / 2f, height / 2f, background)
        val active = isActionActive()
        val isDraggingToCancel = active && cancelDragOffsetPx > touchSlop
        when {
            isDraggingToCancel -> drawIcon(
                canvas,
                closeIcon,
                if (cancelArmed) Color.WHITE else Color.rgb(8, 8, 8),
            )
            phase.isProcessing -> drawSpinner(canvas)
            active -> drawIcon(canvas, stopIcon, activeColor())
            menuExpanded -> drawIcon(canvas, closeIcon, dictationColor)
            else -> drawIcon(canvas, idleIcon, dictationColor)
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, color: Int) {
        val size = (24f * density).roundToInt()
        val centerX = width / 2
        val centerY = height / 2
        val left = centerX - size / 2
        val top = centerY - size / 2
        icon.setTint(color)
        icon.setBounds(left, top, left + size, top + size)
        icon.draw(canvas)
    }

    private fun drawSpinner(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = 10f * density
        val rotation = SystemClock.uptimeMillis() % SPINNER_PERIOD_MS *
            360f / SPINNER_PERIOD_MS
        spinner.color = activeColor()
        canvas.drawArc(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
            rotation,
            SPINNER_SWEEP_DEGREES,
            false,
            spinner,
        )
        postInvalidateOnAnimation()
    }

    private fun icon(resourceId: Int): Drawable = requireNotNull(
        ContextCompat.getDrawable(context, resourceId),
    ).mutate()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragReturnAnimator?.cancel()
                removeCallbacks(longPressRunnable)
                downX = event.rawX
                downY = event.rawY
                dragStartedActive = isActionActive()
                cancelArmed = false
                dismissArmed = false
                longPressTriggered = false
                if (!dragStartedActive && menuAvailable) {
                    postDelayed(longPressRunnable, longPressTimeout)
                }
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val horizontalDistance = abs(event.rawX - downX)
                val verticalDistance = abs(event.rawY - downY)
                if (horizontalDistance >= touchSlop || verticalDistance >= touchSlop) {
                    removeCallbacks(longPressRunnable)
                }
                if (dragStartedActive) {
                    updateCancelDrag(event)
                } else {
                    updateDismissDrag(event)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                val shouldCancel = dragStartedActive && cancelArmed
                val shouldDismiss = !dragStartedActive && dismissArmed
                val isTap = !longPressTriggered &&
                    abs(event.rawX - downX) < touchSlop &&
                    abs(event.rawY - downY) < touchSlop
                resetGesture(keepOffsets = true)
                when {
                    shouldCancel -> {
                        settleDrags()
                        onCancel()
                    }
                    shouldDismiss -> onDismiss()
                    isTap -> {
                        settleDrags()
                        performClick()
                    }
                    else -> settleDrags()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                resetGesture(keepOffsets = true)
                settleDrags()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateCancelDrag(event: MotionEvent) {
        val nextOffset = OverlaySwipeToCancel.dragOffsetPx(
            downRawX = downX,
            currentRawX = event.rawX,
            density = density,
        )
        val wasArmed = cancelArmed
        cancelArmed = OverlaySwipeToCancel.isArmed(
            downRawX = downX,
            downRawY = downY,
            currentRawX = event.rawX,
            currentRawY = event.rawY,
            density = density,
        )
        setCancelDragOffset(nextOffset)
        if (!wasArmed && cancelArmed) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun updateDismissDrag(event: MotionEvent) {
        val nextOffset = OverlaySwipeToDismiss.dragOffsetPx(
            downRawX = downX,
            currentRawX = event.rawX,
            density = density,
        )
        val wasArmed = dismissArmed
        dismissArmed = OverlaySwipeToDismiss.isArmed(
            downRawX = downX,
            downRawY = downY,
            currentRawX = event.rawX,
            currentRawY = event.rawY,
            density = density,
        )
        setDismissDragOffset(nextOffset)
        if (!wasArmed && dismissArmed) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (menuExpanded && !isActionActive()) {
            onMenuToggle()
        } else {
            onClick()
        }
        return true
    }

    override fun performLongClick(): Boolean {
        if (isActionActive() || !menuAvailable) return false
        super.performLongClick()
        onMenuToggle()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (isActionActive()) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    cancelAccessibilityActionId(),
                    context.getString(cancelAccessibilityLabelId()),
                ),
            )
        } else {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.accessibility_action_dismiss_overlay,
                    context.getString(R.string.overlay_hide_for_now),
                ),
            )
            if (menuAvailable) {
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        R.id.accessibility_action_toggle_overlay_menu,
                        context.getString(
                            if (menuExpanded) {
                                R.string.overlay_close_menu
                            } else {
                                R.string.overlay_open_menu
                            },
                        ),
                    ),
                )
            }
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when {
            action == cancelAccessibilityActionId() && isActionActive() -> {
                onCancel()
                return true
            }
            action == R.id.accessibility_action_dismiss_overlay && !isActionActive() -> {
                onDismiss()
                return true
            }
            action == R.id.accessibility_action_toggle_overlay_menu &&
                !isActionActive() && menuAvailable -> {
                onMenuToggle()
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun cancelAccessibilityActionId(): Int =
        if (operation == DictationOperation.TRANSFORMATION) {
            R.id.accessibility_action_cancel_transformation
        } else {
            R.id.accessibility_action_cancel_dictation
        }

    private fun cancelAccessibilityLabelId(): Int =
        if (operation == DictationOperation.TRANSFORMATION) {
            R.string.overlay_cancel_transformation
        } else {
            R.string.overlay_cancel_dictation
        }

    private fun isActionActive(): Boolean = phase.isActive

    private fun activeColor(): Int = if (operation == DictationOperation.TRANSFORMATION) {
        transformationColor
    } else {
        dictationColor
    }

    private fun setCancelDragOffset(value: Float) {
        if (cancelDragOffsetPx == value) return
        cancelDragOffsetPx = value
        invalidate()
    }

    private fun setDismissDragOffset(value: Float) {
        if (dismissDragOffsetPx == value) return
        dismissDragOffsetPx = value
        translationX = value
    }

    private fun settleDrags() {
        dragReturnAnimator?.cancel()
        if (cancelDragOffsetPx == 0f && dismissDragOffsetPx == 0f) return
        val initialCancelOffset = cancelDragOffsetPx
        val initialDismissOffset = dismissDragOffsetPx
        dragReturnAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                cancelDragOffsetPx = initialCancelOffset * fraction
                dismissDragOffsetPx = initialDismissOffset * fraction
                translationX = dismissDragOffsetPx
                invalidate()
            }
            start()
        }
    }

    private fun resetGesture(keepOffsets: Boolean = false) {
        if (!keepOffsets) {
            cancelDragOffsetPx = 0f
            dismissDragOffsetPx = 0f
            translationX = 0f
        }
        dragStartedActive = false
        cancelArmed = false
        dismissArmed = false
        longPressTriggered = false
    }

    private fun blendColor(from: Int, to: Int, fraction: Float): Int {
        fun channel(start: Int, end: Int): Int = (start + (end - start) * fraction).roundToInt()
        return Color.rgb(
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to)),
        )
    }

    private companion object {
        const val SPINNER_PERIOD_MS = 900L
        const val SPINNER_SWEEP_DEGREES = 270f
    }
}

@SuppressLint("ViewConstructor")
private class OverlayMenuActionView(
    context: Context,
    onClick: () -> Unit,
    iconResource: Int,
    labelResource: Int,
    descriptionResource: Int,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val icon = requireNotNull(
        ContextCompat.getDrawable(context, iconResource),
    ).mutate()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(8, 8, 8)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            14f,
            resources.displayMetrics,
        )
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bounds = RectF()
    private val label = context.getString(labelResource)
    private var renderedLabel: CharSequence = label

    init {
        elevation = 5f * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 14f * density)
            }
        }
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(descriptionResource)
        setOnClickListener { onClick() }
    }

    fun render(state: DictationState) {
        isEnabled = !state.isActive
        alpha = if (isEnabled) 1f else 0.46f
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val availableTextWidth = width -
            (MENU_START_PADDING_DP + ICON_SIZE_DP + MENU_ICON_GAP_DP + MENU_END_PADDING_DP) * density
        renderedLabel = TextUtils.ellipsize(
            label,
            labelPaint,
            availableTextWidth.coerceAtLeast(0f),
            TextUtils.TruncateAt.END,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        background.color = if (isPressed) {
            Color.rgb(227, 227, 227)
        } else {
            Color.WHITE
        }
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, 14f * density, 14f * density, background)

        val iconSize = (ICON_SIZE_DP * density).roundToInt()
        val iconLeft = (MENU_START_PADDING_DP * density).roundToInt()
        val iconTop = (height - iconSize) / 2
        icon.setTint(Color.rgb(8, 8, 8))
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        icon.draw(canvas)

        val textX = (MENU_START_PADDING_DP + ICON_SIZE_DP + MENU_ICON_GAP_DP) * density
        val metrics = labelPaint.fontMetrics
        val textY = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(renderedLabel, 0, renderedLabel.length, textX, textY, labelPaint)
    }

    private companion object {
        const val MENU_START_PADDING_DP = 16f
        const val MENU_END_PADDING_DP = 16f
        const val MENU_ICON_GAP_DP = 12f
        const val ICON_SIZE_DP = 24f
    }
}
