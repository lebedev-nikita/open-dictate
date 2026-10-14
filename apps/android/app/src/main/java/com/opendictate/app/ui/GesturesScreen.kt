package com.opendictate.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opendictate.app.R

@Composable
internal fun GesturesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.gestures_back))
                }
                Text(stringResource(R.string.gestures_title), Modifier.padding(start = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(16.dp),
                    color = SettingsSurface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))) {
                    Column {
                        GestureRow(ButtonGesture.HOLD, R.string.gestures_hold_title, R.string.gestures_hold_description)
                        GuideDivider()
                        GestureRow(ButtonGesture.SWIPE_LEFT, R.string.gestures_left_title, R.string.gestures_left_description)
                        GuideDivider()
                        GestureRow(ButtonGesture.SWIPE_RIGHT, R.string.gestures_right_title, R.string.gestures_right_description)
                    }
                }

                Text(stringResource(R.string.gestures_menu_title),
                    Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                MenuInstruction(R.string.overlay_last_dictation, R.string.gestures_retry_body)
                GuideDivider()
                MenuInstruction(R.string.overlay_paste_last, R.string.gestures_paste_body)
                GuideDivider()
                MenuInstruction(R.string.overlay_edit_text, R.string.gestures_edit_body)
                GuideDivider()
                MenuInstruction(R.string.overlay_add_to_dictionary, R.string.gestures_dictionary_body)
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun GestureRow(gesture: ButtonGesture, title: Int, description: Int) {
    Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ButtonIllustration(gesture, stringResource(description))
        Text(stringResource(title), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private enum class ButtonGesture { HOLD, SWIPE_LEFT, SWIPE_RIGHT }

/** Static pictures use the overlay's own drawables, size, colors and elevation. */
@Composable
private fun ButtonIllustration(gesture: ButtonGesture, description: String) {
    val outline = MaterialTheme.colorScheme.outline
    Box(Modifier.size(width = 116.dp, height = 72.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        if (gesture == ButtonGesture.HOLD) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(outline.copy(alpha = 0.25f), radius = 34.dp.toPx(), style = Stroke(1.dp.toPx()))
                    drawCircle(outline.copy(alpha = 0.5f), radius = 30.dp.toPx(), style = Stroke(1.dp.toPx()))
                }
                FloatingButtonPicture(R.drawable.ic_mic_chatgpt_24)
            }
        } else {
            val pointsLeft = gesture == ButtonGesture.SWIPE_LEFT
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pointsLeft) SwipeChevrons(pointsLeft = true)
                FloatingButtonPicture(if (pointsLeft) R.drawable.ic_stop_rounded_24 else R.drawable.ic_mic_chatgpt_24)
                if (!pointsLeft) SwipeChevrons(pointsLeft = false)
            }
        }
    }
}

@Composable
private fun SwipeChevrons(pointsLeft: Boolean) {
    val ink = SettingsMuted
    Canvas(Modifier.size(width = 44.dp, height = 44.dp)) {
        repeat(3) { index ->
            val offset = index * 14.dp.toPx()
            val tip = offset + (if (pointsLeft) 2.dp else 14.dp).toPx()
            val wing = offset + (if (pointsLeft) 14.dp else 2.dp).toPx()
            val emphasis = if (pointsLeft) 2 - index else index
            val opacity = when (emphasis) {
                2 -> 1f
                1 -> 0.55f
                else -> 0.25f
            }
            val chevron = Path().apply {
                moveTo(wing, 2.dp.toPx())
                lineTo(tip, size.height / 2)
                lineTo(wing, size.height - 2.dp.toPx())
            }
            drawPath(chevron, ink.copy(alpha = opacity),
                style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun FloatingButtonPicture(drawable: Int) {
    Surface(Modifier.size(52.dp), shape = CircleShape, color = Color.White, shadowElevation = 5.dp) {
        Box(contentAlignment = Alignment.Center) {
            Image(painterResource(drawable), contentDescription = null, modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(Color(0xFF080808)))
        }
    }
}

@Composable
private fun GuideDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), thickness = 0.5.dp)
}

@Composable
private fun MenuInstruction(title: Int, body: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
    }
}
