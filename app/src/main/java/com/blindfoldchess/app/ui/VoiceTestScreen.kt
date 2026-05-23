package com.blindfoldchess.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindfoldchess.app.voice.VoskRecognizer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recognizer = remember { VoskRecognizer(context) }
    val state by recognizer.state.collectAsStateWithLifecycle()
    val partial = remember { mutableStateOf("") }
    val finals = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    LaunchedEffect(recognizer) {
        recognizer.events.collect { event ->
            if (event.isFinal) {
                finals.add(event.text)
                partial.value = ""
            } else {
                partial.value = event.text
            }
        }
    }

    LaunchedEffect(finals.size) {
        if (finals.isNotEmpty()) listState.animateScrollToItem(finals.size - 1)
    }

    DisposableEffect(recognizer) {
        onDispose { recognizer.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        finals.clear()
                        partial.value = ""
                    }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Phase 3a bare ASR. Loads the small English Vosk model and streams " +
                    "recognized text. No grammar, no engine — say anything to verify the " +
                    "mic + ASR pipeline. Try \"echo two to echo four\" for the eventual " +
                    "move-parser pattern.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !micGranted -> Button(onClick = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("Grant mic permission") }

                    state == VoskRecognizer.State.Listening -> Button(onClick = {
                        recognizer.stopListening()
                    }) { Text("Stop listening") }

                    state == VoskRecognizer.State.Loading -> Button(enabled = false, onClick = {}) {
                        Text("Loading model...")
                    }

                    else -> Button(onClick = {
                        scope.launch {
                            try {
                                recognizer.ensureModel()
                                recognizer.startListening()
                            } catch (t: Throwable) {
                                finals.add("[host] start failed: ${t.message}")
                            }
                        }
                    }) { Text("Start listening") }
                }
                Text(
                    "state: $state",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider()

            Text("Live partial:", style = MaterialTheme.typography.labelMedium)
            Text(
                text = partial.value.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            Text("Final results:", style = MaterialTheme.typography.labelMedium)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(finals) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
