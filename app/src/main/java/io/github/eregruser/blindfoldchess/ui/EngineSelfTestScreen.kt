package io.github.eregruser.blindfoldchess.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.eregruser.blindfoldchess.engine.EngineAssets
import io.github.eregruser.blindfoldchess.engine.StockfishEngine
import io.github.eregruser.blindfoldchess.engine.StockfishJni
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineSelfTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val jni = remember { StockfishJni() }
    val engine = remember { StockfishEngine(jni) }
    val assets = remember { EngineAssets(context) }

    val lines = remember { mutableStateListOf<String>() }
    var started by remember { mutableStateOf(false) }
    var bestmove by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var commandInput by remember { mutableStateOf("uci") }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val handshakeOk by remember(lines) {
        derivedStateOf { lines.any { it.startsWith("id name Stockfish") } }
    }

    LaunchedEffect(jni) {
        jni.output.collect { lines.add(it) }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    DisposableEffect(engine) {
        onDispose { if (started) engine.stop() }
    }

    fun appendHost(msg: String) {
        lines.add("[host] $msg")
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
                    TextButton(onClick = {
                        lines.clear()
                        bestmove = null
                        lastError = null
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
                text = "Phase 2a: \"Start + handshake\" verifies the libstockfish_bridge.so " +
                    "build and UCI handshake.\nPhase 2b: \"Play first move\" extracts NNUE " +
                    "assets, sets EvalFile options, and runs a 200ms search from the start " +
                    "position; you should see a bestmove like e2e4.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (started) {
                    Button(onClick = {
                        engine.stop()
                        started = false
                    }) { Text("Stop engine") }
                } else {
                    Button(onClick = {
                        scope.launch {
                            try {
                                engine.start()
                                started = true
                            } catch (t: Throwable) {
                                appendHost("start failed: ${t.message}")
                                lastError = t.message
                            }
                        }
                    }) { Text("Start + handshake") }
                }
                Button(
                    enabled = started && handshakeOk,
                    onClick = {
                        scope.launch {
                            bestmove = null
                            lastError = null
                            try {
                                val nnue = withContext(Dispatchers.IO) { assets.ensureExtracted() }
                                if (nnue == null) {
                                    appendHost("NNUE files missing — run scripts/fetch_nnue.sh and rebuild")
                                } else {
                                    appendHost("NNUE: big=${nnue.big.absolutePath}")
                                    appendHost("NNUE: small=${nnue.small.absolutePath}")
                                    engine.setOption("EvalFile", nnue.big.absolutePath)
                                    engine.setOption("EvalFileSmall", nnue.small.absolutePath)
                                }
                                engine.newGame()
                                engine.setPosition(startFen = null, moves = emptyList())
                                val mv = engine.goMoveTime(200)
                                bestmove = mv
                                appendHost("bestmove → $mv")
                            } catch (t: Throwable) {
                                appendHost("play failed: ${t.message}")
                                lastError = t.message
                            }
                        }
                    },
                ) { Text("Play first move") }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (handshakeOk) {
                    Text("handshake ok", style = MaterialTheme.typography.bodyMedium)
                }
                bestmove?.let {
                    Text("bestmove: $it", style = MaterialTheme.typography.titleSmall)
                }
                lastError?.let {
                    Text("error: $it", style = MaterialTheme.typography.bodySmall)
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
                    enabled = started,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val cmd = commandInput.trim()
                        if (cmd.isNotEmpty()) scope.launch { jni.send(cmd) }
                    },
                    enabled = started,
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
