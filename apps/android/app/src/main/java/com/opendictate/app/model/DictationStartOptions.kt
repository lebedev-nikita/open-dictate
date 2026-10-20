package com.opendictate.app.model

/** Resolves a one-session choice without changing the main button's saved settings. */
data class DictationStartOptions(val model: TranscriptionModel, val postProcessing: PostProcessingSettings) {
    companion object {
        fun resolve(model: TranscriptionModel, postProcessing: PostProcessingSettings,
                    postProcessingOverride: Boolean? = null): DictationStartOptions {
            val sessionModel = if (postProcessingOverride != null) TranscriptionModel.ACCURATE else model
            var processing = postProcessing.copy(enabled = postProcessingOverride ?: postProcessing.enabled)
                .forMode(sessionModel)
            // A forced processing session still has a useful default when all rules are off.
            if (postProcessingOverride == true && !processing.shouldProcess) processing = processing.copy(punctuation = true)
            return DictationStartOptions(sessionModel, processing)
        }
    }
}
