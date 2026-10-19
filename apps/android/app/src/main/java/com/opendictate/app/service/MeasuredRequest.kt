package com.opendictate.app.service

internal data class MeasuredRequest<T>(val value: T, val milliseconds: Long)

internal suspend fun <T> measureRequest(nowMillis: () -> Long, request: suspend () -> T): MeasuredRequest<T> {
    val started = nowMillis()
    val value = request()
    return MeasuredRequest(value, (nowMillis() - started).coerceAtLeast(0))
}
