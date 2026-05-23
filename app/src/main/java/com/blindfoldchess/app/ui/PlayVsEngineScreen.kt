package com.blindfoldchess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blindfoldchess.app.engine.TextGameViewModel
import com.blindfoldchess.app.engine.TextGameViewModel.Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayVsEngineScreen(
    onBack: () -> Unit,
    vm: TextGameViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var moveInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.moves.size) {
        if (state.moves.isNotEmpty()) listState.animateScrollToItem(state.moves.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Play vs engine (text)") },
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
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StrengthControl(
                value = state.skillLevel,
                onChange = vm::setSkillLevel,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = vm::startNewGame) { Text("New game") }
                Spacer(Modifier.width(8.dp))
                StatusChip(state.status)
                state.lastEngineMove?.let {
                    Text(
                        "engine: $it",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Text(
                "Moves (UCI long algebraic — e.g. e2e4, e7e5, e1g1 for castle, e7e8q for promo)",
                style = MaterialTheme.typography.labelMedium,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                itemsIndexed(state.moves.chunked(2)) { idx, pair ->
                    val moveNumber = idx + 1
                    val white = pair[0]
                    val black = pair.getOrNull(1) ?: ""
                    Text(
                        text = "%2d. %-6s   %s".format(moveNumber, white, black),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = moveInput,
                    onValueChange = { moveInput = it },
                    label = { Text("Your move (UCI)") },
                    singleLine = true,
                    enabled = state.status == Status.WaitingForUser,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (moveInput.isNotBlank()) {
                            vm.submitUserMove(moveInput)
                            moveInput = ""
                        }
                    }),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        vm.submitUserMove(moveInput)
                        moveInput = ""
                    },
                    enabled = state.status == Status.WaitingForUser && moveInput.isNotBlank(),
                ) { Text("Submit") }
            }
        }
    }
}

@Composable
private fun StrengthControl(value: Int, onChange: (Int) -> Unit) {
    val label = when (value) {
        in 0..4 -> "beginner"
        in 5..10 -> "club"
        in 11..15 -> "strong"
        else -> "master"
    }
    Column {
        Text(
            "Skill Level: $value ($label)",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..20f,
            steps = 19,
        )
    }
}

@Composable
private fun StatusChip(status: Status) {
    val text = when (status) {
        Status.Idle -> "idle"
        Status.Starting -> "starting..."
        Status.WaitingForUser -> "your move"
        Status.Thinking -> "thinking..."
        Status.GameOver -> "game over"
        Status.Error -> "error"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status == Status.Thinking || status == Status.Starting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
