package com.museenfc.backend.routes

import com.museenfc.backend.db.Checkpoints
import com.museenfc.backend.db.GuardRole
import com.museenfc.backend.db.Guards
import com.museenfc.backend.db.ScanStatus
import com.museenfc.backend.db.Scans
import com.museenfc.backend.models.ErrorResponse
import com.museenfc.backend.models.ScanHistoryItem
import com.museenfc.backend.models.ScanRequest
import com.museenfc.backend.models.ScanResponse
import com.museenfc.backend.security.requireRole
import com.museenfc.backend.service.SupervisionService
import com.museenfc.backend.ws.SupervisionHub
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

fun Route.scanRoutes() {

    // Enregistrement d'un passage. Ouvert à tout rôle authentifié (GUARD minimum) :
    // c'est l'action de base de la ronde.
    post("/scans") {
        val caller = call.requireRole(GuardRole.GUARD) ?: return@post
        val body = call.receive<ScanRequest>()

        val checkpointRow = transaction {
            Checkpoints.selectAll().where {
                (Checkpoints.checkpointCode eq body.checkpointCode) and
                    (Checkpoints.museumId eq caller.museumId) and
                    (Checkpoints.isActive eq true)
            }.firstOrNull()
        }

        // R2 : patch inconnu / jamais provisionné -> refusé, pas enregistré comme passage valide.
        if (checkpointRow == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch inconnu : aucune salle associée à ce code"))
            return@post
        }
        // Détecte un mésappariement UID <-> code (patch retiré/reposé sur un autre support, tentative de fraude).
        if (checkpointRow[Checkpoints.tagUid] != body.tagUid) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("L'UID du patch ne correspond pas à celui enregistré pour cette salle"),
            )
            return@post
        }

        val status = runCatching { ScanStatus.valueOf(body.status) }.getOrDefault(ScanStatus.OK)
        val now = Instant.now()

        val scanId = transaction {
            Scans.insertAndGetId {
                it[guardId] = caller.guardId
                it[checkpointId] = checkpointRow[Checkpoints.id]
                it[scannedAt] = body.scannedAt
                it[receivedAt] = now // l'heure serveur fait foi (R8), jamais celle du téléphone
                it[Scans.status] = status
                it[note] = body.note
                it[deviceId] = body.deviceId
                it[createdAt] = now
            }
        }

        val guardName = transaction {
            Guards.selectAll().where { Guards.id eq caller.guardId }.first()[Guards.fullName]
        }

        // Effet Wow : la salle repasse au vert (ou change d'état) en direct sur tous les écrans ouverts.
        SupervisionHub.broadcast(SupervisionService.snapshot(caller.museumId))

        call.respond(
            HttpStatusCode.Created,
            ScanResponse(
                id = scanId.value,
                checkpointId = checkpointRow[Checkpoints.id].value,
                roomName = checkpointRow[Checkpoints.roomName],
                guardName = guardName,
                receivedAt = now,
                status = status.name,
            ),
        )
    }

    // Historique filtrable, réservé chef de poste / direction (cadrage §1).
    get("/scans") {
        val caller = call.requireRole(GuardRole.SUPERVISOR) ?: return@get
        val checkpointId = call.request.queryParameters["checkpointId"]?.toIntOrNull()
        val guardId = call.request.queryParameters["guardId"]?.toIntOrNull()
        val from = call.request.queryParameters["from"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val to = call.request.queryParameters["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val items = transaction {
            var query = (Scans innerJoin Checkpoints innerJoin Guards).selectAll()
                .where { Checkpoints.museumId eq caller.museumId }

            checkpointId?.let { query = query.andWhere { Scans.checkpointId eq it } }
            guardId?.let { query = query.andWhere { Scans.guardId eq it } }
            from?.let { query = query.andWhere { Scans.receivedAt greaterEq it } }
            to?.let { query = query.andWhere { Scans.receivedAt lessEq it } }

            query.orderBy(Scans.receivedAt, SortOrder.DESC)
                .limit(500)
                .map { row ->
                    ScanHistoryItem(
                        id = row[Scans.id].value,
                        roomName = row[Checkpoints.roomName],
                        guardName = row[Guards.fullName],
                        receivedAt = row[Scans.receivedAt],
                        status = row[Scans.status].name,
                        note = row[Scans.note],
                    )
                }
        }
        call.respond(items)
    }
}
