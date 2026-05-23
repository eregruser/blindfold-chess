package com.blindfoldchess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindfoldchess.app.service.ChessGameService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val state by ChessGameService.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blindfold Chess") },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (state.mockGameActive) "Mock game running" else "Mock game stopped",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Starting the mock game takes full audio focus and streams silent audio " +
                    "so Bluetooth/AVRCP routes headset buttons to this app. Music in other apps " +
                    "will pause for the duration of the session.\n\n" +
                    "Headset buttons map to TTS:\n" +
                    "  • play / play-pause  →  \"listening\"\n" +
                    "  • next               →  \"repeat\"\n" +
                    "  • previous           →  \"cancel\"",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (state.mockGameActive) {
                Button(onClick = { ChessGameService.stopMockGame(context) }) {
                    Text("Stop mock game")
                }
            } else {
                Button(onClick = { ChessGameService.startMockGame(context) }) {
                    Text("Start mock game")
                }
            }
        }
    }
}
