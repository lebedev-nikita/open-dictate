package com.opendictate.app.network

import arrow.core.Either
import com.opendictate.app.chatgpt.ChatGPTException
import com.opendictate.app.model.DiagnosticException
import com.opendictate.app.model.DictationError
import java.io.IOException
import org.json.JSONException

/** Translate expected transport/decoding failures; cancellation and defects escape. */
internal inline fun <A> requestAttempt(block: () -> A): Either<DictationError, A> =
    Either.catch(block).mapLeft { error ->
        when (error) {
            is IOException, is JSONException, is DiagnosticException, is ChatGPTException -> DictationError.from(error)
            else -> throw error
        }
    }
