package com.example.grandfatherclock.ui

import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.grandfatherclock.MainViewModel
import com.example.grandfatherclock.ui.theme.IdleGray
import com.example.grandfatherclock.ui.theme.Orange
import com.example.grandfatherclock.ui.theme.SyncGreen
import com.example.grandfatherclock.ui.theme.Teal
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

/**
 * Returns true when the device is physically held upside-down (reverse portrait)
 * but the system display has not rotated to match (i.e. still showing ROTATION_0).
 * In that case the UI content should be rotated 180 degrees so the user can read it.
 */
@Composable
private fun rememberIsUpsideDown(): Boolean {
    val context = LocalContext.current
    var isUpsideDown by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val physicallyFlipped = orientation in 135..225
                // Only flip our content if the system hasn't already rotated the display
                @Suppress("DEPRECATION")
                val displayRotation = context.display?.rotation ?: Surface.ROTATION_0
                isUpsideDown = physicallyFlipped && displayRotation == Surface.ROTATION_0
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    return isUpsideDown
}

@Composable
fun ClockTunerScreen(
    viewModel: MainViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val isUpsideDown = rememberIsUpsideDown()

    M3Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .rotate(if (isUpsideDown) 180f else 0f)
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

            // Real-time period (always shown once available)
            if (state.periodMicros > 0) {
                PeriodDisplay(
                    label = "Real-time",
                    periodMicros = state.periodMicros,
                    uncertaintyMicros = state.uncertaintyMicros,
                    synced = state.synced,
                    isLarge = state.wavPeriodMicros <= 0,
                )
            } else if (state.running) {
                Text(
                    text = "Listening\u2026",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }

            // WAV-refined period (shown after stop, below the real-time value)
            if (state.wavPeriodMicros > 0) {
                Spacer(modifier = Modifier.height(24.dp))
                PeriodDisplay(
                    label = "Full recording",
                    periodMicros = state.wavPeriodMicros,
                    uncertaintyMicros = state.wavUncertaintyMicros,
                    synced = true,
                    isLarge = true,
                )
            } else if (state.analyzing) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Analyzing recording\u2026",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Stats
            if (state.running || state.beatCount > 0) {
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

            // Start / Stop button
            if (!hasPermission) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        "Grant Microphone Permission",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Button(
                    onClick = {
                        if (state.running) viewModel.stop() else viewModel.start()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        if (state.running) "Stop" else "Start",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (!state.running && state.wavPath != null) {
                val dir = state.wavPath!!.substringBeforeLast('/')
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Debug files saved at $dir",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun PeriodDisplay(
    label: String,
    periodMicros: Double,
    uncertaintyMicros: Double,
    synced: Boolean,
    isLarge: Boolean,
) {
    val numFmt = NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 0
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = "${numFmt.format(periodMicros)} \u00B5s",
        style = if (isLarge) MaterialTheme.typography.displayLarge
                else MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )

    if (uncertaintyMicros > 0) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "\u00B1 ${numFmt.format(uncertaintyMicros)} \u00B5s",
            style = MaterialTheme.typography.bodyLarge,
            color = if (synced) SyncGreen
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
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
