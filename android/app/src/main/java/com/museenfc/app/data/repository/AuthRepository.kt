package com.museenfc.app.data.repository

import com.museenfc.app.network.ApiResult
import com.museenfc.app.network.MuseeApi
import com.museenfc.app.network.Session
import com.museenfc.app.network.TokenStore

sealed class LoginOutcome {
    data class Success(val session: Session) : LoginOutcome()
    data class Rejected(val message: String) : LoginOutcome()
    data object NetworkUnavailable : LoginOutcome()
}

class AuthRepository(private val api: MuseeApi, private val tokenStore: TokenStore) {

    fun currentSession(): Session? = tokenStore.load()

    suspend fun login(login: String, password: String): LoginOutcome =
        when (val result = api.login(login, password)) {
            is ApiResult.Success -> {
                val session = Session(
                    token = result.data.token,
                    guardId = result.data.guard.id,
                    fullName = result.data.guard.fullName,
                    login = result.data.guard.login,
                    role = result.data.guard.role,
                )
                tokenStore.save(session)
                LoginOutcome.Success(session)
            }
            is ApiResult.Rejected -> LoginOutcome.Rejected(result.message)
            is ApiResult.NetworkFailure -> LoginOutcome.NetworkUnavailable
        }

    fun logout() = tokenStore.clear()
}
