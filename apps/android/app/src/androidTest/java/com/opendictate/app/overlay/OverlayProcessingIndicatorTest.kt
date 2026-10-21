package com.opendictate.app.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.DictationButtonPosition
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.service.DictationPhase
import com.opendictate.app.service.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class OverlayProcessingIndicatorTest {
    @Test fun standaloneSpinnerUsesOriginalCompactRadiusAndStagesKeepTheirLargerRing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        instrumentation.runOnMainSync {
            val overlay = DictationOverlayView(context, {}, {}, {}, {})
            for (diameter in listOf(24, 52, 96)) {
                overlay.setAppearance(DictationButtonAppearance(diameter, DictationButtonPosition.RIGHT))
                val button = overlay.getChildAt(0)
                val size = button.layoutParams.width
                button.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                button.layout(0, 0, size, size)
                val initialBounds = android.graphics.Rect(button.left, button.top, button.right, button.bottom)
                for (processed in listOf(false, true)) {
                    overlay.render(DictationState(sessionId = 1, phase = DictationPhase.PROCESSING,
                        model = TranscriptionModel.ACCURATE, postProcessingEnabled = processed))
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    button.draw(canvas)
                    val center = size / 2f
                    var outerRadius = 0f
                    var darkCenterPixels = 0
                    for (y in 0 until size) for (x in 0 until size) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) {
                            val radius = hypot(x + 0.5f - center, y + 0.5f - center)
                            outerRadius = maxOf(outerRadius, radius)
                            if (radius < diameter * density * 0.08f) darkCenterPixels++
                        }
                    }
                    val expectedRadius = diameter * density * (if (processed) 18f else 10f) / 52f
                    val strokeRadius = 1.25f * density
                    assertTrue("Ring radius must follow the visible content ($diameter dp, processing=$processed): $outerRadius",
                        outerRadius in (expectedRadius + strokeRadius - density)..(expectedRadius + strokeRadius + density))
                    if (!processed) assertEquals("Standalone spinner must leave its center empty", 0, darkCenterPixels)
                    assertEquals(initialBounds, android.graphics.Rect(button.left, button.top, button.right, button.bottom))
                    if (diameter == 52 && InstrumentationRegistry.getArguments().getString("captureOverlay") == "true") {
                        java.io.File(context.cacheDir, "spinner-${if (processed) "stage" else "compact"}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    bitmap.recycle()
                }
            }
        }
    }
}
