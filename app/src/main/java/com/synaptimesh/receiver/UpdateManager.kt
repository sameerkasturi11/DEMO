package com.synaptimesh.receiver

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

class UpdateManager(private val context: Context) {

    fun checkForUpdates(serverUrl: String) {
        thread {
            try {
                val versionJsonStr = URL("$serverUrl/version.json").readText()
                val json = JSONObject(versionJsonStr)
                val latestVersion = json.getInt("versionCode")
                
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersion = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    pInfo.versionCode
                }
                
                if (latestVersion > currentVersion) {
                    val apkUrl = json.getString("apkUrl")
                    val checksum = json.optString("sha256", "")
                    downloadApk(apkUrl, checksum)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun downloadApk(apkUrl: String, checksum: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("SynaptiMesh Update")
            .setDescription("Downloading latest version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "synaptimesh_update.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
    }
}
