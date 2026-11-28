package com.opendictate.app.model

/**
 * One entry of the dictation model list. The model decides the mode: a Realtime model streams
 * text while the user speaks, a file model transcribes the finished recording, and the phone's
 * own recognizer streams without a key.
 */
data class DictationChoice(val mode: TranscriptionModel, val modelId: String) {
    /** The OpenAI model's name; the phone's recognizer is named by the screen in its language. */
    val title: String? get() = if (mode == TranscriptionModel.ON_DEVICE) null else title(modelId)

    companion object {
        val ON_DEVICE = DictationChoice(TranscriptionModel.ON_DEVICE, OnDeviceModels.ID)

        /** File models first, then Realtime models; the phone's recognizer is offered where it works, or while chosen. */
        fun options(catalog: ModelCatalog, onDeviceOffered: Boolean, selected: DictationChoice): List<DictationChoice> = buildList {
            catalog.accurate.ifEmpty { ModelCatalog.DEFAULT.accurate }.forEach { add(DictationChoice(TranscriptionModel.ACCURATE, it)) }
            catalog.live.ifEmpty { ModelCatalog.DEFAULT.live }.forEach { add(DictationChoice(TranscriptionModel.LIVE, it)) }
            if (onDeviceOffered || selected.mode == TranscriptionModel.ON_DEVICE) add(ON_DEVICE)
        }

        fun selected(mode: TranscriptionModel, liveModelId: String, accurateModelId: String): DictationChoice = when (mode) {
            TranscriptionModel.LIVE -> DictationChoice(mode, liveModelId)
            TranscriptionModel.ACCURATE -> DictationChoice(mode, accurateModelId)
            TranscriptionModel.ON_DEVICE -> ON_DEVICE
        }

        fun title(modelId: String): String = when (modelId) {
            "gpt-transcribe" -> "GPT Transcribe"
            "gpt-live-transcribe" -> "GPT Live Transcribe"
            else -> modelId
        }
    }
}
