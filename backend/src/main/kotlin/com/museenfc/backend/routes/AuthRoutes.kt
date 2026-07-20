package com.museenfc.backend.routes

import com.museenfc.backend.db.Guards
import com.museenfc.backend.models.ErrorResponse
import com.museenfc.backend.models.GuardDto
import com.museenfc.backend.models.LoginRequest
import com.museenfc.backend.models.LoginResponse
import com.museenfc.backend.security.JwtConfig
import com.museenfc.backend.security.Passwords
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.authRoutes() {
    // Pas de rate limiting sur /auth/login dans ce POC : à ajouter avant prod (R6, brute force).
    post("/auth/login") {
        val body = call.receive<LoginRequest>()

        val row = transaction {
            Guards.selectAll().where { Guards.login eq body.login }.firstOrNull()
        }

        if (row == null || !Passwords.matches(body.password, row[Guards.passwordHash])) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Identifiants invalides"))
            return@post
        }
        if (!row[Guards.isActive]) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Compte désactivé"))
            return@post
        }

        val token = JwtConfig.generateToken(
            guardId = row[Guards.id].value,
            museumId = row[Guards.museumId].value,
            login = row[Guards.login],
            role = row[Guards.role],
        )

        call.respond(
            LoginResponse(
                token = token,
                guard = GuardDto(
                    id = row[Guards.id].value,
                    fullName = row[Guards.fullName],
                    badgeNumber = row[Guards.badgeNumber],
                    login = row[Guards.login],
                    role = row[Guards.role].name,
                ),
            ),
        )
    }
}
