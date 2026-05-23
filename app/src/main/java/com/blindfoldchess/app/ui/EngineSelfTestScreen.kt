package com.blindfoldchess.app.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blindfoldchess.app.engine.StockfishJni
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineSelfTestScreen(onBack: () -> Unit) {
    val engine = remember { StockfishJni() }
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var commandInput by remember { mutableStateOf("uci") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val handshakeOk by remember(lines) {
        derivedStateOf { lines.any { it.startsWith("id name Stockfish") } }
    }

    LaunchedEffect(engine) {
        engine.output.collect { lines.add(it) }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    DisposableEffect(engine) {
        onDispose {
            if (running) engine.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Engine self-test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { lines.clear() }) { Text("Clear") }
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
                text = "Loads libstockfish_bridge.so, spawns the embedded UCI engine, " +
                    "and pipes commands to its stdin / lines from its stdout. Tap Start " +
                    "and confirm an \"id name Stockfish\" line appears.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    Button(onClick = {
                        engine.stop()
                        running = false
                    }) { Text("Stop engine") }
                } else {
                    Button(onClick = {
                        if (engine.start()) {
                            running = true
                            scope.launch { engine.send("uci") }
                        } else {
                            lines.add("[host] nativeStart() returned false")
                        }
                    }) { Text("Start + send \"uci\"") }
                }
                if (handshakeOk) {
                    Text(
                        text = "handshake ok",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    label = { Text("UCI command") },
                    singleLine = true,
                    enabled = running,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val cmd = commandInput.trim()
                        if (cmd.isNotEmpty()) {
                            scope.launch { engine.send(cmd) }
                        }
                    },
                    enabled = running,
                ) { Text("Send") }
            }

            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
