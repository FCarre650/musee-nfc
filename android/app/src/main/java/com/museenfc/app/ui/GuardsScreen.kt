package com.museenfc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.museenfc.app.MuseeNfcApp
import com.museenfc.app.network.ApiResult
import com.museenfc.app.network.CreateGuardRequest
import com.museenfc.app.network.GuardDto
import com.museenfc.app.network.Session
import kotlinx.coroutines.launch

private val ROLES = listOf("GUARD", "SUPERVISOR", "ADMIN")

@Composable
fun GuardsScreen(app: MuseeNfcApp, session: Session) {
    var guards by remember { mutableStateOf<List<GuardDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<GuardDto?>(null) }
    val scope = rememberCoroutineScope()

    var fullName by remember { mutableStateOf("") }
    var badgeNumber by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("GUARD") }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        loading = true
        error = null
        when (val result = app.api.listGuards(session.token)) {
            is ApiResult.Success -> guards = result.data
            is ApiResult.Rejected -> error = result.message
            is ApiResult.NetworkFailure -> error = "Serveur injoignable"
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Comptes gardiens", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Créer un compte", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Nom complet") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = badgeNumber,
                    onValueChange = { badgeNumber = it },
                    label = { Text("Numéro de badge") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text("Identifiant de connexion") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Rôle", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ROLES.forEach { r ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r) })
                    }
                }

                Button(
                    enabled = !creating && fullName.isNotBlank() && badgeNumber.isNotBlank() &&
                        login.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        creating = true
                        error = null
                        scope.launch {
                            val request = CreateGuardRequest(
                                fullName = fullName.trim(),
                                badgeNumber = badgeNumber.trim(),
                                login = login.trim(),
                                password = password,
                                role = role,
                            )
                            when (val result = app.api.createGuard(session.token, request)) {
                                is ApiResult.Success -> {
                                    fullName = ""
                                    badgeNumber = ""
                                    login = ""
                                    password = ""
                                    role = "GUARD"
                                    refreshTrigger++
                                }
                                is ApiResult.Rejected -> error = result.message
                                is ApiResult.NetworkFailure -> error = "Serveur injoignable"
                            }
                            creating = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (creating) "Création…" else "Créer le compte")
                }
            }
        }

        if (loading) {
            CircularProgressIndicator()
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Text("Comptes existants (${guards.size})", style = MaterialTheme.typography.titleMedium)
        guards.forEach { guard ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(guard.fullName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${guard.login} · badge ${guard.badgeNumber} · ${guard.role}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Un admin ne peut pas se supprimer lui-même (refusé aussi côté serveur) :
                    // le bouton n'est même pas proposé sur sa propre ligne.
                    if (guard.id != session.guardId) {
                        TextButton(onClick = { deleting = guard }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    deleting?.let { guard ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer le compte de ${guard.fullName} ?") },
            text = {
                Text(
                    "Action irréversible. Refusée si ce compte a déjà des passages enregistrés : " +
                        "l'historique des rondes n'est jamais supprimé avec un compte.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (val result = app.api.deleteGuard(session.token, guard.id)) {
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
