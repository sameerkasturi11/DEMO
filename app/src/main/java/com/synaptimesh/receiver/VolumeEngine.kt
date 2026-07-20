package com.synaptimesh.receiver

import android.content.Context
import android.media.AudioManager

class VolumeEngine(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setVolumePercentage(percent: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (max * percent).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, AudioManager.FLAG_SHOW_UI)
    }

    fun execute(commandUpper: String): String {
        return when (commandUpper) {
            "RIGHT_VOLUMEUP" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "200 OK"
            }
            "RIGHT_VOLUMEDOWN", "DROP" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "200 OK"
            }
            else -> "500 Internal Server Error"
        }
    }
}
