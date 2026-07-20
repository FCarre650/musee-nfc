package com.museenfc.backend.routes

import com.museenfc.backend.db.GuardRole
import com.museenfc.backend.security.requireRole
import com.museenfc.backend.service.SupervisionService
import com.museenfc.backend.ws.SupervisionHub
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.consumeEach

/**
 * Écran de supervision : réservé chef de poste / direction (cadrage §1).
 * Un gardien scanne des salles mais ne "voit" pas l'état global du musée.
 */
fun Route.supervisionRoutes() {
    get("/supervision/snapshot") {
        val caller = call.requireRole(GuardRole.SUPERVISOR) ?: return@get
        call.respond(SupervisionService.snapshot(caller.museumId))
    }
}

fun Route.supervisionWebSocket() {
    // Le WS vit hors du plugin Authentication JWT "classique" (pas de header pratique en WS depuis un navigateur) ;
    // le jeton est passé en paramètre de requête et vérifié manuellement. Documenté comme limite POC dans le README.
    webSocket("/ws/supervision") {
        val token = call.request.queryParameters["token"]
        val museumId = runCatching {
            val payload = com.museenfc.backend.security.JwtConfig.verifier.verify(token)
            val role = GuardRole.valueOf(payload.getClaim("role").asString())
            if (!role.atLeastSupervisor()) error("rôle insuffisant")
            payload.getClaim("museumId").asInt()
        }.getOrNull()

        if (museumId == null) {
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }

        SupervisionHub.register(this)
        try {
            send(Frame.Text(snapshotJson(museumId)))
            incoming.consumeEach { /* le client n'envoie rien ; on garde juste la connexion vivante */ }
        } finally {
            SupervisionHub.unregister(this)
        }
    }
}

private fun GuardRole.atLeastSupervisor() = this == GuardRole.SUPERVISOR || this == GuardRole.ADMIN

private fun snapshotJson(museumId: Int): String {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    return json.encodeToString(
        com.museenfc.backend.models.SupervisionSnapshotDto.serializer(),
        SupervisionService.snapshot(museumId),
    )
}
