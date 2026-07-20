package com.museenfc.backend.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
data class LoginRequest(val login: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val guard: GuardDto)

@Serializable
data class GuardDto(
    val id: Int,
    val fullName: String,
    val badgeNumber: String,
    val login: String,
    val role: String,
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
data class UpdateCheckpointRequest(
    val roomName: String? = null,
    val zone: String? = null,
    val alertThresholdMin: Int? = null,
    val isActive: Boolean? = null,
)

@Serializable
data class ScanRequest(
    val tagUid: String,
    val checkpointCode: String,
    @Serializable(with = InstantSerializer::class)
    val scannedAt: Instant,
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
    @Serializable(with = InstantSerializer::class)
    val receivedAt: Instant,
    val status: String,
)

@Serializable
data class ScanHistoryItem(
    val id: Int,
    val roomName: String,
    val guardName: String,
    @Serializable(with = InstantSerializer::class)
    val receivedAt: Instant,
    val status: String,
    val note: String?,
)

@Serializable
data class SupervisionRoomDto(
    val checkpointId: Int,
    val roomName: String,
    val zone: String?,
    val alertThresholdMin: Int,
    @Serializable(with = InstantSerializer::class)
    val lastScanAt: Instant?,
    val lastGuardName: String?,
    val elapsedSeconds: Long?,
    val state: String, // GREEN | ORANGE | RED
)

@Serializable
data class SupervisionSnapshotDto(
    val rooms: List<SupervisionRoomDto>,
    @Serializable(with = InstantSerializer::class)
    val serverTime: Instant,
)

@Serializable
data class ErrorResponse(val error: String)
