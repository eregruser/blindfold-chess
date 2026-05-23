package com.blindfoldchess.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindfoldchess.app.service.ChessGameService
import com.blindfoldchess.app.service.KeyEventEntry
import com.blindfoldchess.app.service.KeyEventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadphoneTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by KeyEventLog.entries.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    LifecycleResumeEffect(Unit) {
        ChessGameService.enableTestMode(context)
        onPauseOrDispose {
            ChessGameService.disableTestMode(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Headphone button test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { KeyEventLog.clear() }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Text(
                text = "Capturing media key events. Press any button on a connected headset.\n\n" +
                    "Note: this page also takes full audio focus and streams silent audio so " +
                    "Bluetooth routes events to us. Other media will pause while this page is open.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No events yet", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.id }) { entry ->
                        EventRow(entry = entry, timeFormat = timeFormat)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(entry: KeyEventEntry, timeFormat: SimpleDateFormat) {
    val actionLabel = when (entry.action) {
        KeyEvent.ACTION_DOWN -> "DOWN"
        KeyEvent.ACTION_UP -> "UP"
        else -> "ACTION_${entry.action}"
    }
    val keyLabel = KeyEvent.keyCodeToString(entry.keyCode)
    ListItem(
        headlineContent = { Text("$keyLabel  •  $actionLabel") },
        supportingContent = {
            Text(
                "code=${entry.keyCode}  repeat=${entry.repeatCount}  " +
                    "at ${timeFormat.format(Date(entry.timestamp))}",
            )
        },
    )
}
