@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import android.text.Annotation
import android.text.Spanned
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.opendictate.app.R

@Composable
internal fun GesturesSheet(onDismiss: () -> Unit, mirrorGestures: Boolean = false) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SettingsSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gestures_title),
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.gestures_close), tint = SettingsMuted)
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = SettingsInset, contentColor = SettingsText) {
                Column {
                    GestureRow(ButtonGesture.HOLD, R.string.gestures_hold_title, R.string.gestures_hold_description)
                    GuideDivider()
                    GestureRow(if (mirrorGestures) ButtonGesture.SWIPE_RIGHT else ButtonGesture.SWIPE_LEFT,
                        if (mirrorGestures) R.string.gestures_cancel_right_title else R.string.gestures_left_title,
                        if (mirrorGestures) R.string.gestures_cancel_right_description else R.string.gestures_left_description, active = true)
                    GuideDivider()
                    GestureRow(if (mirrorGestures) ButtonGesture.SWIPE_LEFT else ButtonGesture.SWIPE_RIGHT,
                        if (mirrorGestures) R.string.gestures_dismiss_left_title else R.string.gestures_right_title,
                        if (mirrorGestures) R.string.gestures_dismiss_left_description else R.string.gestures_right_description)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_done))
                }
            }
        }
    }
}

@Composable
private fun GestureRow(gesture: ButtonGesture, title: Int, description: Int, active: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ButtonIllustration(gesture, stringResource(description), active)
        Text(gestureTitleResource(title), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun gestureTitleResource(title: Int) = buildAnnotatedString {
    val text = LocalResources.current.getText(title)
    append(text.toString())
    if (text is Spanned) {
        text.getSpans(0, text.length, Annotation::class.java)
            .filter { it.key == "gesture" }
            .forEach { annotation ->
                addStyle(SpanStyle(fontWeight = FontWeight.Bold),
                    text.getSpanStart(annotation), text.getSpanEnd(annotation))
            }
    }
}

private enum class ButtonGesture { HOLD, SWIPE_LEFT, SWIPE_RIGHT }

private const val ILLUSTRATION_SCALE = 0.75f

/** Static pictures reuse the overlay's drawables at a reduced tutorial scale. */
@Composable
private fun ButtonIllustration(gesture: ButtonGesture, description: String, active: Boolean) {
    val outline = MaterialTheme.colorScheme.outline
    Box(Modifier.size(width = 116.dp * ILLUSTRATION_SCALE, height = 72.dp * ILLUSTRATION_SCALE)
        .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        if (gesture == ButtonGesture.HOLD) {
            Box(Modifier.size(72.dp * ILLUSTRATION_SCALE), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(outline.copy(alpha = 0.25f), radius = 34.dp.toPx() * ILLUSTRATION_SCALE,
                        style = Stroke(1.dp.toPx() * ILLUSTRATION_SCALE))
                    drawCircle(outline.copy(alpha = 0.5f), radius = 30.dp.toPx() * ILLUSTRATION_SCALE,
                        style = Stroke(1.dp.toPx() * ILLUSTRATION_SCALE))
                }
                FloatingButtonPicture(R.drawable.ic_mic_chatgpt_24)
            }
        } else {
            val pointsLeft = gesture == ButtonGesture.SWIPE_LEFT
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp * ILLUSTRATION_SCALE),
                verticalAlignment = Alignment.CenterVertically) {
                if (pointsLeft) SwipeChevrons(pointsLeft = true)
                FloatingButtonPicture(if (active) R.drawable.ic_stop_rounded_24 else R.drawable.ic_mic_chatgpt_24)
                if (!pointsLeft) SwipeChevrons(pointsLeft = false)
            }
        }
    }
}

@Composable
private fun SwipeChevrons(pointsLeft: Boolean) {
    val ink = SettingsMuted
    Canvas(Modifier.size(44.dp * ILLUSTRATION_SCALE)) {
        repeat(3) { index ->
            val offset = index * 14.dp.toPx() * ILLUSTRATION_SCALE
            val tip = offset + (if (pointsLeft) 2.dp else 14.dp).toPx() * ILLUSTRATION_SCALE
            val wing = offset + (if (pointsLeft) 14.dp else 2.dp).toPx() * ILLUSTRATION_SCALE
            val emphasis = if (pointsLeft) 2 - index else index
            val opacity = when (emphasis) {
                2 -> 1f
                1 -> 0.55f
                else -> 0.25f
            }
            val chevron = Path().apply {
                moveTo(wing, 2.dp.toPx() * ILLUSTRATION_SCALE)
                lineTo(tip, size.height / 2)
                lineTo(wing, size.height - 2.dp.toPx() * ILLUSTRATION_SCALE)
            }
            drawPath(chevron, ink.copy(alpha = opacity),
                style = Stroke(1.75.dp.toPx() * ILLUSTRATION_SCALE, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun FloatingButtonPicture(drawable: Int) {
    Surface(Modifier.size(52.dp * ILLUSTRATION_SCALE), shape = CircleShape, color = Color.White,
        shadowElevation = 5.dp * ILLUSTRATION_SCALE) {
        Box(contentAlignment = Alignment.Center) {
            Image(painterResource(drawable), contentDescription = null, modifier = Modifier.size(24.dp * ILLUSTRATION_SCALE),
                colorFilter = ColorFilter.tint(Color(0xFF080808)))
        }
    }
}

@Composable
private fun GuideDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), thickness = 0.5.dp)
}
