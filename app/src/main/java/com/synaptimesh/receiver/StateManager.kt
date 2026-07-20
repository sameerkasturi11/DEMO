package com.synaptimesh.receiver

import org.json.JSONObject

enum class MachineState {
    IDLE,
    CONNECTING,
    CONNECTED,
    OPENING_APP,
    SEARCHING,
    PLAYING,
    READY
}

object StateManager {
    var currentState: MachineState = MachineState.IDLE
        private set

    // Optional listener to publish state changes to MQTT
    var onStateChangedListener: ((MachineState) -> Unit)? = null

    fun transitionTo(newState: MachineState) {
        if (currentState != newState) {
            currentState = newState
            onStateChangedListener?.invoke(newState)
        }
    }
}
