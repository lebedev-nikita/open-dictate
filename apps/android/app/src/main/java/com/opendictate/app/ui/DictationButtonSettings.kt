@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.DictationButtonPosition
import kotlin.math.roundToInt

@Composable
internal fun DictationButtonSettings(
    appearance: DictationButtonAppearance,
    onChange: (DictationButtonAppearance) -> Unit,
) {
    val diameter = stringResource(R.string.dictation_button_pixels,
        appearance.diameterPx(LocalDensity.current.density))
    val sizeLabel = stringResource(R.string.dictation_button_size)
    val previewLabel = stringResource(R.string.dictation_button_preview)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dictation_button_title), style = MaterialTheme.typography.bodyLarge)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(sizeLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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
        Text(stringResource(R.string.dictation_button_position), style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            DictationButtonPosition.entries.forEachIndexed { index, position ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = appearance.position == position,
                    onClick = { onChange(appearance.copy(position = position)) },
                    shape = SegmentedButtonDefaults.itemShape(index, DictationButtonPosition.entries.size),
                ) {
                    Text(stringResource(when (position) {
                        DictationButtonPosition.LEFT -> R.string.dictation_button_left
                        DictationButtonPosition.RIGHT -> R.string.dictation_button_right
                    }))
                }
            }
        }
    }
}
