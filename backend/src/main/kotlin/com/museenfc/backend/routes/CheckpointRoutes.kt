package com.museenfc.backend.routes

import com.museenfc.backend.db.Checkpoints
import com.museenfc.backend.db.GuardRole
import com.museenfc.backend.models.CheckpointDto
import com.museenfc.backend.models.CreateCheckpointRequest
import com.museenfc.backend.models.ErrorResponse
import com.museenfc.backend.models.UpdateCheckpointRequest
import com.museenfc.backend.security.caller
import com.museenfc.backend.security.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

private fun rowToDto(row: org.jetbrains.exposed.sql.ResultRow) = CheckpointDto(
    id = row[Checkpoints.id].value,
    tagUid = row[Checkpoints.tagUid],
    checkpointCode = row[Checkpoints.checkpointCode],
    roomName = row[Checkpoints.roomName],
    zone = row[Checkpoints.zone],
    alertThresholdMin = row[Checkpoints.alertThresholdMin],
    isActive = row[Checkpoints.isActive],
)

/**
 * CRUD salles/patchs. Réservé au chef de poste (SUPERVISOR) et à la direction (ADMIN) :
 * un gardien ne crée ni ne modifie de checkpoint (cadrage §1, tableau des rôles).
 */
fun Route.checkpointRoutes() {
    get("/checkpoints") {
        val caller = call.requireRole(GuardRole.SUPERVISOR) ?: return@get
        val rows = transaction {
            Checkpoints.selectAll().where { Checkpoints.museumId eq caller.museumId }
                .orderBy(Checkpoints.roomName)
                .map(::rowToDto)
        }
        call.respond(rows)
    }

    // Provisioning : le serveur génère le checkpointCode (UUID) que le téléphone écrira et
    // verrouillera ensuite sur le patch physique (R1). tagUid est l'UID lu au premier tap du patch neuf.
    post("/checkpoints") {
        val caller = call.requireRole(GuardRole.ADMIN) ?: return@post
        val body = call.receive<CreateCheckpointRequest>()

        val existing = transaction {
            Checkpoints.selectAll().where { Checkpoints.tagUid eq body.tagUid }.firstOrNull()
        }
        if (existing != null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Ce patch (UID) est déjà associé à une salle"))
            return@post
        }

        val checkpointCode = UUID.randomUUID().toString()
        val id = transaction {
            Checkpoints.insertAndGetId {
                it[museumId] = caller.museumId
                it[tagUid] = body.tagUid
                it[Checkpoints.checkpointCode] = checkpointCode
                it[roomName] = body.roomName
                it[zone] = body.zone
                it[alertThresholdMin] = body.alertThresholdMin
                it[isActive] = true
                it[createdAt] = Instant.now()
            }
        }

        val row = transaction { Checkpoints.selectAll().where { Checkpoints.id eq id }.first() }
        call.respond(HttpStatusCode.Created, rowToDto(row))
    }

    patch("/checkpoints/{id}") {
        val caller = call.requireRole(GuardRole.ADMIN) ?: return@patch
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("id invalide"))
            return@patch
        }
        val body = call.receive<UpdateCheckpointRequest>()

        val updated = transaction {
            val count = Checkpoints.update({ (Checkpoints.id eq id) and (Checkpoints.museumId eq caller.museumId) }) {
                body.roomName?.let { v -> it[roomName] = v }
                body.zone?.let { v -> it[zone] = v }
                body.alertThresholdMin?.let { v -> it[alertThresholdMin] = v }
                body.isActive?.let { v -> it[isActive] = v }
            }
            if (count == 0) null else Checkpoints.selectAll().where { Checkpoints.id eq id }.first()
        }

        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Salle introuvable"))
        } else {
            call.respond(rowToDto(updated))
        }
    }
}
