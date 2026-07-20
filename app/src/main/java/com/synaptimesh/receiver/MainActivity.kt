package com.synaptimesh.receiver

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.Context
import android.net.wifi.WifiManager
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.content.ComponentName
import android.provider.Settings
import android.view.KeyEvent
import android.os.SystemClock
import android.media.AudioManager
import android.content.Intent
import android.provider.MediaStore
import android.app.SearchManager
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import java.net.InetSocketAddress
import javax.net.ssl.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        var activeMqttClient: MqttClient? = null
        var appContext: android.content.Context? = null
        
        fun publishAck(
            messageId: String, 
            command: String, 
            confidence: Double, 
            status: String, 
            resultCode: String, 
            sentTimeMs: Long,
            reason: String = "",
            queueDelayMs: Long = 0,
            executionTimeMs: Long = 0
        ) {
            val client = activeMqttClient ?: return
            try {
                val ack = JSONObject()
                ack.put("command_id", messageId)
                ack.put("command", command)
                ack.put("confidence", confidence)
                val timestampPrefix = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).format(Date())
                ack.put("timestamp", timestampPrefix)
                ack.put("status", status)
                ack.put("result_code", resultCode)
                ack.put("device_id", "Android_01")
                ack.put("sent_time_ms", sentTimeMs)
                
                if (reason.isNotEmpty()) {
                    ack.put("reason", reason)
                }
                ack.put("queue_delay_ms", queueDelayMs)
                ack.put("execution_time_ms", executionTimeMs)
                
                // Get Battery Level
                val bm = appContext?.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val batteryLevel = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                ack.put("battery_level", batteryLevel)
                
                val ackMessage = MqttMessage(ack.toString().toByteArray()).apply {
                    qos = 1
                    isRetained = false
                }
                client.publish("synaptimesh/ack", ackMessage)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        fun appendLog(msg: String) {
            val ctx = appContext as? MainActivity ?: return
            ctx.runOnUiThread {
                val timestampPrefix = SimpleDateFormat("[HH:mm:ss] ", Locale.getDefault()).format(Date())
                ctx.txtLogs.text = timestampPrefix + msg + "\n" + ctx.txtLogs.text
            }
        }
    }

    private lateinit var txtCommand: TextView
    private lateinit var txtAction: TextView
    private lateinit var txtConfidence: TextView
    private lateinit var txtLogs: TextView
    private lateinit var txtSysInfo: TextView

    private lateinit var systemDiagnostics: SystemDiagnostics
    private lateinit var diagnosticAdapter: DiagnosticAdapter
    private var currentMqttState = "Disconnected"
    private var hasPromptedAccessibility = false

    private var mqttClient: MqttClient? = null
    private lateinit var dispatcher: CommandDispatcher
    
    // Store the last received command for duplicate filtering
    private var lastCommand = ""
    
    // Store recent message IDs for Replay Protection
    private val processedMessageIds = mutableListOf<String>()

    private fun getSSLSocketFactory(): javax.net.ssl.SSLSocketFactory? {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val caInput = resources.openRawResource(R.raw.ca)
            val ca = cf.generateCertificate(caInput)
            caInput.close()

            val keyStoreType = KeyStore.getDefaultType()
            val keyStore = KeyStore.getInstance(keyStoreType)
            keyStore.load(null, null)
            keyStore.setCertificateEntry("ca", ca)

            val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
            val tmf = TrustManagerFactory.getInstance(tmfAlgorithm)
            tmf.init(keyStore)

            val clientStore = KeyStore.getInstance("PKCS12")
            val clientInput = resources.openRawResource(R.raw.android_client)
            clientStore.load(clientInput, "SynaptiMesh2026!".toCharArray())
            clientInput.close()

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(clientStore, "SynaptiMesh2026!".toCharArray())

            val context = SSLContext.getInstance("TLSv1.2")
            context.init(kmf.keyManagers, tmf.trustManagers, null)
            return context.socketFactory
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private val topic = "synaptimesh/commands"
    private val ackTopic = "synaptimesh/ack"
    private val controlTopic = "synaptimesh/control"

    // Canonical whitelisted commands
    private val validCommands = setOf(
        "RIGHT_PREVTRACK", "RIGHT_NEXTTRACK", "RIGHT_VOLUMEUP", 
        "RIGHT_VOLUMEDOWN", "RIGHT_PLAY_PAUSE", "RIGHT_LAUNCH_JIOSAAVN", 
        "RIGHT_SEARCH_ALBUM_PLAYLIST", "RIGHT_RETURN_TO_HOME"
    )

    // mDNS / NSD Auto-discovery variables
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscoveryActive = false
    private var hasResolvedService = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (!hasResolvedService) {
            stopDiscovery()
            runOnUiThread {
                txtLogs.text = "[NSD] Auto-discovery timeout, using fallback\n" + txtLogs.text
            }
            connectMQTT("172.18.9.82", 8883)
        }
    }


    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = this
        setContentView(R.layout.activity_main)

        // Hide action bar for full-screen clean dashboard look
        supportActionBar?.hide()

        txtCommand = findViewById(R.id.txtCommand)
        txtAction = findViewById(R.id.txtAction)
        txtConfidence = findViewById(R.id.txtConfidence)
        txtLogs = findViewById(R.id.txtLogs)
        txtSysInfo = findViewById(R.id.txtSysInfo)

        val rvDiagnostics: androidx.recyclerview.widget.RecyclerView = findViewById(R.id.rvDiagnostics)
        rvDiagnostics.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        systemDiagnostics = SystemDiagnostics(this)
        diagnosticAdapter = DiagnosticAdapter(emptyList())
        rvDiagnostics.adapter = diagnosticAdapter

        // Initialize Multicast Lock
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("SynaptiMeshMulticastLock").apply {
                setReferenceCounted(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        dispatcher = CommandDispatcher(this)
        dispatcher.startProcessing()
        
        scope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AutomationEvent.Started -> {
                        // Logged elsewhere
                    }
                    is AutomationEvent.Progress -> {
                        // Logged in appendLog
                    }
                    is AutomationEvent.Completed -> {
                        val status = if (event.result == ResultType.SUCCESS) "SUCCESS" else "FAILED"
                        val code = if (event.result == ResultType.SUCCESS) "200 OK" else "500 Error"
                        publishAck(
                            event.request.messageId, 
                            event.command, 
                            event.request.confidence, 
                            status, 
                            code, 
                            event.request.sentTimeMs, 
                            reason = event.reason,
                            queueDelayMs = event.queueDelayMs,
                            executionTimeMs = event.executionTimeMs
                        )
                    }
                }
            }
        }
        
        StateManager.onStateChangedListener = { state ->
            runOnUiThread {
                txtLogs.text = "[STATE] $state\n" + txtLogs.text
            }
            thread {
                try {
                    val ack = JSONObject()
                    ack.put("status", state.name)
                    ack.put("device_id", "Android_01")
                    val ackMessage = MqttMessage(ack.toString().toByteArray()).apply {
                        qos = 0
                        isRetained = false
                    }
                    mqttClient?.publish(ackTopic, ackMessage)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        btnStart.setOnClickListener {

            startDiscovery()
        }

        btnStop.setOnClickListener {
            thread {
                try {
                    val stopJson = JSONObject()
                    stopJson.put("command", "STOP_SYSTEM")
                    mqttClient?.publish(
                        controlTopic,
                        MqttMessage(stopJson.toString().toByteArray())
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                stopDiscovery()
                disconnectMQTT()
            }
        }
    }

    private fun connectMQTT(ip: String = "172.18.9.82", port: Int = 8883) {
        thread {
            try {
                if (mqttClient?.isConnected == true) return@thread

                runOnUiThread {
                    currentMqttState = "Connecting..."
                    refreshDashboard()
                }

                val brokerUri = "ssl://$ip:$port"
                val clientId = MqttClient.generateClientId()
                mqttClient = MqttClient(brokerUri, clientId, MemoryPersistence())
                activeMqttClient = mqttClient

                val options = MqttConnectOptions()
                options.isCleanSession = true
                options.keepAliveInterval = 60
                options.isAutomaticReconnect = true
                options.userName = "synaptimesh"
                options.password = "SynaptiMesh2026!".toCharArray()
                options.socketFactory = getSSLSocketFactory()

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        if (reconnect) {
                            runOnUiThread {
                                currentMqttState = "Connected"
                                refreshDashboard()
                            }
                            try {
                                StateManager.transitionTo(MachineState.READY)
                                mqttClient?.subscribe(topic, 0)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        runOnUiThread {
                            currentMqttState = "Reconnecting..."
                            refreshDashboard()
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        try {
                            val payload = message.toString()
                            

                            val json = JSONObject(payload)
                            val command = json.optString("command", "UNKNOWN")
                            val confidence = json.optDouble("confidence", 0.0)
                            val messageId = json.optString("command_id", json.optString("message_id", "unknown_msg_id"))
                            val domain = json.optString("domain", "Unknown")
                            val sentTimeMs = json.optLong("sent_time_ms", 0L)
                            val protocolVersion = json.optInt("protocol_version", 1)
                            val sequenceNumber = json.optInt("sequence_number", 0)
                            val dynamicPayload = json.optString("playlist", null)
                            
                            val timestampPrefix = SimpleDateFormat("[yyyy-MM-dd HH:mm:ss] ", Locale.getDefault()).format(Date())

                            // 1. Replay Protection & Staleness (Sprint 8)
                            if (processedMessageIds.contains(messageId)) return
                            processedMessageIds.add(messageId)
                            if (processedMessageIds.size > 50) processedMessageIds.removeAt(0)
                            
                            val currentTime = System.currentTimeMillis()
                            if (sentTimeMs > 0 && Math.abs(currentTime - sentTimeMs) > 10000) {
                                runOnUiThread {
                                    if (txtLogs.text == "Logs will appear here...") txtLogs.text = ""
                                    txtLogs.text = "${timestampPrefix}[REJECTED] Command: $command (Reason: Stale Packet)\n" + txtLogs.text
                                }
                                return
                            }

                            val commandUpper = command.uppercase(Locale.getDefault())

                            // 1. Normalize (Legacy -> Canonical)
                            val standardizedCmd = when (commandUpper) {
                                "RIGHT_PREVIOUS_SONG", "LEFT" -> "RIGHT_PREVTRACK"
                                "RIGHT_NEXT_SONG" -> "RIGHT_NEXTTRACK"
                                "RIGHT_VOLUME_UP", "NEUTRAL" -> "RIGHT_VOLUMEUP"
                                "RIGHT_VOLUME_DOWN", "DROP" -> "RIGHT_VOLUMEDOWN"
                                "RIGHT_PLAY_PAUSE", "PUSH" -> "RIGHT_PLAY_PAUSE"
                                "RIGHT_RETURN_TO_HOME", "LIFT" -> "RIGHT_RETURN_TO_HOME"
                                "RIGHT_SEARCH_PLAYLIST", "PULL" -> "RIGHT_SEARCH_ALBUM_PLAYLIST"
                                "RIGHT_JIOSAAVN", "RIGHT" -> "RIGHT_LAUNCH_JIOSAAVN"
                                else -> commandUpper
                            }

                            // 2. Validation Check against Canonical Whitelist
                            val isWhitelisted = validCommands.contains(standardizedCmd)
                            if (!isWhitelisted) {
                                runOnUiThread {
                                    if (txtLogs.text == "Logs will appear here...") {
                                        txtLogs.text = ""
                                    }
                                    txtLogs.text = "${timestampPrefix}[REJECTED] Command: $command (Reason: Invalid Command)\n" + txtLogs.text
                                }
                                
                                thread {
                                    try {
                                        val ack = JSONObject()
                                        ack.put("ack_id", messageId)
                                        ack.put("command", command)
                                        ack.put("confidence", confidence)
                                        ack.put("received_time", timestampPrefix.trim())
                                        ack.put("status", "REJECTED")
                                        ack.put("result_code", "400 Bad Request")
                                        ack.put("device_id", "Android_01")
                                        ack.put("sent_time_ms", json.optLong("sent_time_ms", 0L))
                                        
                                        val ackMessage = MqttMessage(ack.toString().toByteArray()).apply {
                                            qos = 0
                                            isRetained = false
                                        }
                                        mqttClient?.publish(ackTopic, ackMessage)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                return
                            }

                            // 3. Confidence check (Pass / Fail validation)
                            val confPass = confidence >= 0.80
                            if (!confPass) {
                                runOnUiThread {
                                    if (txtLogs.text == "Logs will appear here...") {
                                        txtLogs.text = ""
                                    }
                                    txtLogs.text = "${timestampPrefix}[REJECTED] Command: $command (Reason: Confidence $confidence < 0.80)\n" + txtLogs.text
                                }
                                thread {
                                    try {
                                        val ack = JSONObject()
                                        ack.put("ack_id", messageId)
                                        ack.put("command", command)
                                        ack.put("confidence", confidence)
                                        ack.put("received_time", timestampPrefix.trim())
                                        ack.put("status", "REJECTED")
                                        ack.put("result_code", "400 Bad Request")
                                        ack.put("device_id", "Android_01")
                                        ack.put("sent_time_ms", json.optLong("sent_time_ms", 0L))
                                        
                                        val ackMessage = MqttMessage(ack.toString().toByteArray()).apply {
                                            qos = 0
                                            isRetained = false
                                        }
                                        mqttClient?.publish(ackTopic, ackMessage)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                return
                            }

                            if (standardizedCmd == lastCommand) {
                                return
                            }
                            lastCommand = standardizedCmd
                            


                            // Mapping action string to standard BCI name
                            val action = when (standardizedCmd) {
                                "RIGHT_PREVTRACK" -> "Previous Song"
                                "RIGHT_NEXTTRACK" -> "Next Song"
                                "RIGHT_VOLUMEUP" -> "Increase Volume"
                                "RIGHT_VOLUMEDOWN" -> "Decrease Volume"
                                "RIGHT_PLAY_PAUSE" -> "Toggle Play/Pause"
                                "RIGHT_RETURN_TO_HOME" -> "Go to Home Screen"
                                "RIGHT_SEARCH_ALBUM_PLAYLIST" -> "Open JioSaavn Search"
                                "RIGHT_LAUNCH_JIOSAAVN" -> "Launch JioSaavn"
                                else -> "Unknown"
                            }

                            runOnUiThread {
                                txtCommand.text = command
                                txtAction.text = action.uppercase(Locale.getDefault())
                                txtConfidence.text = "${(confidence * 100).toInt()}% (Threshold: PASS)"
                                if (txtLogs.text == "Logs will appear here...") {
                                    txtLogs.text = ""
                                }
                                val cleanLog =
                                """
                                ${timestampPrefix}[$domain]
                                Command    : $command
                                Action     : $action
                                Confidence : ${(confidence * 100).toInt()}% (Threshold: PASS)
                                ────────────────────
                                """.trimIndent()

                                txtLogs.text = cleanLog + "\n" + txtLogs.text
                            }

                            // Push to Command Queue
                            val receivedTime = System.currentTimeMillis()
                            CommandQueue.push(CommandRequest(standardizedCmd, messageId, confidence, json.optLong("sent_time_ms", 0L), payload = payload, receivedTimeMs = receivedTime))

                            runOnUiThread {
                                txtLogs.text = "${timestampPrefix}[Exec] Queued for Dispatcher\n" + txtLogs.text
                            }

                            // 4. Publish QUEUED ACK
                            thread {
                                publishAck(
                                    messageId = messageId,
                                    command = command,
                                    confidence = confidence,
                                    status = "QUEUED",
                                    resultCode = "202 Accepted",
                                    sentTimeMs = json.optLong("sent_time_ms", 0L)
                                )
                            }

                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                // Connect synchronously
                mqttClient?.connect(options)

                // Subscribing immediately on successful initial connection
                runOnUiThread {
                    currentMqttState = "Connected"
                    refreshDashboard()
                    if (txtLogs.text == "Logs will appear here...") {
                        txtLogs.text = ""
                    }
                }

                StateManager.transitionTo(MachineState.READY)
                mqttClient?.subscribe(topic, 1)

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    currentMqttState = "Disconnected"
                    refreshDashboard()
                }
            }
        }
    }

    private fun disconnectMQTT() {
        thread {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
                mqttClient = null
                runOnUiThread {
                    currentMqttState = "Disconnected"
                    refreshDashboard()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startDiscovery() {
        if (isDiscoveryActive) return
        hasResolvedService = false
        isDiscoveryActive = true

        try {
            multicastLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                isDiscoveryActive = false
                runOnUiThread {
                    txtLogs.text = "[NSD] Discovery Start Failed: $errorCode\n" + txtLogs.text
                }
                handler.removeCallbacks(timeoutRunnable)
                connectMQTT("172.18.9.82", 8883)
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                runOnUiThread {
                    txtLogs.text = "[NSD] Discovery Started\n" + txtLogs.text
                }
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                isDiscoveryActive = false
                runOnUiThread {
                    txtLogs.text = "[NSD] Discovery Stopped\n" + txtLogs.text
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo?.serviceType == "_mqtt._tcp." || serviceInfo?.serviceType == "_mqtt._tcp") {
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            runOnUiThread {
                                txtLogs.text = "[NSD] Resolve Failed: $errorCode\n" + txtLogs.text
                            }
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                            if (hasResolvedService) return
                            hasResolvedService = true
                            handler.removeCallbacks(timeoutRunnable)
                            stopDiscovery()

                            val host = serviceInfo?.host?.hostAddress
                            val port = serviceInfo?.port ?: 8883
                            if (host != null) {
                                runOnUiThread {
                                    txtLogs.text = "[NSD] Discovered Broker: tcp://$host:$port\n" + txtLogs.text
                                }
                                connectMQTT(host, port)
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                runOnUiThread {
                    txtLogs.text = "[NSD] Service Lost: ${serviceInfo?.serviceName}\n" + txtLogs.text
                }
            }
        }

        handler.postDelayed(timeoutRunnable, 5000)
        nsdManager?.discoverServices("_mqtt._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {
        if (!isDiscoveryActive) return
        isDiscoveryActive = false
        
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        discoveryListener = null
    }




    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        thread {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        
        if (!systemDiagnostics.isAccessibilityEnabled() && !hasPromptedAccessibility) {
            hasPromptedAccessibility = true
            android.app.AlertDialog.Builder(this)
                .setTitle("Accessibility Required")
                .setMessage("SynaptiMesh needs Accessibility permissions to automate music controls. Would you like to enable it now?")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun refreshDashboard() {
        runOnUiThread {
            diagnosticAdapter.updateItems(systemDiagnostics.runDiagnostics(currentMqttState))
            
            txtSysInfo.text = """
                Version : v${BuildConfig.VERSION_NAME}
                Protocol : 2
                Broker : MQTT
                Automation : ${if (currentMqttState == "Connected") "Ready" else "Disconnected"}
            """.trimIndent()
        }
    }
}
