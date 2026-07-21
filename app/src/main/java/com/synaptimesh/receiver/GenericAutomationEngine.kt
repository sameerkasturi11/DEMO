package com.synaptimesh.receiver

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import kotlinx.coroutines.delay
import android.graphics.Rect
import android.graphics.Path
import android.accessibilityservice.GestureDescription
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object ScriptStore {
    val searchPlaylist = """
    {
      "script": "search_playlist",
      "version": "1.0",
      "steps": [
        {
          "action": "launch",
          "package": "com.jio.media.jiobeats",
          "if_not_running": true,
          "timeout": 5000,
          "retry": 0,
          "on_failure": "abort"
        },
        {
          "action": "click",
          "target": "Search",
          "target_id": "search_button",
          "timeout": 12000,
          "retry": 1,
          "on_failure": "abort"
        },
        {
          "action": "click",
          "target": "Music",
          "timeout": 5000,
          "retry": 1,
          "on_failure": "ignore"
        },
        {
          "action": "focus",
          "is_editable": true,
          "timeout": 5000,
          "retry": 1,
          "on_failure": "abort"
        },
        {
          "action": "set_text",
          "value": "${'$'}{playlist}",
          "timeout": 2000,
          "retry": 1,
          "on_failure": "abort"
        },
        {
          "action": "click",
          "target": "${'$'}{playlist_target}",
          "fallback_target": "${'$'}{playlist_first_word}",
          "is_editable": false,
          "timeout": 5000,
          "retry": 1,
          "on_failure": "abort"
        },
        {
          "action": "click_first_song",
          "timeout": 5000,
          "on_failure": "continue"
        },
        {
          "action": "verify_media",
          "state": "PLAYING",
          "timeout": 4000,
          "retry": 0,
          "on_failure": "continue"
        },
        {
          "action": "media_play",
          "timeout": 2000,
          "retry": 0,
          "on_failure": "continue"
        },
        {
          "action": "verify_media",
          "state": "PLAYING",
          "timeout": 4000,
          "retry": 0,
          "on_failure": "abort"
        }
      ]
    }
    """.trimIndent()

    val returnToHome = """
    {
      "script": "return_to_home",
      "version": "1.0",
      "steps": [
        {
          "action": "global_home",
          "timeout": 2000,
          "retry": 0,
          "on_failure": "continue"
        }
      ]
    }
    """.trimIndent()

    fun getScript(action: String): String? {
        return when (action.lowercase(java.util.Locale.getDefault())) {
            "search_playlist", "right_search_playlist", "right_search_album_playlist" -> searchPlaylist
            "right_return_to_home", "return_to_home" -> returnToHome
            else -> null
        }
    }
}

class GenericAutomationEngine(private val service: SynaptiMeshAccessibilityService) {

    private fun searchNode(node: AccessibilityNodeInfo, matcher: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matcher(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNode(child, matcher)
            if (found != null) return found
        }
        return null
    }

    private fun isNodeMatch(
        it: AccessibilityNodeInfo,
        target: String,
        targetId: String,
        fallbackTarget: String = "",
        fallbackId: String = "",
        targetClass: String = "",
        isEditable: Boolean? = null,
        targetDesc: String = ""
    ): Boolean {
        val t = it.text?.toString() ?: ""
        val c = it.contentDescription?.toString() ?: ""
        val h = if (android.os.Build.VERSION.SDK_INT >= 26) it.hintText?.toString() ?: "" else ""
        
        val textMatch = target.isNotEmpty() && (t.contains(target, true) || c.contains(target, true) || h.contains(target, true))
        val idMatch = targetId.isNotEmpty() && it.viewIdResourceName?.contains(targetId, true) == true
        val fallbackTextMatch = fallbackTarget.isNotEmpty() && (t.contains(fallbackTarget, true) || c.contains(fallbackTarget, true) || h.contains(fallbackTarget, true))
        val fallbackIdMatch = fallbackId.isNotEmpty() && it.viewIdResourceName?.contains(fallbackId, true) == true
        val descMatch = targetDesc.isNotEmpty() && c.contains(targetDesc, true)
        
        val hasBaseTarget = target.isNotEmpty() || targetId.isNotEmpty() || fallbackTarget.isNotEmpty() || fallbackId.isNotEmpty() || targetDesc.isNotEmpty()
        val baseMatch = if (hasBaseTarget) (textMatch || idMatch || fallbackTextMatch || fallbackIdMatch || descMatch) else true
        
        val classMatch = if (targetClass.isNotEmpty()) it.className?.toString()?.contains(targetClass, true) == true else true
        val editableMatch = if (isEditable != null) (it.isEditable == isEditable) else true
        
        return baseMatch && classMatch && editableMatch
    }
    
    private fun dumpTree(node: AccessibilityNodeInfo?, prefix: String = "", dumpLogs: MutableList<String>? = null) {
        if (node == null) return
        val t = node.text?.toString() ?: ""
        val c = node.contentDescription?.toString() ?: ""
        val h = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString() ?: "" else ""
        
        if (t.isNotEmpty() || c.isNotEmpty() || h.isNotEmpty()) {
            val msg = "[DUMP] $prefix class=${node.className} text='$t' desc='$c' hint='$h'"
            MainActivity.appendLog(msg)
            dumpLogs?.add("  -> $msg")
        }
        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), "$prefix  ", dumpLogs)
        }
    }

    private suspend fun pollForNode(matcher: (AccessibilityNodeInfo) -> Boolean, timeoutMs: Long): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val found = searchNode(root, matcher)
                if (found != null) return found
            }
            delay(50)
        }
        return null
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    MainActivity.appendLog("[AUTO] Successfully executed ACTION_CLICK")
                    return true
                }
            }
            current = current.parent
        }
        
        // 3: Fallback to Gesture Tap
        MainActivity.appendLog("[AUTO] ACTION_CLICK failed or node/parents not clickable. Falling back to Gesture Tap...")
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        val path = Path()
        path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
            
        return suspendCancellableCoroutine { cont ->
            val result = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    MainActivity.appendLog("[AUTO] Gesture Tap SUCCESS on [bounds=$rect]")
                    cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    MainActivity.appendLog("[AUTO] Gesture Tap CANCELLED on [bounds=$rect]")
                    cont.resume(false)
                }
            }, null)
            
            if (!result) {
                MainActivity.appendLog("[AUTO] Failed to dispatch Gesture Tap.")
                cont.resume(false)
            }
        }
    }

    private suspend fun dispatchGestureTap(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
            
        return suspendCancellableCoroutine { cont ->
            val result = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cont.resume(false)
                }
            }, null)
            
            if (!result) {
                cont.resume(false)
            }
        }
    }

    suspend fun execute(scriptJson: String, request: CommandRequest, queueDelayMs: Long) {
        val executionStartTime = System.currentTimeMillis()
        val stepLogs = mutableListOf<String>()

        fun logStep(stepIdx: Int, action: String, status: String, reason: String = "") {
            val msg = "STEP ${stepIdx + 1} $action - $status" + if (reason.isNotEmpty()) " ($reason)" else ""
            stepLogs.add(msg)
            MainActivity.appendLog("[AUTO] $msg")
            EventBus.emit(AutomationEvent.Progress(request.command, msg))
        }

        fun logDetail(msg: String) {
            stepLogs.add("  -> $msg")
            MainActivity.appendLog("[AUTO] $msg")
        }

        try {
            val json = JSONObject(scriptJson)
            val steps = json.getJSONArray("steps")
            
            // Extract parameters from payload if it exists
            val parameters = mutableMapOf<String, String>()
            if (request.payload != null) {
                try {
                    val reqJson = JSONObject(request.payload)
                    if (reqJson.has("parameters")) {
                        val params = reqJson.getJSONObject("parameters")
                        params.keys().forEach { k ->
                            parameters[k] = params.getString(k)
                        }
                    } else if (reqJson.has("playlist")) {
                         parameters["playlist"] = reqJson.getString("playlist")
                    }
                } catch (e: Exception) {}
            }
            
            // Default variable fallbacks
            if (!parameters.containsKey("playlist")) parameters["playlist"] = "Sai Abhiyankar Telugu Songs"
            parameters["playlist_first_word"] = parameters["playlist"]!!.split(" ").firstOrNull() ?: parameters["playlist"]!!
            
            // Map demo inputs to actual on-screen text
            if (parameters["playlist"] == "Anirudh Telugu Hits") {
                parameters["playlist_target"] = "Let's Play - Anirudh Ravichander"
            } else {
                parameters["playlist_target"] = parameters["playlist"]!!
            }
            
            var lastFocusedNode: AccessibilityNodeInfo? = null

            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val action = step.getString("action")
                val timeout = step.optLong("timeout", 2000)
                val retries = step.optInt("retry", 0)
                val onFailure = step.optString("on_failure", "abort")
                
                var success = false
                var errorMsg = ""
                
                for (attempt in 0..retries) {
                    try {
                        if (attempt > 0) MainActivity.appendLog("[AUTO] Retrying STEP ${i+1}...")
                        
                        when (action) {
                            "launch" -> {
                                val pkg = step.getString("package")
                                val ifNotRunning = step.optBoolean("if_not_running", false)
                                
                                val isRunning = service.rootInActiveWindow?.packageName?.toString() == pkg
                                if (ifNotRunning && isRunning) {
                                    success = true
                                    break
                                }
                                
                                val launchIntent = service.packageManager.getLaunchIntentForPackage(pkg)
                                launchIntent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                launchIntent?.let { service.startActivity(it) }
                                
                                val loaded = pollForNode({ it.packageName?.toString() == pkg }, timeout)
                                if (loaded != null) {
                                    success = true
                                } else {
                                    errorMsg = "Timeout waiting for package ${pkg}"
                                }
                            }
                            "click" -> {
                                var target = step.optString("target", "")
                                parameters.forEach { (k, v) -> target = target.replace("$" + "{${k}}", v) }
                                
                                val targetId = step.optString("target_id", "")
                                val targetDesc = step.optString("target_desc", "")
                                val fallbackTarget = step.optString("fallback_target", "")
                                val fallbackId = step.optString("fallback_id", "")
                                val targetClass = step.optString("target_class", "")
                                val isEditable = if (step.has("is_editable")) step.getBoolean("is_editable") else null
                                
                                val node = pollForNode({ isNodeMatch(it, target, targetId, fallbackTarget, fallbackId, targetClass, isEditable, targetDesc) }, timeout)
                                
                                if (node != null) {
                                    val nText = node.text?.toString() ?: "null"
                                    val nDesc = node.contentDescription?.toString() ?: "null"
                                    val nClass = node.className?.toString() ?: "null"
                                    val nId = node.viewIdResourceName ?: "null"
                                    
                                    val clicked = clickNode(node)
                                    success = clicked
                                    
                                    if (clicked) {
                                        logDetail("Clicked node [text=$nText, desc=$nDesc, class=$nClass, id=$nId]")
                                    } else {
                                        errorMsg = "Node found but NOT clickable [text=$nText, desc=$nDesc, class=$nClass, id=$nId]"
                                        logDetail(errorMsg)
                                    }
                                } else {
                                    errorMsg = "Node not found for targets [target=$target, target_desc=$targetDesc, id=$targetId, fallbacks=...]"
                                    logDetail("Dumping tree because click failed:")
                                    dumpTree(service.rootInActiveWindow, "", stepLogs)
                                }
                            }
                            "focus" -> {
                                val target = step.optString("target", "")
                                val targetId = step.optString("target_id", "")
                                val targetDesc = step.optString("target_desc", "")
                                val fallbackTarget = step.optString("fallback_target", "")
                                val fallbackId = step.optString("fallback_id", "")
                                val targetClass = step.optString("target_class", "")
                                val isEditable = if (step.has("is_editable")) step.getBoolean("is_editable") else null
                                
                                val node = pollForNode({ isNodeMatch(it, target, targetId, fallbackTarget, fallbackId, targetClass, isEditable, targetDesc) }, timeout)
                                if (node != null) {
                                    success = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || clickNode(node)
                                    lastFocusedNode = node
                                } else {
                                    errorMsg = "Node not found for focus targets [${target}, ${targetId}, ${fallbackTarget}, ${fallbackId}, class=${targetClass}]"
                                    MainActivity.appendLog("[AUTO] Dumping tree because focus failed:")
                                    dumpTree(service.rootInActiveWindow, "", stepLogs)
                                }
                            }
                            "set_text" -> {
                                var value = step.getString("value")
                                parameters.forEach { (k, v) -> value = value.replace("$" + "{${k}}", v) }
                                
                                val target = step.optString("target", "")
                                val targetId = step.optString("target_id", "")
                                
                                val node = if (target.isNotEmpty() || targetId.isNotEmpty()) {
                                    pollForNode({ isNodeMatch(it, target, targetId) }, timeout)
                                } else {
                                    lastFocusedNode ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                }
                                
                                if (node != null) {
                                    val args = Bundle()
                                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                                    success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                    if (!success) {
                                        MainActivity.appendLog("[AUTO] ACTION_SET_TEXT failed. Falling back to Clipboard paste...")
                                        try {
                                            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("search_query", value)
                                            clipboard.setPrimaryClip(clip)
                                            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                            delay(500)
                                            success = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                            if (success) {
                                                MainActivity.appendLog("[AUTO] Pasted text successfully from clipboard.")
                                            } else {
                                                errorMsg = "ACTION_PASTE failed"
                                            }
                                        } catch (e: Exception) {
                                            errorMsg = "Clipboard fallback failed: ${e.message}"
                                        }
                                    }
                                } else {
                                    errorMsg = "No focused node available"
                                }
                            }
                            "trigger_search" -> {
                                var playlistName = step.optString("playlist", "")
                                parameters.forEach { (k, v) -> playlistName = playlistName.replace("$" + "{${k}}", v) }
                                
                                val rootNode = service.rootInActiveWindow
                                if (rootNode != null) {
                                    // 1. Try to find screen search button
                                    val searchTrigger = findSearchTriggerButton(rootNode)
                                    if (searchTrigger != null) {
                                        MainActivity.appendLog("[AUTO] Found search trigger button. Clicking it...")
                                        success = clickNode(searchTrigger)
                                    } else {
                                        // 2. Try finding suggestion
                                        val suggestion = findFirstSearchSuggestion(rootNode, playlistName)
                                        if (suggestion != null) {
                                            MainActivity.appendLog("[AUTO] Found search suggestion. Clicking it...")
                                            success = clickNode(suggestion)
                                        } else {
                                            // 3. Fallback search via IME
                                            MainActivity.appendLog("[AUTO] Triggering fallback search via IME on focused field...")
                                            if (android.os.Build.VERSION.SDK_INT >= 30) {
                                                val imeAction = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                                                success = lastFocusedNode?.performAction(imeAction.id) ?: false
                                            }
                                            if (!success) {
                                                success = lastFocusedNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                                            }
                                        }
                                    }
                                }
                                if (!success) errorMsg = "Failed to trigger search"
                            }
                            "verify_media" -> {
                                val state = step.getString("state")
                                val startTime = System.currentTimeMillis()
                                val engine = MediaEngine(service)
                                while (System.currentTimeMillis() - startTime < timeout) {
                                    if (state == "PLAYING" && engine.isPlaying()) {
                                        success = true
                                        break
                                    }
                                    delay(200)
                                }
                                if (!success) {
                                    errorMsg = "Media state did not become ${state}"
                                    logDetail(errorMsg)
                                }
                            }
                            "media_play" -> {
                                val engine = MediaEngine(service)
                                logDetail("Executing media_play fallback...")
                                val result = engine.play()
                                logDetail("Media Play Result: $result")
                                success = true
                            }
                            "click_first_song" -> {
                                var rootNode = service.rootInActiveWindow
                                var foundRow: AccessibilityNodeInfo? = null
                                
                                fun attemptFindRow(): AccessibilityNodeInfo? {
                                    if (rootNode == null) return null
                                    
                                    val containers = mutableListOf<AccessibilityNodeInfo>()
                                    fun findContainers(n: AccessibilityNodeInfo) {
                                        if (n.isScrollable || n.className?.toString()?.contains("RecyclerView") == true || n.className?.toString()?.contains("ListView") == true) {
                                            containers.add(n)
                                        }
                                        for (i in 0 until n.childCount) {
                                            n.getChild(i)?.let { findContainers(it) }
                                        }
                                    }
                                    findContainers(rootNode!!)
                                    
                                    logDetail("Found ${containers.size} scrollable/list containers")
                                    
                                    for (container in containers) {
                                        for (j in 0 until container.childCount) {
                                            val row = container.getChild(j) ?: continue
                                            
                                            val texts = mutableListOf<String>()
                                            fun collectTexts(n: AccessibilityNodeInfo) {
                                                val t = n.text?.toString()?.trim() ?: ""
                                                val desc = n.contentDescription?.toString()?.trim() ?: ""
                                                if (t.isNotEmpty() && t.length > 1) texts.add(t)
                                                if (desc.isNotEmpty() && desc.length > 1) texts.add(desc)
                                                
                                                for (k in 0 until n.childCount) {
                                                    n.getChild(k)?.let { collectTexts(it) }
                                                }
                                            }
                                            collectTexts(row)
                                            
                                            val isInvalidRow = texts.any {
                                                val lower = it.lowercase()
                                                lower.contains("play") || lower.contains("jiotune") || 
                                                lower.contains("favorite") || (lower.contains("song") && texts.size == 1)
                                            }
                                            
                                            if (!isInvalidRow && texts.size >= 2) {
                                                logDetail("Found valid row. Texts: $texts")
                                                return row
                                            }
                                        }
                                    }
                                    return null
                                }
                                
                                foundRow = attemptFindRow()
                                
                                if (foundRow == null && rootNode != null) {
                                    logDetail("No song rows found. Attempting scroll...")
                                    val scrollable = searchNode(rootNode!!) { it.isScrollable }
                                    if (scrollable != null) {
                                        scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                        kotlinx.coroutines.delay(1000)
                                        rootNode = service.rootInActiveWindow
                                        foundRow = attemptFindRow()
                                    } else {
                                        logDetail("No scrollable node found to scroll.")
                                    }
                                }
                                
                                if (foundRow != null) {
                                    success = clickNode(foundRow!!)
                                    if (success) {
                                        logDetail("Clicked song row container SUCCESS")
                                    }
                                } else {
                                    logDetail("Accessibility failed to find song rows. Attempting Gesture Fallback...")
                                    val referenceNode = searchNode(service.rootInActiveWindow ?: rootNode!!) { node ->
                                        val text = node.text?.toString() ?: ""
                                        text.contains("Songs", ignoreCase = true) || 
                                        text.contains("Fans", ignoreCase = true) ||
                                        text.contains("Let's Play", ignoreCase = true)
                                    }
                                    
                                    val displayMetrics = service.resources.displayMetrics
                                    var clickX = displayMetrics.widthPixels / 2f
                                    var clickY = displayMetrics.heightPixels * 0.65f // 65% down as fallback
                                    
                                    if (referenceNode != null) {
                                        val rect = android.graphics.Rect()
                                        referenceNode.getBoundsInScreen(rect)
                                        logDetail("Found reference node '${referenceNode.text}' at $rect")
                                        clickX = rect.centerX().toFloat()
                                        // The first track is usually just below the subtitle/stats line
                                        clickY = rect.bottom + (displayMetrics.density * 60) // roughly 60dp below
                                    } else {
                                        logDetail("No reference node found. Using blind gesture fallback at 65% screen height.")
                                    }
                                    
                                    logDetail("Dispatching gesture tap at X=$clickX, Y=$clickY")
                                    val gestureSuccess = dispatchGestureTap(clickX, clickY)
                                    if (gestureSuccess) {
                                        logDetail("Dispatched gesture tap SUCCESS")
                                        success = true
                                    } else {
                                        logDetail("Failed to dispatch gesture tap")
                                    }
                                }
                                
                                if (!success) {
                                    errorMsg = "Failed to find or click first valid song row container"
                                    dumpTree(service.rootInActiveWindow, "  -> TREE DUMP: ", stepLogs)
                                    logDetail(errorMsg)
                                }
                            }
                            "global_back" -> {
                                success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                if (!success) errorMsg = "GLOBAL_ACTION_BACK failed"
                                delay(500)
                            }
                            "global_home" -> {
                                success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                                if (!success) errorMsg = "GLOBAL_ACTION_HOME failed"
                                delay(500)
                            }

                            "app_home_loop" -> {
                                val maxBacks = step.optInt("max_backs", 4)
                                val appPackage = service.rootInActiveWindow?.packageName?.toString()
                                
                                var backPresses = 0
                                while (backPresses < maxBacks) {
                                    val homeTabs = service.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.jio.media.jiobeats:id/bottom_nav_home")
                                    if (!homeTabs.isNullOrEmpty() && homeTabs[0].isSelected) {
                                        success = true
                                        break
                                    }
                                    
                                    val performed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                    if (performed) {
                                        delay(1500) // Wait for UI to settle
                                        if (service.rootInActiveWindow?.packageName?.toString() != appPackage) {
                                            errorMsg = "App ${appPackage} exited unexpectedly during back navigation"
                                            break
                                        }
                                    }
                                    backPresses++
                                }
                                if (!success && errorMsg.isEmpty()) {
                                    errorMsg = "Failed to reach home after ${maxBacks} back presses."
                                }
                            }
                            else -> {
                                errorMsg = "Unknown action: ${action}"
                            }
                        }
                        
                        if (success) break
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Exception"
                    }
                }
                
                if (success) {
                    logStep(i, action, "SUCCESS")
                } else {
                    logStep(i, action, "FAILED", errorMsg)
                    if (onFailure == "abort") {
                        val isRetryable = errorMsg.contains("Node not found") || errorMsg.contains("Timeout")
                        val fullLog = stepLogs.joinToString("\n")
                        throw Exception(if (isRetryable) "RETRYABLE: STEP ${i+1} (${action}) Failed: ${errorMsg}\n\n=== EXECUTION LOGS ===\n$fullLog" else "NON_RETRYABLE: STEP ${i+1} (${action}) Failed: ${errorMsg}\n\n=== EXECUTION LOGS ===\n$fullLog")
                    }
                }
            }
            
            StateManager.transitionTo(MachineState.PLAYING)
            StateManager.transitionTo(MachineState.READY)
            EventBus.emit(AutomationEvent.Completed(
                request.command, request, ResultType.SUCCESS, 
                reason = stepLogs.joinToString(" | "), queueDelayMs = queueDelayMs, executionTimeMs = System.currentTimeMillis() - executionStartTime
            ))
            
        } catch (e: Exception) {
            e.printStackTrace()
            MainActivity.appendLog("[AUTO] ${e.message}")
            StateManager.transitionTo(MachineState.READY)
            
            val msg = e.message ?: "Unknown Error"
            val resultType = if (msg.contains("RETRYABLE")) ResultType.FAILED else ResultType.FAILED
            // You can use ResultType or pass retryable boolean for future enhancements
            EventBus.emit(AutomationEvent.Completed(
                request.command, request, resultType, 
                reason = msg, queueDelayMs = queueDelayMs, executionTimeMs = System.currentTimeMillis() - executionStartTime
            ))
        }
    }

    private fun findSearchTriggerButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val className = node.className?.toString() ?: ""
        val isEditText = className.contains("EditText") || node.isEditable

        if (!isEditText) {
            val desc = node.contentDescription?.toString()?.lowercase()?.trim()
            if (desc == "search") return node
            val text = node.text?.toString()?.lowercase()?.trim()
            if (text == "search") return node
        }

        for (i in 0 until node.childCount) {
            val result = findSearchTriggerButton(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    private fun findFirstSearchSuggestion(node: AccessibilityNodeInfo?, playlistName: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.lowercase()
        if (text != null && text.contains(playlistName.lowercase())) {
            if (node.isClickable) return node
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
        }

        val className = node.className?.toString() ?: ""
        if (className.contains("RecyclerView") || className.contains("ListView")) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null && child.isClickable) return child
            }
        }

        for (i in 0 until node.childCount) {
            val result = findFirstSearchSuggestion(node.getChild(i), playlistName)
            if (result != null) return result
        }
        return null
    }
}
