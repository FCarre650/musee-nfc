package com.museenfc.backend.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * museum_id partout = isolation multi-tenant (1 déploiement -> N musées).
 * Non exploité par les routes du POC (mono-musée), mais présent dans le modèle
 * pour que le chemin de mise à l'échelle (§9 du cadrage) soit réel, pas discours.
 */
object Museums : IntIdTable("museums") {
    val name = varchar("name", 128)
    val createdAt = timestamp("created_at")
}

enum class GuardRole { GUARD, SUPERVISOR, ADMIN }

object Guards : IntIdTable("guards") {
    val museumId = reference("museum_id", Museums)
    val fullName = varchar("full_name", 128)
    val badgeNumber = varchar("badge_number", 32)
    val login = varchar("login", 64).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val role = enumerationByName("role", 16, GuardRole::class)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at")
}

/**
 * Une "checkpoint" = un patch NFC associé à une salle.
 * tagUid = UID physique de la puce (lecture seule, gravé usine).
 * checkpointCode = UUID applicatif qu'on écrit et verrouille sur le patch au provisioning (R1/R2) :
 * ça permet de révoquer/réassocier un patch cassé sans dépendre d'un UID qu'on ne maîtrise pas.
 */
object Checkpoints : IntIdTable("checkpoints") {
    val museumId = reference("museum_id", Museums)
    val tagUid = varchar("tag_uid", 64).uniqueIndex()
    val checkpointCode = varchar("checkpoint_code", 64).uniqueIndex()
    val roomName = varchar("room_name", 128)
    val zone = varchar("zone", 64).nullable()
    val alertThresholdMin = integer("alert_threshold_min").default(60)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at")
}

enum class ScanStatus { OK, ANOMALY }

object Scans : IntIdTable("scans") {
    val guardId = reference("guard_id", Guards)
    val checkpointId = reference("checkpoint_id", Checkpoints)
    // Horodatage du téléphone : purement indicatif (R8, un téléphone rooté peut mentir dessus).
    val scannedAt = timestamp("scanned_at")
    // Horodatage serveur : celui qui fait foi pour tous les calculs d'état (vert/orange/rouge).
    val receivedAt = timestamp("received_at")
    val status = enumerationByName("status", 16, ScanStatus::class).default(ScanStatus.OK)
    val note = varchar("note", 512).nullable()
    val deviceId = varchar("device_id", 128).nullable()
    val createdAt = timestamp("created_at")
}
