package com.museenfc.app.ui

import android.nfc.Tag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.launch

/**
 * Mot de passe de provisioning : identique pour tous les patchs dans ce POC, embarqué dans
 * l'app. Limite assumée et documentée (README, section sécurité) : en production, ce secret
 * doit être géré côté serveur (par musée, voire par patch), jamais codé en dur côté client.
 */
private val PROVISION_PASSWORD = byteArrayOf(0x4D, 0x55, 0x53, 0x45) // "MUSE"
private val PROVISION_PACK = byteArrayOf(0x01, 0x00)

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
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { stopListeningForTags() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Provisionner un patch", style = MaterialTheme.typography.titleLarge)
        Text(
            "Réservé direction / chef habilité. Crée la salle côté serveur puis écrit et " +
                "verrouille le code sur le patch NFC (R1 : le patch refusera toute réécriture non autorisée).",
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
            enabled = nfcAvailable && roomName.isNotBlank() && thresholdText.isNotBlank(),
            onClick = {
                val threshold = thresholdText.toIntOrNull() ?: 60
                statusText = "Approchez le patch neuf du téléphone…"
                listenForTags { tag ->
                    val uid = NfcHelper.readUid(tag)
                    scope.launch {
                        statusText = "UID $uid lu, création côté serveur…"
                        when (
                            val outcome = app.checkpointRepository.create(
                                session, uid, roomName, zone.ifBlank { null }, threshold,
                            )
                        ) {
                            is CheckpointOutcome.Success -> {
                                val lockResult = NfcHelper.provisionAndLock(
                                    tag, outcome.checkpoint.checkpointCode, PROVISION_PASSWORD, PROVISION_PACK,
                                )
                                statusText = when (lockResult) {
                                    is ProvisionResult.Success ->
                                        "✔ '$roomName' créée et patch verrouillé (code ${lockResult.checkpointCode.take(8)}…)"
                                    is ProvisionResult.Failure ->
                                        "⚠ Salle créée côté serveur, mais verrouillage du patch échoué : ${lockResult.reason}"
                                }
                            }
                            is CheckpointOutcome.Failure -> statusText = "✘ ${outcome.message}"
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("1. Provisionner (approcher le patch après ce clic)")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Vérification du verrouillage", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tente une réécriture SANS mot de passe sur un patch déjà provisionné : " +
                "doit être refusée si le verrouillage a fonctionné.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            enabled = nfcAvailable,
            onClick = {
                statusText = "Approchez un patch verrouillé pour tester la réécriture…"
                listenForTags { tag ->
                    val accepted = NfcHelper.attemptUnauthorizedRewrite(tag, "tentative-sabotage")
                    statusText = if (accepted) {
                        "✘ ATTENTION : la réécriture a été ACCEPTÉE — ce patch n'est pas verrouillé."
                    } else {
                        "✔ Réécriture refusée par la puce : le verrouillage fonctionne."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("2. Tester le verrouillage d'un patch")
        }

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
