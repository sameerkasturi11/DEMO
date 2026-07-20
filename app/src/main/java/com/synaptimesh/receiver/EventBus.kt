package com.synaptimesh.receiver

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private val _events = MutableSharedFlow<AutomationEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun emit(event: AutomationEvent) {
        _events.tryEmit(event)
    }
}
