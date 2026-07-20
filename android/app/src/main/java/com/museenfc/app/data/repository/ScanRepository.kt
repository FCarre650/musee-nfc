package com.museenfc.app.data.repository

import android.os.Build
import com.museenfc.app.data.local.ScanDao
import com.museenfc.app.data.local.ScanEntity
import com.museenfc.app.data.local.SyncState
import com.museenfc.app.network.ApiResult
import com.museenfc.app.network.MuseeApi
import com.museenfc.app.network.ScanRequest
import com.museenfc.app.network.Session
import com.museenfc.app.nfc.ScannedPatch
import kotlinx.coroutines.flow.Flow
import java.time.Instant

sealed class ScanOutcome {
    data class Accepted(val roomName: String, val receivedAt: String) : ScanOutcome()
    data class Rejected(val message: String) : ScanOutcome()
    data object QueuedOffline : ScanOutcome()
}

class ScanRepository(private val api: MuseeApi, private val dao: ScanDao) {

    fun recentQueue(): Flow<List<ScanEntity>> = dao.recent()
    fun pendingCount(): Flow<Int> = dao.pendingCount()

    /** Appelé juste après un tap NFC. [roomNameHint] vient du dernier provisioning connu
     *  localement, purement indicatif pour l'affichage tant que le serveur n'a pas confirmé. */
    suspend fun submitScan(session: Session, patch: ScannedPatch, roomNameHint: String): ScanOutcome {
        val checkpointCode = patch.checkpointCode
            ?: return ScanOutcome.Rejected("Patch non provisionné : aucun code de salle enregistré dessus")

        val scannedAt = Instant.now().toString()
        val request = ScanRequest(
            tagUid = patch.tagUid,
            checkpointCode = checkpointCode,
            scannedAt = scannedAt,
            deviceId = Build.MODEL,
        )

        return when (val result = api.submitScan(session.token, request)) {
            is ApiResult.Success -> {
                dao.insert(
                    ScanEntity(
                        tagUid = patch.tagUid,
                        checkpointCode = checkpointCode,
                        roomNameHint = result.data.roomName,
                        scannedAtIso = scannedAt,
                        createdAtMillis = System.currentTimeMillis(),
                        syncState = SyncState.SYNCED,
                    ),
                )
                ScanOutcome.Accepted(result.data.roomName, result.data.receivedAt)
            }
            // R2/R3 : refus définitif du serveur (patch inconnu / UID incohérent) -> pas de mise en file,
            // retenter ne rendra pas le scan valide.
            is ApiResult.Rejected -> {
                dao.insert(
                    ScanEntity(
                        tagUid = patch.tagUid,
                        checkpointCode = checkpointCode,
                        roomNameHint = roomNameHint,
                        scannedAtIso = scannedAt,
                        createdAtMillis = System.currentTimeMillis(),
                        syncState = SyncState.REJECTED,
                        rejectionReason = result.message,
                    ),
                )
                ScanOutcome.Rejected(result.message)
            }
            // Pas de réseau (cas du sous-sol) : on garde le scan, il partira à la reconnexion.
            is ApiResult.NetworkFailure -> {
                dao.insert(
                    ScanEntity(
                        tagUid = patch.tagUid,
                        checkpointCode = checkpointCode,
                        roomNameHint = roomNameHint,
                        scannedAtIso = scannedAt,
                        createdAtMillis = System.currentTimeMillis(),
                        syncState = SyncState.PENDING,
                    ),
                )
                ScanOutcome.QueuedOffline
            }
        }
    }

    /** À appeler quand la connectivité revient (ou périodiquement) pour vider la file. */
    suspend fun syncPending(session: Session): Int {
        var synced = 0
        for (entity in dao.pending()) {
            val request = ScanRequest(
                tagUid = entity.tagUid,
                checkpointCode = entity.checkpointCode,
                scannedAt = entity.scannedAtIso,
                deviceId = Build.MODEL,
            )
            when (val result = api.submitScan(session.token, request)) {
                is ApiResult.Success -> {
                    dao.update(entity.copy(syncState = SyncState.SYNCED))
                    synced++
                }
                is ApiResult.Rejected -> {
                    dao.update(entity.copy(syncState = SyncState.REJECTED, rejectionReason = result.message))
                }
                is ApiResult.NetworkFailure -> {
                    // Toujours pas de réseau : on arrête, on réessaiera au prochain déclenchement.
                    return synced
                }
            }
        }
        return synced
    }
}
