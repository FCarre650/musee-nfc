package com.museenfc.backend.ws

import com.museenfc.backend.models.SupervisionSnapshotDto
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Registre des connexions WebSocket ouvertes par l'écran de supervision.
 * À chaque scan reçu, on recalcule l'état des salles et on le pousse à tous les
 * clients connectés -> c'est ce qui fait "virer une salle au rouge en direct".
 * Une seule instance de process ici (POC) ; en multi-instances (§9 du cadrage),
 * ce broadcast est remplacé par un pub/sub Redis relayé à chaque instance.
 */
object SupervisionHub {
    private val sessions = mutableSetOf<WebSocketSession>()
    private val mutex = Mutex()

    suspend fun register(session: WebSocketSession) {
        mutex.withLock { sessions.add(session) }
    }

    suspend fun unregister(session: WebSocketSession) {
        mutex.withLock { sessions.remove(session) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun broadcast(snapshot: SupervisionSnapshotDto) {
        val payload = json.encodeToString(snapshot)
        val dead = mutableListOf<WebSocketSession>()
        mutex.withLock {
            for (session in sessions) {
                try {
                    session.send(Frame.Text(payload))
                } catch (e: Exception) {
                    dead.add(session)
                }
            }
            sessions.removeAll(dead.toSet())
        }
    }
}
