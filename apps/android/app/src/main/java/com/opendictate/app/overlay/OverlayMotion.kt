package com.opendictate.app.overlay

import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.DictationButtonPosition
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

internal object OverlayMotion {
    private const val WIDTH_DP = 52f
    private const val MENU_WIDTH_DP = 232f
    private const val ACTION_HEIGHT_DP = 52f
    private const val GAP_DP = 8f
    private const val VERTICAL_SHADOW_INSET_DP = 8f
    // The content stays 14 dp from the screen edge; the window reaches the edge.
    private const val HORIZONTAL_SHADOW_INSET_DP = 14f
    private const val CONTENT_END_MARGIN_DP = 14f
    private const val FIELD_CLEARANCE_DP = 8f
    private const val KEYBOARD_CLEARANCE_DP = 72f
    private const val TOP_MARGIN_DP = 32f

    fun primaryWindowWidthPx(density: Float, appearance: DictationButtonAppearance): Int =
        appearance.touchDiameterPx(density) + 2 * horizontalShadowInsetPx(density)

    fun primaryWindowHeightPx(density: Float, appearance: DictationButtonAppearance): Int =
        appearance.touchDiameterPx(density) + 2 * verticalShadowInsetPx(density)

    /** Physical left/right, independent of the language's layout direction. */
    fun windowLeftPx(displayWidthPx: Int, windowWidthPx: Int, position: DictationButtonPosition): Int =
        when (position) {
            DictationButtonPosition.LEFT -> 0
            DictationButtonPosition.RIGHT -> displayWidthPx - windowWidthPx
        }.coerceAtLeast(0)

    fun windowOffsetY(
        displayHeightPx: Int,
        keyboardTopPx: Int,
        focusedFieldTopPx: Int?,
        density: Float,
        showMenu: Boolean = true,
        showTransformation: Boolean = false,
        appearance: DictationButtonAppearance = DictationButtonAppearance(),
    ): Int {
        val fieldTop = focusedFieldTopPx?.takeIf { it in 0..keyboardTopPx }
        val anchorTop = fieldTop ?: keyboardTopPx
        val clearance = if (fieldTop != null) FIELD_CLEARANCE_DP else KEYBOARD_CLEARANCE_DP
        val preferredOffset = (displayHeightPx - anchorTop).coerceAtLeast(0) +
            dpToPx(clearance, density) - verticalShadowInsetPx(density)
        val maximumOffset = (
            displayHeightPx - (if (showMenu) windowHeightPx(density, showMenu, showTransformation)
                else primaryWindowHeightPx(density, appearance)) -
                dpToPx(TOP_MARGIN_DP, density)
        ).coerceAtLeast(0)
        return min(preferredOffset, maximumOffset)
    }

    fun heightPx(
        density: Float,
        showMenu: Boolean = true,
        showTransformation: Boolean = false,
    ): Int {
        val heightDp = if (showMenu) {
            ACTION_HEIGHT_DP * (if (showTransformation) 4 else 3) +
                GAP_DP * (if (showTransformation) 3 else 2)
        } else {
            ACTION_HEIGHT_DP
        }
        return dpToPx(heightDp, density)
    }

    fun widthPx(density: Float, showMenu: Boolean = false): Int = dpToPx(
        if (showMenu) MENU_WIDTH_DP else WIDTH_DP,
        density,
    )

    fun verticalShadowInsetPx(density: Float): Int =
        dpToPx(VERTICAL_SHADOW_INSET_DP, density)

    fun horizontalShadowInsetPx(density: Float): Int =
        dpToPx(HORIZONTAL_SHADOW_INSET_DP, density)

    fun windowWidthPx(density: Float, showMenu: Boolean = false): Int =
        widthPx(density, showMenu) + 2 * horizontalShadowInsetPx(density)

    fun windowOffsetX(density: Float): Int =
        dpToPx(CONTENT_END_MARGIN_DP, density) - horizontalShadowInsetPx(density)

    fun windowHeightPx(
        density: Float,
        showMenu: Boolean = true,
        showTransformation: Boolean = false,
    ): Int = heightPx(density, showMenu, showTransformation) +
        2 * verticalShadowInsetPx(density)

    fun menuHeightPx(
        density: Float,
        showTransformation: Boolean,
        showDictionary: Boolean = false,
    ): Int {
        val actions = 2 + (if (showTransformation) 1 else 0) + (if (showDictionary) 1 else 0)
        return actions * actionHeightPx(density) + (actions - 1) * gapPx(density)
    }

    fun menuWindowHeightPx(
        density: Float,
        showTransformation: Boolean,
        showDictionary: Boolean = false,
    ): Int = menuHeightPx(density, showTransformation, showDictionary) +
        2 * verticalShadowInsetPx(density)

    /** Places the menu beside the fixed button, above it when there is room. */
    fun menuWindowOffsetY(
        primaryOffsetY: Int,
        displayHeightPx: Int,
        density: Float,
        showTransformation: Boolean,
        showDictionary: Boolean = false,
        appearance: DictationButtonAppearance = DictationButtonAppearance(),
    ): Int {
        val menuHeight = menuHeightPx(density, showTransformation, showDictionary)
        val gap = gapPx(density)
        val aboveOffset = primaryOffsetY + appearance.touchDiameterPx(density) + gap
        val aboveTop = displayHeightPx - aboveOffset -
            menuWindowHeightPx(density, showTransformation, showDictionary)
        return if (aboveTop >= dpToPx(TOP_MARGIN_DP, density)) {
            aboveOffset
        } else {
            primaryOffsetY - menuHeight - gap
        }
    }

    fun actionHeightPx(density: Float): Int = dpToPx(ACTION_HEIGHT_DP, density)

    fun gapPx(density: Float): Int = dpToPx(GAP_DP, density)

    private fun dpToPx(dp: Float, density: Float): Int = (dp * density).toInt()
}

internal object OverlaySwipeToDismiss {
    // The overlay sits close to the right screen edge, so the threshold must be reachable from
    // the center of the 52 dp button without requiring the pointer to move beyond the display.
    private const val DISMISS_THRESHOLD_DP = 32f
    private const val MAX_DRAG_DP = 48f

    fun dragOffsetPx(downRawX: Float, currentRawX: Float, density: Float): Float =
        (currentRawX - downRawX).coerceIn(0f, MAX_DRAG_DP * density)

    fun isArmed(
        downRawX: Float,
        downRawY: Float,
        currentRawX: Float,
        currentRawY: Float,
        density: Float,
    ): Boolean {
        val horizontal = currentRawX - downRawX
        val vertical = abs(currentRawY - downRawY)
        return horizontal >= DISMISS_THRESHOLD_DP * density && horizontal >= vertical
    }

}

internal class OverlayVisibilitySession {
    private var dismissed = false

    fun dismiss() {
        dismissed = true
    }

    fun shouldShow(hasEligibleTarget: Boolean): Boolean {
        if (!hasEligibleTarget) dismissed = false
        return hasEligibleTarget && !dismissed
    }
}

internal object OverlaySwipeToCancel {
    private const val CANCEL_THRESHOLD_DP = 72f
    private const val MAX_DRAG_DP = 104f

    fun dragOffsetPx(downRawX: Float, currentRawX: Float, density: Float): Float =
        (downRawX - currentRawX).coerceIn(0f, MAX_DRAG_DP * density)

    fun isArmed(
        downRawX: Float,
        downRawY: Float,
        currentRawX: Float,
        currentRawY: Float,
        density: Float,
    ): Boolean {
        val horizontal = downRawX - currentRawX
        val vertical = abs(currentRawY - downRawY)
        return horizontal >= CANCEL_THRESHOLD_DP * density && horizontal >= vertical
    }

    fun progress(offsetPx: Float, density: Float): Float =
        (offsetPx / (CANCEL_THRESHOLD_DP * density)).coerceIn(0f, 1f)
}

/**
 * A critically damped position follower whose velocity survives target changes.
 *
 * Accessibility reports several intermediate keyboard bounds while the IME animates. Restarting
 * a conventional easing animation for every bound resets its velocity and makes the overlay look
 * like it is moving in a series of small starts and stops. This motion keeps both its rendered
 * position and velocity when a new bound arrives.
 */
internal class OverlayPositionMotion(initialPositionPx: Int) {
    var positionPx: Float = initialPositionPx.toFloat()
        private set

    var velocityPxPerSecond: Float = 0f
        private set

    var targetPositionPx: Int = initialPositionPx
        private set

    val isAtRest: Boolean
        get() = abs(positionPx - targetPositionPx) < REST_POSITION_EPSILON_PX &&
            abs(velocityPxPerSecond) < REST_VELOCITY_EPSILON_PX_PER_SECOND

    fun retarget(positionPx: Int) {
        targetPositionPx = positionPx
    }

    fun advanceByMillis(elapsedMillis: Long): Int {
        if (isAtRest) {
            positionPx = targetPositionPx.toFloat()
            velocityPxPerSecond = 0f
            return targetPositionPx
        }

        val elapsedSeconds = elapsedMillis
            .coerceIn(1L, MAX_FRAME_MILLIS)
            .toFloat() / 1_000f
        val target = targetPositionPx.toFloat()
        val displacement = positionPx - target
        val velocityTerm = velocityPxPerSecond + ANGULAR_FREQUENCY * displacement
        val decay = exp(-ANGULAR_FREQUENCY * elapsedSeconds)

        positionPx = target + (displacement + velocityTerm * elapsedSeconds) * decay
        velocityPxPerSecond = (
            velocityPxPerSecond - ANGULAR_FREQUENCY * velocityTerm * elapsedSeconds
        ) * decay

        if (isAtRest) {
            positionPx = target
            velocityPxPerSecond = 0f
        }
        return positionPx.roundToInt()
    }

    private companion object {
        const val ANGULAR_FREQUENCY = 30f
        const val MAX_FRAME_MILLIS = 64L
        const val REST_POSITION_EPSILON_PX = 0.5f
        const val REST_VELOCITY_EPSILON_PX_PER_SECOND = 5f
    }
}
