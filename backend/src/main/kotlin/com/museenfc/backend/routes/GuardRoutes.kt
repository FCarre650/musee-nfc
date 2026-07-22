package com.museenfc.backend.routes

import com.museenfc.backend.db.GuardRole
import com.museenfc.backend.db.Guards
import com.museenfc.backend.db.Scans
import com.museenfc.backend.models.CreateGuardRequest
import com.museenfc.backend.models.ErrorResponse
import com.museenfc.backend.models.GuardDto
import com.museenfc.backend.security.Passwords
import com.museenfc.backend.security.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

private fun rowToDto(row: org.jetbrains.exposed.sql.ResultRow) = GuardDto(
    id = row[Guards.id].value,
    fullName = row[Guards.fullName],
    badgeNumber = row[Guards.badgeNumber],
    login = row[Guards.login],
    role = row[Guards.role].name,
)

/**
 * CRUD des comptes gardiens. Réservé à la direction (ADMIN) : c'est elle qui, dans le cadrage,
 * détient la gestion des comptes (cadrage §1, tableau des rôles).
 */
fun Route.guardRoutes() {
    get("/guards") {
        val caller = call.requireRole(GuardRole.ADMIN) ?: return@get
        val rows = transaction {
            Guards.selectAll().where { Guards.museumId eq caller.museumId }
                .orderBy(Guards.fullName)
                .map(::rowToDto)
        }
        call.respond(rows)
    }

    post("/guards") {
        val caller = call.requireRole(GuardRole.ADMIN) ?: return@post
        val body = call.receive<CreateGuardRequest>()

        val role = runCatching { GuardRole.valueOf(body.role) }.getOrNull()
        if (role == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Rôle invalide : ${body.role}"))
            return@post
        }

        val existing = transaction {
            Guards.selectAll().where { Guards.login eq body.login }.firstOrNull()
        }
        if (existing != null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Ce login est déjà utilisé"))
            return@post
        }

        val id = transaction {
            Guards.insertAndGetId {
                it[museumId] = caller.museumId
                it[fullName] = body.fullName
                it[badgeNumber] = body.badgeNumber
                it[login] = body.login
                it[passwordHash] = Passwords.hash(body.password)
                it[Guards.role] = role
                it[isActive] = true
                it[createdAt] = Instant.now()
            }
        }

        val row = transaction { Guards.selectAll().where { Guards.id eq id }.first() }
        call.respond(HttpStatusCode.Created, rowToDto(row))
    }

    // Suppression réelle (pas de désactivation dans ce POC). Refusée si le compte a déjà des
    // passages enregistrés : contrairement aux salles, l'historique des rondes ne doit jamais
    // dépendre de la survie d'un compte gardien — c'est une preuve d'activité, pas une donnée de
    // confort (cadrage R10/audit). Un compte de test jamais utilisé se supprime sans contrainte.
    delete("/guards/{id}") {
        val caller = call.requireRole(GuardRole.ADMIN) ?: return@delete
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("id invalide"))
            return@delete
        }
        if (id == caller.guardId) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Impossible de supprimer son propre compte"))
            return@delete
        }

        val result = transaction {
            val exists = Guards.selectAll()
                .where { (Guards.id eq id) and (Guards.museumId eq caller.museumId) }
                .firstOrNull()
            when {
                exists == null -> "not_found"
                Scans.selectAll().where { Scans.guardId eq id }.any() -> "has_history"
                else -> {
                    Guards.deleteWhere { builder -> builder.run { (Guards.id eq id) and (Guards.museumId eq caller.museumId) } }
                    "deleted"
                }
            }
        }

        when (result) {
            "deleted" -> call.respond(HttpStatusCode.NoContent)
            "has_history" -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Ce compte a des passages enregistrés à son nom : suppression refusée pour préserver l'historique des rondes"),
            )
            else -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Compte introuvable"))
        }
    }
}
