package com.museenfc.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncState { PENDING, SYNCED, REJECTED }

/**
 * File d'attente locale des scans. Sert deux usages :
 * 1) mode hors-ligne (sous-sol sans réseau) : le scan est stocké ici puis renvoyé dès que
 *    la connexion revient, avec l'heure du téléphone (scannedAt) — mais c'est bien received_at
 *    côté serveur qui fait foi une fois synchronisé (R8).
 * 2) historique local consultable même sans réseau.
 */
@Entity(tableName = "scan_queue")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagUid: String,
    val checkpointCode: String,
    val roomNameHint: String,
    val scannedAtIso: String,
    val createdAtMillis: Long,
    val syncState: SyncState = SyncState.PENDING,
    val rejectionReason: String? = null,
)
