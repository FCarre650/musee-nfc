package com.museenfc.backend.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.museenfc.backend.security.Passwords
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * H2 embarqué (fichier local) pour le POC : zéro installation, tourne sur un simple laptop.
 * Exposed abstrait l'accès aux données -> passage en PostgreSQL en production = changer
 * cette seule URL de connexion (+ le driver dans build.gradle.kts), rien d'autre ne bouge.
 */
object DatabaseFactory {

    fun init() {
        Database.connect(
            url = "jdbc:h2:file:./data/musee_nfc;AUTO_SERVER=TRUE",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        transaction {
            SchemaUtils.create(Museums, Guards, Checkpoints, Scans)
        }
        seedIfEmpty()
    }

    private fun seedIfEmpty() = transaction {
        if (!Museums.selectAll().empty()) return@transaction

        val now = Instant.now()
        val museumId = Museums.insertAndGetId {
            it[name] = "Musée Carré — site pilote"
            it[createdAt] = now
        }

        fun guard(fullName: String, badge: String, login: String, password: String, role: GuardRole) =
            Guards.insertAndGetId {
                it[Guards.museumId] = museumId
                it[Guards.fullName] = fullName
                it[badgeNumber] = badge
                it[Guards.login] = login
                it[passwordHash] = Passwords.hash(password)
                it[Guards.role] = role
                it[createdAt] = now
            }

        guard("Bruno Lefèvre", "G-001", "bruno", "bruno123", GuardRole.GUARD)
        guard("Claire Dubois", "G-002", "claire", "claire123", GuardRole.SUPERVISOR)
        guard("Amina Direction", "G-003", "admin", "admin123", GuardRole.ADMIN)

        fun checkpoint(uid: String, code: String, room: String, zone: String, threshold: Int) =
            Checkpoints.insertAndGetId {
                it[Checkpoints.museumId] = museumId
                it[tagUid] = uid
                it[checkpointCode] = code
                it[roomName] = room
                it[Checkpoints.zone] = zone
                it[alertThresholdMin] = threshold
                it[createdAt] = now
            }

        // Un checkpoint "TEST-..." à UID prévisible, pratique pour tester au curl sans patch physique (voir PLAN_TEST_PROGRESSIF.md)
        val salleEgypte = checkpoint("TEST-UID-EGYPTE", "chk-egypte-001", "Salle Égypte antique", "RDC", 60)
        checkpoint("TEST-UID-RENAISSANCE", "chk-renaissance-001", "Salle Renaissance", "RDC", 60)
        checkpoint("TEST-UID-BIJOUX", "chk-bijoux-001", "Cabinet des bijoux", "1er étage", 30)
        checkpoint("TEST-UID-RESERVE", "chk-reserve-001", "Réserve sous-sol", "Sous-sol", 30)
        checkpoint("TEST-UID-SCULPTURE", "chk-sculpture-001", "Galerie Sculpture", "RDC", 90)
        checkpoint("TEST-UID-CONTEMPORAIN", "chk-contemporain-001", "Salle Art contemporain", "2e étage", 60)

        // Un scan récent pour que la salle Égypte démarre verte -> effet visuel immédiat au premier lancement.
        val bruno = Guards.selectAll().first { it[Guards.login] == "bruno" }[Guards.id]
        Scans.insertAndGetId {
            it[guardId] = bruno
            it[checkpointId] = salleEgypte
            it[scannedAt] = now.minus(3, ChronoUnit.MINUTES)
            it[receivedAt] = now.minus(3, ChronoUnit.MINUTES)
            it[status] = ScanStatus.OK
            it[deviceId] = "seed"
            it[createdAt] = now
        }
    }
}
