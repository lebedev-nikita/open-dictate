package com.opendictate.app.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets settings learn a hardware key from the accessibility service, which sees key events before
 * apps and the system volume handling do. Key events are filtered only while capturing or bound.
 */
internal object DictationKeyCapture {
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    private val mutableCaptured = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val captured: SharedFlow<Int> = mutableCaptured.asSharedFlow()

    fun begin() {
        mutableActive.value = true
    }

    fun end() {
        mutableActive.value = false
    }

    fun report(keyCode: Int) {
        if (mutableActive.value) mutableCaptured.tryEmit(keyCode)
    }
}
