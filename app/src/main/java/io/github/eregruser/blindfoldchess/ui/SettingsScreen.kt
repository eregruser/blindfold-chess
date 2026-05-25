package io.github.eregruser.blindfoldchess.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.eregruser.blindfoldchess.R

/**
 * Settings index. Items are grouped by frequency of use rather than alphabetically:
 *   1. Everyday — Game preferences, Game history, Board view, Voice commands.
 *   2. Meta — Send feedback, About.
 *   3. Diagnostics — Headphone button test, Engine self-test, Voice test.
 * No section headers (only 9 items total); the grouping is conveyed by order.
 */
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
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val feedbackUrl = stringResource(R.string.feedback_url)

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
            // --- Everyday ---
            ListItem(
                headlineContent = { Text("Game preferences") },
                supportingContent = { Text("Engine strength, think time, spoken notation, verbosity, fog default") },
                modifier = Modifier.clickable(onClick = onOpenPreferences),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Game history") },
                supportingContent = { Text("Completed games — result, date, move list") },
                modifier = Modifier.clickable(onClick = onOpenGameHistory),
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

            // --- Meta ---
            ListItem(
                headlineContent = { Text("Send feedback") },
                supportingContent = { Text("Open GitHub Issues to report a bug, request a feature, or share thoughts") },
                modifier = Modifier.clickable {
                    if (feedbackUrl.isNotBlank()) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(feedbackUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("About") },
                supportingContent = { Text("Version, source code, privacy policy, licenses") },
                modifier = Modifier.clickable(onClick = onOpenAbout),
            )
            HorizontalDivider()

            // --- Diagnostics ---
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
        }
    }
}
