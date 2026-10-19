package com.opendictate.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.audio.PcmAudioRecorder
import com.opendictate.app.audio.LastDictationAudioStore
import com.opendictate.app.audio.WavFile
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.network.OpenAiTranscriptionClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class DictationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val apiClient by lazy { OpenAiTranscriptionClient(resources) }
    private var activeJob: Job? = null
    private var activeSessionId = 0L
    private var stopSignal = CompletableDeferred<Unit>()
    private var activeOperation = DictationOperation.DICTATION

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_CANCEL -> requestCancel(intent.getLongExtra(EXTRA_SESSION_ID, 0L))
            ACTION_START -> if (!DictationStateBus.state.value.isActive) {
                startDictation(DictationOperation.DICTATION)
            }
            ACTION_START_TRANSFORMATION -> if (!DictationStateBus.state.value.isActive) {
                startDictation(
                    operation = DictationOperation.TRANSFORMATION,
                    sourceText = intent.getStringExtra(EXTRA_SOURCE_TEXT),
                )
            }
            ACTION_RETRY_LAST -> if (!DictationStateBus.state.value.isActive) {
                retryLastDictation()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSignal.complete(Unit)
        scope.coroutineContext[Job]?.cancel()
        val state = DictationStateBus.state.value
        if (state.isActive) clearSessionIfCurrent(activeSessionId)
        super.onDestroy()
    }

    private fun startDictation(
        operation: DictationOperation,
        sourceText: String? = null,
    ) {
        val apiKey = SecureApiKeyStore(this).get()
        if (apiKey.isNullOrBlank()) {
            publishError(getString(R.string.error_add_api_key))
            stopSelf()
            return
        }
        if (operation == DictationOperation.TRANSFORMATION && sourceText.isNullOrBlank()) {
            publishError(
                getString(R.string.error_nothing_to_transform),
                operation = operation,
            )
            stopSelf()
            return
        }
        val settings = SettingsStore(this)
        val model = settings.model
        val liveModelId = settings.liveModelId
        val accurateModelId = settings.accurateModelId
        val transformationModelId = settings.transformationModelId
        val postProcessing = settings.postProcessing
        val replacements = (application as OpenDictateApplication).replacementStore.engine()
        val languages = settings.languages
        val dictionary = settings.prompt
        val transcriptionPrompt = settings.customTranscriptionPrompt ?: com.opendictate.app.model.TranscriptionPrompt.defaultText(languages)
        val responseTimeoutSeconds = settings.transcriptionResponseTimeoutSeconds
        val sessionId = nextSession.incrementAndGet()
        val previousJob = activeJob
        activeSessionId = sessionId
        activeOperation = operation
        stopSignal = CompletableDeferred()
        startForeground(NOTIFICATION_ID, notification(false, operation))
        DictationStateBus.set(
            DictationState(
                sessionId = sessionId,
                phase = DictationPhase.CONNECTING,
                operation = operation,
                model = model,
            ),
        )
        previousJob?.cancel()

        activeJob = scope.launch {
            val recorder = PcmAudioRecorder(this@DictationForegroundService)
            var tempFile: File? = null
            var wavFile: WavFile? = null
            var savedRecording: LastDictationAudioStore.Recording? = null
            var modelMessage: String? = null
            var postProcessingFailed = false
            try {
                // The idle button can be tapped while the previous session is still cleaning up.
                // Publish ownership first, then wait before opening another microphone or file.
                withContext(NonCancellable) { previousJob?.join() }
                ensureActive()
                val transcript = when (model) {
                    TranscriptionModel.LIVE -> {
                        if (operation == DictationOperation.DICTATION) {
                            savedRecording = (application as OpenDictateApplication)
                                .lastDictationAudioStore.begin(scope)
                        }
                        apiClient.transcribeLive(
                            apiKey = apiKey,
                            modelId = liveModelId,
                            scope = scope,
                            recorder = recorder,
                            languages = languages,
                            dictionary = dictionary,
                            prompt = transcriptionPrompt,
                            responseTimeoutSeconds = responseTimeoutSeconds,
                            waitForStop = { stopSignal.await() },
                            onReady = {
                                publishWhileActive(
                                    sessionId,
                                    DictationState(
                                        sessionId = sessionId,
                                        phase = DictationPhase.LISTENING,
                                        operation = operation,
                                        model = model,
                                    ),
                                )
                            },
                            onPartial = { text ->
                                val phase = if (
                                    DictationStateBus.state.value.phase == DictationPhase.PROCESSING
                                ) {
                                    DictationPhase.PROCESSING
                                } else {
                                    DictationPhase.LISTENING
                                }
                                publishWhileActive(
                                    sessionId,
                                    DictationState(
                                        sessionId = sessionId,
                                        phase = phase,
                                        operation = operation,
                                        model = model,
                                        transcript = if (operation == DictationOperation.DICTATION) {
                                            replacements.apply(text, final = false)
                                        } else {
                                            ""
                                        },
                                    ),
                                )
                            },
                            onAudio = { chunk -> savedRecording?.append(chunk) },
                        ).also {
                            withContext(NonCancellable) { savedRecording?.finish() }
                        }
                    }
                    TranscriptionModel.ACCURATE -> {
                        val file = if (operation == DictationOperation.DICTATION) {
                            (application as OpenDictateApplication).lastDictationAudioStore
                                .begin(scope).also { savedRecording = it }.file
                        } else {
                            File.createTempFile("dictation-", ".wav", cacheDir).also {
                                tempFile = it
                                wavFile = WavFile(it)
                            }
                        }
                        recorder.start(scope) { chunk ->
                            if (savedRecording != null) savedRecording.append(chunk)
                            else wavFile?.write(chunk)
                        }
                        publishWhileActive(
                            sessionId,
                            DictationState(
                                sessionId = sessionId,
                                phase = DictationPhase.LISTENING,
                                operation = operation,
                                model = model,
                            ),
                        )
                        stopSignal.await()
                        recorder.stop()
                        withContext(NonCancellable) {
                            savedRecording?.finish()
                            wavFile?.close()
                        }
                        publishWhileActive(
                            sessionId,
                            DictationState(
                                sessionId = sessionId,
                                phase = DictationPhase.PROCESSING,
                                operation = operation,
                                model = model,
                            ),
                        )
                        updateNotification(true, operation)
                        apiClient.transcribeFile(
                            apiKey,
                            accurateModelId,
                            file,
                            languages,
                            dictionary,
                            responseTimeoutSeconds,
                            prompt = transcriptionPrompt,
                        )
                    }
                }
                if (transcript.isBlank()) {
                    throw IllegalStateException(getString(R.string.error_speech_not_recognized))
                }
                val result = if (operation == DictationOperation.TRANSFORMATION) {
                    publishWhileActive(
                        sessionId,
                        DictationState(
                            sessionId = sessionId,
                            phase = DictationPhase.PROCESSING,
                            operation = operation,
                            model = model,
                        ),
                    )
                    updateNotification(true, operation)
                    val transformation = apiClient.transformText(
                        apiKey = apiKey,
                        model = transformationModelId,
                        sourceText = requireNotNull(sourceText),
                        instruction = transcript.trim(),
                    )
                    modelMessage = transformation.message
                    transformation.text
                } else {
                    val processed = apiClient.postProcess(apiKey, transcript, postProcessing)
                    ensureActive()
                    postProcessingFailed = processed.failed
                    replacements.apply(processed.text)
                }
                if (result.isBlank()) {
                    throw IllegalStateException(getString(R.string.error_transformation_empty))
                }
                val completed = publishWhileActive(
                    sessionId,
                    DictationState(
                        sessionId = sessionId,
                        phase = DictationPhase.COMPLETED,
                        operation = operation,
                        model = model,
                        transcript = if (modelMessage == null) result else "",
                    ),
                )
                if (completed && operation == DictationOperation.DICTATION) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            (application as OpenDictateApplication).transcriptHistoryStore.add(result)
                        }
                    }
                }
                modelMessage?.let(::showModelMessage)
                if (completed && postProcessingFailed) showModelMessage(getString(R.string.postprocessing_failed))
                delay(1_200)
                clearSessionIfCurrent(sessionId)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    recorder.stop()
                    runCatching { savedRecording?.finish() }
                    runCatching { wavFile?.close() }
                }
                if (error !is kotlinx.coroutines.CancellationException) {
                    publishWhileActive(
                        sessionId,
                        DictationState(
                            sessionId = sessionId,
                            phase = DictationPhase.ERROR,
                            operation = operation,
                            model = model,
                            message = error.message ?: getString(R.string.error_transcription_failed),
                        ),
                    )
                    delay(3_000)
                    clearSessionIfCurrent(sessionId)
                }
            } finally {
                tempFile?.delete()
                finishSessionIfCurrent(sessionId)
            }
        }
    }

    private fun retryLastDictation() {
        val apiKey = SecureApiKeyStore(this).get()
        if (apiKey.isNullOrBlank()) {
            publishError(getString(R.string.error_add_api_key))
            stopSelf()
            return
        }
        val settings = SettingsStore(this)
        val replacements = (application as OpenDictateApplication).replacementStore.engine()
        val sessionId = nextSession.incrementAndGet()
        val previousJob = activeJob
        activeSessionId = sessionId
        val model = TranscriptionModel.ACCURATE
        val accurateModelId = settings.accurateModelId
        val languages = settings.languages
        val dictionary = settings.prompt
        val transcriptionPrompt = settings.customTranscriptionPrompt ?: com.opendictate.app.model.TranscriptionPrompt.defaultText(languages)
        val responseTimeoutSeconds = settings.transcriptionResponseTimeoutSeconds
        val postProcessing = settings.postProcessing
        activeOperation = DictationOperation.DICTATION
        startForeground(NOTIFICATION_ID, notification(true, DictationOperation.DICTATION))
        DictationStateBus.set(
            DictationState(sessionId = sessionId, phase = DictationPhase.PROCESSING, model = model),
        )
        previousJob?.cancel()
        activeJob = scope.launch {
            try {
                withContext(NonCancellable) { previousJob?.join() }
                ensureActive()
                val file = withContext(Dispatchers.IO) {
                    (application as OpenDictateApplication).lastDictationAudioStore.latestFile()
                } ?: throw IllegalStateException(getString(R.string.error_no_last_dictation))
                val transcript = apiClient.transcribeFile(
                    apiKey,
                    accurateModelId,
                    file,
                    languages,
                    dictionary,
                    responseTimeoutSeconds,
                    prompt = transcriptionPrompt,
                )
                if (transcript.isBlank()) {
                    throw IllegalStateException(getString(R.string.error_speech_not_recognized))
                }
                val processed = apiClient.postProcess(apiKey, transcript, postProcessing)
                ensureActive()
                val result = replacements.apply(processed.text)
                val completed = publishWhileActive(
                    sessionId,
                    DictationState(
                        sessionId = sessionId,
                        phase = DictationPhase.COMPLETED,
                        model = model,
                        transcript = result,
                    ),
                )
                if (completed) withContext(Dispatchers.IO) {
                    runCatching {
                        (application as OpenDictateApplication).transcriptHistoryStore.add(result)
                    }
                }
                if (completed && processed.failed) showModelMessage(getString(R.string.postprocessing_failed))
                delay(1_200)
                clearSessionIfCurrent(sessionId)
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    publishWhileActive(
                        sessionId,
                        DictationState(
                            sessionId = sessionId,
                            phase = DictationPhase.ERROR,
                            model = model,
                            message = error.message ?: getString(R.string.error_transcription_failed),
                        ),
                    )
                    delay(3_000)
                    clearSessionIfCurrent(sessionId)
                }
            } finally {
                finishSessionIfCurrent(sessionId)
            }
        }
    }

    private fun requestStop() {
        stopSignal.complete(Unit)
        val state = DictationStateBus.state.value
        if (state.isActive && !state.phase.isProcessing) {
            DictationStateBus.set(state.copy(phase = DictationPhase.PROCESSING))
            updateNotification(true, activeOperation)
        }
    }

    private fun finishSessionIfCurrent(sessionId: Long) {
        if (activeSessionId != sessionId) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestCancel(sessionId: Long) {
        val state = DictationStateBus.state.value
        if (!state.acceptsCancellation(sessionId)) return
        activeJob?.cancel()
        DictationStateBus.set(state.copy(phase = DictationPhase.IDLE, transcript = ""))
    }

    private fun publishWhileActive(sessionId: Long, state: DictationState): Boolean {
        if (DictationStateBus.state.value.acceptsActiveUpdate(sessionId)) {
            DictationStateBus.set(state)
            return true
        }
        return false
    }

    private fun clearSessionIfCurrent(sessionId: Long) {
        val state = DictationStateBus.state.value
        if (state.sessionId == sessionId) {
            DictationStateBus.set(state.copy(phase = DictationPhase.IDLE, transcript = ""))
        }
    }

    private fun publishError(
        message: String,
        sessionId: Long = nextSession.get(),
        operation: DictationOperation = DictationOperation.DICTATION,
    ) {
        DictationStateBus.set(
            DictationState(
                sessionId = sessionId,
                phase = DictationPhase.ERROR,
                operation = operation,
                message = message,
            ),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.accessibility_description)
                    setSound(null, null)
                },
                NotificationChannel(
                    MODEL_MESSAGE_CHANNEL_ID,
                    getString(R.string.notification_model_messages_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    private fun showModelMessage(message: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, MODEL_MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_chatgpt_24)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            MODEL_MESSAGE_NOTIFICATION_ID,
            notification,
        )
    }

    private fun notification(
        processing: Boolean,
        operation: DictationOperation,
    ): Notification {
        val stopIntent = Intent(this, DictationForegroundService::class.java).setAction(ACTION_STOP)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_chatgpt_24)
            .setContentTitle(
                getString(
                    when {
                        operation == DictationOperation.TRANSFORMATION && processing ->
                            R.string.notification_transforming
                        operation == DictationOperation.TRANSFORMATION ->
                            R.string.notification_instruction_recording
                        processing -> R.string.notification_processing
                        else -> R.string.notification_recording
                    },
                ),
            )
            .setContentText(
                getString(
                    when {
                        operation == DictationOperation.TRANSFORMATION && processing ->
                            R.string.notification_text_transforming
                        operation == DictationOperation.TRANSFORMATION ->
                            R.string.notification_text_instruction_recording
                        processing -> R.string.notification_text_processing
                        else -> R.string.notification_text_recording
                    },
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun updateNotification(
        processing: Boolean,
        operation: DictationOperation,
    ) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(processing, operation),
        )
    }

    companion object {
        const val ACTION_START = "com.opendictate.app.action.START_DICTATION"
        const val ACTION_START_TRANSFORMATION =
            "com.opendictate.app.action.START_TEXT_TRANSFORMATION"
        const val ACTION_RETRY_LAST = "com.opendictate.app.action.RETRY_LAST_DICTATION"
        const val ACTION_STOP = "com.opendictate.app.action.STOP_DICTATION"
        const val ACTION_CANCEL = "com.opendictate.app.action.CANCEL_DICTATION"
        const val EXTRA_SOURCE_TEXT = "source_text"
        const val EXTRA_SESSION_ID = "com.opendictate.app.extra.SESSION_ID"
        private const val CHANNEL_ID = "dictation"
        private const val MODEL_MESSAGE_CHANNEL_ID = "transformation_messages"
        private const val NOTIFICATION_ID = 41
        private const val MODEL_MESSAGE_NOTIFICATION_ID = 42
        private val nextSession = AtomicLong(System.currentTimeMillis())
    }
}
