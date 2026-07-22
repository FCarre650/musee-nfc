package com.museenfc.app.ui

import android.nfc.Tag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.museenfc.app.MuseeNfcApp
import com.museenfc.app.network.ApiResult
import com.museenfc.app.network.CheckpointDto
import com.museenfc.app.network.Session
import com.museenfc.app.network.UpdateCheckpointRequest
import kotlinx.coroutines.launch

private enum class RoomsTab { LIST, PROVISION }

/**
 * Regroupe tout ce qui touche aux salles/patchs dans un seul onglet (au lieu d'un onglet
 * "Provisionner" séparé) : c'est le même sujet du point de vue de l'utilisateur (gérer les
 * salles), et ça évite de multiplier les entrées de la barre de navigation sur téléphone.
 */
@Composable
fun RoomsScreen(
    app: MuseeNfcApp,
    session: Session,
    nfcAvailable: Boolean,
    listenForTags: ((Tag) -> Unit) -> Unit,
    stopListeningForTags: () -> Unit,
) {
    var tab by remember { mutableStateOf(RoomsTab.LIST) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(
                selected = tab == RoomsTab.LIST,
                onClick = { tab = RoomsTab.LIST },
                text = { Text("Salles existantes") },
            )
            Tab(
                selected = tab == RoomsTab.PROVISION,
                onClick = { tab = RoomsTab.PROVISION },
                text = { Text("Provisionner") },
            )
        }

        when (tab) {
            RoomsTab.LIST -> RoomsListScreen(app = app, session = session)
            RoomsTab.PROVISION -> ProvisionScreen(
                app = app,
                session = session,
                nfcAvailable = nfcAvailable,
                listenForTags = listenForTags,
                stopListeningForTags = stopListeningForTags,
            )
        }
    }
}

@Composable
private fun RoomsListScreen(app: MuseeNfcApp, session: Session) {
    var rooms by remember { mutableStateOf<List<CheckpointDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<CheckpointDto?>(null) }
    var deleting by remember { mutableStateOf<CheckpointDto?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshTrigger) {
        loading = true
        error = null
        when (val result = app.api.listCheckpoints(session.token)) {
            is ApiResult.Success -> rooms = result.data
            is ApiResult.Rejected -> error = result.message
            is ApiResult.NetworkFailure -> error = "Serveur injoignable"
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Salles (${rooms.size})", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { refreshTrigger++ }) { Text("Rafraîchir") }
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            items(rooms, key = { it.id }) { room ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(room.roomName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(room.zone, "seuil ${room.alertThresholdMin} min").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!room.isActive) {
                            Text(
                                "Salle désactivée",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { editing = room }) { Text("Modifier") }
                            TextButton(onClick = { deleting = room }) { Text("Supprimer") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { room ->
        EditRoomDialog(
            room = room,
            onDismiss = { editing = null },
            onSave = { update ->
                scope.launch {
                    when (val result = app.api.updateCheckpoint(session.token, room.id, update)) {
                        is ApiResult.Success -> {
                            editing = null
                            refreshTrigger++
                        }
                        is ApiResult.Rejected -> error = result.message
                        is ApiResult.NetworkFailure -> error = "Serveur injoignable"
                    }
                }
            },
        )
    }

    deleting?.let { room ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer '${room.roomName}' ?") },
            text = {
                Text(
                    "Le patch physique redevient disponible pour un nouveau provisioning. " +
                        "L'historique des passages de cette salle sera supprimé avec elle.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (val result = app.api.deleteCheckpoint(session.token, room.id)) {
                            is ApiResult.Success -> {
                                deleting = null
                                refreshTrigger++
                            }
                            is ApiResult.Rejected -> {
                                error = result.message
                                deleting = null
                            }
                            is ApiResult.NetworkFailure -> {
                                error = "Serveur injoignable"
                                deleting = null
                            }
                        }
                    }
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun EditRoomDialog(
    room: CheckpointDto,
    onDismiss: () -> Unit,
    onSave: (UpdateCheckpointRequest) -> Unit,
) {
    var roomName by remember { mutableStateOf(room.roomName) }
    var zone by remember { mutableStateOf(room.zone.orEmpty()) }
    var thresholdText by remember { mutableStateOf(room.alertThresholdMin.toString()) }
    var isActive by remember { mutableStateOf(room.isActive) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier la salle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("Nom de la salle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = zone,
                    onValueChange = { zone = it },
                    label = { Text("Zone (optionnel)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { thresholdText = it.filter(Char::isDigit) },
                    label = { Text("Seuil d'alerte (minutes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isActive, onCheckedChange = { isActive = it })
                    Text(if (isActive) "Salle active" else "Salle désactivée")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = roomName.isNotBlank() && thresholdText.isNotBlank(),
                onClick = {
                    onSave(
                        UpdateCheckpointRequest(
                            roomName = roomName,
                            zone = zone.ifBlank { null },
                            alertThresholdMin = thresholdText.toIntOrNull() ?: room.alertThresholdMin,
                            isActive = isActive,
                        ),
                    )
                },
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
