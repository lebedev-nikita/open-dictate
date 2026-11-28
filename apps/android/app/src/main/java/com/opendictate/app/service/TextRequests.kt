package com.opendictate.app.service

import arrow.core.Either
import com.opendictate.app.model.AssistantDecision
import com.opendictate.app.model.AssistantFunction
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.DictationLanguage
import com.opendictate.app.model.ErrorRoute
import com.opendictate.app.model.FailedCall
import com.opendictate.app.model.OnDeviceModels
import com.opendictate.app.model.OnDeviceText
import com.opendictate.app.model.PostProcessing
import com.opendictate.app.model.PostProcessingResult
import com.opendictate.app.model.PostProcessingSettings
import com.opendictate.app.model.TextAnswer
import com.opendictate.app.model.TextProcessingServiceTier
import com.opendictate.app.network.OpenAiTranscriptionClient
import com.opendictate.app.network.TextTransformationResult
import com.opendictate.app.ondevice.GeminiNano
import com.opendictate.app.ondevice.OnDeviceAssistant

/**
 * Routes one session's text requests: to Gemini Nano first when the user chose it, otherwise or
 * when Nano cannot answer to the OpenAI text model, which needs a saved key.
 */
internal class TextRequests(
    private val client: OpenAiTranscriptionClient,
    private val apiKey: String?,
    private val onDevice: Boolean,
    private val cloudModel: String,
    private val serviceTier: TextProcessingServiceTier = TextProcessingServiceTier.STANDARD,
    private val chatGPTAccount: String? = null,
) {
    private val hasCloud = !apiKey.isNullOrBlank()

    /** The model that answers when Nano does not; recorded in history and diagnostics. */
    fun modelId(answeredOnDevice: Boolean): String = if (answeredOnDevice) OnDeviceModels.ID else cloudModel

    /** A failure comes from the cloud whenever it is available, since it answers after Nano fails. */
    val diagnosticModelId: String get() = modelId(onDevice && !hasCloud)

    fun route(chatGPTAccount: String?): ErrorRoute = when {
        onDevice && !hasCloud -> ErrorRoute.DEVICE
        chatGPTAccount != null -> ErrorRoute.CHATGPT
        else -> ErrorRoute.OPENAI_API
    }

    /** A rejected Nano edit is retried in the cloud, since a larger model usually keeps the words. */
    suspend fun postProcess(source: String, settings: PostProcessingSettings, languages: Set<DictationLanguage>): TextAnswer<PostProcessingResult> {
        if (onDevice) {
            val instruction = settings.prompt(languages.map { it.code })
            val nano = PostProcessing.apply(source, settings) {
                GeminiNano.generate(OnDeviceText.POST_PROCESSING_INSTRUCTIONS, OnDeviceText.input(instruction, source)).map(OnDeviceText::reply)
            }
            if (!nano.failed || !hasCloud) return TextAnswer(nano, onDevice = true)
        }
        if (!hasCloud) return TextAnswer(PostProcessingResult(source, DictationError.MissingApiKey.takeIf { settings.shouldProcess }), onDevice = false)
        return TextAnswer(client.postProcess(requireNotNull(apiKey), source, settings, languages, cloudModel, chatGPTAccount, serviceTier), onDevice = false)
    }

    suspend fun transform(sourceText: String, instruction: String): Either<DictationError, TextAnswer<TextTransformationResult>> =
        OnDeviceText.prefer(
            onDevice = if (!onDevice) null else {
                {
                    GeminiNano.generate(OnDeviceText.TRANSFORMATION_INSTRUCTIONS, OnDeviceText.input(instruction, sourceText))
                        .map { TextTransformationResult(OnDeviceText.reply(it), message = null) }
                        .mapLeft { if (it == DictationError.EmptyResult) DictationError.EmptyTransformation else it }
                }
            },
            cloud = if (!hasCloud) null else {
                { client.transformText(requireNotNull(apiKey), sourceText, instruction, cloudModel, chatGPTAccount, serviceTier) }
            },
        )

    /** [failed] is a call the server refused, which the model may correct once. */
    suspend fun decide(command: String, functions: List<AssistantFunction>, failed: FailedCall? = null): Either<DictationError, TextAnswer<AssistantDecision>> =
        OnDeviceText.prefer(
            onDevice = if (!onDevice) null else { { OnDeviceAssistant.decide(command, functions, failed) } },
            cloud = if (!hasCloud) null else {
                { client.assistantDecision(requireNotNull(apiKey), command, functions, cloudModel, chatGPTAccount, failed) }
            },
        )
}
