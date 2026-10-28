package com.opendictate.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.audio.LastDictationAudioStore
import com.opendictate.app.audio.PcmAudioRecorder
import com.opendictate.app.audio.WavFile
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.ErrorOperation
import com.opendictate.app.model.ErrorRoute
import com.opendictate.app.model.ErrorStage
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.network.OpenAiTranscriptionClient
import com.opendictate.app.ui.userMessage
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DictationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val apiClient by lazy { OpenAiTranscriptionClient(chatGPT = (application as OpenDictateApplication).chatGPT) }
    private var activeJob: Job? = null
    private var activeSessionId = 0L
    private var stopSignal = CompletableDeferred<Unit>()
    private var activeOperation = DictationOperation.DICTATION
    private var activeDiagnostics: DictationDiagnostics? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_CANCEL -> requestCancel(intent.getLongExtra(EXTRA_SESSION_ID, 0L))
            ACTION_START -> if (!DictationStateBus.state.value.isActive) {
                startDictation(DictationOperation.DICTATION, postProcessingOverride =
                    if (intent.hasExtra(EXTRA_POST_PROCESSING_ENABLED)) intent.getBooleanExtra(EXTRA_POST_PROCESSING_ENABLED, false) else null)
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
        postProcessingOverride: Boolean? = null,
    ) {
        val apiKey = SecureApiKeyStore(this).get()
        if (apiKey.isNullOrBlank()) {
            publishError(DictationError.MissingApiKey)
            stopSelf()
            return
        }
        if (operation == DictationOperation.TRANSFORMATION && sourceText.isNullOrBlank()) {
            publishError(
                DictationError.NothingToTransform,
                operation = operation,
            )
            stopSelf()
            return
        }
        val settings = SettingsStore(this)
        val options = com.opendictate.app.model.DictationStartOptions.resolve(settings.model, settings.postProcessing, postProcessingOverride)
        val model = options.model
        val liveModelId = settings.liveModelId
        val accurateModelId = settings.accurateModelId
        val chatGPTAccount = (application as OpenDictateApplication).chatGPT.activeAccount()
        val textProcessingModel = settings.textProcessingModel
        val textProcessingServiceTier = settings.textProcessingServiceTier
        val diagnostics = DictationDiagnostics((application as OpenDictateApplication).errorHistoryStore, model,
            if (operation == DictationOperation.TRANSFORMATION) ErrorOperation.VOICE_EDIT else ErrorOperation.DICTATION)
        activeDiagnostics = diagnostics
        val textRoute = if (chatGPTAccount != null) ErrorRoute.CHATGPT else ErrorRoute.OPENAI_API
        val postProcessing = options.postProcessing
        activePostProcessingEnabled = operation == DictationOperation.DICTATION && postProcessing.shouldProcess
        val replacements = (application as OpenDictateApplication).replacementStore.engine()
        val languages = settings.languages
        val dictionary = settings.prompt
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
                postProcessingEnabled = activePostProcessingEnabled,
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
            var transcriptionMillis: Long? = null
            var postProcessingMillis: Long? = null
            suspend fun stopRecording() = withContext(NonCancellable) {
                try { recorder.stop() } finally {
                    runCatching { savedRecording?.finish() }
                    runCatching { wavFile?.close() }
                }
            }
            try {
                // The idle button can be tapped while the previous session is still cleaning up.
                // Publish ownership first, then wait before opening another microphone or file.
                withContext(NonCancellable) { previousJob?.join() }
                ensureActive()
                val outcome = either<DictationError, Unit> {
                    ensure(ContextCompat.checkSelfPermission(this@DictationForegroundService, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) { DictationError.MicrophonePermission }
                    val transcript = when (model) {
                        TranscriptionModel.LIVE -> {
                            diagnostics.stage(ErrorStage.TRANSCRIPTION, ErrorRoute.OPENAI_API, liveModelId)
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
                                    val currentState = DictationStateBus.state.value
                                    val phase = if (
                                        currentState.phase == DictationPhase.PROCESSING
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
                                            processingStage = currentState.activeProcessingStage,
                                        ),
                                    )
                                },
                                onAudio = { chunk -> savedRecording?.append(chunk) },
                            ).bind().also {
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
                            publishProcessingStage(sessionId, operation, model, DictationProcessingStage.TRANSCRIPTION)
                            updateNotification(true, operation)
                            diagnostics.stage(ErrorStage.TRANSCRIPTION, ErrorRoute.OPENAI_API, accurateModelId)
                            val timed = measureRequest(SystemClock::elapsedRealtime) {
                                apiClient.transcribeFile(apiKey, accurateModelId, file, languages, dictionary, responseTimeoutSeconds).bind()
                            }
                            transcriptionMillis = timed.milliseconds
                            timed.value
                        }
                    }
                    ensure(transcript.isNotBlank()) { DictationError.EmptyResult }
                    val result = if (operation == DictationOperation.TRANSFORMATION) {
                        publishProcessingStage(sessionId, operation, model, DictationProcessingStage.TRANSFORMATION)
                        updateNotification(true, operation)
                        diagnostics.stage(ErrorStage.TRANSFORMATION, textRoute, textProcessingModel)
                        val transformation = apiClient.transformText(
                            apiKey = apiKey,
                            sourceText = requireNotNull(sourceText),
                            instruction = transcript.trim(),
                            modelId = textProcessingModel,
                            serviceTier = textProcessingServiceTier,
                            chatGPTAccount = chatGPTAccount,
                        ).bind()
                        modelMessage = transformation.message
                        transformation.text
                    } else {
                        if (postProcessing.shouldProcess) {
                            publishProcessingStage(sessionId, operation, model, DictationProcessingStage.POST_PROCESSING)
                        }
                        diagnostics.stage(ErrorStage.POST_PROCESSING, textRoute, textProcessingModel)
                        val timed = measureRequest(SystemClock::elapsedRealtime) { apiClient.postProcess(apiKey, transcript, postProcessing, languages, textProcessingModel, chatGPTAccount, textProcessingServiceTier) }
                        if (postProcessing.shouldProcess) postProcessingMillis = timed.milliseconds
                        val processed = timed.value
                        ensureActive()
                        processed.failure?.let { diagnostics.record(it.failureDetails) }
                        postProcessingFailed = processed.failed
                        replacements.apply(processed.text)
                    }
                    ensure(result.isNotBlank()) { DictationError.EmptyTransformation }
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
                                (application as OpenDictateApplication).transcriptHistoryStore.add(result,
                                    mode = model, transcriptionMillis = transcriptionMillis, postProcessingMillis = postProcessingMillis,
                                    transcriptionModelId = if (model == TranscriptionModel.LIVE) liveModelId else accurateModelId,
                                    postProcessingModelId = textProcessingModel.takeIf { postProcessing.shouldProcess },
                                    postProcessingServiceTier = textProcessingServiceTier.takeIf { postProcessing.shouldProcess })
                            }
                        }
                    }
                    if (completed && postProcessingFailed) showPostProcessingError()
                    delay(1_200)
                    clearSessionIfCurrent(sessionId)
                }
                outcome.onLeft { failure ->
                    stopRecording()
                    publishFailure(sessionId, operation, model, failure, diagnostics)
                }
            } catch (error: Exception) {
                stopRecording()
                if (error is CancellationException) throw error
                publishFailure(sessionId, operation, model, DictationError.from(error), diagnostics)
            } finally {
                try { stopRecording() } finally {
                    tempFile?.delete()
                    finishSessionIfCurrent(sessionId)
                }
            }
        }
    }

    private var activePostProcessingEnabled = false

    private fun retryLastDictation() {
        val apiKey = SecureApiKeyStore(this).get()
        if (apiKey.isNullOrBlank()) {
            publishError(DictationError.MissingApiKey,
                diagnosticMode = TranscriptionModel.ACCURATE, diagnosticOperation = ErrorOperation.RETRY)
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
        val responseTimeoutSeconds = settings.transcriptionResponseTimeoutSeconds
        val chatGPTAccount = (application as OpenDictateApplication).chatGPT.activeAccount()
        val textProcessingModel = settings.textProcessingModel
        val textProcessingServiceTier = settings.textProcessingServiceTier
        val diagnostics = DictationDiagnostics((application as OpenDictateApplication).errorHistoryStore, model, ErrorOperation.RETRY)
        activeDiagnostics = diagnostics
        val textRoute = if (chatGPTAccount != null) ErrorRoute.CHATGPT else ErrorRoute.OPENAI_API
        val postProcessing = settings.postProcessing.forMode(model)
        activePostProcessingEnabled = postProcessing.shouldProcess
        activeOperation = DictationOperation.DICTATION
        startForeground(NOTIFICATION_ID, notification(true, DictationOperation.DICTATION))
        DictationStateBus.set(
            DictationState(sessionId = sessionId, phase = DictationPhase.PROCESSING, model = model,
                processingStage = DictationProcessingStage.TRANSCRIPTION, postProcessingEnabled = activePostProcessingEnabled),
        )
        previousJob?.cancel()
        activeJob = scope.launch {
            try {
                withContext(NonCancellable) { previousJob?.join() }
                ensureActive()
                val outcome = either<DictationError, Unit> {
                    val file = ensureNotNull(withContext(Dispatchers.IO) {
                        (application as OpenDictateApplication).lastDictationAudioStore.latestFile()
                    }) { DictationError.MissingRecording }
                    diagnostics.stage(ErrorStage.TRANSCRIPTION, ErrorRoute.OPENAI_API, accurateModelId)
                    val transcription = measureRequest(SystemClock::elapsedRealtime) {
                        apiClient.transcribeFile(apiKey, accurateModelId, file, languages, dictionary, responseTimeoutSeconds).bind()
                    }
                    val transcript = transcription.value
                    ensure(transcript.isNotBlank()) { DictationError.EmptyResult }
                    if (postProcessing.shouldProcess) {
                        publishProcessingStage(sessionId, DictationOperation.DICTATION, model, DictationProcessingStage.POST_PROCESSING)
                    }
                    diagnostics.stage(ErrorStage.POST_PROCESSING, textRoute, textProcessingModel)
                    val postProcessingAttempt = measureRequest(SystemClock::elapsedRealtime) { apiClient.postProcess(apiKey, transcript, postProcessing, languages, textProcessingModel, chatGPTAccount, textProcessingServiceTier) }
                    val processed = postProcessingAttempt.value
                    ensureActive()
                    processed.failure?.let { diagnostics.record(it.failureDetails) }
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
                            (application as OpenDictateApplication).transcriptHistoryStore.add(result, mode = model,
                                transcriptionMillis = transcription.milliseconds,
                                postProcessingMillis = postProcessingAttempt.milliseconds.takeIf { postProcessing.shouldProcess },
                                transcriptionModelId = accurateModelId,
                                postProcessingModelId = textProcessingModel.takeIf { postProcessing.shouldProcess },
                                postProcessingServiceTier = textProcessingServiceTier.takeIf { postProcessing.shouldProcess })
                        }
                    }
                    if (completed && processed.failed) showPostProcessingError()
                    delay(1_200)
                    clearSessionIfCurrent(sessionId)
                }
                outcome.onLeft { publishFailure(sessionId, DictationOperation.DICTATION, model, it, diagnostics) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                publishFailure(sessionId, DictationOperation.DICTATION, model, DictationError.from(error), diagnostics)
            } finally {
                finishSessionIfCurrent(sessionId)
            }
        }
    }

    private fun requestStop() {
        stopSignal.complete(Unit)
        val state = DictationStateBus.state.value
        if (state.isActive && !state.phase.isProcessing) {
            DictationStateBus.set(state.copy(phase = DictationPhase.PROCESSING,
                processingStage = DictationProcessingStage.TRANSCRIPTION))
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
        DictationStateBus.set(state.copy(phase = DictationPhase.IDLE, transcript = "", processingStage = null))
    }

    private fun publishWhileActive(sessionId: Long, state: DictationState): Boolean {
        if (DictationStateBus.state.value.acceptsActiveUpdate(sessionId)) {
            DictationStateBus.set(state.copy(postProcessingEnabled = activePostProcessingEnabled,
                diagnostics = activeDiagnostics?.context))
            return true
        }
        return false
    }

    private fun publishProcessingStage(
        sessionId: Long,
        operation: DictationOperation,
        model: TranscriptionModel,
        stage: DictationProcessingStage,
    ) = publishWhileActive(sessionId, DictationState(
        sessionId = sessionId,
        phase = DictationPhase.PROCESSING,
        operation = operation,
        model = model,
        processingStage = stage,
    ))

    private fun clearSessionIfCurrent(sessionId: Long) {
        val state = DictationStateBus.state.value
        if (state.sessionId == sessionId) {
            DictationStateBus.set(state.copy(phase = DictationPhase.IDLE, transcript = "", processingStage = null,
                message = null, failure = null))
        }
    }

    private fun publishError(
        failure: DictationError,
        sessionId: Long = nextSession.get(),
        operation: DictationOperation = DictationOperation.DICTATION,
        diagnosticMode: TranscriptionModel = SettingsStore(this).model,
        diagnosticOperation: ErrorOperation = if (operation == DictationOperation.TRANSFORMATION) ErrorOperation.VOICE_EDIT else ErrorOperation.DICTATION,
    ) {
        val diagnostics = DictationDiagnostics((application as OpenDictateApplication).errorHistoryStore,
            diagnosticMode, diagnosticOperation)
        (application as OpenDictateApplication).recordError(diagnostics.context, failure.failureDetails)
        DictationStateBus.set(
            DictationState(
                sessionId = sessionId,
                phase = DictationPhase.ERROR,
                operation = operation,
                message = failure.userMessage(resources),
                failure = failure,
            ),
        )
    }

    private suspend fun publishFailure(
        sessionId: Long,
        operation: DictationOperation,
        model: TranscriptionModel,
        failure: DictationError,
        diagnostics: DictationDiagnostics,
    ) {
        currentCoroutineContext().ensureActive()
        diagnostics.record(failure.failureDetails)
        val published = publishWhileActive(sessionId, DictationState(
            sessionId = sessionId,
            phase = DictationPhase.ERROR,
            operation = operation,
            model = model,
            message = failure.userMessage(resources),
            failure = failure,
        ))
        if (published) {
            delay(3_000)
            clearSessionIfCurrent(sessionId)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // Remove the retired informational channel and any messages left by older versions.
        manager.deleteNotificationChannel("transformation_messages")
        manager.createNotificationChannels(
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
                    ERROR_CHANNEL_ID,
                    getString(R.string.notification_errors_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    private fun showPostProcessingError() {
        val message = getString(R.string.postprocessing_failed)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_chatgpt_24)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            ERROR_NOTIFICATION_ID,
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
        const val EXTRA_POST_PROCESSING_ENABLED = "com.opendictate.app.extra.POST_PROCESSING_ENABLED"
        const val EXTRA_SESSION_ID = "com.opendictate.app.extra.SESSION_ID"
        private const val CHANNEL_ID = "dictation"
        private const val ERROR_CHANNEL_ID = "errors"
        private const val NOTIFICATION_ID = 41
        private const val ERROR_NOTIFICATION_ID = 42
        private val nextSession = AtomicLong(System.currentTimeMillis())
    }
}
