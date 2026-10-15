@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gestures_title),
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.gestures_close), tint = SettingsMuted)
                }
            }
            Spacer(Modifier.height(12.dp))
            GestureRow(ButtonGesture.HOLD, R.string.gestures_menu_action,
                R.string.gestures_hold_hint, R.string.gestures_hold_description)
            GuideDivider()
            GestureRow(if (mirrorGestures) ButtonGesture.SWIPE_RIGHT else ButtonGesture.SWIPE_LEFT,
                R.string.gestures_cancel_action,
                if (mirrorGestures) R.string.gestures_cancel_right_hint else R.string.gestures_cancel_left_hint,
                if (mirrorGestures) R.string.gestures_cancel_right_description else R.string.gestures_left_description,
                active = true)
            GuideDivider()
            GestureRow(if (mirrorGestures) ButtonGesture.SWIPE_LEFT else ButtonGesture.SWIPE_RIGHT,
                R.string.gestures_hide_action,
                if (mirrorGestures) R.string.gestures_hide_left_hint else R.string.gestures_hide_right_hint,
                if (mirrorGestures) R.string.gestures_dismiss_left_description else R.string.gestures_right_description)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.gestures_keyboard_recovery),
                style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
        }
    }
}

@Composable
private fun GestureRow(gesture: ButtonGesture, title: Int, hint: Int, description: Int, active: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ButtonIllustration(gesture, stringResource(description), active)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = SettingsText)
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}
