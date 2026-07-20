package com.museenfc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.museenfc.app.MuseeNfcApp
import com.museenfc.app.network.ApiResult
import com.museenfc.app.network.ScanHistoryItem
import com.museenfc.app.network.Session

@Composable
fun HistoryScreen(app: MuseeNfcApp, session: Session) {
    var items by remember { mutableStateOf<List<ScanHistoryItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        loading = true
        error = null
        when (val result = app.api.history(session.token)) {
            is ApiResult.Success -> items = result.data
            is ApiResult.Rejected -> error = result.message
            is ApiResult.NetworkFailure -> error = "Serveur injoignable"
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Historique des contrôles", style = MaterialTheme.typography.titleLarge)
        Button(
            onClick = { refreshTrigger++ },
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text("Rafraîchir")
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.roomName, style = MaterialTheme.typography.titleSmall)
                        Text("${item.guardName} — ${item.receivedAt}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            item.status,
                            color = if (item.status == "ANOMALY") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
