package com.museenfc.app.data.repository

import com.museenfc.app.network.ApiResult
import com.museenfc.app.network.CheckpointDto
import com.museenfc.app.network.CreateCheckpointRequest
import com.museenfc.app.network.MuseeApi
import com.museenfc.app.network.Session

sealed class CheckpointOutcome {
    data class Success(val checkpoint: CheckpointDto) : CheckpointOutcome()
    data class Failure(val message: String) : CheckpointOutcome()
}

class CheckpointRepository(private val api: MuseeApi) {

    suspend fun listAll(session: Session): List<CheckpointDto> =
        when (val result = api.listCheckpoints(session.token)) {
            is ApiResult.Success -> result.data
            else -> emptyList()
        }

    suspend fun create(session: Session, tagUid: String, roomName: String, zone: String?, thresholdMin: Int): CheckpointOutcome =
        when (val result = api.createCheckpoint(session.token, CreateCheckpointRequest(tagUid, roomName, zone, thresholdMin))) {
            is ApiResult.Success -> CheckpointOutcome.Success(result.data)
            is ApiResult.Rejected -> CheckpointOutcome.Failure(result.message)
            is ApiResult.NetworkFailure -> CheckpointOutcome.Failure("Pas de réseau : le provisioning nécessite une connexion (impossible à mettre en file)")
        }
}
