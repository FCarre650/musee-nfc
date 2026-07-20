package com.museenfc.app.network

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val login: String, val password: String)

@Serializable
data class GuardDto(
    val id: Int,
    val fullName: String,
    val badgeNumber: String,
    val login: String,
    val role: String,
)

@Serializable
data class LoginResponse(val token: String, val guard: GuardDto)

@Serializable
data class ScanRequest(
    val tagUid: String,
    val checkpointCode: String,
    val scannedAt: String,
    val status: String = "OK",
    val note: String? = null,
    val deviceId: String? = null,
)

@Serializable
data class ScanResponse(
    val id: Int,
    val checkpointId: Int,
    val roomName: String,
    val guardName: String,
    val receivedAt: String,
    val status: String,
)

@Serializable
data class CheckpointDto(
    val id: Int,
    val tagUid: String,
    val checkpointCode: String,
    val roomName: String,
    val zone: String?,
    val alertThresholdMin: Int,
    val isActive: Boolean,
)

@Serializable
data class CreateCheckpointRequest(
    val tagUid: String,
    val roomName: String,
    val zone: String? = null,
    val alertThresholdMin: Int = 60,
)

@Serializable
data class UpdateCheckpointRequest(val alertThresholdMin: Int)

@Serializable
data class ScanHistoryItem(
    val id: Int,
    val roomName: String,
    val guardName: String,
    val receivedAt: String,
    val status: String,
    val note: String?,
)

@Serializable
data class ErrorResponse(val error: String)
