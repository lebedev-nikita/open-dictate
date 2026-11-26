package com.opendictate.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.ExternalCallerAccess
import com.opendictate.app.model.ExternalDictationContract
import com.opendictate.app.model.ExternalDictationOutcome
import com.opendictate.app.model.externalCallerAccess
import com.opendictate.app.model.toResult
import com.opendictate.app.service.DictationForegroundService
import com.opendictate.app.service.DictationPhase
import com.opendictate.app.service.DictationStateBus
import com.opendictate.app.service.ExternalDictationBus
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Answers `android.speech.action.RECOGNIZE_SPEECH` for apps the user allowed. The visible panel
 * lets Android 14+ start the microphone service; the result goes back only to the calling app.
 */
class ExternalDictationActivity : ComponentActivity() {
    private val settings by lazy { SettingsStore(this) }
    private var requestId: String? = null
    private var callerPackage: String? = null
    private var consentPending by mutableStateOf(false)
    private var callerLabel by mutableStateOf("")
    private var startWhenResumed = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callerPackage = callingPackage
        callerLabel = callerPackage?.let(::appLabel).orEmpty()
        requestId = savedInstanceState?.getString(KEY_REQUEST_ID)
        consentPending = savedInstanceState?.getBoolean(KEY_CONSENT_PENDING) == true
        val restoredRequest = requestId
        if (restoredRequest != null) {
            awaitResult(restoredRequest)
        } else if (!consentPending) {
            when (externalCallerAccess(callerPackage, settings.externalDictationPackages)) {
                ExternalCallerAccess.REJECTED -> {
                    // Without a result there is no identified caller to receive the transcript.
                    Toast.makeText(this, R.string.external_dictation_needs_result, Toast.LENGTH_LONG).show()
                    finished = true
                    finish()
                    return
                }
                ExternalCallerAccess.ALLOWED -> startWhenResumed = true
                ExternalCallerAccess.ASK -> consentPending = true
            }
        }
        onBackPressedDispatcher.addCallback(this) { cancel() }
        setContent {
            OpenDictateTheme(theme = settings.theme) {
                val state by DictationStateBus.state.collectAsStateWithLifecycle()
                ExternalDictationPanel(
                    callerLabel = callerLabel,
                    consentPending = consentPending,
                    processing = state.external && state.phase == DictationPhase.PROCESSING,
                    onAllow = ::allowCaller,
                    onDeny = { finishWith(ExternalDictationOutcome.NotAllowed) },
                    onStop = ::stopRecording,
                    onCancel = ::cancel,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (startWhenResumed) {
            startWhenResumed = false
            start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_REQUEST_ID, requestId)
        outState.putBoolean(KEY_CONSENT_PENDING, consentPending)
    }

    override fun onDestroy() {
        // Leaving the panel for good, for example from Recents, discards the recording.
        if (isFinishing && !finished) cancel()
        super.onDestroy()
    }

    private fun allowCaller() {
        val caller = callerPackage ?: return cancel()
        settings.externalDictationPackages = settings.externalDictationPackages + caller
        consentPending = false
        start()
    }

    private fun start() {
        if (finished || requestId != null) return
        if (!SecureApiKeyStore(this).hasKey()) return finishWith(ExternalDictationOutcome.Failed(DictationError.MissingApiKey))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return finishWith(ExternalDictationOutcome.Failed(DictationError.MicrophonePermission))
        }
        if (DictationStateBus.state.value.isActive) return finishWith(ExternalDictationOutcome.Busy)
        val id = UUID.randomUUID().toString()
        requestId = id
        ExternalDictationBus.await(id)
        awaitResult(id)
        try {
            ContextCompat.startForegroundService(this, serviceIntent(DictationForegroundService.ACTION_START_EXTERNAL, id))
        } catch (_: IllegalStateException) {
            finishWith(ExternalDictationOutcome.Failed(DictationError.Unexpected))
        }
    }

    private fun awaitResult(id: String) {
        lifecycleScope.launch {
            val delivery = ExternalDictationBus.delivery.filterNotNull().first { it.requestId == id }
            finishWith(delivery.outcome)
        }
    }

    private fun stopRecording() {
        requestId?.let { runCatching { startService(serviceIntent(DictationForegroundService.ACTION_STOP_EXTERNAL, it)) } }
    }

    private fun cancel() {
        if (finished) return
        requestId?.let { runCatching { startService(serviceIntent(DictationForegroundService.ACTION_CANCEL_EXTERNAL, it)) } }
        finishWith(ExternalDictationOutcome.Cancelled)
    }

    private fun serviceIntent(action: String, id: String) =
        Intent(this, DictationForegroundService::class.java)
            .setAction(action)
            .putExtra(DictationForegroundService.EXTRA_REQUEST_ID, id)

    private fun finishWith(outcome: ExternalDictationOutcome) {
        if (finished) return
        finished = true
        val result = outcome.toResult()
        val data = Intent()
        result.text?.let {
            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(it))
            data.putExtra(ExternalDictationContract.EXTRA_TEXT, it)
        }
        result.error?.let { data.putExtra(ExternalDictationContract.EXTRA_ERROR, it) }
        setResult(result.resultCode, data)
        val message = when (outcome) {
            is ExternalDictationOutcome.Failed -> outcome.error.userMessage(resources)
            ExternalDictationOutcome.Busy -> getString(R.string.external_dictation_busy)
            else -> null
        }
        message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        requestId?.let(ExternalDictationBus::release)
        finish()
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_CONSENT_PENDING = "consent_pending"
    }
}

@Composable
internal fun ExternalDictationPanel(
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
                if (consentPending) ConsentContent(callerLabel) else SessionContent(callerLabel, processing)
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
private fun SessionContent(callerLabel: String, processing: Boolean) {
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
            if (callerLabel.isNotEmpty()) {
                Text(stringResource(R.string.external_dictation_for_app, callerLabel),
                    style = MaterialTheme.typography.bodyMedium, color = SettingsMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
