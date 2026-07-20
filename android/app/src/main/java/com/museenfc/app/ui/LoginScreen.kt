package com.museenfc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.museenfc.app.data.repository.AuthRepository
import com.museenfc.app.data.repository.LoginOutcome
import com.museenfc.app.network.Session
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(authRepository: AuthRepository, onLoggedIn: (Session) -> Unit) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Musée Carré", style = MaterialTheme.typography.headlineMedium)
        Text("Ronde NFC — connexion gardien", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.padding(top = 24.dp))

        OutlinedTextField(
            value = login,
            onValueChange = { login = it; error = null },
            label = { Text("Identifiant") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Mot de passe") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }

        Button(
            onClick = {
                error = null
                loading = true
                scope.launch {
                    when (val outcome = authRepository.login(login.trim(), password)) {
                        is LoginOutcome.Success -> onLoggedIn(outcome.session)
                        is LoginOutcome.Rejected -> error = outcome.message
                        LoginOutcome.NetworkUnavailable ->
                            error = "Serveur injoignable. Vérifiez l'adresse configurée et le réseau Wi-Fi."
                    }
                    loading = false
                }
            },
            enabled = !loading && login.isNotBlank() && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp))
            } else {
                Text("Se connecter")
            }
        }
    }
}
