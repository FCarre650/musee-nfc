package com.museenfc.app

import android.app.Application
import com.museenfc.app.data.local.AppDatabase
import com.museenfc.app.data.repository.AuthRepository
import com.museenfc.app.data.repository.CheckpointRepository
import com.museenfc.app.data.repository.ScanRepository
import com.museenfc.app.network.MuseeApi
import com.museenfc.app.network.TokenStore

/**
 * Pas de framework de DI (Hilt/Koin) pour ce POC : un simple conteneur manuel suffit
 * pour la taille du projet et reste facile à suivre pendant la semaine de dev.
 */
class MuseeNfcApp : Application() {

    lateinit var api: MuseeApi
        private set
    lateinit var tokenStore: TokenStore
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var scanRepository: ScanRepository
        private set
    lateinit var checkpointRepository: CheckpointRepository
        private set

    override fun onCreate() {
        super.onCreate()
        api = MuseeApi(BuildConfig.BASE_URL)
        tokenStore = TokenStore(this)
        authRepository = AuthRepository(api, tokenStore)
        scanRepository = ScanRepository(api, AppDatabase.get(this).scanDao())
        checkpointRepository = CheckpointRepository(api)
    }
}
