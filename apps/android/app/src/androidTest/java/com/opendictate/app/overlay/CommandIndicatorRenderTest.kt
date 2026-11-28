package com.opendictate.app.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.model.CommandIndicatorStage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class CommandIndicatorRenderTest {
    @Test fun listeningShowsStopAndLaterStagesShowSpinnerAroundTheirIcon() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val capture = InstrumentationRegistry.getArguments().getString("captureOverlay") == "true"
        instrumentation.runOnMainSync {
            for (diameter in listOf(24, 52, 96)) {
                val view = CommandIndicatorView(context, diameter, {}, {})
                val size = view.windowSizePx
                view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, size, size)
                for (stage in CommandIndicatorStage.entries) for (background in listOf(Color.WHITE, Color.rgb(16, 17, 20))) {
                    view.stage = stage
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply { drawColor(background) }.also(view::draw)
                    val center = size / 2f
                    var ringPixels = 0
                    for (y in 0 until size) for (x in 0 until size) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.red(pixel) > 80 || Color.green(pixel) > 80 || Color.blue(pixel) > 80) continue
                        val radius = hypot(x + 0.5f - center, y + 0.5f - center)
                        val ring = diameter * density * 18f / 52f
                        if (radius in (ring - density)..(ring + 2.5f * density)) ringPixels++
                    }
                    val label = "$stage at $diameter dp over ${Integer.toHexString(background)}"
                    if (stage == CommandIndicatorStage.LISTENING) {
                        assertTrue("$label must show the stop icon without a ring", ringPixels < 4 * diameter * density / 52f)
                    } else {
                        assertTrue("$label must show the spinner ring: $ringPixels", ringPixels > 20 * diameter * density / 52f)
                    }
                    if (capture && diameter == 52) {
                        val name = "command-${stage.name.lowercase()}-${if (background == Color.WHITE) "light" else "dark"}.png"
                        java.io.File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    bitmap.recycle()
                }
            }
        }
    }
}
