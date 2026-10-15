package com.opendictate.app.overlay

import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.DictationButtonPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMotionTest {
    @Test fun `custom sizes stay on screen and menus clear the entire button`() {
        for (size in listOf(24, 48, 52, 96)) for (density in listOf(1f, 2.625f, 3f)) {
            val appearance = DictationButtonAppearance(size)
            val height = 2_400
            val offset = OverlayMotion.windowOffsetY(height, 80, null, density,
                showMenu = false, appearance = appearance)
            assertEquals((32 * density).toInt(), height - offset -
                OverlayMotion.primaryWindowHeightPx(density, appearance))
            val primaryOffset = 500
            val menuOffset = OverlayMotion.menuWindowOffsetY(primaryOffset, height, density,
                showTransformation = true, showDictionary = true, appearance = appearance)
            assertEquals(appearance.touchDiameterPx(density) + OverlayMotion.gapPx(density),
                menuOffset - primaryOffset)
            for (position in DictationButtonPosition.entries) {
                val width = OverlayMotion.primaryWindowWidthPx(density, appearance)
                val left = OverlayMotion.windowLeftPx(1_080, width, position)
                assertTrue(left >= 0 && left + width <= 1_080)
                when (position) {
                    DictationButtonPosition.LEFT -> assertEquals(0, left)
                    DictationButtonPosition.RIGHT -> assertEquals(1_080 - width, left)
                    DictationButtonPosition.CENTER -> assertTrue(kotlin.math.abs(1_080 - 2 * left - width) <= 1)
                }
            }
        }
    }

    @Test
    fun `window leaves room for shadows while keeping actions at original right edge`() {
        val density = 2f

        assertEquals(344, OverlayMotion.heightPx(density, showMenu = true))
        assertEquals(464, OverlayMotion.heightPx(density, showMenu = true, showTransformation = true))
        assertEquals(104, OverlayMotion.heightPx(density, showMenu = false))
        assertEquals(376, OverlayMotion.windowHeightPx(density, showMenu = true))
        assertEquals(496, OverlayMotion.windowHeightPx(density, showMenu = true, showTransformation = true))
        assertEquals(136, OverlayMotion.windowHeightPx(density, showMenu = false))
        assertEquals(104, OverlayMotion.widthPx(density))
        assertEquals(464, OverlayMotion.widthPx(density, showMenu = true))
        assertEquals(160, OverlayMotion.windowWidthPx(density))
        assertEquals(520, OverlayMotion.windowWidthPx(density, showMenu = true))
        assertEquals(0, OverlayMotion.windowOffsetX(density))
        val actionRightInset = OverlayMotion.windowOffsetX(density) +
            (OverlayMotion.windowWidthPx(density) - OverlayMotion.widthPx(density)) / 2
        assertEquals(28, actionRightInset)
    }

    @Test
    fun `overlay actions are square icon buttons`() {
        val density = 2f

        assertEquals(OverlayMotion.actionHeightPx(density), OverlayMotion.widthPx(density))
    }

    @Test
    fun `open menu widens the overlay without changing action height`() {
        val density = 2f

        assertEquals(464, OverlayMotion.widthPx(density, showMenu = true))
        assertEquals(344, OverlayMotion.heightPx(density, showMenu = true))
        assertEquals(104, OverlayMotion.actionHeightPx(density))
    }

    @Test
    fun `single action overlay can sit closer to the top edge`() {
        val offset = OverlayMotion.windowOffsetY(
            displayHeightPx = 600,
            keyboardTopPx = 80,
            focusedFieldTopPx = null,
            density = 2f,
            showMenu = false,
        )

        val windowTop = 600 - offset - OverlayMotion.windowHeightPx(
            density = 2f,
            showMenu = false,
        )
        assertEquals(64f, windowTop.toFloat(), 0.001f)
    }

    @Test
    fun `menu opens below a button near the top without moving the button`() {
        val density = 2f
        val displayHeight = 600
        val primaryOffset = OverlayMotion.windowOffsetY(
            displayHeightPx = displayHeight,
            keyboardTopPx = 80,
            focusedFieldTopPx = null,
            density = density,
            showMenu = false,
        )
        val menuOffset = OverlayMotion.menuWindowOffsetY(
            primaryOffsetY = primaryOffset,
            displayHeightPx = displayHeight,
            density = density,
            showTransformation = false,
        )
        val primaryBottom = displayHeight - primaryOffset -
            OverlayMotion.verticalShadowInsetPx(density)
        val menuTop = displayHeight - menuOffset -
            OverlayMotion.menuWindowHeightPx(density, false) +
            OverlayMotion.verticalShadowInsetPx(density)

        assertEquals(OverlayMotion.gapPx(density), menuTop - primaryBottom)
        assertEquals(OverlayMotion.widthPx(density), OverlayMotion.actionHeightPx(density))
        assertEquals(224, OverlayMotion.menuHeightPx(density, false))
    }

    @Test
    fun `menu opens above a button when there is room`() {
        val density = 3f
        val displayHeight = 2_400
        val primaryOffset = OverlayMotion.windowOffsetY(
            displayHeightPx = displayHeight,
            keyboardTopPx = 1_600,
            focusedFieldTopPx = 1_200,
            density = density,
            showMenu = false,
        )

        listOf(false, true).forEach { showTransformation ->
            val menuOffset = OverlayMotion.menuWindowOffsetY(
                primaryOffsetY = primaryOffset,
                displayHeightPx = displayHeight,
                density = density,
                showTransformation = showTransformation,
            )
            val primaryTop = displayHeight - primaryOffset -
                OverlayMotion.verticalShadowInsetPx(density) -
                OverlayMotion.actionHeightPx(density)
            val menuBottom = displayHeight - menuOffset -
                OverlayMotion.verticalShadowInsetPx(density)

            assertEquals(OverlayMotion.gapPx(density), primaryTop - menuBottom)
            assertTrue(
                displayHeight - menuOffset -
                    OverlayMotion.menuWindowHeightPx(density, showTransformation) >= 0,
            )
        }
    }

    @Test
    fun `dictionary action increases menu height by one action and gap`() {
        val density = 3f
        listOf(false, true).forEach { showTransformation ->
            assertEquals(
                OverlayMotion.menuHeightPx(density, showTransformation) +
                    OverlayMotion.actionHeightPx(density) + OverlayMotion.gapPx(density),
                OverlayMotion.menuHeightPx(density, showTransformation, showDictionary = true),
            )
        }
    }

    @Test
    fun `swipe to cancel follows leftward motion and clamps its travel`() {
        val density = 2f

        assertEquals(0f, OverlaySwipeToCancel.dragOffsetPx(500f, 540f, density), 0.001f)
        assertEquals(80f, OverlaySwipeToCancel.dragOffsetPx(500f, 420f, density), 0.001f)
        assertEquals(208f, OverlaySwipeToCancel.dragOffsetPx(500f, 100f, density), 0.001f)
    }

    @Test
    fun `swipe arms only after a deliberate mostly horizontal pull`() {
        val density = 2f

        assertFalse(OverlaySwipeToCancel.isArmed(500f, 300f, 357f, 300f, density))
        assertTrue(OverlaySwipeToCancel.isArmed(500f, 300f, 356f, 340f, density))
        assertFalse(OverlaySwipeToCancel.isArmed(500f, 300f, 356f, 460f, density))
    }

    @Test
    fun `swipe to dismiss follows rightward motion and clamps its travel`() {
        val density = 2f

        assertEquals(0f, OverlaySwipeToDismiss.dragOffsetPx(500f, 460f, density), 0.001f)
        assertEquals(80f, OverlaySwipeToDismiss.dragOffsetPx(500f, 580f, density), 0.001f)
        assertEquals(96f, OverlaySwipeToDismiss.dragOffsetPx(500f, 700f, density), 0.001f)
    }

    @Test
    fun `dismiss arms only after a deliberate mostly horizontal pull`() {
        val density = 2f

        assertFalse(OverlaySwipeToDismiss.isArmed(500f, 300f, 563f, 300f, density))
        assertTrue(OverlaySwipeToDismiss.isArmed(500f, 300f, 564f, 330f, density))
        assertFalse(OverlaySwipeToDismiss.isArmed(500f, 300f, 564f, 380f, density))
    }

    @Test
    fun `dismissal lasts only until the current keyboard target disappears`() {
        val session = OverlayVisibilitySession()

        assertTrue(session.shouldShow(hasEligibleTarget = true))
        session.dismiss()
        assertFalse(session.shouldShow(hasEligibleTarget = true))

        assertFalse(session.shouldShow(hasEligibleTarget = false))
        assertTrue(session.shouldShow(hasEligibleTarget = true))
    }

    @Test
    fun `retarget preserves position and velocity`() {
        val motion = OverlayPositionMotion(initialPositionPx = 800)
        motion.retarget(980)
        motion.advanceByMillis(32)

        val positionBeforeRetarget = motion.positionPx
        val velocityBeforeRetarget = motion.velocityPxPerSecond

        motion.retarget(1_070)

        assertEquals(positionBeforeRetarget, motion.positionPx, 0.001f)
        assertEquals(velocityBeforeRetarget, motion.velocityPxPerSecond, 0.001f)
    }

    @Test
    fun `successive keyboard positions produce continuous monotonic motion`() {
        val motion = OverlayPositionMotion(initialPositionPx = 800)
        val renderedPositions = mutableListOf(motion.positionPx)

        listOf(860, 940, 1_020, 1_070).forEach { keyboardPosition ->
            motion.retarget(keyboardPosition)
            repeat(3) {
                motion.advanceByMillis(16)
                renderedPositions += motion.positionPx
            }
        }
        repeat(120) {
            if (!motion.isAtRest) {
                motion.advanceByMillis(16)
                renderedPositions += motion.positionPx
            }
        }

        renderedPositions.zipWithNext().forEach { (previous, current) ->
            assertTrue("$current moved backwards from $previous", current >= previous)
        }
        assertTrue("motion did not settle", motion.isAtRest)
        assertEquals(1_070f, renderedPositions.last(), 0.001f)
    }

    @Test
    fun `closing keyboard produces continuous downward motion`() {
        val motion = OverlayPositionMotion(initialPositionPx = 1_070)
        val renderedPositions = mutableListOf(motion.positionPx)

        listOf(1_020, 940, 860, 800).forEach { keyboardPosition ->
            motion.retarget(keyboardPosition)
            repeat(3) {
                motion.advanceByMillis(16)
                renderedPositions += motion.positionPx
            }
        }
        repeat(120) {
            if (!motion.isAtRest) {
                motion.advanceByMillis(16)
                renderedPositions += motion.positionPx
            }
        }

        renderedPositions.zipWithNext().forEach { (previous, current) ->
            assertTrue("$current moved upwards from $previous", current <= previous)
        }
        assertTrue("motion did not settle", motion.isAtRest)
        assertEquals(800f, renderedPositions.last(), 0.001f)
    }

    @Test
    fun `button sits just above the focused field`() {
        val density = 3f
        val displayHeight = 2_400
        val keyboardTop = 1_600
        val fieldTop = 1_200

        val offset = OverlayMotion.windowOffsetY(
            displayHeightPx = displayHeight,
            keyboardTopPx = keyboardTop,
            focusedFieldTopPx = fieldTop,
            density = density,
        )
        val buttonBottom = displayHeight - offset - OverlayMotion.verticalShadowInsetPx(density)

        assertEquals(8f * density, (fieldTop - buttonBottom).toFloat(), 0.001f)
    }

    @Test
    fun `button falls back to clearing the keyboard when field bounds are unavailable`() {
        val density = 3f
        val displayHeight = 2_400
        val keyboardTop = 1_600

        val offset = OverlayMotion.windowOffsetY(
            displayHeightPx = displayHeight,
            keyboardTopPx = keyboardTop,
            focusedFieldTopPx = null,
            density = density,
        )
        val buttonBottom = displayHeight - offset - OverlayMotion.verticalShadowInsetPx(density)

        assertEquals(72f * density, (keyboardTop - buttonBottom).toFloat(), 0.001f)
    }

    @Test
    fun `button ignores field bounds below the keyboard`() {
        val density = 3f
        val displayHeight = 2_400
        val keyboardTop = 1_600

        val offset = OverlayMotion.windowOffsetY(
            displayHeightPx = displayHeight,
            keyboardTopPx = keyboardTop,
            focusedFieldTopPx = 1_700,
            density = density,
        )
        val buttonBottom = displayHeight - offset - OverlayMotion.verticalShadowInsetPx(density)

        assertEquals(72f * density, (keyboardTop - buttonBottom).toFloat(), 0.001f)
    }

    @Test
    fun `position keeps a top margin when vertical space is tight`() {
        val density = 2f
        val displayHeight = 500
        val keyboardTop = 260

        val offset = OverlayMotion.windowOffsetY(
            displayHeightPx = displayHeight,
            keyboardTopPx = keyboardTop,
            focusedFieldTopPx = 80,
            density = density,
        )
        val windowTop = displayHeight - offset - OverlayMotion.windowHeightPx(density)

        assertEquals(32f * density, windowTop.toFloat(), 0.001f)
    }
}
