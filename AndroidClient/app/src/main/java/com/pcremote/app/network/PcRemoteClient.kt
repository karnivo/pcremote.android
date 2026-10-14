package com.pcremote.app.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.pcremote.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

enum class ConnectionState { DISCONNECTED, CONNECTING, PAIRING_REQUIRED, CONNECTED, ERROR }

class PcRemoteClient(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("pcremote_secure_store", Context.MODE_PRIVATE)

    private var webSocket: WebSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var activeProfile: SavedServerProfile? = null
    private var tokenHashPrefixBytes: ByteArray? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _statusMessage = MutableStateFlow("Ready to connect")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _discoveredServers = MutableStateFlow<List<DiscoveryBeacon>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveryBeacon>> = _discoveredServers

    private val _savedProfiles = MutableStateFlow<List<SavedServerProfile>>(emptyList())
    val savedProfiles: StateFlow<List<SavedServerProfile>> = _savedProfiles

    private val _fileEntries = MutableStateFlow<List<FileEntryDto>>(emptyList())
    val fileEntries: StateFlow<List<FileEntryDto>> = _fileEntries

    private val _currentPath = MutableStateFlow("ROOTS")
    val currentPath: StateFlow<String> = _currentPath

    private val _telemetry = MutableStateFlow(SystemTelemetryDto())
    val telemetry: StateFlow<SystemTelemetryDto> = _telemetry

    private val _liveScreenBitmap = MutableStateFlow<Bitmap?>(null)
    val liveScreenBitmap: StateFlow<Bitmap?> = _liveScreenBitmap

    private val _pendingConfirmation = MutableStateFlow<Pair<String, String?>?>(null)
    val pendingConfirmation: StateFlow<Pair<String, String?>?> = _pendingConfirmation

    private val _customButtons = MutableStateFlow<List<CustomRemoteButton>>(emptyList())
    val customButtons: StateFlow<List<CustomRemoteButton>> = _customButtons

    init {
        loadSavedState()
        udpSocket = DatagramSocket()
    }

    private fun loadSavedState() {
        prefs.getString("saved_profiles", null)?.let {
            runCatching { _savedProfiles.value = json.decodeFromString(it) }
        }
        val rawButtons = prefs.getString("custom_buttons", null)
        if (rawButtons != null) {
            runCatching { _customButtons.value = json.decodeFromString(rawButtons) }
        } else {
            _customButtons.value = listOf(
                CustomRemoteButton("1", "Task Manager", "KEY", "ESCAPE", listOf("CTRL", "SHIFT")),
                CustomRemoteButton("2", "Show Desktop", "KEY", "D", listOf("WIN")),
                CustomRemoteButton("3", "Alt + F4", "KEY", "F4", listOf("ALT")),
                CustomRemoteButton("4", "Snipping Tool", "KEY", "S", listOf("WIN", "SHIFT"))
            )
        }
    }

    fun saveCustomButtons(buttons: List<CustomRemoteButton>) {
        _customButtons.value = buttons
        prefs.edit().putString("custom_buttons", json.encodeToString(buttons)).apply()
    }

    fun discoverServersOnLan() {
        scope.launch {
            val found = mutableMapOf<String, DiscoveryBeacon>()
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 1500
                    val payload = "PCREMOTE_DISCOVER_V1".toByteArray()
                    val broadcastPacket = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 45990)
                    socket.send(broadcastPacket)

                    val buf = ByteArray(2048)
                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < 1500) {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val text = String(recv.data, 0, recv.length)
                        val beacon = json.decodeFromString<DiscoveryBeacon>(text)
                            .copy(hostAddress = recv.address.hostAddress ?: "")
                        found[beacon.machineId] = beacon
                    }
                }
            }
            _discoveredServers.value = found.values.toList()
        }
    }

    fun connect(host: String, port: Int, udpPort: Int = 45990, expectedFingerprint: String = "") {
        webSocket?.close(1000, "Reconnecting")
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Connecting to $host:$port..."

        val existing = _savedProfiles.value.firstOrNull {
            it.host == host || (expectedFingerprint.isNotEmpty() && it.certFingerprint.equals(expectedFingerprint, true))
        }

        val pinnedClient = buildPinnedOkHttpClient(existing?.certFingerprint ?: expectedFingerprint)
        val request = Request.Builder().url("wss://$host:$port/ws").build()

        webSocket = pinnedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (existing != null && existing.sessionToken.isNotEmpty()) {
                    activeProfile = existing.copy(host = host, port = port, udpPort = udpPort)
                    computeUdpTokenPrefix(existing.sessionToken)
                    _connectionState.value = ConnectionState.CONNECTED
                    _statusMessage.value = "Connected to ${existing.serverName}"
                    requestTelemetry()
                    listFiles("ROOTS")
                } else {
                    activeProfile = SavedServerProfile(UUID.randomUUID().toString(), host, host, port, udpPort, expectedFingerprint, "")
                    _connectionState.value = ConnectionState.PAIRING_REQUIRED
                    _statusMessage.value = "Enter the 6-digit PIN shown on PC Remote Server"
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleIncomingMessage(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.ERROR
                _statusMessage.value = "Connection error: ${t.localizedMessage ?: "Unreachable"}"
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "Disconnected"
            }
        })
    }

    fun submitPairingPin(pin: String, deviceName: String = android.os.Build.MODEL) {
        val deviceId = prefs.getString("client_device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("client_device_id", it).apply()
        }
        sendEnvelope("PAIR_REQUEST", buildJsonObject {
            put("deviceId", deviceId); put("deviceName", deviceName); put("pin", pin.trim())
        })
    }

    private fun handleIncomingMessage(rawJson: String) {
        runCatching {
            val env = json.decodeFromString<RemoteEnvelope>(rawJson)
            val obj = env.payload.jsonObject
            when (env.type) {
                "PAIR_RESPONSE" -> {
                    if (obj["paired"]?.jsonPrimitive?.booleanOrNull == true) {
                        val token = obj["sessionToken"]?.jsonPrimitive?.contentOrNull ?: ""
                        val sName = obj["serverName"]?.jsonPrimitive?.contentOrNull ?: "Windows PC"
                        val current = activeProfile ?: return
                        val updated = current.copy(serverName = sName, sessionToken = token)
                        activeProfile = updated
                        computeUdpTokenPrefix(token)

                        val list = _savedProfiles.value.filterNot { it.host == updated.host }.toMutableList()
                        list.add(0, updated)
                        _savedProfiles.value = list
                        prefs.edit().putString("saved_profiles", json.encodeToString(list)).apply()

                        _connectionState.value = ConnectionState.CONNECTED
                        _statusMessage.value = "Paired & Connected to $sName"
                        requestTelemetry()
                        listFiles("ROOTS")
                    } else {
                        _statusMessage.value = obj["error"]?.jsonPrimitive?.contentOrNull ?: "Pairing rejected"
                    }
                }
                "AUTH_ERROR" -> {
                    _connectionState.value = ConnectionState.PAIRING_REQUIRED
                    _statusMessage.value = "Session token expired. Please re-enter PIN."
                }
                "CONFIRM_REQUIRED" -> {
                    val cmd = obj["command"]?.jsonPrimitive?.contentOrNull ?: ""
                    val arg = obj["argument"]?.jsonPrimitive?.contentOrNull
                    _pendingConfirmation.value = cmd to arg
                }
                "TELEMETRY_RESP" -> _telemetry.value = json.decodeFromJsonElement(env.payload)
                "FILE_LIST_RESP" -> {
                    _currentPath.value = obj["path"]?.jsonPrimitive?.contentOrNull ?: "ROOTS"
                    _fileEntries.value = obj["entries"]?.let { json.decodeFromJsonElement<List<FileEntryDto>>(it) } ?: emptyList()
                }
                "SCREEN_FRAME" -> {
                    val b64 = obj["base64Jpeg"]?.jsonPrimitive?.contentOrNull ?: return
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { _liveScreenBitmap.value = it }
                }
            }
        }
    }

    fun sendMouseDelta(dx: Int, dy: Int, scrollX: Int = 0, scrollY: Int = 0) {
        val prof = activeProfile ?: return
        val prefix = tokenHashPrefixBytes
        if (prefix != null && prefix.size >= 16) {
            scope.launch {
                runCatching {
                    val buf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
                    buf.put(0x50.toByte()).put(0x52.toByte())
                    buf.putShort(dx.coerceIn(-32000, 32000).toShort())
                    buf.putShort(dy.coerceIn(-32000, 32000).toShort())
                    buf.putShort(scrollX.coerceIn(-32000, 32000).toShort())
                    buf.putShort(scrollY.coerceIn(-32000, 32000).toShort())
                    buf.put(prefix, 0, 16)
                    val arr = buf.array()
                    udpSocket?.send(DatagramPacket(arr, arr.size, InetAddress.getByName(prof.host), prof.udpPort))
                }
            }
        } else {
            sendEnvelope("MOUSE_MOVE", buildJsonObject {
                put("dx", dx); put("dy", dy); put("scrollX", scrollX); put("scrollY", scrollY)
            })
        }
    }

    fun sendMouseClick(button: String = "LEFT", action: String = "CLICK") =
        sendEnvelope("MOUSE_CLICK", buildJsonObject { put("button", button); put("action", action) })

    fun sendKey(key: String, modifiers: List<String> = emptyList(), action: String = "PRESS") =
        sendEnvelope("KEY_EVENT", buildJsonObject {
            put("key", key); put("action", action)
            putJsonArray("modifiers") { modifiers.forEach { add(it) } }
        })

    fun sendText(text: String) =
        sendEnvelope("KEY_EVENT", buildJsonObject { put("key", ""); put("text", text); put("action", "TEXT") })

    fun sendSystemCommand(command: String, argument: String? = null, confirmed: Boolean = false) =
        sendEnvelope("SYS_CMD", buildJsonObject {
            put("command", command); if (argument != null) put("argument", argument); put("confirmed", confirmed)
        })

    fun confirmPendingAction() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        if (pending.first == "DELETE") sendFileAction("DELETE", pending.second ?: "", confirmed = true)
        else sendSystemCommand(pending.first, pending.second, confirmed = true)
    }

    fun dismissPendingConfirmation() { _pendingConfirmation.value = null }
    fun requestTelemetry() = sendEnvelope("TELEMETRY_REQ", buildJsonObject {})
    fun listFiles(path: String) = sendFileAction("LIST", path)

    fun sendFileAction(action: String, path: String, newName: String? = null, base64Data: String? = null, confirmed: Boolean = false) =
        sendEnvelope("FILE_ACTION", buildJsonObject {
            put("action", action); put("path", path)
            if (newName != null) put("newName", newName)
            if (base64Data != null) put("base64Data", base64Data)
            put("confirmed", confirmed)
        })

    fun setScreenStream(enabled: Boolean, normX: Float? = null, normY: Float? = null, clickButton: String? = null) =
        sendEnvelope("SCREEN_STREAM", buildJsonObject {
            put("enabled", enabled); put("fps", 10); put("quality", 55); put("scale", 0.5f)
            if (normX != null) put("normalizedX", normX)
            if (normY != null) put("normalizedY", normY)
            if (clickButton != null) put("clickButton", clickButton)
        })

    private fun sendEnvelope(type: String, payload: JsonElement) {
        val ws = webSocket ?: return
        ws.send(json.encodeToString(RemoteEnvelope(UUID.randomUUID().toString(), type, activeProfile?.sessionToken, payload = payload)))
    }

    private fun computeUdpTokenPrefix(token: String) {
        tokenHashPrefixBytes = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
    }

    private fun buildPinnedOkHttpClient(expectedFingerprintHex: String): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw javax.net.ssl.SSLHandshakeException("Empty server certificate chain")
                val actualHex = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02X".format(it) }
                if (expectedFingerprintHex.isNotEmpty() && !actualHex.equals(expectedFingerprintHex, ignoreCase = true)) {
                    throw javax.net.ssl.SSLHandshakeException("Certificate fingerprint mismatch!")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
