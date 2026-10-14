package com.pcremote.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RemoteEnvelope(
    val id: String,
    val type: String,
    val token: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: JsonElement
)

@Serializable
data class DiscoveryBeacon(
    val serverName: String,
    val machineId: String,
    val port: Int,
    val udpPort: Int = 45990,
    val certFingerprint: String = "",
    val protocolVersion: Int = 1,
    val hostAddress: String = ""
)

@Serializable
data class SavedServerProfile(
    val machineId: String,
    val serverName: String,
    val host: String,
    val port: Int,
    val udpPort: Int,
    val certFingerprint: String,
    val sessionToken: String
)

@Serializable
data class FileEntryDto(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

@Serializable
data class ProcessDto(
    val pid: Int,
    val name: String,
    val title: String,
    val memoryMb: Double
)

@Serializable
data class SystemTelemetryDto(
    val cpuUsagePercent: Double = 0.0,
    val ramUsedMb: Long = 0,
    val ramTotalMb: Long = 0,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = true,
    val clipboardText: String = "",
    val processes: List<ProcessDto> = emptyList()
)

@Serializable
data class CustomRemoteButton(
    val id: String,
    val label: String,
    val commandType: String,
    val primaryArg: String,
    val modifiers: List<String> = emptyList()
)
