package com.opendictate.app.ondevice

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
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
 * Speech recognition on the phone through ML Kit GenAI. It prefers the Gemini Nano model that
 * Android's AICore runs and otherwise uses the phone's older on-device model; audio stays on the
 * phone either way, and recognition keeps working while another app is in front.
 */
object OnDeviceSpeech {
    /** Failures mean the phone cannot recognize [locale] on its own. */
    suspend fun status(locale: String): OnDeviceStatus =
        withRecognizer(locale) { it.checkStatus().toStatus() } ?: OnDeviceStatus.UNAVAILABLE

    suspend fun download(locale: String): OnDeviceStatus = withRecognizer(locale) { recognizer ->
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

    private fun options(locale: String) = speechRecognizerOptions {
        this.locale = Locale.forLanguageTag(locale)
        preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
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
    private const val DRAIN_TIMEOUT_MS = 2_000L
    private const val FINAL_RESULT_TIMEOUT_MS = 10_000L
}
