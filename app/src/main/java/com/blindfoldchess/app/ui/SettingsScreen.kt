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
    onOpenPlayVsEngine: () -> Unit,
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
                headlineContent = { Text("Play vs engine (text)") },
                supportingContent = { Text("Type UCI moves; engine replies with adjustable Skill Level") },
                modifier = Modifier.clickable(onClick = onOpenPlayVsEngine),
            )
            HorizontalDivider()
        }
    }
}
