package com.museenfc.backend.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.museenfc.backend.db.GuardRole
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * JWT = le "qui" (authentification). Le rôle transporté dans le claim "role"
 * sert de base au RBAC (le "quoi", vérifié route par route) — voir Routes/Roles.kt.
 *
 * Secret lu depuis la variable d'env JWT_SECRET si présente (déploiement), sinon
 * une valeur de dev fixe pour ne pas bloquer le premier lancement du POC.
 */
object JwtConfig {
    const val ISSUER = "musee-nfc-backend"
    const val AUDIENCE = "musee-nfc-clients"
    const val REALM = "musee-nfc"
    private val SECRET = System.getenv("JWT_SECRET") ?: "dev-secret-changeme-en-production-0123456789"
    private val VALIDITY_MS = TimeUnit.HOURS.toMillis(12)

    val algorithm: Algorithm = Algorithm.HMAC256(SECRET)

    val verifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()

    fun generateToken(guardId: Int, museumId: Int, login: String, role: GuardRole): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withClaim("guardId", guardId)
            .withClaim("museumId", museumId)
            .withClaim("login", login)
            .withClaim("role", role.name)
            .withExpiresAt(Date(System.currentTimeMillis() + VALIDITY_MS))
            .sign(algorithm)
}
