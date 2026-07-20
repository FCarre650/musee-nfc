package com.museenfc.backend.service

import com.museenfc.backend.db.Checkpoints
import com.museenfc.backend.db.Guards
import com.museenfc.backend.db.Scans
import com.museenfc.backend.models.SupervisionRoomDto
import com.museenfc.backend.models.SupervisionSnapshotDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.Instant

/**
 * Calcule l'état vert/orange/rouge de chaque salle à partir du dernier scan connu.
 * Une salle jamais scannée est RED : le client veut voir "pas contrôlée" immédiatement,
 * pas un état neutre qui banalise l'absence de contrôle.
 */
object SupervisionService {

    fun snapshot(museumId: Int): SupervisionSnapshotDto = transaction {
        val now = Instant.now()

        val rooms = Checkpoints.selectAll()
            .where { (Checkpoints.museumId eq museumId) and (Checkpoints.isActive eq true) }
            .orderBy(Checkpoints.roomName)
            .map { cpRow ->
                val checkpointId = cpRow[Checkpoints.id].value

                val lastScan = (Scans innerJoin Guards).selectAll()
                    .where { Scans.checkpointId eq checkpointId }
                    .orderBy(Scans.receivedAt, SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()

                val lastScanAt = lastScan?.get(Scans.receivedAt)
                val elapsedSeconds = lastScanAt?.let { Duration.between(it, now).seconds }
                val thresholdMin = cpRow[Checkpoints.alertThresholdMin]
                val thresholdSeconds = thresholdMin * 60L

                val state = when {
                    elapsedSeconds == null -> "RED"
                    elapsedSeconds < (thresholdSeconds * 2) / 3 -> "GREEN"
                    elapsedSeconds < thresholdSeconds -> "ORANGE"
                    else -> "RED"
                }

                SupervisionRoomDto(
                    checkpointId = checkpointId,
                    roomName = cpRow[Checkpoints.roomName],
                    zone = cpRow[Checkpoints.zone],
                    alertThresholdMin = thresholdMin,
                    lastScanAt = lastScanAt,
                    lastGuardName = lastScan?.get(Guards.fullName),
                    elapsedSeconds = elapsedSeconds,
                    state = state,
                )
            }

        SupervisionSnapshotDto(rooms = rooms, serverTime = now)
    }
}
