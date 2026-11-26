package com.opendictate.app.service

import com.opendictate.app.model.ExternalDictationOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ExternalDictationDelivery(val requestId: String, val outcome: ExternalDictationOutcome)

/**
 * Hands results from [DictationForegroundService] to the waiting external request. Transcripts stay
 * in memory only while their request is awaited, so abandoned results are never retained.
 */
internal class ExternalDictationExchange {
    private var awaited: String? = null
    private val mutableDelivery = MutableStateFlow<ExternalDictationDelivery?>(null)
    val delivery: StateFlow<ExternalDictationDelivery?> = mutableDelivery.asStateFlow()

    /** The request currently awaiting a result; a stop may arrive before the service starts it. */
    val awaitedRequestId: String?
        @Synchronized get() = awaited

    @Synchronized
    fun await(requestId: String) {
        awaited = requestId
        if (mutableDelivery.value?.requestId != requestId) mutableDelivery.value = null
    }

    @Synchronized
    fun deliver(requestId: String, outcome: ExternalDictationOutcome) {
        if (awaited == requestId) mutableDelivery.value = ExternalDictationDelivery(requestId, outcome)
    }

    @Synchronized
    fun release(requestId: String) {
        if (awaited == requestId) awaited = null
        if (mutableDelivery.value?.requestId == requestId) mutableDelivery.value = null
    }
}

internal val ExternalDictationBus = ExternalDictationExchange()
