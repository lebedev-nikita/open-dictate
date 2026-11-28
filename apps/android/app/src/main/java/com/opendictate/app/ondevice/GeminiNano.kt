package com.opendictate.app.ondevice

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.schema.tools.FunctionDeclaration
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.opendictate.app.audio.PcmAudioRecorder
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.OnDeviceStatus
import com.opendictate.app.model.OnDeviceTranscript
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini Nano through ML Kit GenAI and Android's AICore service. Audio and text stay on the phone.
 * AICore lets recognition run behind other apps, but answers text requests only while an
 * OpenDictate window is in front; callers fall back to the OpenAI text model for those.
 */
object GeminiNano {
    /** Whether AICore offers Gemini Nano for text; failures mean the phone has no usable Nano. */
    suspend fun textStatus(): OnDeviceStatus = withModel { it.checkStatus().toStatus() } ?: OnDeviceStatus.UNAVAILABLE

    suspend fun speechStatus(locale: String): OnDeviceStatus =
        withRecognizer(locale) { it.checkStatus().toStatus() } ?: OnDeviceStatus.UNAVAILABLE

    /** Downloads what AICore still needs; returns the status afterwards. */
    suspend fun downloadText(): OnDeviceStatus = withModel { model ->
        model.download().catch { }.collect { }
        model.checkStatus().toStatus()
    } ?: OnDeviceStatus.UNAVAILABLE

    suspend fun downloadSpeech(locale: String): OnDeviceStatus = withRecognizer(locale) { recognizer ->
        recognizer.download().catch { }.collect { }
        recognizer.checkStatus().toStatus()
    } ?: OnDeviceStatus.UNAVAILABLE

    /**
     * Streams the microphone to the recognizer through an in-memory pipe, at the pace it is spoken.
     * [onPartial] receives the cumulative text, like the Live path, so each update replaces the last.
     */
    suspend fun transcribe(
        locale: String,
        scope: CoroutineScope,
        recorder: PcmAudioRecorder,
        waitForStop: suspend () -> Unit,
        onReady: () -> Unit,
        onPartial: (String) -> Unit,
        onAudio: (ByteArray) -> Unit = {},
    ): Either<DictationError, String> {
        val recognizer = SpeechRecognition.getClient(options(locale))
        val pipe = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val source = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING, FORMAT)
        // The microphone never waits for the recognizer; a stalled recognizer loses the oldest audio.
        val chunks = Channel<ByteArray>(MAX_QUEUED_CHUNKS, BufferOverflow.DROP_OLDEST)
        return try {
            coroutineScope {
                val transcript = OnDeviceTranscript()
                val completed = CompletableDeferred<Either<DictationError, String>>()
                // Speech starts at once; the queue holds it while AICore confirms the model.
                recorder.start(scope) { chunk ->
                    onAudio(chunk)
                    chunks.trySend(chunk)
                }
                onReady()
                if (recognizer.checkStatus().toStatus() != OnDeviceStatus.AVAILABLE) {
                    return@coroutineScope DictationError.OnDeviceUnavailable.left()
                }
                val writer = launch(Dispatchers.IO) {
                    runCatching { output.use { for (chunk in chunks) it.write(chunk) } }
                }
                val reader = launch {
                    try {
                        recognizer.startRecognition(speechRecognizerRequest { audioSource = source }).collect { response ->
                            when (response) {
                                is SpeechRecognizerResponse.PartialTextResponse -> onPartial(transcript.partial(response.text))
                                is SpeechRecognizerResponse.FinalTextResponse -> onPartial(transcript.final(response.text))
                                is SpeechRecognizerResponse.ErrorResponse -> completed.complete(response.e.toError().left())
                                is SpeechRecognizerResponse.CompletedResponse -> completed.complete(transcript.text.right())
                                else -> Unit
                            }
                        }
                        completed.complete(transcript.text.right())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        completed.complete(error.toError().left())
                    }
                }
                // Recognition can end before the user stops, for example when AICore is busy.
                val stopped = async { waitForStop() }
                select {
                    completed.onAwait { }
                    stopped.onAwait { }
                }
                stopped.cancel()
                recorder.stop()
                chunks.close()
                // Remaining audio reaches the recognizer before it is told to finish, unless it already has.
                if (completed.isCompleted || withTimeoutOrNull(DRAIN_TIMEOUT_MS) { writer.join() } == null) {
                    runCatching { output.close() }
                }
                runCatching { recognizer.stopRecognition() }
                val result = withTimeoutOrNull(FINAL_RESULT_TIMEOUT_MS) { completed.await() } ?: DictationError.Timeout.left()
                reader.cancel()
                writer.cancel()
                result.fold({ it.left() }, { text ->
                    text.trim().takeIf(String::isNotEmpty)?.right() ?: DictationError.EmptyResult.left()
                })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.toError().left()
        } finally {
            withContext(NonCancellable) {
                runCatching { recorder.stop() }
                chunks.close()
                runCatching { output.close() }
                runCatching { source.close() }
                runCatching { recognizer.close() }
            }
        }
    }

    /** One text request. Returns [DictationError.OnDeviceBlocked] when Android refuses it from the background. */
    suspend fun generate(instructions: String, input: String, maxOutputTokens: Int = MAX_OUTPUT_TOKENS): Either<DictationError, String> =
        respond(instructions, input, maxOutputTokens, tools = emptyList()).map { it.text }

    /** A text request that may call one of [tools]; see [com.opendictate.app.model.OnDeviceAssistant]. */
    suspend fun respond(
        instructions: String,
        input: String,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
        tools: List<FunctionDeclaration>,
    ): Either<DictationError, Candidate> = withContext(Dispatchers.Default) {
        val model = Generation.getClient()
        try {
            if (model.checkStatus().toStatus() != OnDeviceStatus.AVAILABLE) return@withContext DictationError.OnDeviceUnavailable.left()
            val systemPrompt = model.isSystemPromptAvailable()
            val request = if (systemPrompt) {
                generateContentRequest(SystemInstruction(instructions), TextPart(input)) { configure(maxOutputTokens, tools) }
            } else {
                generateContentRequest(TextPart("$instructions\n\n$input")) { configure(maxOutputTokens, tools) }
            }
            val candidate = model.generateContent(request).candidates.firstOrNull()
                ?: return@withContext DictationError.EmptyResult.left()
            if (candidate.finishReason == Candidate.FinishReason.MAX_TOKENS) return@withContext DictationError.InvalidResponse.left()
            candidate.right()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.toError().left()
        } finally {
            model.close()
        }
    }

    private fun com.google.mlkit.genai.prompt.GenerateContentRequest.Builder.configure(maxOutputTokens: Int, tools: List<FunctionDeclaration>) {
        temperature = 0f
        topK = 1
        this.maxOutputTokens = maxOutputTokens
        if (tools.isNotEmpty()) setTools(tools)
    }

    private fun options(locale: String) = speechRecognizerOptions {
        this.locale = Locale.forLanguageTag(locale)
        preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
    }

    private suspend fun <T> withModel(block: suspend (GenerativeModel) -> T): T? = withContext(Dispatchers.Default) {
        val model = runCatching { Generation.getClient() }.getOrNull() ?: return@withContext null
        try { block(model) } catch (error: CancellationException) { throw error } catch (_: Exception) { null } finally { model.close() }
    }

    private suspend fun <T> withRecognizer(locale: String, block: suspend (SpeechRecognizer) -> T): T? = withContext(Dispatchers.Default) {
        val recognizer = runCatching { SpeechRecognition.getClient(options(locale)) }.getOrNull() ?: return@withContext null
        try { block(recognizer) } catch (error: CancellationException) { throw error } catch (_: Exception) { null } finally { recognizer.close() }
    }

    private fun Int.toStatus(): OnDeviceStatus = when (this) {
        FeatureStatus.AVAILABLE -> OnDeviceStatus.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> OnDeviceStatus.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> OnDeviceStatus.DOWNLOADING
        else -> OnDeviceStatus.UNAVAILABLE
    }

    /** Error codes only; AICore messages are never kept. */
    private fun Throwable.toError(): DictationError {
        val code = generateSequence(this) { it.cause.takeUnless { cause -> cause === it } }.take(8)
            .filterIsInstance<GenAiException>().firstOrNull()?.errorCode
        return when (code) {
            null -> DictationError.from(this)
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> DictationError.OnDeviceBlocked
            GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> DictationError.OnDeviceUnavailable
            GenAiException.ErrorCode.BUSY, GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> DictationError.OnDeviceBusy
            else -> DictationError.Provider()
        }
    }

    private val FORMAT: AudioFormat = AudioFormat.Builder()
        .setSampleRate(PcmAudioRecorder.SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()
    /** 12 seconds of 40 ms chunks, the bound of the Live path's queue. */
    private const val MAX_QUEUED_CHUNKS = 300
    private const val MAX_OUTPUT_TOKENS = 1_024
    private const val DRAIN_TIMEOUT_MS = 2_000L
    private const val FINAL_RESULT_TIMEOUT_MS = 10_000L
}
