package com.blindfoldchess.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenHeadphoneTest: () -> Unit,
    onOpenEngineSelfTest: () -> Unit,
    onOpenVoiceTest: () -> Unit,
    onOpenGameHistory: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenBoard: () -> Unit,
    onOpenVoiceCommands: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ListItem(
                headlineContent = { Text("Game preferences") },
                supportingContent = { Text("Engine strength, think time, spoken notation, verbosity, fog default") },
                modifier = Modifier.clickable(onClick = onOpenPreferences),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Board view") },
                supportingContent = { Text("Visualize the current position; tap squares to peek through fog") },
                modifier = Modifier.clickable(onClick = onOpenBoard),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Voice commands") },
                supportingContent = { Text("Cheat sheet of every phrase the recognizer accepts") },
                modifier = Modifier.clickable(onClick = onOpenVoiceCommands),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Headphone button test") },
                supportingContent = { Text("Log every media key event received from a connected headset") },
                modifier = Modifier.clickable(onClick = onOpenHeadphoneTest),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Engine self-test") },
                supportingContent = { Text("Load embedded Stockfish and drive it via UCI commands") },
                modifier = Modifier.clickable(onClick = onOpenEngineSelfTest),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Voice test") },
                supportingContent = { Text("Bare Vosk ASR — see what the recognizer hears") },
                modifier = Modifier.clickable(onClick = onOpenVoiceTest),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Game history") },
                supportingContent = { Text("Completed games — result, date, move list") },
                modifier = Modifier.clickable(onClick = onOpenGameHistory),
            )
            HorizontalDivider()
        }
    }
}
