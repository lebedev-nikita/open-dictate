package com.opendictate.app.model

import kotlin.math.roundToInt

enum class DictationButtonPosition {
    LEFT, CENTER, RIGHT;

    companion object {
        fun fromStored(value: String?): DictationButtonPosition =
            entries.firstOrNull { it.name == value } ?: RIGHT
    }
}

data class DictationButtonAppearance(
    val diameterDp: Int = DEFAULT_DIAMETER_DP,
    val position: DictationButtonPosition = DictationButtonPosition.RIGHT,
) {
    init { require(diameterDp in MIN_DIAMETER_DP..MAX_DIAMETER_DP) }

    fun diameterPx(density: Float): Int = (diameterDp * density).roundToInt()

    fun touchDiameterPx(density: Float): Int = (diameterDp.coerceAtLeast(48) * density).roundToInt()

    // At the left edge cancellation moves inward and dismissal moves toward the edge.
    val gestureDirection: Int get() = if (position == DictationButtonPosition.LEFT) -1 else 1

    companion object {
        const val DEFAULT_DIAMETER_DP = 52
        const val MIN_DIAMETER_DP = 24
        const val MAX_DIAMETER_DP = 96

        fun fromStored(diameterDp: Int, position: String?) = DictationButtonAppearance(
            diameterDp.coerceIn(MIN_DIAMETER_DP, MAX_DIAMETER_DP),
            DictationButtonPosition.fromStored(position),
        )
    }
}
