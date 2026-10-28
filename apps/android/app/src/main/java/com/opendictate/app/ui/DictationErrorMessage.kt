package com.opendictate.app.ui

import android.content.res.Resources
import com.opendictate.app.R
import com.opendictate.app.model.DictationError

/** Exhaustive, localized presentation; raw response/exception messages never reach the UI. */
internal fun DictationError.userMessage(resources: Resources): String = when (this) {
    is DictationError.Http -> resources.getString(R.string.error_openai_code, status)
    DictationError.Network -> resources.getString(R.string.error_no_internet)
    DictationError.Timeout -> resources.getString(R.string.error_openai_timeout)
    DictationError.InvalidResponse -> resources.getString(R.string.error_reason_invalid_response)
    DictationError.EmptyResult -> resources.getString(R.string.error_speech_not_recognized)
    DictationError.StreamIncomplete -> resources.getString(R.string.error_connection_closed)
    is DictationError.Provider -> resources.getString(R.string.error_reason_provider)
    is DictationError.ChatGPT -> resources.getString(R.string.chatgpt_request_failed)
    DictationError.MicrophonePermission -> resources.getString(R.string.error_reason_microphone_permission)
    DictationError.MissingApiKey -> resources.getString(R.string.error_add_api_key)
    DictationError.MissingRecording -> resources.getString(R.string.error_no_last_dictation)
    DictationError.NothingToTransform -> resources.getString(R.string.error_nothing_to_transform)
    DictationError.EmptyTransformation -> resources.getString(R.string.error_transformation_empty)
    is DictationError.Rejected -> resources.getString(R.string.postprocessing_failed)
    DictationError.Unexpected -> resources.getString(R.string.error_transcription_failed)
}
