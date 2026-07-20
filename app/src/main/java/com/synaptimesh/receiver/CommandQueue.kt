package com.synaptimesh.receiver

import kotlinx.coroutines.channels.Channel

data class CommandRequest(
    val command: String,
    val messageId: String,
    val confidence: Double,
    val sentTimeMs: Long,
    val protocolVersion: Int = 1,
    val payload: String? = null,
    val receivedTimeMs: Long = System.currentTimeMillis()
)

object CommandQueue {
    val channel = Channel<CommandRequest>(Channel.UNLIMITED)

    fun push(request: CommandRequest) {
        channel.trySend(request)
    }
}
