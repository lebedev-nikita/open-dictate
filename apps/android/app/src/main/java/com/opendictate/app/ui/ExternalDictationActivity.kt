package com.opendictate.app.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.model.ExternalCallerAccess
import com.opendictate.app.model.ExternalDictationContract
import com.opendictate.app.model.ExternalDictationOutcome
import com.opendictate.app.model.externalCallerAccess
import com.opendictate.app.model.toResult

/**
 * Answers `android.speech.action.RECOGNIZE_SPEECH` for apps the user allowed. The result goes back
 * only to the calling app.
 */
class ExternalDictationActivity : DictationPanelActivity() {
    private var callerPackage: String? = null
    private var consentPending by mutableStateOf(false)
    private var callerLabel by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callerPackage = callingPackage
        callerLabel = callerPackage?.let(::appLabel).orEmpty()
        consentPending = savedInstanceState?.getBoolean(KEY_CONSENT_PENDING) == true
        if (!restoreRequest(savedInstanceState) && !consentPending) {
            when (externalCallerAccess(callerPackage, settings.externalDictationPackages)) {
                ExternalCallerAccess.REJECTED -> {
                    // Without a result there is no identified caller to receive the transcript.
                    Toast.makeText(this, R.string.external_dictation_needs_result, Toast.LENGTH_LONG).show()
                    return abandon()
                }
                ExternalCallerAccess.ALLOWED -> startWhenResumed()
                ExternalCallerAccess.ASK -> consentPending = true
            }
        }
        showPanel { processing ->
            ExternalDictationPanel(
                destination = if (callerLabel.isEmpty()) "" else stringResource(R.string.external_dictation_for_app, callerLabel),
                callerLabel = callerLabel,
                consentPending = consentPending,
                processing = processing,
                onAllow = ::allowCaller,
                onDeny = { finishWith(ExternalDictationOutcome.NotAllowed) },
                onStop = ::stopRecording,
                onCancel = ::cancel,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CONSENT_PENDING, consentPending)
    }

    private fun allowCaller() {
        val caller = callerPackage ?: return cancel()
        settings.externalDictationPackages = settings.externalDictationPackages + caller
        consentPending = false
        start()
    }

    override fun deliver(outcome: ExternalDictationOutcome) {
        val result = outcome.toResult()
        val data = Intent()
        result.text?.let {
            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(it))
            data.putExtra(ExternalDictationContract.EXTRA_TEXT, it)
        }
        result.error?.let { data.putExtra(ExternalDictationContract.EXTRA_ERROR, it) }
        setResult(result.resultCode, data)
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val KEY_CONSENT_PENDING = "consent_pending"
    }
}

@Composable
internal fun ExternalDictationPanel(
    destination: String,
    callerLabel: String,
    consentPending: Boolean,
    processing: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            // Outside taps dismiss only the consent question; a recording is never lost by accident.
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = consentPending, onClick = onDeny),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            Modifier.navigationBarsPadding().padding(16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 12.dp)) {
                if (consentPending) ConsentContent(callerLabel) else SessionContent(destination, processing)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (consentPending) {
                        TextButton(onDeny) { Text(stringResource(R.string.external_dictation_deny)) }
                        Button(onAllow) { Text(stringResource(R.string.external_dictation_allow)) }
                    } else {
                        TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) }
                        Button(onStop, enabled = !processing) { Text(stringResource(R.string.settings_done)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentContent(callerLabel: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.RecordVoiceOver, null, Modifier.padding(top = 2.dp).size(24.dp), tint = SettingsMuted)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.external_dictation_consent_title, callerLabel),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.external_dictation_consent_body, callerLabel),
                style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
        }
    }
}

@Composable
private fun SessionContent(destination: String, processing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (processing) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                val pulse by rememberInfiniteTransition(label = "listening").animateFloat(
                    initialValue = 1f, targetValue = 0.35f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
                )
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Mic, null, Modifier.size(24.dp).alpha(pulse), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(if (processing) R.string.external_dictation_processing else R.string.external_dictation_listening),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (destination.isNotEmpty()) {
                Text(destination,
                    style = MaterialTheme.typography.bodyMedium, color = SettingsMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
