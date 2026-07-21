package com.museenfc.app.ui

import android.nfc.Tag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.museenfc.app.MuseeNfcApp
import com.museenfc.app.data.repository.CheckpointOutcome
import com.museenfc.app.network.Session
import com.museenfc.app.nfc.NfcHelper
import com.museenfc.app.nfc.ProvisionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProvisionScreen(
    app: MuseeNfcApp,
    session: Session,
    nfcAvailable: Boolean,
    listenForTags: ((Tag) -> Unit) -> Unit,
    stopListeningForTags: () -> Unit,
) {
    var roomName by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf("") }
    var thresholdText by remember { mutableStateOf("60") }
    var statusText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { stopListeningForTags() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Provisionner un patch", style = MaterialTheme.typography.titleLarge)
        Text(
            "Réservé direction / chef habilité. Crée la salle côté serveur puis écrit le code " +
                "sur le patch NFC. Verrouillage physique (R1) implémenté côté code mais " +
                "temporairement désactivé pour cette démo — voir NfcHelper.provisionAndLock.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = roomName,
            onValueChange = { roomName = it },
            label = { Text("Nom de la salle") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = zone,
            onValueChange = { zone = it },
            label = { Text("Zone (optionnel)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = thresholdText,
            onValueChange = { thresholdText = it.filter(Char::isDigit) },
            label = { Text("Seuil d'alerte (minutes)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            enabled = nfcAvailable && !isBusy && roomName.isNotBlank() && thresholdText.isNotBlank(),
            onClick = {
                val threshold = thresholdText.toIntOrNull() ?: 60
                statusText = "Approchez le patch neuf du téléphone…"
                isBusy = true
                // Verrou local (pas stopListeningForTags ici) : sur certains téléphones
                // (Xiaomi/MIUI constaté), désactiver le mode lecteur NFC est un appel bloquant
                // qui peut prendre plusieurs secondes. L'appeler AVANT d'utiliser le tag qu'on
                // vient de capturer laissait le temps à Android d'invalider la référence
                // ("Tag is out of date"), recréant le même problème que l'appel réseau. On
                // ignore donc juste les redéclenchements du même tap avec un simple booléen,
                // et on ne coupe l'écoute qu'une fois le résultat obtenu, quand la lenteur
                // éventuelle de cet appel ne coûte plus rien.
                var tap1Handled = false
                listenForTags { tag ->
                    if (tap1Handled) return@listenForTags
                    tap1Handled = true
                    val uid = NfcHelper.readUid(tag)
                    scope.launch {
                        statusText = "UID $uid lu, création côté serveur…"
                        when (
                            val outcome = app.checkpointRepository.create(
                                session, uid, roomName, zone.ifBlank { null }, threshold,
                            )
                        ) {
                            is CheckpointOutcome.Success -> {
                                val checkpointCode = outcome.checkpoint.checkpointCode
                                // Un objet Tag Android devient invalide ("Tag is out of date")
                                // dès qu'on le garde en mémoire pendant un appel réseau — on ne
                                // réutilise donc JAMAIS le tag de ce premier tap pour l'écriture.
                                // On redemande un second tap, tout frais, immédiatement suivi de
                                // l'écriture sans plus aucune attente entre les deux.
                                statusText = "Salle '$roomName' créée. Approchez À NOUVEAU LE MÊME " +
                                    "patch pour y écrire le code…"
                                var tap2Handled = false
                                listenForTags { freshTag ->
                                    if (tap2Handled) return@listenForTags
                                    tap2Handled = true
                                    scope.launch {
                                        try {
                                            // I/O NFC bas niveau bloquant : hors du thread
                                            // principal pour ne pas déclencher d'ANR.
                                            val writeResult = withContext(Dispatchers.IO) {
                                                NfcHelper.writeCheckpointCode(freshTag, checkpointCode)
                                            }
                                            statusText = when (writeResult) {
                                                is ProvisionResult.Success ->
                                                    "✔ '$roomName' créée, patch écrit (code ${writeResult.checkpointCode.take(8)}…)"
                                                is ProvisionResult.Failure ->
                                                    "⚠ Salle créée côté serveur, mais écriture du patch échouée : ${writeResult.reason}"
                                            }
                                        } finally {
                                            isBusy = false
                                            stopListeningForTags()
                                        }
                                    }
                                }
                            }
                            is CheckpointOutcome.Failure -> {
                                statusText = "✘ ${outcome.message}"
                                isBusy = false
                                stopListeningForTags()
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("1. Provisionner (2 taps : création puis écriture)")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            "Verrouillage physique du patch (R1) : implémenté (NfcHelper.provisionAndLock / " +
                "testLock) mais désactivé dans cette démo, le temps de fiabiliser son " +
                "comportement sur l'ensemble du parc de téléphones de test. Risque documenté, " +
                "non démontré en direct.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        if (statusText.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(statusText, modifier = Modifier.padding(16.dp))
            }
        }

        if (!nfcAvailable) {
            Text(
                "Aucun adaptateur NFC détecté : le provisioning nécessite un appareil compatible.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
