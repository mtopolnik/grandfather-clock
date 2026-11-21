package com.example.grandfatherclock.ui

import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.grandfatherclock.MainViewModel
import com.example.grandfatherclock.audio.TickDetector
import com.example.grandfatherclock.data.SessionStore
import com.example.grandfatherclock.ui.theme.IdleGray
import com.example.grandfatherclock.ui.theme.Orange
import com.example.grandfatherclock.ui.theme.SyncGreen
import com.example.grandfatherclock.ui.theme.Teal
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
                val displayRotation = context.display.rotation ?: Surface.ROTATION_0
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
    onShowHistory: () -> Unit = {},
    hasSilenceAccess: Boolean = false,
    onRequestSilenceAccess: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val recordingMinutes by viewModel.recordingMinutes.collectAsState()
    val isUpsideDown = rememberIsUpsideDown()

    M3Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .rotate(if (isUpsideDown) 180f else 0f),
        ) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Flashing circle
            val bpm = if (state.periodMicros > 0) SessionStore.classifyBpm(state.periodMicros) else null
            FlashingCircle(
                lastBeatIsTick = state.lastBeatIsTick,
                flashTrigger = state.flashTrigger,
                synced = state.synced,
                bpm = bpm,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Period (shown once available); after stop it is the session's result.
            if (state.periodMicros > 0) {
                PeriodDisplay(
                    label = if (state.running) "Live" else "Final",
                    periodMicros = state.periodMicros,
                    uncertaintyMicros = state.uncertaintyMicros,
                    synced = state.synced,
                )
            }

            if (state.audioClockSuspect) {
                Spacer(modifier = Modifier.height(16.dp))
                AudioClockWarning(state.crystalBiasPpm)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Health: is the app hearing the clock? Shown from the first second,
            // so a bad setup is obvious long before the rate settles.
            if (state.running || state.beatCount > 0) {
                StatusLine(state)
            }

            if (state.periodMicros > 0 && state.imbalanceUncertaintyMicros > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                val ms = kotlin.math.abs(state.imbalanceMicros) / 1000
                val unc = state.imbalanceUncertaintyMicros / 1000
                Text(
                    text = "Beat imbalance: ${formatMillis(ms, unc)} \u00B1 ${formatMillis(unc, unc)} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }

            state.recognisedClock?.let { clock ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Recognised: ${clock.name} (${clock.escapeWheelTeeth}-tooth escape wheel)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }

            if (state.trace.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                TimingTrace(
                    trace = state.trace,
                    elapsedSeconds = state.elapsedSeconds,
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            }
            if (state.history.size >= 2) {
                Spacer(modifier = Modifier.height(12.dp))
                ProgressChart(
                    history = state.history,
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

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
            } else if (!hasSilenceAccess && !state.running) {
                // Silencing the ringer requires Do Not Disturb access
                Button(
                    onClick = onRequestSilenceAccess,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        "Allow Silencing the Phone",
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

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Recording stopped: $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Duration selector
            DurationSelector(
                minutes = recordingMinutes,
                onMinutesChange = { viewModel.setRecordingMinutes(it) },
            )

            if (state.running || state.elapsedSeconds > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                val totalSeconds = state.elapsedSeconds.toInt()
                val mm = totalSeconds / 60
                val ss = totalSeconds % 60
                val tenths = ((state.elapsedSeconds - totalSeconds) * 10).toInt()
                val elapsedFormatted = String.format(Locale.US, "%02d:%02d.%d", mm, ss, tenths)
                Text(
                    text = elapsedFormatted,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
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
        } // BoxWithConstraints

            // History button (top-left). It comes after the scrolling content so it
            // sits on top and receives taps.
            IconButton(
                onClick = onShowHistory,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 64.dp, start = 20.dp)
                    .size(56.dp)
                    .rotate(if (isUpsideDown) 180f else 0f),
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = "Session History",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        } // Box
    }
}

@Composable
private fun PeriodDisplay(
    label: String,
    periodMicros: Double,
    uncertaintyMicros: Double,
    synced: Boolean,
) {
    val bpm = SessionStore.classifyBpm(periodMicros)
    val idealPeriodMicros = 2.0 * 60_000_000.0 / bpm
    // positive = clock running fast (short period)
    val secPerWeek = (idealPeriodMicros - periodMicros) / idealPeriodMicros * 604_800.0
    val uncSecPerWeek = uncertaintyMicros / idealPeriodMicros * 604_800.0
    val sign = if (secPerWeek >= 0) "+" else ""
    val devFmt = String.format(Locale.US, "%.1f", secPerWeek)
    val uncFmt = String.format(Locale.US, "%.1f", uncSecPerWeek)

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = "${sign}${devFmt}\u2009s/week",
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )

    if (uncSecPerWeek > 0) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "± ${uncFmt} s/week",
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
    bpm: Int? = null,
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
            .size(100.dp)
            .then(borderModifier)
            .clip(CircleShape)
            .background(animatedColor),
        contentAlignment = Alignment.Center,
    ) {
        if (bpm != null) {
            Text(
                text = "$bpm",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun DurationSelector(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
) {
    val textColor = Color.White
    val arrowColor = SyncGreen
    val disabledAlpha = 0.25f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "\u25C0",
            fontSize = 24.sp,
            color = if (minutes > 1) arrowColor else arrowColor.copy(alpha = disabledAlpha),
            modifier = Modifier
                .clickable(enabled = minutes > 1) { onMinutesChange(minutes - 1) }
                .padding(12.dp),
        )

        Spacer(modifier = Modifier.width(4.dp))

        Box(
            modifier = Modifier
                .border(1.dp, textColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$minutes min",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "\u25B6",
            fontSize = 24.sp,
            color = if (minutes < 30) arrowColor else arrowColor.copy(alpha = disabledAlpha),
            modifier = Modifier
                .clickable(enabled = minutes < 30) { onMinutesChange(minutes + 1) }
                .padding(12.dp),
        )
    }
}

/** Seconds of listening after which a missing rhythm is reported as a problem. */
private const val LOCK_PATIENCE_SECONDS = 15.0
/** Below this share of recent beats heard, the clock counts as hard to hear. */
private const val MIN_HIT_RATE = 0.8
private val WarningAmber = Color(0xFFFFB300)

/**
 * Shown when the phone's audio sample rate is off by more than any phone clock
 * should be: the audio timing is unreliable, and so is the result.
 */
@Composable
private fun AudioClockWarning(offsetPpm: Double) {
    val ppm = abs(offsetPpm)
    val secPerWeek = ppm * 1e-6 * 604_800.0
    M3Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Phone audio clock unreliable",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = String.format(Locale.US,
                    "Its sample rate is off by %.0f ppm (%.0f s/week), far more than a phone's " +
                        "should be. The result may be wrong. Disconnect any headset or external " +
                        "microphone and try again.", ppm, secPerWeek),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One line telling the user whether the app is hearing the clock properly, with
 * a concrete suggestion when it isn't.
 */
@Composable
private fun StatusLine(state: MainViewModel.UiState) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    val (text, color) = when {
        state.running && state.listening && state.elapsedSeconds < LOCK_PATIENCE_SECONDS ->
            "Finding the beat…" to muted
        state.running && state.listening ->
            "Can’t find a steady beat. Move the phone closer to the clock or reduce noise." to
                MaterialTheme.colorScheme.error
        state.running && state.recentHitRate < MIN_HIT_RATE ->
            "Missing beats: heard ${(state.recentHitRate * 100).roundToInt()}% of the last 20. " +
                "The clock is hard to hear." to WarningAmber
        else -> "Beats heard: ${state.beatCount} of ${state.beatsExpected}" to muted
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ChartLabel(left: AnnotatedString, right: String) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = left, style = MaterialTheme.typography.labelSmall, color = muted)
        Text(text = right, style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

@Composable
private fun ChartLabel(left: String, right: String) = ChartLabel(AnnotatedString(left), right)

/**
 * Timegrapher-style trace: one dot per beat, placed by how early or late it came
 * relative to the fitted straight line. Two tidy bands (tick and tock) mean the
 * app is hearing the clock well; a scattered cloud means it isn't. A slow wave in
 * the bands is the clock's own escape-wheel pattern and is normal.
 */
@Composable
private fun TimingTrace(
    trace: List<TickDetector.TracePoint>,
    elapsedSeconds: Double,
    modifier: Modifier = Modifier,
) {
    val spread = trace.filter { it.inlier }.maxOfOrNull { abs(it.residualMillis) } ?: 10f
    val span = (spread * 1.2f).coerceIn(10f, 100f)
    val axis = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
    Column(modifier = modifier) {
        val legend = buildAnnotatedString {
            append("Beat timing: ")
            withStyle(SpanStyle(color = Teal)) { append("tick") }
            append(", ")
            withStyle(SpanStyle(color = Orange)) { append("tock") }
        }
        ChartLabel(legend, "±${span.roundToInt()} ms")
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val tMax = maxOf(elapsedSeconds.toFloat(), trace.last().timeSeconds, 10f)
            val mid = size.height / 2
            val r = 1.8.dp.toPx()
            drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
            for (p in trace) {
                val x = p.timeSeconds / tMax * size.width
                val y = mid - p.residualMillis.coerceIn(-span, span) / span * (mid - r)
                val c = when {
                    !p.inlier -> IdleGray
                    p.isTick -> Teal
                    else -> Orange
                }
                drawCircle(c, r, Offset(x, y))
            }
        }
    }
}

/**
 * The live rate over time with its uncertainty band, so the user can watch the
 * result settle. The vertical range follows the current uncertainty, and the
 * dashed line marks perfect timekeeping when it is in view.
 */
@Composable
private fun ProgressChart(
    history: List<MainViewModel.HistoryPoint>,
    modifier: Modifier = Modifier,
) {
    val last = history.last()
    val nominal = 2.0 * 60_000_000.0 / SessionStore.classifyBpm(last.periodMicros)
    fun rate(p: Double) = (nominal - p) / nominal * 604_800.0
    fun spread(u: Double) = u / nominal * 604_800.0
    val center = rate(last.periodMicros)
    val range = maxOf(3 * spread(last.uncertaintyMicros), 15.0)
    val lineColor = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    Column(modifier = modifier) {
        ChartLabel("Rate over time", "${signed(center - range)} … ${signed(center + range)} s/week")
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val tMax = maxOf(last.elapsedSeconds, 1f)
            fun x(t: Float) = t / tMax * size.width
            fun y(v: Double) = (size.height / 2 - (v - center) / range * size.height / 2)
                .toFloat().coerceIn(0f, size.height)
            val band = Path().apply {
                history.forEachIndexed { i, h ->
                    val px = x(h.elapsedSeconds)
                    val py = y(rate(h.periodMicros) + spread(h.uncertaintyMicros))
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                for (h in history.asReversed()) {
                    lineTo(x(h.elapsedSeconds), y(rate(h.periodMicros) - spread(h.uncertaintyMicros)))
                }
                close()
            }
            drawPath(band, lineColor.copy(alpha = 0.2f))
            if (abs(center) < range) {
                drawLine(
                    axis, Offset(0f, y(0.0)), Offset(size.width, y(0.0)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
            }
            // The line is drawn only where the estimate is inside the view; early
            // on, the band filling the whole height shows the uncertainty instead.
            val line = Path().apply {
                var drawing = false
                for (h in history) {
                    val v = rate(h.periodMicros)
                    if (abs(v - center) > range) { drawing = false; continue }
                    if (drawing) lineTo(x(h.elapsedSeconds), y(v)) else moveTo(x(h.elapsedSeconds), y(v))
                    drawing = true
                }
            }
            drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private fun signed(v: Double): String = String.format(Locale.US, "%+.0f", v)

/** Formats milliseconds with one decimal when the uncertainty is below a millisecond. */
private fun formatMillis(value: Double, uncertainty: Double): String =
    String.format(Locale.US, if (uncertainty < 1.0) "%.1f" else "%.0f", value)
