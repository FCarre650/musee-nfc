package com.museenfc.app.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class Session(
    val token: String,
    val guardId: Int,
    val fullName: String,
    val login: String,
    val role: String,
)

/**
 * Le jeton JWT est stocké chiffré, adossé à une clé matérielle du Keystore Android
 * (jamais en clair dans des SharedPreferences classiques ou en mémoire persistée). R9.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "musee_nfc_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(session: Session) {
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putInt(KEY_GUARD_ID, session.guardId)
            .putString(KEY_FULL_NAME, session.fullName)
            .putString(KEY_LOGIN, session.login)
            .putString(KEY_ROLE, session.role)
            .apply()
    }

    fun load(): Session? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val fullName = prefs.getString(KEY_FULL_NAME, null) ?: return null
        val login = prefs.getString(KEY_LOGIN, null) ?: return null
        val role = prefs.getString(KEY_ROLE, null) ?: return null
        val guardId = prefs.getInt(KEY_GUARD_ID, -1)
        if (guardId < 0) return null
        return Session(token, guardId, fullName, login, role)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_GUARD_ID = "guard_id"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_LOGIN = "login"
        const val KEY_ROLE = "role"
    }
}
