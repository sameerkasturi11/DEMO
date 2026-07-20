package com.synaptimesh.receiver

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

enum class DiagnosticStatus {
    PASS, WARNING, FAIL
}

data class DiagnosticItem(
    val title: String,
    val status: DiagnosticStatus,
    val message: String
)

class SystemDiagnostics(private val context: Context) {

    fun isAccessibilityEnabled(): Boolean {
        return SynaptiMeshAccessibilityService.instance != null
    }

    fun isJioSaavnInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.jio.media.jiobeats", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || 
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun runDiagnostics(mqttState: String): List<DiagnosticItem> {
        val list = mutableListOf<DiagnosticItem>()

        // 1. MQTT State
        val mqttStatus = when (mqttState) {
            "Connected" -> DiagnosticStatus.PASS
            "Connecting...", "Reconnecting..." -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.FAIL
        }
        list.add(DiagnosticItem("MQTT Broker", mqttStatus, mqttState))

        // 2. Accessibility
        if (isAccessibilityEnabled()) {
            list.add(DiagnosticItem("Accessibility", DiagnosticStatus.PASS, "Enabled"))
        } else {
            list.add(DiagnosticItem("Accessibility", DiagnosticStatus.FAIL, "Disabled"))
        }

        // 3. JioSaavn
        if (isJioSaavnInstalled()) {
            list.add(DiagnosticItem("JioSaavn", DiagnosticStatus.PASS, "Installed"))
        } else {
            list.add(DiagnosticItem("JioSaavn", DiagnosticStatus.FAIL, "Not Installed"))
        }

        // 4. Network
        if (isWifiConnected()) {
            list.add(DiagnosticItem("Network", DiagnosticStatus.PASS, "Connected"))
        } else {
            list.add(DiagnosticItem("Network", DiagnosticStatus.FAIL, "No Internet"))
        }

        return list
    }
}
