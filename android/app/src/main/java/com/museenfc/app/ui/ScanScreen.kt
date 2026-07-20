package com.museenfc.app.ui

import android.nfc.Tag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.museenfc.app.MuseeNfcApp
import com.museenfc.app.data.repository.ScanOutcome
import com.museenfc.app.network.Session
import com.museenfc.app.nfc.NfcHelper
import kotlinx.coroutines.launch

@Composable
fun ScanScreen(
    app: MuseeNfcApp,
    session: Session,
    nfcAvailable: Boolean,
    listenForTags: ((Tag) -> Unit) -> Unit,
    stopListeningForTags: () -> Unit,
    onLogout: () -> Unit,
) {
    var lastResultText by remember { mutableStateOf("Approchez le téléphone d'un patch pour commencer la ronde.") }
    var lastResultIsError by remember { mutableStateOf(false) }
    val pendingCount by app.scanRepository.pendingCount().collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        listenForTags { tag ->
            // Lecture NFC (I/O bref sur le thread du callback) puis envoi réseau en coroutine.
            val patch = NfcHelper.scan(tag)
            scope.launch {
                when (val outcome = app.scanRepository.submitScan(session, patch, roomNameHint = "salle inconnue localement")) {
                    is ScanOutcome.Accepted -> {
                        lastResultIsError = false
                        lastResultText = "✔ ${outcome.roomName} — passage enregistré"
                    }
                    is ScanOutcome.Rejected -> {
                        lastResultIsError = true
                        lastResultText = "✘ Refusé : ${outcome.message}"
                    }
                    ScanOutcome.QueuedOffline -> {
                        lastResultIsError = false
                        lastResultText = "⏳ Pas de réseau : scan mis en file, sera envoyé à la reconnexion"
                    }
                }
            }
        }
        onDispose { stopListeningForTags() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Bonjour ${session.fullName}", style = MaterialTheme.typography.titleLarge)
        Text(session.role, style = MaterialTheme.typography.labelMedium)

        if (!nfcAvailable) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text(
                    "Aucun adaptateur NFC détecté sur cet appareil : le scan n'est pas possible ici.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text(
                    lastResultText,
                    color = if (lastResultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (pendingCount > 0) {
            Text(
                "$pendingCount scan(s) en attente de synchronisation",
                modifier = Modifier.padding(top = 12.dp),
            )
            Button(
                onClick = { scope.launch { app.scanRepository.syncPending(session) } },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Synchroniser maintenant")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = onLogout) {
                Text("Déconnexion")
            }
        }
    }
}
