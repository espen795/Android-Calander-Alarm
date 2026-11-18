package io.github.calendaralarm.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.ui.theme.CalGreen
import io.github.calendaralarm.ui.theme.CalGrey
import io.github.calendaralarm.ui.theme.CalPurple
import io.github.calendaralarm.ui.theme.CalRed

private const val TAG_DIALOG = "AddAlarmDialog"

// Day-of-week labels (1=Mon .. 7=Sun)
private val DAY_LABELS = listOf(
    1 to "Mo",
    2 to "Tu",
    3 to "We",
    4 to "Th",
    5 to "Fr",
    6 to "Sa",
    7 to "Su"
)

/**
 * Dialog for adding or editing an alarm.
 *
 * v2.5.0 changes:
 * - Added [editAlarm] parameter: when set, dialog opens in edit mode
 * - Per-alarm snooze override (null = use global, 0 = no snooze, 5/10/15/30 = custom)
 * - Title changes to "Edit alarm" in edit mode
 *
 * Contains:
 * - TimePicker (HH:mm)
 * - TextField for label (max 100 chars)
 * - Day-of-week selector chips (Mon-Sun, multi-select, optional)
 * - Snooze override selector (v2.5.0)
 * - Save/Cancel buttons with validation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAlarmDialog(
    onDismiss: () -> Unit,
    onAlarmAdded: () -> Unit,
    editAlarm: AlarmStore.OwnedAlarm? = null  // v2.5.0: null = add mode, set = edit mode
) {
    val context = LocalContext.current
    val repo = remember { AlarmRepository(context) }
    val scope = rememberCoroutineScope()
    val settingsRepo = CalendarAlarmApp.settingsRepository

    val isEditMode = editAlarm != null
    val globalSnoozeMinutes = remember { settingsRepo.getSnoozeMinutes() }

    // Parse initial time from edit alarm or use defaults
    val initialHour = editAlarm?.timeLabel?.split(":")?.get(0)?.toIntOrNull() ?: 7
    val initialMinute = editAlarm?.timeLabel?.split(":")?.get(1)?.toIntOrNull() ?: 0

    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }
    var label by remember { mutableStateOf(editAlarm?.label ?: "") }
    var selectedDays by remember { mutableStateOf<Set<Int>>(editAlarm?.repeatDays?.toSet() ?: emptySet()) }
    var showTimePicker by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // v2.5.0: Snooze override — null = use global setting
    var snoozeOverride by remember { mutableStateOf<Int?>(editAlarm?.snoozeMinutesOverride) }

    val timeLabel = String.format("%02d:%02d", hour, minute)
    val isValid = label.isNotBlank() && label.length <= 100

    // TimePicker dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Pick a time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    hour = timePickerState.hour
                    minute = timePickerState.minute
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title — changes based on mode
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isEditMode) Icons.Default.Edit else Icons.Default.AlarmAdd,
                        contentDescription = null,
                        tint = CalPurple
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isEditMode) "Edit alarm" else "New alarm",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                // Time picker button
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Label input
                OutlinedTextField(
                    value = label,
                    onValueChange = { if (it.length <= 100) label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g. 'Alarm — Office'") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("${label.length}/100") },
                    isError = label.isEmpty()
                )

                // Day-of-week selector
                Text("Repeat on", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (selectedDays.isEmpty()) {
                        "One-time (today or tomorrow)"
                    } else {
                        "Every: ${selectedDays.sorted().joinToString(", ") { day -> DAY_LABELS.find { it.first == day }?.second ?: "?" }}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey
                )

                // Day chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DAY_LABELS.forEach { (dayNum, dayLabel) ->
                        FilterChip(
                            selected = dayNum in selectedDays,
                            onClick = {
                                selectedDays = if (dayNum in selectedDays) {
                                    selectedDays - dayNum
                                } else {
                                    selectedDays + dayNum
                                }
                            },
                            label = { Text(dayLabel) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // v2.5.0: Snooze override selector
                Text("Snooze for this alarm", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Default: ${if (globalSnoozeMinutes == 0) "Off" else "$globalSnoozeMinutes min"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey
                )

                val snoozeOptions = listOf(
                    null to "Default",
                    0 to "Off",
                    5 to "5 min",
                    10 to "10 min",
                    15 to "15 min",
                    30 to "30 min"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    snoozeOptions.forEach { (minutes, labelText) ->
                        FilterChip(
                            selected = snoozeOverride == minutes,
                            onClick = { snoozeOverride = minutes },
                            label = { Text(labelText, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            AppLogger.i(TAG_DIALOG, "User ${if (isEditMode) "editing" else "adding"} alarm: $timeLabel label='$label' days=$selectedDays snooze=$snoozeOverride")
                            // v2.5.8: User actions are ALWAYS allowed, even during Alarm Freeze
                            // Freeze only blocks AUTOMATED sync (AlarmSyncWorker)
                            isSaving = true
                            scope.launch {
                                if (isEditMode && editAlarm != null) {
                                    // v2.6.13 L5: edit = in-place update with the SAME ID (editAlarm.copy → schedule).
                                    // Result: editing = max 1 notification ("changed"), "save without
                                    // change" = 0 (state-key dedup recognizes identical values).
                                    // (Old path: cancel(old) → REMOVED + scheduleOneShot new ID → CHANGED = 2 notifications.)
                                    val trigger = repo.computeNextTrigger(timeLabel, selectedDays.sorted(), null)
                                    val updated = if (trigger != null) {
                                        val zone = java.time.ZoneId.systemDefault()
                                        val dateStr = java.time.LocalDate.ofInstant(trigger, zone).toString()
                                        editAlarm.copy(
                                            triggerAtMillis = trigger.toEpochMilli(),
                                            timeLabel = timeLabel,
                                            label = label.trim(),
                                            date = dateStr,
                                            repeatDays = selectedDays.sorted(),
                                            snoozeMinutesOverride = snoozeOverride,
                                            enabled = true,
                                            isSnooze = false
                                        )
                                    } else {
                                        editAlarm.copy(
                                            timeLabel = timeLabel,
                                            label = label.trim(),
                                            repeatDays = selectedDays.sorted(),
                                            snoozeMinutesOverride = snoozeOverride,
                                            enabled = true
                                        )
                                    }
                                    // HN-02 principle: on failure (e.g. no permission) leave the OLD alarm
                                    // untouched — no force-cancel, then we keep the working alarm.
                                    val result = repo.schedule(updated)
                                    isSaving = false
                                    if (result != null) {
                                        AppLogger.i(TAG_DIALOG, "Alarm updated in-place: id=${result.id} at $timeLabel")
                                        Toast.makeText(context, "Alarm updated: $timeLabel", Toast.LENGTH_SHORT).show()
                                        onAlarmAdded()
                                    } else {
                                        AppLogger.e(TAG_DIALOG, "Failed to update alarm")
                                        Toast.makeText(
                                            context,
                                            "Could not update alarm — check permission",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } else {
                                    // Add mode
                                    val result = repo.scheduleOneShot(
                                        timeLabel = timeLabel,
                                        label = label.trim(),
                                        days = selectedDays.sorted(),
                                        type = "manual"
                                    )
                                    // Apply snooze override to the new alarm
                                    if (result != null && snoozeOverride != null) {
                                        val updated = result.copy(snoozeMinutesOverride = snoozeOverride)
                                        repo.store.save(updated)
                                    }
                                    isSaving = false
                                    if (result != null) {
                                        AppLogger.i(TAG_DIALOG, "Manual alarm added: id=${result.id} at $timeLabel")
                                        Toast.makeText(context, "Alarm added: $timeLabel", Toast.LENGTH_SHORT).show()
                                        onAlarmAdded()
                                    } else {
                                        AppLogger.e(TAG_DIALOG, "Failed to add manual alarm")
                                        Toast.makeText(
                                            context,
                                            "Could not add alarm — check permission",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        enabled = isValid && !isSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isEditMode) "Update" else "Save")
                    }
                }
            }
        }
    }
}
