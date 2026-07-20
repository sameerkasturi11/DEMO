package com.synaptimesh.receiver

import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CommandDispatcher(private val context: Context) {
    private val mediaEngine = MediaEngine(context)
    private val volumeEngine = VolumeEngine(context)
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun startProcessing() {
        scope.launch {
            for (request in CommandQueue.channel) {
                dispatch(request)
            }
        }
    }

    private suspend fun dispatch(request: CommandRequest) {
        val commandUpper = request.command.uppercase(java.util.Locale.getDefault())
        val queueDelayMs = System.currentTimeMillis() - request.receivedTimeMs
        
        EventBus.emit(AutomationEvent.Started(request.command, request, queueDelayMs))
        
        try {
            when (commandUpper) {
                "RIGHT_PLAY_PAUSE", "RIGHT_NEXTTRACK", "RIGHT_PREVTRACK" -> {
                    mediaEngine.execute(commandUpper)
                    EventBus.emit(AutomationEvent.Completed(request.command, request, ResultType.SUCCESS, queueDelayMs = queueDelayMs))
                }
                "RIGHT_VOLUMEUP", "RIGHT_VOLUMEDOWN", "DROP" -> {
                    volumeEngine.execute(commandUpper)
                    EventBus.emit(AutomationEvent.Completed(request.command, request, ResultType.SUCCESS, queueDelayMs = queueDelayMs))
                }
                "RIGHT_LAUNCH_JIOSAAVN", "RIGHT_SEARCH_ALBUM_PLAYLIST", "RIGHT_SEARCH_PLAYLIST", "RIGHT_RETURN_TO_HOME" -> {
                    val service = SynaptiMeshAccessibilityService.instance
                    if (service != null) {
                        service.executeAutomation(request, queueDelayMs)
                    } else {
                        if (commandUpper == "RIGHT_LAUNCH_JIOSAAVN" || commandUpper == "RIGHT_SEARCH_ALBUM_PLAYLIST" || commandUpper == "RIGHT_SEARCH_PLAYLIST") {
                             val launchIntent = context.packageManager.getLaunchIntentForPackage("com.jio.media.jiobeats")
                             if (launchIntent != null) {
                                 launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                 context.startActivity(launchIntent)
                                 EventBus.emit(AutomationEvent.Completed(request.command, request, ResultType.SUCCESS, queueDelayMs = queueDelayMs))
                             } else {
                                 EventBus.emit(AutomationEvent.Completed(request.command, request, ResultType.FAILED, reason = "App not installed", queueDelayMs = queueDelayMs))
                             }
                        }
                    }
                }
                else -> {
                    EventBus.emit(AutomationEvent.Completed(request.command, request, ResultType.FAILED, reason = "Invalid command", queueDelayMs = queueDelayMs))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            EventBus.emit(AutomationEvent.Completed(request.command, request, ResultType.FAILED, reason = e.message ?: "Exception", queueDelayMs = queueDelayMs))
        }
    }
}
