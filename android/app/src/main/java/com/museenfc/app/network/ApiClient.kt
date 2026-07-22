package com.museenfc.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException

/** Résultat d'appel réseau : distingue "pas de réseau" (-> à mettre en file offline)
 *  de "le serveur a refusé" (-> définitif, ne pas réessayer en boucle, ex. R2/R3). */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Rejected(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkFailure(val cause: Throwable) : ApiResult<Nothing>()
}

class MuseeApi(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(Logging) { level = LogLevel.INFO }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    }

    private suspend inline fun <reified T> handle(block: () -> HttpResponse): ApiResult<T> = try {
        val response = block()
        if (response.status.isSuccess()) {
            ApiResult.Success(json.decodeFromString<T>(response.bodyAsText()))
        } else {
            val message = runCatching { json.decodeFromString<ErrorResponse>(response.bodyAsText()).error }
                .getOrDefault(response.bodyAsText())
            ApiResult.Rejected(response.status.value, message)
        }
    } catch (e: IOException) {
        ApiResult.NetworkFailure(e)
    } catch (e: Exception) {
        ApiResult.NetworkFailure(e)
    }

    // Réponses sans corps (204 No Content) : décoder du JSON vide échouerait avec `handle`.
    private suspend fun handleNoContent(block: () -> HttpResponse): ApiResult<Unit> = try {
        val response = block()
        if (response.status.isSuccess()) {
            ApiResult.Success(Unit)
        } else {
            val message = runCatching { json.decodeFromString<ErrorResponse>(response.bodyAsText()).error }
                .getOrDefault(response.bodyAsText())
            ApiResult.Rejected(response.status.value, message)
        }
    } catch (e: IOException) {
        ApiResult.NetworkFailure(e)
    } catch (e: Exception) {
        ApiResult.NetworkFailure(e)
    }

    suspend fun login(login: String, password: String): ApiResult<LoginResponse> = handle {
        client.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(login, password))
        }
    }

    suspend fun submitScan(token: String, request: ScanRequest): ApiResult<ScanResponse> = handle {
        client.post("$baseUrl/api/scans") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun listCheckpoints(token: String): ApiResult<List<CheckpointDto>> = handle {
        client.get("$baseUrl/api/checkpoints") {
            header("Authorization", "Bearer $token")
        }
    }

    suspend fun createCheckpoint(token: String, request: CreateCheckpointRequest): ApiResult<CheckpointDto> = handle {
        client.post("$baseUrl/api/checkpoints") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun updateCheckpoint(token: String, id: Int, request: UpdateCheckpointRequest): ApiResult<CheckpointDto> = handle {
        client.patch("$baseUrl/api/checkpoints/$id") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun deleteCheckpoint(token: String, id: Int): ApiResult<Unit> = handleNoContent {
        client.delete("$baseUrl/api/checkpoints/$id") {
            header("Authorization", "Bearer $token")
        }
    }

    suspend fun history(token: String, checkpointId: Int? = null): ApiResult<List<ScanHistoryItem>> = handle {
        client.get("$baseUrl/api/scans") {
            header("Authorization", "Bearer $token")
            checkpointId?.let { parameter("checkpointId", it) }
        }
    }

    suspend fun listGuards(token: String): ApiResult<List<GuardDto>> = handle {
        client.get("$baseUrl/api/guards") {
            header("Authorization", "Bearer $token")
        }
    }

    suspend fun createGuard(token: String, request: CreateGuardRequest): ApiResult<GuardDto> = handle {
        client.post("$baseUrl/api/guards") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun deleteGuard(token: String, id: Int): ApiResult<Unit> = handleNoContent {
        client.delete("$baseUrl/api/guards/$id") {
            header("Authorization", "Bearer $token")
        }
    }
}
