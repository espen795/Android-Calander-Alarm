package io.github.calendaralarm.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.sync.SyncRepository
import io.github.calendaralarm.ui.theme.CalGreen
import io.github.calendaralarm.ui.theme.CalGrey
import io.github.calendaralarm.ui.theme.CalOrange
import io.github.calendaralarm.ui.theme.CalPurple
import io.github.calendaralarm.ui.theme.CalRed

private const val TAG_UI = "RulesEditor"

private val TIME_REGEX = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
private val ID_REGEX = Regex("^[a-z0-9_-]{1,32}$")

/**
 * Editor for the server-side alarm rules (dynamic config).
 *
 * Pull model: the server is the source of truth. The user can edit
 * labels, wake times and keywords per rule here. Changes are pushed
 * to the server via POST /api/config and validated server-side.
 */
@Composable
fun ServerRulesEditor() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rules by remember { mutableStateOf<List<SyncRepository.AlarmRule>>(emptyList()) }
    var defaultRuleId by remember { mutableStateOf("") }  // Empty = server uses first rule
    var weekendRuleId by remember { mutableStateOf("") }  // Empty = no alarm on weekends
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }

    // Load config from server on mount
    LaunchedEffect(Unit) {
        AppLogger.d(TAG_UI, "RulesEditor: loading config from server")
        val syncRepo = SyncRepository(context)
        val result = syncRepo.fetchConfig()
        syncRepo.close()
        isLoading = false
        if (result.success && result.config != null) {
            rules = result.config.rules
            defaultRuleId = result.config.defaultRuleId
            weekendRuleId = result.config.weekendRuleId
            AppLogger.i(TAG_UI, "RulesEditor: loaded ${rules.size} rules from server")
        } else {
            statusMsg = "❌ Could not fetch config: ${result.error ?: "unknown"}"
            AppLogger.e(TAG_UI, "RulesEditor: load failed — ${result.error}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Fetching config from the server…", style = MaterialTheme.typography.bodyMedium, color = CalGrey)
            }
            return
        }

        if (rules.isEmpty()) {
            Text("No rules loaded", style = MaterialTheme.typography.bodyMedium, color = CalGrey)
            Button(onClick = {
                isLoading = true
                scope.launch {
                    val syncRepo = SyncRepository(context)
                    val result = syncRepo.fetchConfig()
                    syncRepo.close()
                    isLoading = false
                    if (result.success && result.config != null) {
                        rules = result.config.rules
                        defaultRuleId = result.config.defaultRuleId
                        weekendRuleId = result.config.weekendRuleId
                    }
                }
            }) { Text("Fetch again") }
            return
        }

        // Status line: weekend info (compact, not redundant with toggle)
        if (weekendRuleId.isEmpty()) {
            Text(
                "😴 No alarm on weekends — set a rule as 'Weekend' if you do want to be woken up",
                style = MaterialTheme.typography.bodySmall,
                color = CalGrey
            )
        } else {
            Text(
                "⏰ Weekend alarm: ${rules.find { it.id == weekendRuleId }?.name ?: weekendRuleId}",
                style = MaterialTheme.typography.bodySmall,
                color = CalOrange
            )
        }

        Divider()

        rules.forEachIndexed { index, rule ->
            RuleCard(
                rule = rule,
                isDefault = rule.id == defaultRuleId,
                isWeekend = rule.id == weekendRuleId,
                hasWeekendRule = weekendRuleId.isNotEmpty(),
                onChange = { updated ->
                    val newList = rules.toMutableList()
                    newList[index] = updated
                    rules = newList
                    dirty = true
                },
                onDelete = {
                    if (rules.size <= 1) {
                        Toast.makeText(context, "At least 1 rule required", Toast.LENGTH_SHORT).show()
                        return@RuleCard
                    }
                    // If this is the weekend rule, reset weekendRuleId to empty (no alarm)
                    if (rule.id == weekendRuleId) {
                        weekendRuleId = ""
                    }
                    // If this is the default rule, reset to empty (server uses first rule as fallback)
                    if (rule.id == defaultRuleId) {
                        defaultRuleId = ""
                    }
                    AppLogger.i(TAG_UI, "User deleted rule: ${rule.id}")
                    rules = rules.filter { it.id != rule.id }
                    dirty = true
                },
                onSetDefault = {
                    defaultRuleId = rule.id
                    dirty = true
                    AppLogger.i(TAG_UI, "User set default rule: ${rule.id}")
                },
                onSetWeekend = {
                    weekendRuleId = if (weekendRuleId == rule.id) "" else rule.id
                    dirty = true
                    AppLogger.i(TAG_UI, "User set weekend rule: ${if (weekendRuleId.isEmpty()) "none" else rule.id}")
                }
            )
        }

        // Add new rule
        OutlinedButton(
            onClick = {
                val newId = "rule${rules.size + 1}"
                rules = rules + SyncRepository.AlarmRule(
                    id = newId, name = "New rule", time = "08:00", keywords = emptyList()
                )
                dirty = true
                AppLogger.i(TAG_UI, "User added new rule: $newId")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add rule")
        }

        // Status message
        if (statusMsg != null) {
            Text(
                statusMsg!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusMsg!!.startsWith("✅")) CalGreen else CalRed
            )
        }

        // Save button
        Button(
            onClick = {
                AppLogger.i(TAG_UI, "User saving ${rules.size} rules to server")
                isSaving = true
                statusMsg = null
                scope.launch {
                    // Client-side validation before sending
                    val invalidTime = rules.find { !TIME_REGEX.matches(it.time) }
                    if (invalidTime != null) {
                        statusMsg = "❌ Invalid time for '${invalidTime.name}' (use HH:mm)"
                        isSaving = false
                        return@launch
                    }
                    val ids = rules.map { it.id.lowercase() }
                    if (ids.distinct().size != ids.size) {
                        statusMsg = "❌ Duplicate rule ids — ids must be unique"
                        isSaving = false
                        return@launch
                    }
                    val badId = rules.find { !ID_REGEX.matches(it.id.lowercase()) }
                    if (badId != null) {
                        statusMsg = "❌ Invalid id '${badId.id}' (only a-z, 0-9, _ , -)"
                        isSaving = false
                        return@launch
                    }
                    // Default rule may be EMPTY (server uses first rule as fallback)
                    if (defaultRuleId.isNotEmpty() && defaultRuleId !in ids) {
                        statusMsg = "❌ Default rule does not exist"
                        isSaving = false
                        return@launch
                    }
                    // Weekend rule may be EMPTY (no alarm on weekends) or must exist
                    if (weekendRuleId.isNotEmpty() && weekendRuleId !in ids) {
                        statusMsg = "❌ Weekend rule does not exist"
                        isSaving = false
                        return@launch
                    }

                    val config = SyncRepository.AlarmConfig(
                        rules = rules.map { it.copy(id = it.id.lowercase(), keywords = it.keywords.map { k -> k.lowercase().trim() }.filter { it.isNotEmpty() }) },
                        defaultRuleId = defaultRuleId,
                        weekendRuleId = weekendRuleId
                    )
                    val syncRepo = SyncRepository(context)
                    val result = syncRepo.saveConfig(config)
                    syncRepo.close()
                    isSaving = false
                    if (result.success) {
                        dirty = false
                        statusMsg = "✅ Saved on the server (${rules.size} rules)"
                        Toast.makeText(context, "Wake times saved on the server", Toast.LENGTH_SHORT).show()
                        AppLogger.i(TAG_UI, "Rules saved to server successfully")
                    } else {
                        statusMsg = "❌ ${result.error ?: "Save failed"}"
                        AppLogger.e(TAG_UI, "Rules save failed: ${result.error}")
                        Toast.makeText(context, "Save failed", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !isSaving && dirty,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Saving…")
            } else {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (dirty) "Save on the server" else "Saved")
            }
        }
    }
}

@Composable
fun RuleCard(
    rule: SyncRepository.AlarmRule,
    isDefault: Boolean,
    isWeekend: Boolean,
    hasWeekendRule: Boolean,  // Whether a weekend rule is set at all
    onChange: (SyncRepository.AlarmRule) -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onSetWeekend: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row: name + time + expand
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    Text(rule.time, style = MaterialTheme.typography.bodyMedium, color = CalPurple)
                    Row {
                        if (isDefault) {
                            Text("default", style = MaterialTheme.typography.bodySmall, color = CalGreen)
                        }
                        if (isWeekend) {
                            if (isDefault) Spacer(modifier = Modifier.width(8.dp))
                            Text("weekend", style = MaterialTheme.typography.bodySmall, color = CalOrange)
                        }
                    }
                    // Show hint when there is no weekend rule
                    if (!hasWeekendRule) {
                        Text("No alarm on weekends", style = MaterialTheme.typography.bodySmall, color = CalGrey)
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Edit")
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { onChange(rule.copy(name = it)) },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = rule.time,
                    onValueChange = { onChange(rule.copy(time = it)) },
                    label = { Text("Wake time (HH:mm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !TIME_REGEX.matches(rule.time)
                )

                OutlinedTextField(
                    value = rule.keywords.joinToString(", "),
                    onValueChange = { onChange(rule.copy(keywords = it.split(",").map { k -> k.trim() }.filter { k -> k.isNotEmpty() })) },
                    label = { Text("Keywords (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("e.g. office, work") }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isDefault) {
                        OutlinedButton(onClick = onSetDefault, modifier = Modifier.weight(1f)) {
                            Text("Default")
                        }
                    }
                    // Weekend toggle — on/off (empty = no alarm on weekends)
                    OutlinedButton(
                        onClick = onSetWeekend,
                        modifier = Modifier.weight(1f),
                        colors = if (isWeekend)
                            ButtonDefaults.outlinedButtonColors(containerColor = CalOrange.copy(alpha = 0.1f))
                        else
                            ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text(if (isWeekend) "Weekend ✓" else "Weekend")
                    }
                    OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = CalRed)) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}
