package io.github.calendaralarm.ui.screens

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.calendaralarm.MainActivity
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.TimerAlarmHelper
import io.github.calendaralarm.receiver.TimerActionReceiver
import io.github.calendaralarm.ui.TimerRingingActivity
import io.github.calendaralarm.ui.theme.CalGreen
import io.github.calendaralarm.ui.theme.CalGrey
import io.github.calendaralarm.ui.theme.CalOrange
import io.github.calendaralarm.ui.theme.CalPurple
import io.github.calendaralarm.ui.theme.CalRed
import io.github.calendaralarm.ui.viewmodels.TimerViewModel
import io.github.calendaralarm.service.TimerForegroundService
import java.util.Locale
import kotlin.math.abs

private const val TAG_UI = "TimerUI"

/**
 * Countdown timer screen — v2.5.3 with larger time display and ongoing notification.
 *
 * Features:
 * - Large time display (no progress circle) — 64-72sp
 * - Horizontally scrollable preset buttons (5/10/15/25/30/45/60 min)
 * - iOS-style wheel picker (hours/minutes/seconds) with snap scrolling
 * - Start / Pause / Reset / Stop controls
 * - Real alarm sound (looping MediaPlayer) + continuous vibration when timer completes
 * - Ongoing notification while timer is running (with Stop action)
 * - Full-screen TimerRingingActivity when timer finishes
 * - State survives tab switches (ViewModel scoped to NavBackStackEntry)
 * - Uses SystemClock.elapsedRealtime() via ViewModel for monotonic time
 * - Fully responsive layout (works on all screen sizes)
 *
 * v2.5.6 changes (Timer survives app kill):
 * - ViewModel sync with AlarmManager via TimerScheduler
 * - State persistence via TimerStateStore (SharedPreferences)
 * - Restore indicator when reloaded after app restart
 * - TimerForegroundService for Samsung OOM killer protection
 * - TimerFireReceiver for alarm handling when the app is dead
 *
 * v2.5.3 changes:
 * - Story 2: Preset chips in horizontally scrollable Row (was FlowRow)
 * - Story 3: Removed progress circle, time text larger (64-72sp)
 * - Story 4: Ongoing notification while timer running (with Stop action)
 * - Story 5: Full-screen TimerRingingActivity when timer finishes
 *
 * v2.5.2 changes:
 * - Fix 1: Replaced +/- buttons with scrollable wheel pickers (iOS-style)
 * - Fix 2: Timer now rings like a real alarm (looping sound + continuous vibration)
 *          via TimerAlarmHelper. Sound stops on Stop/Reset or when leaving screen.
 */
@Composable
fun TimerScreen(
    viewModel: TimerViewModel = run {
        val context = LocalContext.current
        viewModel(
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TimerViewModel(context) as T
                }
            }
        )
    }
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // v2.5.2 Fix 2 + v2.5.3: TimerAlarmHelper for real alarm sound + vibration
    // v2.5.3: Use shared instance so TimerRingingActivity can stop the alarm
    val alarmHelper = remember { TimerAlarmHelper.getInstance(context) }

    // v2.5.6 Story 6: Start/stop foreground service for Samsung OOM killer protection
    LaunchedEffect(uiState.isRunning) {
        if (uiState.isRunning) {
            TimerForegroundService.start(context, uiState.remainingMs)
        } else {
            TimerForegroundService.stop(context)
        }
    }

    // v2.5.3 Story 4: Ongoing notification while timer is running
    LaunchedEffect(uiState.isRunning) {
        if (uiState.isRunning) {
            showTimerNotification(context, uiState.remainingMs)
        } else {
            cancelTimerNotification(context)
        }
    }

    // v2.5.6 Story 7: Refresh state on resume (e.g. after returning from another app)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // v2.5.3 Story 5: Launch full-screen activity when timer finishes
    LaunchedEffect(uiState.isFinished) {
        if (uiState.isFinished) {
            val intent = Intent(context, TimerRingingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                context.startActivity(intent)
                AppLogger.i(TAG_UI, "Launched TimerRingingActivity")
            } catch (e: Exception) {
                AppLogger.e(TAG_UI, "Failed to launch TimerRingingActivity: ${e.message}")
            }
        }
    }

    // Wire up the "timer finished" callback (alarm sound + vibration + toast)
    DisposableEffect(viewModel, alarmHelper) {
        viewModel.onTimerFinished = {
            alarmHelper.startAlarm()
            AppLogger.i(TAG_UI, "Timer completed: ${TimerViewModel.formatDuration(uiState.totalMs)}")
            Toast.makeText(context, "⏰ Timer expired! Tap to stop", Toast.LENGTH_LONG).show()
        }
        onDispose {
            viewModel.onTimerFinished = null
            // Stop alarm when leaving the screen
            alarmHelper.stopAlarm()
            alarmHelper.release()
            // v2.5.3: Cancel notification when leaving screen
            cancelTimerNotification(context)
        }
    }

    // Stop alarm when timer is no longer finished (user pressed stop/reset)
    LaunchedEffect(uiState.isFinished) {
        if (!uiState.isFinished) {
            alarmHelper.stopAlarm()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // v2.5.6 Story 7: Restore indicator — show banner when the timer was restored after a restart
        if (uiState.isRestored) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CalPurple.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = CalPurple
                    )
                    Text(
                        text = if (uiState.isRunning) "Timer restored — still running" else "Timer restored — paused",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CalPurple
                    )
                }
            }
        }

        // ── Timer display (v2.5.3: no circle, larger time text) ─────
        TimerDisplay(
            remainingMs = uiState.remainingMs,
            isRunning = uiState.isRunning,
            isFinished = uiState.isFinished,
            screenWidthDp = screenWidthDp
        )

        // ── Preset buttons (v2.5.4 Story 3: "Snelkiezer" titel verwijderd) ──
        val presets = listOf(
            5 to "5 min",
            10 to "10 min",
            15 to "15 min",
            25 to "25 min",
            30 to "30 min",
            45 to "45 min",
            60 to "1 hr"
        )
        // v2.5.3: Horizontally scrollable row (1 line, swipe left/right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { (minutes, label) ->
                FilterChip(
                    selected = !uiState.isRunning && uiState.totalMs == minutes * 60 * 1000L,
                    onClick = {
                        if (!uiState.isRunning) {
                            val ms = minutes * 60 * 1000L
                            viewModel.setDuration(ms)
                            AppLogger.i(TAG_UI, "Preset selected: $label")
                        }
                    },
                    label = { Text(label) }
                )
            }
        }

        // ── Time picker (only when not running) ─────────────────────
        if (!uiState.isRunning) {
            TimerTimePicker(
                hours = uiState.customHours,
                minutes = uiState.customMinutes,
                seconds = uiState.customSeconds,
                onTimeChange = { h, m, s ->
                    viewModel.setCustomTime(h, m, s)
                }
            )
        }

        // ── Control buttons ─────────────────────────────────────────
        TimerControls(
            isRunning = uiState.isRunning,
            isPaused = uiState.isPaused,
            isFinished = uiState.isFinished,
            remainingMs = uiState.remainingMs,
            totalMs = uiState.totalMs,
            onStartPause = { viewModel.toggleStartPause() },
            onReset = { viewModel.reset() },
            onStop = { viewModel.stop() }
        )
    }
}

// ── Sub-components ──────────────────────────────────────────────────

/**
 * v2.5.3: Timer display without progress circle — just the large time text.
 * Time is larger (64-72sp depending on screen width) since we removed the circle.
 * Color coding preserved: green=finished, purple=running, orange=ready.
 */
@Composable
private fun TimerDisplay(
    remainingMs: Long,
    isRunning: Boolean,
    isFinished: Boolean,
    screenWidthDp: androidx.compose.ui.unit.Dp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 24.dp)
    ) {
        Text(
            text = TimerViewModel.formatDuration(remainingMs),
            fontSize = if (screenWidthDp.value < 360) 64.sp else 72.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                isFinished -> CalGreen
                isRunning -> CalPurple
                else -> CalOrange
            }
        )
        // v2.5.4 Story 3: Only show status when running or finished (no "ready")
        if (isRunning || isFinished) {
            Text(
                text = if (isRunning) "running…" else "done!",
                fontSize = 18.sp,
                color = CalGrey
            )
        }
    }
}

/**
 * iOS-style wheel time picker — hours, minutes, seconds.
 *
 * v2.5.2 Fix 1: Replaced +/- buttons with scrollable wheel pickers.
 * Each column is a LazyColumn with snap fling behavior, showing
 * a center-selected item that's larger and colored, with fading
 * items above and below.
 */
@Composable
private fun TimerTimePicker(
    hours: Int,
    minutes: Int,
    seconds: Int,
    onTimeChange: (hours: Int, minutes: Int, seconds: Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Custom time", style = MaterialTheme.typography.titleMedium)

            // Wheel pickers for hours, minutes, seconds
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hours wheel (0-23 with wrap-around)
                WheelPickerColumn(
                    value = hours,
                    label = "hr",
                    maxValue = 23,
                    color = CalPurple,
                    onValueChange = { onTimeChange(it, minutes, seconds) }
                )

                Text(":", style = MaterialTheme.typography.displaySmall, color = CalGrey)

                // Minutes wheel (0-59 with wrap-around)
                WheelPickerColumn(
                    value = minutes,
                    label = "min",
                    maxValue = 59,
                    color = CalOrange,
                    onValueChange = { onTimeChange(hours, it, seconds) }
                )

                Text(":", style = MaterialTheme.typography.displaySmall, color = CalGrey)

                // Seconds wheel (0-59 with wrap-around)
                WheelPickerColumn(
                    value = seconds,
                    label = "sec",
                    maxValue = 59,
                    color = CalGreen,
                    onValueChange = { onTimeChange(hours, minutes, it) }
                )
            }
        }
    }
}

/**
 * A single scrollable wheel picker column (iOS-style).
 *
 * Features:
 * - LazyColumn with snap fling behavior for smooth scrolling
 * - Wrap-around: scrolling past max goes to 0, scrolling below 0 goes to max
 * - Center item is highlighted (larger, colored, bold)
 * - Items above/below center fade out
 * - Selection indicator lines at top and bottom of center item
 *
 * @param value Current selected value
 * @param label Unit label (e.g. "hr", "min", "sec")
 * @param maxValue Maximum value (inclusive). Values wrap: 0..maxValue
 * @param color Highlight color for selected item
 * @param onValueChange Callback when selection changes
 */
@Composable
private fun WheelPickerColumn(
    value: Int,
    label: String,
    maxValue: Int,
    color: Color,
    onValueChange: (Int) -> Unit
) {
    val itemHeight = 40.dp        // v2.5.4 Story 4: was 48.dp
    val visibleItemCount = 3      // v2.5.4 Story 4: was 5 — show 1 above, center, 1 below
    val wheelHeight = itemHeight * visibleItemCount  // = 120.dp (was 240.dp)

    // We create a virtual infinite list by repeating values with an offset
    // so the user can scroll "forever" in either direction (wrap-around).
    // The actual range is 0..maxValue, and we modulo when reading.
    val rangeSize = maxValue + 1
    val repeatCount = 100 // Large enough to feel infinite
    val totalItems = rangeSize * repeatCount

    // Find the virtual index that corresponds to the current value.
    // We center it in the middle of the repeated range for balanced scrolling.
    val centerRepeat = repeatCount / 2
    val virtualCenterIndex = centerRepeat * rangeSize + value

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = virtualCenterIndex - visibleItemCount / 2
    )
    val flingBehavior = rememberSnapFlingBehavior(listState)
    val density = LocalDensity.current

    // Track the currently selected (center) item
    var selectedIndex by remember { mutableStateOf(virtualCenterIndex) }

    // Detect when scrolling settles and update the selected value
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            // Scrolling has settled — find the center item
            val layoutInfo = listState.layoutInfo
            val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2

            var closestIndex = selectedIndex
            var closestDistance = Int.MAX_VALUE

            for (item in layoutInfo.visibleItemsInfo) {
                val itemCenter = item.offset + item.size / 2
                val distance = abs(itemCenter - viewportCenter)
                if (distance < closestDistance) {
                    closestDistance = distance
                    closestIndex = item.index
                }
            }

            if (closestIndex != selectedIndex) {
                selectedIndex = closestIndex
                // Convert virtual index back to actual value (with wrap-around)
                val actualValue = ((closestIndex % rangeSize) + rangeSize) % rangeSize
                if (actualValue != value) {
                    onValueChange(actualValue)
                }
            }
        }
    }

    // v2.5.2 fix: When the external value changes (e.g. from preset buttons), always scroll to it.
    // Previously used `> rangeSize` threshold which missed small changes (e.g. 25→5 min = diff 20 < 60).
    // Now we always snap when the value differs from what's currently selected.
    LaunchedEffect(value) {
        val targetIndex = centerRepeat * rangeSize + value
        // Only scroll if the target differs from the currently selected virtual index
        // (avoids infinite loop when user scrolls and triggers onValueChange)
        val currentValue = ((selectedIndex % rangeSize) + rangeSize) % rangeSize
        if (currentValue != value) {
            listState.scrollToItem(targetIndex - visibleItemCount / 2)
            selectedIndex = targetIndex
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .height(wheelHeight)
                .width(72.dp)
        ) {
            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                itemsIndexed(
                    items = List(totalItems) { it },
                    key = { index, _ -> index }
                ) { index, virtualIndex ->
                    // Convert virtual index to actual value with wrap-around
                    val actualValue = ((virtualIndex % rangeSize) + rangeSize) % rangeSize
                    val isSelected = virtualIndex == selectedIndex

                    // Calculate distance from center for fade effect
                    val itemOffset = with(density) {
                        val layoutInfo = listState.layoutInfo
                        val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2
                        val item = layoutInfo.visibleItemsInfo.find { it.index == virtualIndex }
                        if (item != null) {
                            val itemCenter = item.offset + item.size / 2
                            abs(itemCenter - viewportCenter) / itemHeight.toPx()
                        } else {
                            if (isSelected) 0f else visibleItemCount.toFloat()
                        }
                    }

                    // v2.5.4 Story 4: Fade and scale for 3 visible items (1 above, center, 1 below)
                    val alpha = when {
                        isSelected -> 1f
                        itemOffset < 1f -> 0.5f   // direct above/below
                        else -> 0f                // off-screen
                    }
                    val fontSize = if (isSelected) 24.sp else 18.sp
                    val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal

                    Box(
                        modifier = Modifier
                            .height(itemHeight)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = String.format(Locale.getDefault(), "%02d", actualValue),
                            fontSize = fontSize,
                            fontWeight = fontWeight,
                            color = if (isSelected) color else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.alpha(alpha)
                        )
                    }
                }
            }

            // Selection indicator — top and bottom lines around the center item
            val centerOffset = wheelHeight / 2
            val halfItemHeight = itemHeight / 2

            // Top selection line
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = centerOffset - halfItemHeight)
                    .fillMaxWidth(0.8f)
                    .height(2.dp)
                    .background(color.copy(alpha = 0.6f))
            )
            // Bottom selection line
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(centerOffset - halfItemHeight))
                    .fillMaxWidth(0.8f)
                    .height(2.dp)
                    .background(color.copy(alpha = 0.6f))
            )

            // Center highlight background
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(itemHeight)
                    .padding(horizontal = 8.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(color.copy(alpha = 0.08f))
            )
        }

        // Label below the wheel
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CalGrey
        )
    }
}

/**
 * Timer control buttons: Start/Pause, Reset, Stop.
 */
@Composable
private fun TimerControls(
    isRunning: Boolean,
    isPaused: Boolean,
    isFinished: Boolean,
    remainingMs: Long,
    totalMs: Long,
    onStartPause: () -> Unit,
    onReset: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Start / Pause + Reset row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Start / Pause
            Button(
                onClick = onStartPause,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) CalOrange else CalPurple
                )
            ) {
                Icon(
                    if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        isRunning -> "Pause"
                        isPaused -> "Resume"
                        else -> "Start"
                    }
                )
            }

            // Reset
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset")
            }
        }

        // Stop button (when running, paused with progress, or finished/alarm ringing)
        if (isRunning || isFinished || (isPaused && remainingMs < totalMs)) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CalRed)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop")
            }
        }
    }
}

// ── v2.5.3 Story 4: Notification helpers ──────────────────────────
// v2.5.3 PM fix C-01: Gebruik centrale constante uit CalendarAlarmApp
private val TIMER_NOTIFICATION_ID get() = CalendarAlarmApp.NOTIFICATION_ID_TIMER

/**
 * v2.5.3 PM fix: Check notification permission (Android 13+ / Samsung S24).
 * Without permission, NotificationManagerCompat.notify() fails silently.
 */
private fun hasNotificationPermission(context: Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

/**
 * Show an ongoing notification while the timer is running.
 * Content: "⏱️ Timer: {remaining} left" with a "Stop" action.
 */
private fun showTimerNotification(context: Context, remainingMs: Long) {
    // v2.5.3 PM fix: Permission check — Android 13+ requires POST_NOTIFICATIONS
    if (!hasNotificationPermission(context)) {
        AppLogger.w(TAG_UI, "No POST_NOTIFICATIONS permission — skipping timer notification")
        return
    }
    try {
        val remainingText = TimerViewModel.formatDuration(remainingMs)

        // Tap → open MainActivity
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action → broadcast to TimerActionReceiver
        val stopIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, TimerActionReceiver::class.java).apply {
                action = TimerActionReceiver.ACTION_STOP_TIMER
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_TIMER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏱️ Timer")
            .setContentText("$remainingText left")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setAutoCancel(false)
            .build()

        NotificationManagerCompat.from(context).notify(TIMER_NOTIFICATION_ID, notification)
        AppLogger.i(TAG_UI, "Timer notification shown: $remainingText left")
    } catch (e: Exception) {
        AppLogger.e(TAG_UI, "Failed to show timer notification: ${e.message}")
    }
}

/**
 * Cancel the timer notification.
 */
private fun cancelTimerNotification(context: Context) {
    try {
        NotificationManagerCompat.from(context).cancel(TIMER_NOTIFICATION_ID)
        AppLogger.i(TAG_UI, "Timer notification cancelled")
    } catch (e: Exception) {
        AppLogger.e(TAG_UI, "Failed to cancel timer notification: ${e.message}")
    }
}
