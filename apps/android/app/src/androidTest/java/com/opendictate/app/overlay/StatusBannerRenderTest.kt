package com.opendictate.app.overlay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.model.StatusBanner
import com.opendictate.app.model.StatusBannerKind
import com.opendictate.app.model.StatusBannerStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class StatusBannerRenderTest {
    @Test fun everyStageFitsOnACardAndOnlyACommandShowsItsWords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val capture = InstrumentationRegistry.getArguments().getString("captureOverlay") == "true"
        val words = mapOf(
            "en" to "Remind me to send the quarterly report to Anna tomorrow at five, and add it to my work list",
            "ru" to "Напомни завтра в пять отправить Ане квартальный отчёт и добавь это в рабочий список",
        )
        val outcomeTitle = mapOf("en" to "Todoist · add_task", "ru" to "Todoist · add_task")
        val outcome = mapOf(
            "en" to "Added the task “Send Anna the quarterly report” for tomorrow at 17:00 to the Work project.",
            "ru" to "Добавил задачу «Отправить Ане квартальный отчёт» на завтра, 17:00, в проект «Работа».",
        )
        instrumentation.runOnMainSync {
            for ((language, command) in words) {
                val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
                val context = instrumentation.targetContext.createConfigurationContext(configuration)
                val density = context.resources.displayMetrics.density
                val width = (412 * density).toInt()
                val contents = listOf(
                    StatusBanner.command(null),
                    StatusBanner.command(command),
                    StatusBanner.command(command, recorded = true),
                    StatusBanner.command(command, recorded = true, executing = true),
                    StatusBanner.result(outcomeTitle.getValue(language), outcome.getValue(language)),
                ) + listOf(false, true).flatMap { voiceEdit ->
                    listOf(false to false, true to false, true to true).map { (processing, textStage) ->
                        StatusBanner.field(voiceEdit, processing, textStage)
                    }
                }
                for (content in contents) {
                    val view = StatusBannerView(context, {}, {})
                    view.content = content
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, width, view.measuredHeight)
                    val label = "$language ${content.kind} ${content.stage} with ${if (content.text == null) "no" else "its"} words"
                    val maxHeight = if (content.text == null) 90 else 160
                    assertTrue("$label must stay compact: ${view.measuredHeight / density} dp", view.measuredHeight <= maxHeight * density)
                    // TalkBack reads the words once they stop changing.
                    val readsWords = content.text != null && content.stage != StatusBannerStage.LISTENING
                    assertEquals(label, readsWords, content.text?.let { view.contentDescription.toString().contains(it.take(12)) } == true)
                    val bitmap = Bitmap.createBitmap(width, view.measuredHeight, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply { drawColor(Color.rgb(16, 17, 20)) }.also(view::draw)
                    // The white card reaches close to the window's side, past its text and controls.
                    val edgeX = width - ((StatusBannerView.SHADOW_SIDE_DP + 5) * density).toInt()
                    val middle = bitmap.getPixel(edgeX, bitmap.height / 2 - (4 * density).toInt())
                    assertTrue("$label must draw a white card", Color.red(middle) > 200 && Color.green(middle) > 200)
                    if (capture) {
                        val textTag = if (content.text == null) "" else "-words"
                        val name = "banner-$language-${content.kind.name.lowercase()}-${content.stage.name.lowercase()}$textTag.png"
                        java.io.File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun dictationNeverCarriesWords() {
        for (kind in listOf(StatusBannerKind.DICTATION, StatusBannerKind.VOICE_EDIT)) for (stage in StatusBannerStage.entries) {
            assertEquals(null, StatusBanner(kind, stage).text)
        }
    }
}
