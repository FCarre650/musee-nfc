package com.museenfc.backend

import com.museenfc.backend.db.DatabaseFactory
import com.museenfc.backend.models.ErrorResponse
import com.museenfc.backend.routes.authRoutes
import com.museenfc.backend.routes.checkpointRoutes
import com.museenfc.backend.routes.guardRoutes
import com.museenfc.backend.routes.scanRoutes
import com.museenfc.backend.routes.supervisionRoutes
import com.museenfc.backend.routes.supervisionWebSocket
import com.museenfc.backend.security.JwtConfig
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.Json
import java.time.Duration

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    DatabaseFactory.init()

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = false })
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(30)
    }

    // CORS permissif : POC uniquement (le PC sécurité ouvre la page servie par ce même backend,
    // donc pas nécessaire en usage normal). À restreindre à des origines nommées en production.
    install(CORS) {
        anyHost()
        allowHeader("Authorization")
        allowHeader("Content-Type")
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Patch)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }

    install(CallLogging)

    install(StatusPages) {
        exception<io.ktor.serialization.JsonConvertException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Requête invalide : ${cause.message}"))
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Requête invalide : ${cause.message}"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Erreur serveur"))
        }
    }

    install(Authentication) {
        jwt("auth-jwt") {
            realm = JwtConfig.REALM
            verifier(JwtConfig.verifier)
            validate { credential ->
                if (credential.payload.getClaim("guardId").asInt() != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Jeton invalide ou expiré"))
            }
        }
    }

    routing {
        route("/api") {
            authRoutes()
            authenticate("auth-jwt") {
                checkpointRoutes()
                guardRoutes()
                scanRoutes()
                supervisionRoutes()
            }
        }
        // Jeton vérifié manuellement dans le handler (voir SupervisionRoutes.kt).
        supervisionWebSocket()

        // Sert supervision.html + assets : le PC sécurité ouvre juste http://<ip-serveur>:8080/
        staticResources("/", "static", index = "supervision.html")
    }
}
