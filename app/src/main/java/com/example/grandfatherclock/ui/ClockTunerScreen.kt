package com.example.grandfatherclock.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.grandfatherclock.MainViewModel
import com.example.grandfatherclock.ui.theme.IdleGray
import com.example.grandfatherclock.ui.theme.Orange
import com.example.grandfatherclock.ui.theme.SyncGreen
import com.example.grandfatherclock.ui.theme.Teal
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ClockTunerScreen(
    viewModel: MainViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Flashing circle
            FlashingCircle(
                lastBeatIsTick = state.lastBeatIsTick,
                flashTrigger = state.flashTrigger,
                synced = state.synced,
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Period display
            if (state.tickCount >= 2) {
                val formatted = NumberFormat.getNumberInstance(Locale.US).apply {
                    maximumFractionDigits = 1
                    minimumFractionDigits = 1
                }.format(state.periodMicros)

                Text(
                    text = "$formatted \u00B5s",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (state.uncertaintyMicros > 0) {
                    val uncFormatted = NumberFormat.getNumberInstance(Locale.US).apply {
                        maximumFractionDigits = 1
                        minimumFractionDigits = 1
                    }.format(state.uncertaintyMicros)

                    val uncColor = if (state.synced) SyncGreen
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)

                    Text(
                        text = "\u03C3 $uncFormatted \u00B5s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = uncColor,
                    )
                }

                if (state.synced) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SYNCED",
                        style = MaterialTheme.typography.titleMedium,
                        color = SyncGreen,
                    )
                }
            } else if (state.running) {
                Text(
                    text = "Listening\u2026",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Stats
            if (state.running) {
                Text(
                    text = "Ticks: ${state.tickCount}   Beats: ${state.beatCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )

                Spacer(modifier = Modifier.height(4.dp))

                val elapsedFormatted = String.format(Locale.US, "%.1f", state.elapsedSeconds)
                Text(
                    text = "Elapsed: ${elapsedFormatted}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // WAV file saved indicator
            if (!state.running && state.wavPath != null) {
                Text(
                    text = "WAV saved: ${state.wavPath}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Start / Stop button
            if (!hasPermission) {
                Button(onClick = onRequestPermission) {
                    Text("Grant Microphone Permission")
                }
            } else {
                Button(
                    onClick = {
                        if (state.running) viewModel.stop() else viewModel.start()
                    },
                ) {
                    Text(if (state.running) "Stop" else "Start")
                }
            }
        }
    }
}

@Composable
private fun FlashingCircle(
    lastBeatIsTick: Boolean?,
    flashTrigger: Int,
    synced: Boolean,
) {
    var flashColor by remember { mutableStateOf(Color.Transparent) }

    LaunchedEffect(flashTrigger) {
        if (flashTrigger > 0 && lastBeatIsTick != null) {
            flashColor = if (lastBeatIsTick) Teal else Orange
            delay(150)
            flashColor = Color.Transparent
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = flashColor,
        animationSpec = tween(durationMillis = 100),
        label = "circleColor",
    )

    val borderModifier = if (synced) {
        Modifier.border(3.dp, SyncGreen, CircleShape)
    } else {
        Modifier.border(1.dp, IdleGray.copy(alpha = 0.3f), CircleShape)
    }

    Box(
        modifier = Modifier
            .size(120.dp)
            .then(borderModifier)
            .clip(CircleShape)
            .background(animatedColor),
    )
}
