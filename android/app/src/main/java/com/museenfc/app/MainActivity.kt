package com.museenfc.app

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.museenfc.app.network.Session
import com.museenfc.app.ui.HistoryScreen
import com.museenfc.app.ui.LoginScreen
import com.museenfc.app.ui.ProvisionScreen
import com.museenfc.app.ui.ScanScreen
import com.museenfc.app.ui.theme.MuseeNfcTheme

private enum class Screen { SCAN, PROVISION, HISTORY }

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var currentTagListener: ((Tag) -> Unit)? = null

    /** Reader mode : actif seulement pendant que l'écran Scan/Provisioning est affiché
     *  (voir ScanScreen/ProvisionScreen), pour ne pas capter de tag ailleurs dans l'app. */
    fun listenForTags(onTag: (Tag) -> Unit) {
        currentTagListener = onTag
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> runOnUiThread { currentTagListener?.invoke(tag) } },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
    }

    fun stopListeningForTags() {
        currentTagListener = null
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val app = application as MuseeNfcApp
        val nfcAvailable = nfcAdapter != null

        setContent {
            MuseeNfcTheme {
                MuseeApp(app = app, nfcAvailable = nfcAvailable, activity = this)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopListeningForTags()
    }
}

@Composable
private fun MuseeApp(app: MuseeNfcApp, nfcAvailable: Boolean, activity: MainActivity) {
    var session by remember { mutableStateOf<Session?>(app.authRepository.currentSession()) }
    var screen by remember { mutableStateOf(Screen.SCAN) }

    val current = session
    if (current == null) {
        LoginScreen(
            authRepository = app.authRepository,
            onLoggedIn = { session = it },
        )
        return
    }

    val isSupervisorOrAbove = current.role == "SUPERVISOR" || current.role == "ADMIN"
    val isAdmin = current.role == "ADMIN"

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.SCAN,
                    onClick = { screen = Screen.SCAN },
                    icon = {},
                    label = { Text("Scanner") },
                )
                if (isAdmin) {
                    NavigationBarItem(
                        selected = screen == Screen.PROVISION,
                        onClick = { screen = Screen.PROVISION },
                        icon = {},
                        label = { Text("Provisionner") },
                    )
                }
                if (isSupervisorOrAbove) {
                    NavigationBarItem(
                        selected = screen == Screen.HISTORY,
                        onClick = { screen = Screen.HISTORY },
                        icon = {},
                        label = { Text("Historique") },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (screen) {
                Screen.SCAN -> ScanScreen(
                    app = app,
                    session = current,
                    nfcAvailable = nfcAvailable,
                    listenForTags = activity::listenForTags,
                    stopListeningForTags = activity::stopListeningForTags,
                    onLogout = {
                        app.authRepository.logout()
                        session = null
                    },
                )
                Screen.PROVISION -> ProvisionScreen(
                    app = app,
                    session = current,
                    nfcAvailable = nfcAvailable,
                    listenForTags = activity::listenForTags,
                    stopListeningForTags = activity::stopListeningForTags,
                )
                Screen.HISTORY -> HistoryScreen(app = app, session = current)
            }
        }
    }
}
