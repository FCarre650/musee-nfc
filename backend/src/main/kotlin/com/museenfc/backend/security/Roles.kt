package com.museenfc.backend.security

import com.museenfc.backend.db.GuardRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import com.museenfc.backend.models.ErrorResponse

data class Caller(val guardId: Int, val museumId: Int, val login: String, val role: GuardRole)

fun ApplicationCall.caller(): Caller {
    val principal = principal<JWTPrincipal>()!!
    return Caller(
        guardId = principal.payload.getClaim("guardId").asInt(),
        museumId = principal.payload.getClaim("museumId").asInt(),
        login = principal.payload.getClaim("login").asString(),
        role = GuardRole.valueOf(principal.payload.getClaim("role").asString()),
    )
}

/**
 * true si le rôle de l'appelant suffit. Ordre de privilège : GUARD < SUPERVISOR < ADMIN
 * (chef de poste et direction héritent des droits gardien + le leur, cf. cadrage §1).
 */
fun GuardRole.atLeast(required: GuardRole): Boolean {
    val order = listOf(GuardRole.GUARD, GuardRole.SUPERVISOR, GuardRole.ADMIN)
    return order.indexOf(this) >= order.indexOf(required)
}

suspend fun ApplicationCall.requireRole(required: GuardRole): Caller? {
    val caller = caller()
    if (!caller.role.atLeast(required)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("Rôle insuffisant : $required requis"))
        return null
    }
    return caller
}
