package com.example.grandfatherclock.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.grandfatherclock.data.SessionRecord
import com.example.grandfatherclock.data.SessionStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    sessionStore: SessionStore,
    onBack: () -> Unit,
) {
    var sessions by remember { mutableStateOf(sessionStore.loadAll()) }
    val bpmGroups = sessions.groupBy { it.bpmClass }.toSortedMap()
    val tabs = bpmGroups.keys.toList()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Clamp tab index if tabs change
    val clampedTab = if (tabs.isEmpty()) 0 else selectedTab.coerceIn(0, tabs.lastIndex)
    if (clampedTab != selectedTab) selectedTab = clampedTab

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (tabs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No sessions recorded yet",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
            } else {
                PrimaryTabRow(
                    selectedTabIndex = clampedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    tabs.forEachIndexed { index, bpm ->
                        Tab(
                            selected = clampedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text("$bpm BPM") },
                        )
                    }
                }

                val currentBpm = tabs[clampedTab]
                val filtered = bpmGroups[currentBpm].orEmpty()
                    .sortedByDescending { it.startTimeMillis }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                ) {
                    items(filtered, key = { it.id }) { record ->
                        SwipeToDeleteItem(
                            record = record,
                            onDelete = {
                                sessionStore.delete(record.id)
                                sessions = sessionStore.loadAll()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeToDeleteItem(
    record: SessionRecord,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 120.dp.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var dismissed by remember { mutableStateOf(false) }

    if (dismissed) return

    val bgColor by animateColorAsState(
        targetValue = if (offsetX > thresholdPx / 2) Color(0xFFD32F2F) else Color(0xFF8B0000),
        animationSpec = tween(150),
        label = "swipeBg",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        // Delete background
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(bgColor, RoundedCornerShape(12.dp))
                .padding(start = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
        }

        // Foreground card
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                )
                .pointerInput(record.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX > thresholdPx) {
                                dismissed = true
                                onDelete()
                            } else {
                                offsetX = 0f
                            }
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceAtLeast(0f)
                        },
                    )
                }
                .padding(16.dp),
        ) {
            SessionRow(record)
        }
    }
}

@Composable
private fun SessionRow(record: SessionRecord) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US) }

    val durationMin = (record.durationSeconds / 60).toInt()
    val durationSec = (record.durationSeconds % 60).toInt()
    val durationText = if (durationMin > 0) "${durationMin}m ${durationSec}s" else "${durationSec}s"

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = dateFormat.format(Date(record.startTimeMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Text(
                text = durationText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        val idealPeriodMicros = 2.0 * 60_000_000.0 / record.bpmClass
        val secPerWeek = (idealPeriodMicros - record.periodMicros) / idealPeriodMicros * 604_800.0
        val uncSecPerWeek = record.uncertaintyMicros / idealPeriodMicros * 604_800.0
        val sign = if (secPerWeek >= 0) "+" else ""
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "${sign}${String.format(Locale.US, "%.1f", secPerWeek)} s/week",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "\u00B1 ${String.format(Locale.US, "%.1f", uncSecPerWeek)} s/week",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
