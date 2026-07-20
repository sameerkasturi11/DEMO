package com.synaptimesh.receiver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

class SynaptiMeshAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SynaptiMeshAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window state changes or content changes can be tracked here
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }


    suspend fun executeAutomation(request: CommandRequest, queueDelayMs: Long = 0) {
        val commandUpper = request.command.uppercase(java.util.Locale.getDefault())
        val executionStartTime = System.currentTimeMillis()
        
        withContext(Dispatchers.Default) {
            try {
                // Check for a generic script
                val scriptJson = ScriptStore.getScript(request.command)
                if (scriptJson != null) {
                    StateManager.transitionTo(MachineState.OPENING_APP)
                    val engine = GenericAutomationEngine(this@SynaptiMeshAccessibilityService)
                    engine.execute(scriptJson, request, queueDelayMs)
                } else if (commandUpper == "RIGHT_LAUNCH_JIOSAAVN") {
                    // Fallback to simple hardcoded launch if no script
                    StateManager.transitionTo(MachineState.OPENING_APP)
                    val launchIntent = packageManager.getLaunchIntentForPackage("com.jio.media.jiobeats")
                    if (launchIntent != null) {
                        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(launchIntent)
                        delay(5000)
                        StateManager.transitionTo(MachineState.READY)
                        EventBus.emit(AutomationEvent.Completed(
                            request.command, request, ResultType.SUCCESS, 
                            queueDelayMs = queueDelayMs, executionTimeMs = System.currentTimeMillis() - executionStartTime
                        ))
                    } else {
                        StateManager.transitionTo(MachineState.READY)
                        EventBus.emit(AutomationEvent.Completed(
                            request.command, request, ResultType.FAILED, reason = "App not installed", 
                            queueDelayMs = queueDelayMs, executionTimeMs = System.currentTimeMillis() - executionStartTime
                        ))
                    }
                } else {
                    throw java.lang.Exception("No script found for command: ${request.command}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                MainActivity.appendLog("[AUTO] ${e.message}")
                StateManager.transitionTo(MachineState.READY)
                EventBus.emit(AutomationEvent.Completed(
                    request.command, request, ResultType.FAILED, reason = e.message ?: "Unknown Error", 
                    queueDelayMs = queueDelayMs, executionTimeMs = System.currentTimeMillis() - executionStartTime
                ))
            }
        }
    }
    
    private fun findNodeWithRetry(
        matcher: (AccessibilityNodeInfo) -> Boolean,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val node = pollForNode(matcher, timeoutMs)
        if (node != null) return node
        
        MainActivity.appendLog("[AUTO] Retrying UI element search...")
        return pollForNode(matcher, timeoutMs / 2)
    }

    private fun pollForCondition(
        condition: () -> Boolean,
        timeoutMs: Long,
        intervalMs: Long = 50
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        return false
    }

    private fun pollForNode(
        matcher: (AccessibilityNodeInfo) -> Boolean,
        timeoutMs: Long = 3000,
        intervalMs: Long = 50
    ): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null) {
                val queue = java.util.LinkedList<AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val node = queue.poll()
                    if (node != null && matcher(node)) {
                        return node
                    }
                    if (node != null) {
                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { queue.add(it) }
                        }
                    }
                }
            }
            Thread.sleep(intervalMs)
        }
        return null
    }
    
    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.centerX() > 0 && rect.centerY() > 0) {
            performTap(rect.centerX().toFloat(), rect.centerY().toFloat())
        } else {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
}
