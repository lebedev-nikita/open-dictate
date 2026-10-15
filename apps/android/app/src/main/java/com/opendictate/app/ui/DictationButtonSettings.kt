@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.DictationButtonPosition
import kotlin.math.roundToInt

@Composable
internal fun DictationButtonAppearanceSheet(
    appearance: DictationButtonAppearance,
    onChange: (DictationButtonAppearance) -> Unit,
    onDismiss: () -> Unit,
) {
    val diameter = stringResource(R.string.dictation_button_pixels,
        appearance.diameterPx(LocalDensity.current.density))
    val sizeLabel = stringResource(R.string.dictation_button_size)
    val previewLabel = stringResource(R.string.dictation_button_preview)
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
                Text(stringResource(R.string.dictation_button_appearance_title),
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.dictation_button_appearance_close),
                        tint = SettingsMuted)
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = SettingsInset) {
                Box(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 14.dp)
                    .semantics { contentDescription = previewLabel; stateDescription = diameter },
                    contentAlignment = when (appearance.position) {
                        DictationButtonPosition.LEFT -> AbsoluteAlignment.CenterLeft
                        DictationButtonPosition.RIGHT -> AbsoluteAlignment.CenterRight
                    }) {
                    Surface(Modifier.size(appearance.diameterDp.dp), shape = CircleShape,
                        color = Color.White, shadowElevation = 5.dp) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_mic_chatgpt_24), null,
                                Modifier.size((appearance.diameterDp * 24f / DictationButtonAppearance.DEFAULT_DIAMETER_DP).dp),
                                tint = Color(0xFF080808))
                        }
                    }
                }
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(sizeLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(diameter, style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = appearance.diameterDp.toFloat(),
                    onValueChange = { onChange(appearance.copy(diameterDp = it.roundToInt())) },
                    valueRange = DictationButtonAppearance.MIN_DIAMETER_DP.toFloat()..
                        DictationButtonAppearance.MAX_DIAMETER_DP.toFloat(),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = sizeLabel
                        stateDescription = diameter
                    },
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val positionLabel = stringResource(R.string.dictation_button_position)
                if (maxWidth < (300 * LocalDensity.current.fontScale).dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(positionLabel, style = MaterialTheme.typography.bodyMedium)
                        PositionChoices(appearance, onChange, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(positionLabel, style = MaterialTheme.typography.bodyMedium)
                        PositionChoices(appearance, onChange, Modifier.width(192.dp))
                    }
                }
            }
            Text(stringResource(R.string.settings_saved_automatically),
                style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { onChange(DictationButtonAppearance()) },
                    enabled = appearance != DictationButtonAppearance(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Replay, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dictation_button_reset))
                }
                Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_done))
                }
            }
        }
    }
}

@Composable
private fun PositionChoices(
    appearance: DictationButtonAppearance,
    onChange: (DictationButtonAppearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val positionLabel = stringResource(R.string.dictation_button_position)
    Row(modifier.height(IntrinsicSize.Min)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).clip(shape)
        .selectableGroup().semantics { contentDescription = positionLabel }) {
        DictationButtonPosition.entries.forEachIndexed { index, position ->
            if (index > 0) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            }
            val selected = appearance.position == position
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else SettingsSurface)
                    .selectable(selected, role = Role.RadioButton,
                        onClick = { onChange(appearance.copy(position = position)) })
                    .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (selected) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(position.labelResource()),
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge,
                    color = if (selected) SettingsText else SettingsMuted)
            }
        }
    }
}

internal fun DictationButtonPosition.labelResource(): Int = when (this) {
    DictationButtonPosition.LEFT -> R.string.dictation_button_left
    DictationButtonPosition.RIGHT -> R.string.dictation_button_right
}
