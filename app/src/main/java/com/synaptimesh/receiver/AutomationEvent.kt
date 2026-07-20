package com.synaptimesh.receiver

enum class ResultType {
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED
}

sealed class AutomationEvent {
    data class Started(val command: String, val request: CommandRequest, val queueDelayMs: Long) : AutomationEvent()
    data class Progress(val command: String, val step: String) : AutomationEvent()
    data class Completed(
        val command: String,
        val request: CommandRequest,
        val result: ResultType,
        val reason: String = "",
        val executionTimeMs: Long = 0,
        val queueDelayMs: Long = 0
    ) : AutomationEvent()
}
