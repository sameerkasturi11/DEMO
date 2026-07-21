package com.synaptimesh.receiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent

class MediaEngine(private val context: Context) {
    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun execute(commandUpper: String): String {
        var executionResult = ""
        var resolvedSession = false
        
        try {
            val componentName = ComponentName(context, MyNotificationListener::class.java)
            val activeSessions = mediaSessionManager.getActiveSessions(componentName)
            if (!activeSessions.isNullOrEmpty()) {
                val controller = activeSessions.find { 
                    it.packageName.contains("jiosaavn") 
                } ?: activeSessions.find {
                    it.packageName.contains("spotify") || it.packageName.contains("music")
                } ?: activeSessions[0]
                
                val controls = controller.transportControls
                when (commandUpper) {
                    "RIGHT_PLAY_PAUSE" -> {
                        val pbState = controller.playbackState?.state
                        if (pbState == PlaybackState.STATE_PLAYING) {
                            controls.pause()
                            executionResult = "200 OK (MediaController Pause)"
                        } else {
                            controls.play()
                            executionResult = "200 OK (MediaController Play)"
                        }
                    }
                    "RIGHT_NEXTTRACK" -> {
                        controls.skipToNext()
                        executionResult = "200 OK (MediaController Next)"
                    }
                    "RIGHT_PREVTRACK" -> {
                        controls.skipToPrevious()
                        executionResult = "200 OK (MediaController Previous)"
                    }
                }
                resolvedSession = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (!resolvedSession) {
            val keyCode = when (commandUpper) {
                "RIGHT_PLAY_PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "RIGHT_NEXTTRACK" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "RIGHT_PREVTRACK" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> 0
            }
            if (keyCode != 0) {
                val eventTime = SystemClock.uptimeMillis()
                val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
                }
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
                }
                context.sendOrderedBroadcast(downIntent, null)
                context.sendOrderedBroadcast(upIntent, null)

                audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
                audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
                executionResult = "200 OK (KeyEvent Fallback)"
            } else {
                executionResult = "500 Internal Server Error"
            }
        }
        
        return executionResult
    }

    fun isPlaying(): Boolean {
        try {
            val componentName = ComponentName(context, MyNotificationListener::class.java)
            val activeSessions = mediaSessionManager.getActiveSessions(componentName)
            if (!activeSessions.isNullOrEmpty()) {
                val controller = activeSessions.find { 
                    it.packageName.contains("jiosaavn") 
                } ?: activeSessions[0]
                return controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    fun play(): String {
        var executionResult = ""
        var resolvedSession = false
        
        try {
            val componentName = ComponentName(context, MyNotificationListener::class.java)
            val activeSessions = mediaSessionManager.getActiveSessions(componentName)
            if (!activeSessions.isNullOrEmpty()) {
                val controller = activeSessions.find { 
                    it.packageName.contains("jiosaavn") 
                } ?: activeSessions[0]
                
                controller.transportControls.play()
                executionResult = "MediaController.transportControls.play() SENT"
                resolvedSession = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (!resolvedSession) {
            val keyCode = KeyEvent.KEYCODE_MEDIA_PLAY
            val eventTime = SystemClock.uptimeMillis()
            
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            }
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
            }
            context.sendOrderedBroadcast(downIntent, null)
            context.sendOrderedBroadcast(upIntent, null)

            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
            executionResult = "KEYCODE_MEDIA_PLAY SENT"
        }
        
        return executionResult
    }
}
