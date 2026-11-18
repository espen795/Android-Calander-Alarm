package io.github.calendaralarm.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.compose.runtime.livedata.observeAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.calendaralarm.BuildConfig
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.*
import io.github.calendaralarm.data.SyncStatusCache
import io.github.calendaralarm.data.TimerAlarmHelper
import io.github.calendaralarm.ui.theme.CalGreen
import io.github.calendaralarm.ui.theme.CalGrey
import io.github.calendaralarm.ui.theme.CalOrange
import io.github.calendaralarm.ui.theme.CalPurple
import io.github.calendaralarm.ui.theme.CalRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG_UI = "UI"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestExactAlarmPermission: () -> Unit = {},
    onRequestBatteryExemption: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Status", "Alarms", "Timer", "Settings")
    val icons = listOf(Icons.Default.Home, Icons.Default.Alarm, Icons.Default.Timer, Icons.Default.Settings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⏰ Calendar Alarm") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> StatusScreen(onRequestExactAlarmPermission, onRequestBatteryExemption)
                1 -> AlarmListScreen()
                2 -> TimerScreen()
                3 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun StatusScreen(
    onRequestExactAlarmPermission: () -> Unit = {},
    onRequestBatteryExemption: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsRepo = CalendarAlarmApp.settingsRepository
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = AlarmSettings())
    val repo = remember { AlarmRepository(context) }
    val scope = rememberCoroutineScope()

    // Real state — no hard-coded values (H-11 fix)
    var alarms by remember { mutableStateOf<List<AlarmStore.OwnedAlarm>>(emptyList()) }
    var hasExactAlarmPermission by remember { mutableStateOf(repo.canScheduleExactAlarms()) }
    var secureStorageOk by remember { mutableStateOf(settingsRepo.secureStorageAvailable) }

    // Refresh state periodically (F4 fix)
    LaunchedEffect(Unit) {
        while(true) {
            alarms = repo.store.getEnabled()
            hasExactAlarmPermission = repo.canScheduleExactAlarms()
            secureStorageOk = settingsRepo.secureStorageAvailable
            delay(60_000) // Refresh every minute
        }
    }

    val nextAlarm = alarms.minByOrNull { it.triggerAtMillis }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Permission warning card — only show if something is missing
        if (!hasExactAlarmPermission || !secureStorageOk) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CalRed.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("⚠️ Action needed", style = MaterialTheme.typography.titleMedium, color = CalRed)
                        if (!hasExactAlarmPermission) {
                            Text("Grant exact alarm permission", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onRequestExactAlarmPermission, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Grant permission")
                            }
                        }
                        if (!secureStorageOk) {
                            Text("Secure storage unavailable", style = MaterialTheme.typography.bodyMedium, color = CalRed)
                        }
                    }
                }
            }
        }

        // App version card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("App version", style = MaterialTheme.typography.bodyMedium, color = CalGrey)
                    Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // v2.6.8: TEST BUTTONS — manually test alarm and vibration
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CalOrange.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🧪 Test Features", style = MaterialTheme.typography.titleMedium, color = CalOrange)
                    Text(
                        "Test alarm (sound + screen) and test silent alarm (screen + vibrate, NO sound)",
                        style = MaterialTheme.typography.bodySmall,
                        color = CalGrey
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Test Alarm button (sound + full-screen)
                    Button(
                        onClick = {
                            AppLogger.i(TAG_UI, "v2.6.8: User clicked TEST ALARM button")
                            android.widget.Toast.makeText(context, "🧪 Test alarm (sound) in 5 seconds...", android.widget.Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val testTime = System.currentTimeMillis() + 5000
                                val testAlarm = AlarmStore.OwnedAlarm(
                                    id = 999999,
                                    triggerAtMillis = testTime,
                                    timeLabel = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(testTime)),
                                    label = "TEST ALARM (GELUID)",
                                    date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(testTime)),
                                    type = "test",
                                    enabled = true
                                )
                                val result = repo.schedule(testAlarm, force = true)
                                if (result != null) {
                                    AppLogger.i(TAG_UI, "v2.6.8: Test alarm scheduled successfully")
                                } else {
                                    AppLogger.e(TAG_UI, "v2.6.8: Failed to schedule test alarm")
                                    android.widget.Toast.makeText(context, "❌ Could not schedule test alarm", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CalOrange)
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Alarm (sound, 5 sec)")
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // v2.6.11: Test SILENT Alarm button (fullscreen + vibrate, NO sound)
                    // Start the FULL alarm process (FSI notification → fullscreen activity) but without sound
                    Button(
                        onClick = {
                            AppLogger.i(TAG_UI, "v2.6.11: User clicked TEST SILENT ALARM button")
                            android.widget.Toast.makeText(context, "🔕 Test SILENT alarm started (5 sec, NO sound)...", android.widget.Toast.LENGTH_SHORT).show()
                            
                            // Start silent alarm via AlarmForegroundService (fullscreen + vibration, no sound)
                            val testTime = System.currentTimeMillis()
                            val timeLabel = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(testTime))
                            io.github.calendaralarm.service.AlarmForegroundService.startSilent(
                                context,
                                alarmId = 999998,  // Special ID for silent test
                                timeLabel = timeLabel,
                                alarmLabel = "TEST SILENT ALARM"
                            )
                            
                            // Stop after 5 seconds (when the user did not dismiss themselves)
                            scope.launch {
                                kotlinx.coroutines.delay(5000)
                                io.github.calendaralarm.service.AlarmForegroundService.stop(context)
                                android.widget.Toast.makeText(context, "✅ Test silent alarm stopped", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CalPurple)
                    ) {
                        Icon(Icons.Default.VolumeOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Silent Alarm (5 sec, no sound)")
                    }
                }
            }
        }

        // v2.6.9: Extended permission status card with all critical checks
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🔐 Permission Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    
                    // Check 1: USE_FULL_SCREEN_INTENT (Critical for Android 14+)
                    val canFullScreen = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        nm.canUseFullScreenIntent()
                    } else {
                        true
                    }
                    
                    // Check 2: SYSTEM_ALERT_WINDOW ("Appear on top") — Samsung requires this
                    val canOverlay = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.provider.Settings.canDrawOverlays(context)
                    } else {
                        true
                    }
                    
                    // Check 3: Battery optimization exemption
                    val batteryUnrestricted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        pm.isIgnoringBatteryOptimizations(context.packageName)
                    } else {
                        true
                    }
                    
                    // Check 4: Exact alarm permission
                    val hasExactAlarmPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                        alarmManager.canScheduleExactAlarms()
                    } else {
                        true
                    }
                    
                    Text(
                        "Full screen permission: ${if (canFullScreen) "✅ GRANTED" else "❌ DENIED (Critical!)"}",
                        color = if (canFullScreen) CalGreen else CalRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Appear over other apps: ${if (canOverlay) "✅ GRANTED" else "❌ DENIED (Samsung!)"}",
                        color = if (canOverlay) CalGreen else CalRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Battery optimization: ${if (batteryUnrestricted) "✅ DISABLED" else "⚠️ ENABLED (Samsung kills app!)"}",
                        color = if (batteryUnrestricted) CalGreen else CalOrange,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Exact alarms: ${if (hasExactAlarmPermission) "✅ GRANTED" else "❌ DENIED"}",
                        color = if (hasExactAlarmPermission) CalGreen else CalRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // v2.6.9: Buttons for missing permissions
                    if (!canFullScreen && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        Button(
                            onClick = {
                                // v2.6.9 FIX: Open SPECIFIC full screen intent settings (was general notification settings)
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CalRed)
                        ) {
                            Text("⚠️ Enable 'Full screen' (CRITICAL)")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    
                    if (!canOverlay && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        Button(
                            onClick = {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CalOrange)
                        ) {
                            Text("Enable 'Appear over other apps'")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    
                    if (!batteryUnrestricted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        Button(
                            onClick = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CalOrange)
                        ) {
                            Text("Disable battery optimization")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    
                    if (canFullScreen && canOverlay && batteryUnrestricted) {
                        Text(
                            "✅ All permissions OK for fullscreen alarm!",
                            color = CalGreen,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Server Sync card — pull model status
        item {
            var isSyncing by remember { mutableStateOf(false) }
            var lastSyncTime by remember { mutableStateOf<String?>(null) }
            var lastSyncResult by remember { mutableStateOf<String?>(null) }
            var serverReachable by remember { mutableStateOf<Boolean?>(null) }

            // Load last sync result from storage
            LaunchedEffect(Unit) {
                val cached = settingsRepo.getLastSyncResult()
                if (cached != null) {
                    try {
                        val parsed = kotlinx.serialization.json.Json.decodeFromString<SyncStatusCache>(cached)
                        lastSyncTime = parsed.lastSyncTime
                        lastSyncResult = parsed.lastSyncResult
                        serverReachable = parsed.serverReachable
                    } catch (e: Exception) { /* ignore corrupt cache */ }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (serverReachable) {
                                true -> Icons.Default.CloudDone
                                false -> Icons.Default.CloudOff
                                else -> Icons.Default.Cloud
                            },
                            contentDescription = null,
                            tint = when (serverReachable) {
                                true -> CalGreen
                                false -> CalRed
                                else -> CalGrey
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Server Sync", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        settingsRepo.getServerUrl(),
                        style = MaterialTheme.typography.bodySmall,
                        color = CalGrey
                    )
                    if (lastSyncTime != null) {
                        Text("Laatste sync: $lastSyncTime", style = MaterialTheme.typography.bodySmall, color = CalGrey)
                    }
                    if (lastSyncResult != null) {
                        Text(
                            lastSyncResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (lastSyncResult!!.startsWith("✅")) CalGreen else CalRed
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Manual sync button
                    Button(
                        onClick = {
                            AppLogger.i(TAG_UI, "User clicked 'Sync now with server'")
                            // v2.5.8: User actions are ALWAYS allowed, even during Alarm Freeze
                            // Freeze only blocks AUTOMATED sync (AlarmSyncWorker)
                            isSyncing = true
                            scope.launch {
                                val syncRepo = io.github.calendaralarm.sync.SyncRepository(context)
                                // Health check first
                                val healthy = syncRepo.healthCheck()
                                serverReachable = healthy
                                if (!healthy) {
                                    lastSyncResult = "❌ Server unreachable"
                                    AppLogger.e(TAG_UI, "Manual sync: health check FAILED")
                                    isSyncing = false
                                    syncRepo.close()
                                    return@launch
                                }
                                // Fetch alarm plan
                                val result = syncRepo.fetchAlarmPlan()
                                syncRepo.close()
                                val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                lastSyncTime = now
                                if (result.success && result.plan != null) {
                                    val plan = result.plan
                                    AppLogger.i(TAG_UI, "Manual sync received plan: ${plan.wakeTime} on ${plan.date}")
                                    // Schedule the alarm
                                    val targetDate = try {
                                        java.time.LocalDate.parse(plan.date)
                                    } catch (e: Exception) {
                                        java.time.LocalDate.now().plusDays(1)
                                    }
                                    if (plan.wakeTime == null) {
                                        // No alarm (e.g. weekend without weekend rule)
                                        val cancelled = repo.cancelForDate(targetDate)
                                        lastSyncResult = "😴 No alarm — ${plan.label}"
                                        alarms = repo.store.getEnabled()
                                        AppLogger.i(TAG_UI, "Manual sync: no alarm scheduled (${plan.label})")
                                    } else {
                                        val scheduled = repo.replaceForDate(targetDate, plan.wakeTime, plan.label, plan.workLocation)
                                        if (scheduled != null) {
                                            lastSyncResult = "✅ ${plan.wakeTime} — ${plan.label}"
                                            alarms = repo.store.getEnabled()
                                            AppLogger.i(TAG_UI, "Manual sync: alarm scheduled (id=${scheduled.id})")
                                        } else {
                                            lastSyncResult = "❌ Could not schedule alarm"
                                            AppLogger.e(TAG_UI, "Manual sync: failed to schedule alarm (permission?)")
                                        }
                                    }
                                } else {
                                    lastSyncResult = "❌ ${result.error ?: "Sync failed"}"
                                    AppLogger.e(TAG_UI, "Manual sync FAILED: ${result.error}")
                                }
                                // Cache result
                                settingsRepo.saveLastSyncResult(
                                    kotlinx.serialization.json.Json.encodeToString(
                                        SyncStatusCache.serializer(),
                                        SyncStatusCache(lastSyncTime, lastSyncResult, serverReachable)
                                    )
                                )
                                isSyncing = false
                            }
                        },
                        enabled = !isSyncing && secureStorageOk,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Syncing…")
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sync now with server")
                        }
                    }
                }
            }
        }

        // Next alarm card — real data with "fires in" countdown (v2.5.0)
        item {
            // Live countdown — update every minute
            var timeUntilAlarm by remember { mutableStateOf("") }
            LaunchedEffect(nextAlarm?.id) {
                while (true) {
                    timeUntilAlarm = if (nextAlarm != null) {
                        formatTimeUntil(nextAlarm.triggerAtMillis)
                    } else ""
                    delay(30_000) // Update every 30 seconds
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Alarm, contentDescription = null, tint = CalPurple)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Eerstvolgend alarm", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (nextAlarm != null) {
                        // Two-column layout: Alarm | Fires in
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left column: Alarm info
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Alarm", style = MaterialTheme.typography.labelMedium, color = CalGrey)
                                Text(
                                    nextAlarm.timeLabel,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(nextAlarm.label, style = MaterialTheme.typography.bodyMedium, color = CalGrey)
                                Text(
                                    SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(Date(nextAlarm.triggerAtMillis)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CalGrey
                                )
                            }
                            // Right column: Fires in countdown
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.padding(start = 16.dp)
                            ) {
                                Text("Fires in", style = MaterialTheme.typography.labelMedium, color = CalGrey)
                                Text(
                                    timeUntilAlarm.ifEmpty { "—" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = CalOrange
                                )
                            }
                        }
                    } else {
                        Text("No alarm scheduled", style = MaterialTheme.typography.headlineMedium, color = CalGrey)
                    }
                }
            }
        }

        // Wake time schedule card — from server config (pull model: server decides)
        item {
            var rules by remember { mutableStateOf<List<io.github.calendaralarm.sync.SyncRepository.AlarmRule>>(emptyList()) }
            var rulesLoaded by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                scope.launch {
                    val syncRepo = io.github.calendaralarm.sync.SyncRepository(context)
                    val result = syncRepo.fetchConfig()
                    syncRepo.close()
                    if (result.success && result.config != null) {
                        rules = result.config.rules
                        rulesLoaded = true
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Wake times (via server)", style = MaterialTheme.typography.titleMedium)
                        if (rulesLoaded) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = CalGreen, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (rules.isNotEmpty()) {
                        val emojiMap = mapOf("office" to "🏢", "home" to "🏠", "off" to "🏖️")
                        val colorMap = mapOf("office" to CalPurple, "home" to CalGreen, "off" to CalOrange)
                        rules.forEach { rule ->
                            WakeTimeRow(
                                "${emojiMap[rule.id] ?: "⏰"} ${rule.name}",
                                rule.time,
                                colorMap[rule.id] ?: CalGrey
                            )
                        }
                    } else {
                        Text("Not fetched yet — tap ‘Sync now with server’", style = MaterialTheme.typography.bodyMedium, color = CalGrey)
                    }
                }
            }
        }

        // Info card: how alarms work
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CalPurple.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CalPurple)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("How does it work?", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "The app checks every evening with the server (via HTTPS) what tomorrow's wake time is, " +
                        "based on your Outlook calendar. " +
                        "Alarms are managed locally and fire at the set time, even when the screen is off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CalGrey
                    )
                }
            }
        }

        // Quick actions
        item {
            var testAlarmMinutes by remember { mutableIntStateOf(2) }
            var isSettingTestAlarm by remember { mutableStateOf(false) }
            var lastActionResult by remember { mutableStateOf<String?>(null) }
            var showResultSnackbar by remember { mutableStateOf(false) }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Snelle acties", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Test alarm with configurable delay
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Test alarm in:", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (testAlarmMinutes > 1) testAlarmMinutes-- },
                                enabled = !isSettingTestAlarm
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Less")
                            }
                            Text("$testAlarmMinutes min", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(60.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            IconButton(
                                onClick = { if (testAlarmMinutes < 30) testAlarmMinutes++ },
                                enabled = !isSettingTestAlarm
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "More")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            AppLogger.i(TAG_UI, "User clicked 'Test alarm' ($testAlarmMinutes min)")
                            // v2.5.8: User actions are ALWAYS allowed, even during Alarm Freeze
                            // Freeze only blocks AUTOMATED sync (AlarmSyncWorker)
                            isSettingTestAlarm = true
                            scope.launch {
                                val delayMs = testAlarmMinutes * 60_000L
                                val triggerTime = System.currentTimeMillis() + delayMs
                                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(triggerTime))
                                val result = repo.scheduleOneShot(fmt, "🧪 Test alarm", type = "test")
                                isSettingTestAlarm = false
                                if (result != null) {
                                    alarms = repo.store.getEnabled()
                                    lastActionResult = "✅ Test alarm set for $fmt (in $testAlarmMinutes min)"
                                    AppLogger.i(TAG_UI, "Test alarm scheduled: $fmt (id=${result.id})")
                                    Toast.makeText(context, lastActionResult, Toast.LENGTH_LONG).show()
                                } else {
                                    lastActionResult = "❌ Could not set test alarm — check exact-alarm permission"
                                    AppLogger.e(TAG_UI, "Test alarm FAILED — exact alarm permission?")
                                    Toast.makeText(context, lastActionResult, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = hasExactAlarmPermission && !isSettingTestAlarm,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSettingTestAlarm) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AlarmAdd, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isSettingTestAlarm) "Busy…" else "Test alarm in $testAlarmMinutes min")
                    }

                    // Show last result
                    if (lastActionResult != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            lastActionResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (lastActionResult!!.startsWith("✅")) CalGreen else CalRed
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WakeTimeRow(label: String, time: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(time, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen() {
    val context = LocalContext.current
    val repo = remember { AlarmRepository(context) }
    val scope = rememberCoroutineScope()
    var alarms by remember { mutableStateOf<List<AlarmStore.OwnedAlarm>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var alarmToDelete by remember { mutableStateOf<AlarmStore.OwnedAlarm?>(null) }
    var alarmToEdit by remember { mutableStateOf<AlarmStore.OwnedAlarm?>(null) }  // v2.5.0: edit mode

    LaunchedEffect(Unit) {
        alarms = repo.store.getAll()
    }

    // F2: Delete confirmation dialog
    alarmToDelete?.let { alarm ->
        AlertDialog(
            onDismissRequest = { alarmToDelete = null },
            title = { Text("Delete alarm?") },
            text = { Text("Are you sure you want to delete the alarm '${alarm.timeLabel} — ${alarm.label}'?") },
            confirmButton = {
                TextButton(onClick = {
                    AppLogger.i(TAG_UI, "User confirmed delete alarm: ${alarm.timeLabel} (${alarm.label}, id=${alarm.id})")
                    // v2.5.8: User actions are ALWAYS allowed, even during Alarm Freeze
                    // Freeze only blocks AUTOMATED sync (AlarmSyncWorker)
                    scope.launch {
                        // FC-04 fix: Check the return value of cancel()
                        val success = repo.cancel(alarm.id)
                        alarms = repo.store.getAll()
                        Toast.makeText(
                            context,
                            if (success) "Alarm deleted" else "Could not delete alarm",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    alarmToDelete = null
                }) { Text("Delete", color = CalRed) }
            },
            dismissButton = {
                TextButton(onClick = { alarmToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // F3: Add alarm dialog
    if (showAddDialog) {
        AddAlarmDialog(
            onDismiss = { showAddDialog = false },
            onAlarmAdded = {
                showAddDialog = false
                scope.launch {
                    alarms = repo.store.getAll()
                }
            }
        )
    }

    // v2.5.0: Edit alarm dialog
    alarmToEdit?.let { alarm ->
        AddAlarmDialog(
            onDismiss = { alarmToEdit = null },
            onAlarmAdded = {
                alarmToEdit = null
                scope.launch {
                    alarms = repo.store.getAll()
                }
            },
            editAlarm = alarm
        )
    }

    Scaffold(
        floatingActionButton = {
            // F3: FAB to add a new alarm
            // v2.5.8: User actions are ALWAYS allowed, even during Alarm Freeze
            // Freeze only blocks AUTOMATED sync (AlarmSyncWorker)
            FloatingActionButton(
                onClick = {
                    AppLogger.i(TAG_UI, "User clicked FAB to add alarm")
                    showAddDialog = true
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add alarm")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (alarms.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.AlarmOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = CalGrey)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No alarms scheduled", style = MaterialTheme.typography.bodyLarge, color = CalGrey)
                            Text("Alarms are set automatically every evening by the server", style = MaterialTheme.typography.bodySmall, color = CalGrey)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Tap + to add an alarm manually", style = MaterialTheme.typography.bodySmall, color = CalPurple)
                        }
                    }
                }
            }

            items(alarms, key = { it.id }) { alarm ->
                // v2.5.8: User actions are ALWAYS allowed, even during Alarm Freeze
                // Freeze only blocks AUTOMATED sync (AlarmSyncWorker)
                AlarmCard(
                    alarm = alarm,
                    onToggle = { enabled ->
                        AppLogger.i(TAG_UI, "User toggled alarm ${alarm.timeLabel} (${alarm.label}, id=${alarm.id}) to enabled=$enabled")
                        scope.launch {
                            val ok = repo.setEnabled(alarm.id, enabled)
                            if (ok) {
                                alarms = repo.store.getAll()
                                Toast.makeText(
                                    context,
                                    if (enabled) "Alarm enabled" else "Alarm disabled",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Could not ${if (enabled) "enable" else "disable"} alarm",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onDelete = {
                        alarmToDelete = alarm
                    },
                    onEdit = {
                        // v2.5.0: Open edit dialog
                        AppLogger.i(TAG_UI, "User clicked to edit alarm ${alarm.timeLabel} (${alarm.label}, id=${alarm.id})")
                        alarmToEdit = alarm
                    }
                )
            }
        }
    }
}

@Composable
fun AlarmCard(
    alarm: AlarmStore.OwnedAlarm,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {}  // v2.5.0: click to edit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // v2.5.0: Clickable left side to edit
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEdit() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        alarm.timeLabel,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else CalGrey
                    )
                    if (alarm.repeatDays.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            alarm.repeatDays.sorted().joinToString(",") {
                                listOf("", "Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")[it]
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CalPurple
                        )
                    }
                    if (alarm.skipCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "skip×${alarm.skipCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = CalOrange
                        )
                    }
                    // v2.5.0: Show snooze override badge
                    if (alarm.snoozeMinutesOverride != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (alarm.snoozeMinutesOverride == 0) "🔕" else "💤${alarm.snoozeMinutesOverride}m",
                            style = MaterialTheme.typography.bodySmall,
                            color = CalGrey
                        )
                    }
                }
                Text(
                    alarm.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (alarm.enabled) CalGrey else CalGrey.copy(alpha = 0.5f)
                )
                Text(
                    alarm.date + if (!alarm.enabled) " (disabled)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey.copy(alpha = if (alarm.enabled) 1f else 0.5f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // F1: Toggle switch
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle
                )
                // F2: Delete button
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CalRed)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsRepo = CalendarAlarmApp.settingsRepository
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = AlarmSettings())
    val scope = rememberCoroutineScope()

    // Note: officeTime/homeTime/offTime local settings are no longer used for alarms.
    // The server decides wake times via dynamic rules (see ServerRulesEditor).
    var showToken by remember { mutableStateOf(false) }
    var showRotateDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }

    // M-07 fix: FLAG_SECURE while token is visible
    val activity = context as? android.app.Activity
    DisposableEffect(showToken) {
        if (showToken) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Pull model: wake times are decided by the server (dynamic rules).
        // The local officeTime/homeTime/offTime settings are NOT used for alarms.
        Text("Wake times (server)", style = MaterialTheme.typography.titleLarge)
        Text(
            "These times are determined by the server based on your calendar. " +
            "You can adjust them here — the change is sent to the server.",
            style = MaterialTheme.typography.bodySmall,
            color = CalGrey
        )
        Spacer(modifier = Modifier.height(4.dp))

        ServerRulesEditor()

        Divider()

        // F6: Snooze duration setting
        Text("Alarm", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Snooze duration", style = MaterialTheme.typography.titleMedium)
                Text(
                    "How long the alarm waits after 'Snooze'",
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey
                )
                Spacer(modifier = Modifier.height(8.dp))

                // v2.5.11 PM fix: Read directly from StateFlow — UI updates immediately when settings change
                // (was: var snoozeMinutes by remember { ... } — cached old value, didn't update after save)
                val snoozeMinutes = settings.snoozeMinutes
                val snoozeOptions = listOf(0 to "Off", 5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    snoozeOptions.forEach { (minutes, label) ->
                        FilterChip(
                            selected = snoozeMinutes == minutes,
                            onClick = {
                                AppLogger.i(TAG_UI, "User selected snooze duration: $label")
                                scope.launch {
                                    val ok = settingsRepo.setSnoozeMinutes(minutes)
                                    if (ok) {
                                        Toast.makeText(context, "Snooze duration saved: $label", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Could not save snooze", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (snoozeMinutes == 0) {
                    Text(
                        "⚠️ Snooze is disabled — there is no snooze button when the alarm rings",
                        style = MaterialTheme.typography.bodySmall,
                        color = CalOrange,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Divider()

        // v2.6.1: Alarm behavior — volume, vibration, sound settings
        Text("Alarm behavior", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Volume startniveau
                var volumeStart by remember { mutableFloatStateOf(0.3f) }
                var volumeBuildUp by remember { mutableLongStateOf(30000L) }
                var vibrationEnabled by remember { mutableStateOf(true) }
                var vibrationPattern by remember { mutableStateOf("0,1000,500") }
                var alarmSoundUri by remember { mutableStateOf("") }
                var alarmSoundName by remember { mutableStateOf("Default alarm sound") }

                LaunchedEffect(Unit) {
                    val prefs = context.getSharedPreferences(CalendarAlarmApp.PREFS_ALARM_SETTINGS, Context.MODE_PRIVATE)
                    volumeStart = prefs.getFloat(CalendarAlarmApp.PREF_VOLUME_START_LEVEL, 0.3f)
                    volumeBuildUp = prefs.getLong(CalendarAlarmApp.PREF_VOLUME_BUILD_UP_DURATION, 30000L)
                    vibrationEnabled = prefs.getBoolean(CalendarAlarmApp.PREF_VIBRATION_ENABLED, true)
                    vibrationPattern = prefs.getString(CalendarAlarmApp.PREF_VIBRATION_PATTERN, "0,1000,500") ?: "0,1000,500"
                    val uriString = prefs.getString(CalendarAlarmApp.PREF_ALARM_SOUND_URI, null)
                    if (uriString != null && uriString.isNotEmpty()) {
                        alarmSoundUri = uriString
                        try {
                            val ringtone = android.media.RingtoneManager.getRingtone(context, Uri.parse(uriString))
                            alarmSoundName = ringtone?.getTitle(context) ?: "Selected sound"
                        } catch (e: Exception) {
                            alarmSoundName = "Selected sound"
                        }
                    }
                }

                Text("Start volume", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Volume the alarm starts with (${(volumeStart * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey
                )
                Slider(
                    value = volumeStart,
                    onValueChange = { volumeStart = it },
                    onValueChangeFinished = {
                        val prefs = context.getSharedPreferences(CalendarAlarmApp.PREFS_ALARM_SETTINGS, Context.MODE_PRIVATE)
                        prefs.edit().putFloat(CalendarAlarmApp.PREF_VOLUME_START_LEVEL, volumeStart).apply()
                        Toast.makeText(context, "Start volume saved: ${(volumeStart * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                    },
                    valueRange = 0f..1f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Volume build-up time
                Text("Volume build-up time", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Time to go from start volume to 100% (${volumeBuildUp / 1000} seconds)",
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey
                )
                Slider(
                    value = volumeBuildUp.toFloat(),
                    onValueChange = { volumeBuildUp = it.toLong() },
                    onValueChangeFinished = {
                        val prefs = context.getSharedPreferences(CalendarAlarmApp.PREFS_ALARM_SETTINGS, Context.MODE_PRIVATE)
                        prefs.edit().putLong(CalendarAlarmApp.PREF_VOLUME_BUILD_UP_DURATION, volumeBuildUp).apply()
                        Toast.makeText(context, "Build-up time saved: ${volumeBuildUp / 1000}s", Toast.LENGTH_SHORT).show()
                    },
                    valueRange = 0f..60000f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Vibration on/off
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vibrate", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "App vibrates during the alarm",
                            style = MaterialTheme.typography.bodySmall,
                            color = CalGrey
                        )
                    }
                    Switch(
                        checked = vibrationEnabled,
                        onCheckedChange = { newValue ->
                            vibrationEnabled = newValue
                            val prefs = context.getSharedPreferences(CalendarAlarmApp.PREFS_ALARM_SETTINGS, Context.MODE_PRIVATE)
                            prefs.edit().putBoolean(CalendarAlarmApp.PREF_VIBRATION_ENABLED, newValue).apply()
                            Toast.makeText(
                                context,
                                if (newValue) "Vibration enabled" else "Vibration disabled",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }

                // Vibration pattern (only shown when vibration is on)
                if (vibrationEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Vibration pattern", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pattern: wait, vibrate, wait (ms). Current: $vibrationPattern",
                        style = MaterialTheme.typography.bodySmall,
                        color = CalGrey
                    )
                    val patterns = listOf(
                        "0,1000,500" to "Default (long-short)",
                        "0,500,200,500,200" to "Fast (short-short)",
                        "0,200,100,200,100,200,100" to "Very fast",
                        "0,2000,1000" to "Slow (long-long)"
                    )
                    patterns.forEach { (pattern, label) ->
                        FilterChip(
                            selected = vibrationPattern == pattern,
                            onClick = {
                                vibrationPattern = pattern
                                val prefs = context.getSharedPreferences(CalendarAlarmApp.PREFS_ALARM_SETTINGS, Context.MODE_PRIVATE)
                                prefs.edit().putString(CalendarAlarmApp.PREF_VIBRATION_PATTERN, pattern).apply()
                                Toast.makeText(context, "Pattern saved: $label", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Geluid kiezer
                val ringtonePickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        val uri = result.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                        if (uri != null) {
                            alarmSoundUri = uri.toString()
                            // Try to fetch the name
                            try {
                                val ringtone = android.media.RingtoneManager.getRingtone(context, uri)
                                alarmSoundName = ringtone?.getTitle(context) ?: "Selected sound"
                            } catch (e: Exception) {
                                alarmSoundName = "Selected sound"
                            }
                            val prefs = context.getSharedPreferences(CalendarAlarmApp.PREFS_ALARM_SETTINGS, Context.MODE_PRIVATE)
                            prefs.edit().putString(CalendarAlarmApp.PREF_ALARM_SOUND_URI, alarmSoundUri).apply()
                            Toast.makeText(context, "Sound saved: $alarmSoundName", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                Text("Alarm sound", style = MaterialTheme.typography.titleMedium)
                Text(
                    alarmSoundName,
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey
                )
                Button(
                    onClick = {
                        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM)
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Pick alarm sound")
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            if (alarmSoundUri.isNotEmpty()) {
                                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(alarmSoundUri))
                            }
                        }
                        ringtonePickerLauncher.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick sound")
                }

                // Reset button
                OutlinedButton(
                    onClick = {
                        val prefs = context.getSharedPreferences(CalendarAlarmApp.PREFS_ALARM_SETTINGS, Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            remove(CalendarAlarmApp.PREF_VOLUME_START_LEVEL)
                            remove(CalendarAlarmApp.PREF_VOLUME_BUILD_UP_DURATION)
                            putBoolean(CalendarAlarmApp.PREF_VIBRATION_ENABLED, true)
                            putString(CalendarAlarmApp.PREF_VIBRATION_PATTERN, "0,1000,500")
                            remove(CalendarAlarmApp.PREF_ALARM_SOUND_URI)
                            apply()
                        }
                        volumeStart = 0.3f
                        volumeBuildUp = 30000L
                        vibrationEnabled = true
                        vibrationPattern = "0,1000,500"
                        alarmSoundUri = ""
                        alarmSoundName = "Default alarm sound"
                        Toast.makeText(context, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset to defaults")
                }
            }
        }

        Divider()

        // v2.5.8: Alarm Freeze — blocks ONLY automated sync during configured hours
        // All user-initiated interactions (add, edit, delete, toggle, test, manual sync) work 24/7
        Text("Alarm Freeze", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // v2.5.11 PM fix: Read directly from StateFlow — UI updates immediately when settings change
                // (was: var freezeEnabled by remember { ... } — cached old value, didn't update after save)
                val freezeEnabled = settings.wakkerFreezeEnabled
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable alarm freeze", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "During these times automatic sync is blocked. You can always change alarms manually.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CalGrey
                        )
                    }
                    Switch(
                        checked = freezeEnabled,
                        onCheckedChange = { newValue ->
                            AppLogger.i(TAG_UI, "User toggled Alarm Freeze: $newValue")
                            scope.launch {
                                val ok = settingsRepo.updateWekkerFreeze(
                                    enabled = newValue,
                                    start = settings.wakkerFreezeStart,
                                    end = settings.wakkerFreezeEnd
                                )
                                Toast.makeText(
                                    context,
                                    if (ok) {
                                        if (newValue) "Alarm freeze enabled" else "Alarm freeze disabled"
                                    } else {
                                        "Could not save setting"
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }

                // Show time pickers only when freeze is enabled
                if (freezeEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // v2.5.11 PM fix: Local state for editing + LaunchedEffect to sync from StateFlow
                    // (was: var freezeStart by remember { ... } — cached old value after save)
                    var freezeStart by remember { mutableStateOf(settings.wakkerFreezeStart) }
                    var freezeEnd by remember { mutableStateOf(settings.wakkerFreezeEnd) }
                    val timeRegex = remember { Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$") }

                    // Sync local state when settings change (after save or migration)
                    LaunchedEffect(settings.wakkerFreezeStart, settings.wakkerFreezeEnd) {
                        freezeStart = settings.wakkerFreezeStart
                        freezeEnd = settings.wakkerFreezeEnd
                    }

                    OutlinedTextField(
                        value = freezeStart,
                        onValueChange = { freezeStart = it },
                        label = { Text("Freeze from") },
                        leadingIcon = { Icon(Icons.Default.Bedtime, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = !timeRegex.matches(freezeStart),
                        supportingText = {
                            if (!timeRegex.matches(freezeStart)) {
                                Text("Use format HH:mm (e.g. 22:00)")
                            } else {
                                Text("Format: HH:mm (24-hour)")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = freezeEnd,
                        onValueChange = { freezeEnd = it },
                        label = { Text("Freeze until") },
                        leadingIcon = { Icon(Icons.Default.WbSunny, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = !timeRegex.matches(freezeEnd),
                        supportingText = {
                            if (!timeRegex.matches(freezeEnd)) {
                                Text("Use format HH:mm (e.g. 07:00)")
                            } else {
                                Text("Format: HH:mm (24-hour)")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            AppLogger.i(TAG_UI, "User saved Alarm Freeze times: $freezeStart - $freezeEnd")
                            scope.launch {
                                val ok = settingsRepo.updateWekkerFreeze(
                                    enabled = true,
                                    start = freezeStart,
                                    end = freezeEnd
                                )
                                Toast.makeText(
                                    context,
                                    if (ok) "Freeze times saved: $freezeStart - $freezeEnd"
                                    else "Invalid time — use format HH:mm",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = timeRegex.matches(freezeStart) && timeRegex.matches(freezeEnd),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save freeze times")
                    }

                    // Show current freeze status
                    if (settingsRepo.isWekkerFrozen()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "\uD83E\uDD76 Currently frozen — automatic sync is paused, manual actions work normally",
                            style = MaterialTheme.typography.bodySmall,
                            color = CalOrange
                        )
                    }
                }
            }
        }

        Divider()

        // Sync / Server Configuration (pull model)
        Text("Server Sync", style = MaterialTheme.typography.titleLarge)

        // Server URL
        var serverUrl by remember { mutableStateOf(settingsRepo.getServerUrl()) }
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = !serverUrl.startsWith("https://"),
            supportingText = { if (!serverUrl.startsWith("https://")) Text("Only HTTPS allowed") }
        )
        Button(
            onClick = {
                AppLogger.i(TAG_UI, "User saved server URL: $serverUrl")
                settingsRepo.setServerUrl(serverUrl)
                Toast.makeText(context, "Server URL saved", Toast.LENGTH_SHORT).show()
            },
            enabled = serverUrl.startsWith("https://"),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save server URL")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Device Token — EDITABLE so user can paste the server-configured token
        var deviceToken by remember { mutableStateOf("") }
        var deviceTokenEdited by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            deviceToken = try { settingsRepo.getDeviceToken() } catch (e: Exception) { "" }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Token", style = MaterialTheme.typography.titleMedium)
                    Row {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Device Token", deviceToken))
                            Toast.makeText(context, "Token copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showToken) "Hide" else "Show"
                            )
                        }
                    }
                }

                // Editable field — user pastes the token from the server config
                OutlinedTextField(
                    value = if (showToken) deviceToken else "••••••••••••••••••••••••••••••••",
                    onValueChange = {
                        deviceToken = it
                        deviceTokenEdited = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = !showToken,
                    placeholder = { Text("Paste the token from the backend server here") },
                    supportingText = {
                        Text("The server must know this exact token. Paste the token from the server .env file.")
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            AppLogger.i(TAG_UI, "User saved device token (${deviceToken.trim().length} chars) — token value not logged (SEC-13)")
                            val ok = settingsRepo.setDeviceToken(deviceToken)
                            Toast.makeText(
                                context,
                                if (ok) "Device token saved" else "Invalid token (min 32 chars)",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (ok) deviceTokenEdited = false
                        },
                        enabled = deviceTokenEdited && deviceToken.trim().length >= 32,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = { showRotateDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New")
                    }
                }
            }
        }


        Divider()

        // Cloudflare Access Service Token
        Text("Cloudflare Access", style = MaterialTheme.typography.titleLarge)
        var cfClientId by remember { mutableStateOf(settingsRepo.getCfAccessClientId()) }
        var cfClientSecret by remember { mutableStateOf(settingsRepo.getCfAccessClientSecret()) }
        var showCfSecret by remember { mutableStateOf(false) }
        var cfSaved by remember { mutableStateOf(settingsRepo.hasCfAccessCredentials()) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (cfSaved) "\u2705 Cloudflare Access configured" else "\u26a0\ufe0f Not configured — server may be unreachable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (cfSaved) CalGreen else CalOrange
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = cfClientId,
                    onValueChange = { cfClientId = it },
                    label = { Text("Client ID") },
                    leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("xxx.access") }
                )

                OutlinedTextField(
                    value = cfClientSecret,
                    onValueChange = { cfClientSecret = it },
                    label = { Text("Client Secret") },
                    leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showCfSecret)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showCfSecret = !showCfSecret }) {
                            Icon(
                                if (showCfSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showCfSecret) "Hide" else "Show"
                            )
                        }
                    },
                    placeholder = { Text("cfast_...") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            // M-04 fix: Do NOT log partial CF Access Client ID (privacy)
                            AppLogger.i(TAG_UI, "User saved Cloudflare Access credentials")
                            val ok = settingsRepo.setCfAccessCredentials(cfClientId, cfClientSecret)
                            cfSaved = settingsRepo.hasCfAccessCredentials()
                            Toast.makeText(
                                context,
                                if (ok) "Cloudflare Access saved" else "Invalid credentials",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        enabled = cfClientId.isNotBlank() && cfClientSecret.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = {
                            AppLogger.w(TAG_UI, "User cleared Cloudflare Access credentials")
                            settingsRepo.clearCfAccessCredentials()
                            cfClientId = ""
                            cfClientSecret = ""
                            cfSaved = false
                            Toast.makeText(context, "Cloudflare Access cleared", Toast.LENGTH_SHORT).show()
                        },
                        enabled = cfSaved,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                }

                Text(
                    "Only needed when your backend and update server sit behind Cloudflare Access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey
                )
            }
        }
        Divider()

        // Update info with live status
        var isCheckingUpdate by remember { mutableStateOf(false) }
        var updateStatus by remember { mutableStateOf<String?>(null) }
        var updateAvailable by remember { mutableStateOf(false) }
        var latestVersion by remember { mutableStateOf<String?>(null) }

        // v2.5.4 Story 2: Live download status via WorkManager
        var downloadState by remember { mutableStateOf<androidx.work.WorkInfo.State?>(null) }
        var downloadWorkId by remember { mutableStateOf<java.util.UUID?>(null) }
        var downloadedApkPath by remember { mutableStateOf<String?>(null) }  // v2.5.4 PM fix C-01
        val workManager = androidx.work.WorkManager.getInstance(context)

        // v2.5.11 Story 2 Fix 2: Reset download state on Settings open — don't show old FAILED states from previous sessions
        LaunchedEffect(Unit) {
            AppLogger.i(TAG_UI, "Download state reset on Settings open")
            downloadState = null
            downloadWorkId = null
            downloadedApkPath = null
        }

        // Observe the download worker state by unique work name (v2.5.4 Story 2)
        // Using LiveData.observeAsState() since collectAsStateWithLifecycle doesn't work with LiveData
        val downloadWorkInfos: List<androidx.work.WorkInfo> by workManager.getWorkInfosForUniqueWorkLiveData(
            io.github.calendaralarm.worker.DownloadVerifiedUpdateWorker.WORK_NAME
        ).observeAsState(initial = emptyList())

        // Also observe by ID once we have it (more specific)
        val downloadWorkInfoById: androidx.work.WorkInfo? = if (downloadWorkId != null) {
            workManager.getWorkInfoByIdLiveData(downloadWorkId!!).observeAsState().value
        } else {
            null
        }

        // Sync observed state to downloadState
        LaunchedEffect(downloadWorkInfoById, downloadWorkInfos) {
            val info: androidx.work.WorkInfo? = downloadWorkInfoById ?: downloadWorkInfos.firstOrNull()
            downloadState = info?.state
            if (info != null && downloadWorkId == null) {
                downloadWorkId = info.id
            }
            // v2.5.4 PM fix C-01: Read APK path from output data on SUCCEEDED
            if (info?.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                downloadedApkPath = info.outputData.getString(
                    io.github.calendaralarm.worker.DownloadVerifiedUpdateWorker.KEY_APK_PATH
                )
            }
        }

        // v2.5.11 Story 2 Fix 1: Auto-check for updates when Settings screen opens
        LaunchedEffect(Unit) {
            AppLogger.i(TAG_UI, "Auto-checking for updates on Settings open")
            // v2.5.9 Story 1: Check if Cloudflare Access is configured first
            if (!settingsRepo.hasCfAccessCredentials()) {
                updateStatus = "⚠️ Configure Cloudflare Access first in Settings (see section above)"
                AppLogger.w(TAG_UI, "Auto update check blocked: CF Access credentials not configured")
                return@LaunchedEffect
            }
            isCheckingUpdate = true
            updateStatus = "Checking…"
            try {
                val checker = io.github.calendaralarm.data.UpdateChecker(context)
                val result = checker.checkForUpdate()
                checker.close()
                isCheckingUpdate = false
                if (result.error != null) {
                    // v2.5.9 Story 1: Better, actionable error messages
                    val errorMsg = result.error ?: "Unknown error"
                    val friendlyMsg = when {
                        errorMsg.contains("302") || errorMsg.contains("redirect", ignoreCase = true) ->
                            "Server requires Cloudflare Access login — check credentials"
                        errorMsg.contains("403") ->
                            "Cloudflare blocked the request — check Client ID + Secret"
                        errorMsg.contains("401") ->
                            "Not authorized — check Cloudflare Access credentials"
                        else -> errorMsg
                    }
                    updateStatus = "❌ $friendlyMsg"
                    AppLogger.e(TAG_UI, "Auto update check failed: $friendlyMsg")
                } else if (result.updateAvailable) {
                    updateAvailable = true
                    latestVersion = result.latestVersion
                    updateStatus = "⬆️ Update available: v${result.latestVersion}"
                    AppLogger.i(TAG_UI, "Auto update check: update available v${result.latestVersion} (current: v${BuildConfig.VERSION_NAME})")
                } else {
                    updateStatus = "✅ You have the latest version"
                    AppLogger.i(TAG_UI, "Auto update check: already on latest (v${BuildConfig.VERSION_NAME})")
                }
            } catch (e: Exception) {
                isCheckingUpdate = false
                updateStatus = "❌ Error: ${e.message}"
                AppLogger.e(TAG_UI, "Auto update check exception: ${e.javaClass.simpleName}")
            }
        }

        Text("Updates", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Current version: v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                        Text("Update server: ${io.github.calendaralarm.BuildConfig.UPDATE_SERVER_URL.ifBlank { "(off)" }}", style = MaterialTheme.typography.bodySmall, color = CalGrey)
                    }
                    if (updateAvailable) {
                        Badge(containerColor = CalOrange) { Text("v$latestVersion") }
                    }
                }

                // Status message
                if (updateStatus != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        updateStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            updateStatus!!.startsWith("✅") -> CalGreen
                            updateStatus!!.startsWith("❌") -> CalRed
                            else -> CalGrey
                        }
                    )
                }

                // v2.5.4 Story 2: Live download status from WorkManager
                val downloadStatusText = when (downloadState) {
                    androidx.work.WorkInfo.State.ENQUEUED,
                    androidx.work.WorkInfo.State.BLOCKED,
                    androidx.work.WorkInfo.State.RUNNING -> "⏳ Downloading…"
                    androidx.work.WorkInfo.State.SUCCEEDED -> "✅ Download complete — tap the notification to install"
                    androidx.work.WorkInfo.State.FAILED -> "❌ Download failed"
                    androidx.work.WorkInfo.State.CANCELLED -> "🚫 Download cancelled"
                    null -> null
                }
                if (downloadStatusText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        downloadStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (downloadState) {
                            androidx.work.WorkInfo.State.SUCCEEDED -> CalGreen
                            androidx.work.WorkInfo.State.FAILED,
                            androidx.work.WorkInfo.State.CANCELLED -> CalRed
                            else -> CalGrey
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        AppLogger.i(TAG_UI, "User clicked 'Check for updates now'")
                        
                        // v2.5.9 Story 1: Check if Cloudflare Access is configured first
                        if (!settingsRepo.hasCfAccessCredentials()) {
                            updateStatus = "⚠️ Configure Cloudflare Access first in Settings (see section above)"
                            Toast.makeText(context, "Cloudflare Access not configured — see Settings", Toast.LENGTH_LONG).show()
                            AppLogger.w(TAG_UI, "Update check blocked: CF Access credentials not configured")
                            return@Button
                        }
                        
                        isCheckingUpdate = true
                        updateStatus = "Checking…"  // v2.5.9: Show immediate feedback
                        scope.launch {
                            try {
                                val checker = io.github.calendaralarm.data.UpdateChecker(context)
                                val result = checker.checkForUpdate()
                                checker.close()
                                isCheckingUpdate = false
                                if (result.error != null) {
                                    // v2.5.9 Story 1: Better, actionable error messages
                                    val errorMsg = result.error ?: "Unknown error"
                                    val friendlyMsg = when {
                                        errorMsg.contains("302") || errorMsg.contains("redirect", ignoreCase = true) ->
                                            "Server requires Cloudflare Access login — check credentials"
                                        errorMsg.contains("403") ->
                                            "Cloudflare blocked the request — check Client ID + Secret"
                                        errorMsg.contains("401") ->
                                            "Not authorized — check Cloudflare Access credentials"
                                        else -> errorMsg
                                    }
                                    updateStatus = "❌ $friendlyMsg"
                                    AppLogger.e(TAG_UI, "Update check failed: $friendlyMsg")
                                } else if (result.updateAvailable) {
                                    updateAvailable = true
                                    latestVersion = result.latestVersion
                                    updateStatus = "⬆️ Update available: v${result.latestVersion}"
                                    AppLogger.i(TAG_UI, "Update available: v${result.latestVersion} (current: v${BuildConfig.VERSION_NAME})")
                                } else {
                                    updateStatus = "✅ You have the latest version"
                                    AppLogger.i(TAG_UI, "Update check: already on latest (v${BuildConfig.VERSION_NAME})")
                                }
                            } catch (e: Exception) {
                                isCheckingUpdate = false
                                updateStatus = "❌ Error: ${e.message}"
                                AppLogger.e(TAG_UI, "Update check exception: ${e.javaClass.simpleName}")
                            }
                        }
                    },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking…")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check for updates now")
                    }
                }

                // v2.5.11 Story 2 Fix 4: Better download progress card
                if (downloadState != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when (downloadState) {
                                androidx.work.WorkInfo.State.SUCCEEDED -> CalGreen.copy(alpha = 0.1f)
                                androidx.work.WorkInfo.State.FAILED,
                                androidx.work.WorkInfo.State.CANCELLED -> CalRed.copy(alpha = 0.1f)
                                else -> CalGrey.copy(alpha = 0.1f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (downloadState) {
                                androidx.work.WorkInfo.State.ENQUEUED -> {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download queued…", style = MaterialTheme.typography.bodySmall)
                                }
                                androidx.work.WorkInfo.State.RUNNING,
                                androidx.work.WorkInfo.State.BLOCKED -> {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Downloading…", style = MaterialTheme.typography.bodySmall)
                                }
                                androidx.work.WorkInfo.State.SUCCEEDED -> {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CalGreen, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download complete! Check your notifications.", style = MaterialTheme.typography.bodySmall, color = CalGreen)
                                }
                                androidx.work.WorkInfo.State.FAILED -> {
                                    Icon(Icons.Default.Error, contentDescription = null, tint = CalRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download failed — try again", style = MaterialTheme.typography.bodySmall, color = CalRed)
                                }
                                androidx.work.WorkInfo.State.CANCELLED -> {
                                    Icon(Icons.Default.Cancel, contentDescription = null, tint = CalGrey, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download cancelled", style = MaterialTheme.typography.bodySmall, color = CalGrey)
                                }
                                null -> {}
                            }
                        }
                    }
                }

                // Download & install button (only if update available)
                if (updateAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            AppLogger.i(TAG_UI, "User clicked download button")
                            scope.launch {
                                try {
                                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<io.github.calendaralarm.worker.DownloadVerifiedUpdateWorker>()
                                        .setConstraints(
                                            androidx.work.Constraints.Builder()
                                                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                                                .build()
                                        )
                                        .build()

                                    downloadWorkId = workRequest.id
                                    downloadState = androidx.work.WorkInfo.State.ENQUEUED

                                    val wm = androidx.work.WorkManager.getInstance(context)
                                    wm.enqueueUniqueWork(
                                        io.github.calendaralarm.worker.DownloadVerifiedUpdateWorker.WORK_NAME,
                                        androidx.work.ExistingWorkPolicy.REPLACE,
                                        workRequest
                                    )

                                    AppLogger.i(TAG_UI, "Update download enqueued: workId=${workRequest.id}")
                                    Toast.makeText(context, "Download started — please wait...", Toast.LENGTH_SHORT).show()

                                } catch (e: Exception) {
                                    AppLogger.e(TAG_UI, "Failed to start download: ${e.message}")
                                    Toast.makeText(context, "Failed to start download: ${e.message}", Toast.LENGTH_LONG).show()
                                    downloadState = null
                                }
                            }
                        },
                        enabled = updateAvailable &&
                                  downloadState != androidx.work.WorkInfo.State.ENQUEUED &&
                                  downloadState != androidx.work.WorkInfo.State.RUNNING &&
                                  downloadState != androidx.work.WorkInfo.State.BLOCKED,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CalOrange)
                    ) {
                        // v2.5.11 Story 2 Fix 3: Better button content based on state
                        when (downloadState) {
                            androidx.work.WorkInfo.State.ENQUEUED,
                            androidx.work.WorkInfo.State.RUNNING,
                            androidx.work.WorkInfo.State.BLOCKED -> {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Downloading…")
                            }
                            androidx.work.WorkInfo.State.SUCCEEDED -> {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CalGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download complete — see notification")
                            }
                            androidx.work.WorkInfo.State.FAILED -> {
                                Icon(Icons.Default.Error, contentDescription = null, tint = CalRed)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download again")
                            }
                            else -> {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download & install v$latestVersion")
                            }
                        }
                    }

                    // v2.5.4 PM fix C-01: "Install now" button — opens the REAL APK installer via FileProvider
                    // (was: opened app settings page — critical bug)
                    if (downloadState == androidx.work.WorkInfo.State.SUCCEEDED && downloadedApkPath != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                AppLogger.i(TAG_UI, "User clicked 'Install now' — opening APK installer")
                                try {
                                    val apkFile = java.io.File(downloadedApkPath!!)
                                    if (!apkFile.exists()) {
                                        Toast.makeText(context, "APK file no longer available — download again", Toast.LENGTH_LONG).show()
                                        return@OutlinedButton
                                    }
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        apkFile
                                    )
                                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/vnd.android.package-archive")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(installIntent)
                                } catch (e: Exception) {
                                    AppLogger.e(TAG_UI, "Failed to open installer: ${e.message}")
                                    Toast.makeText(context, "Could not open installer: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CalGreen)
                        ) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Install now")
                        }
                    }
                }
            }
        }

        Divider()

        // Debug: Logs (moved from separate tab into Settings)
        Text("Debug", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("View app logs", style = MaterialTheme.typography.titleMedium)
                Text(
                    "View internal logs for troubleshooting. ${AppLogger.getAll().size} entries in memory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CalGrey
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        AppLogger.i(TAG_UI, "User opened logs from Settings")
                        showLogsDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View logs")
                }
            }
        }

        Divider()

        // About
        Text("About", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⏰ Calendar Alarm v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Smart alarm driven by your calendar.\nSyncs with your self-hosted backend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CalGrey
                )
            }
        }
    }

    // Logs dialog (volledig scherm overlay)
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("Logs") },
            text = {
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)) {
                    LogsScreenCompact()
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showRotateDialog) {
        AlertDialog(
            onDismissRequest = { showRotateDialog = false },
            title = { Text("Rotate token?") },
            text = { Text("The current device token becomes invalid. The server must be reconfigured with the new token.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            settingsRepo.rotateDeviceToken()
                            showRotateDialog = false
                            Toast.makeText(context, "Token rotated — update the server configuration!", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            showRotateDialog = false
                            Toast.makeText(context, "❌ Could not rotate token: ${e.message}", Toast.LENGTH_LONG).show()
                            AppLogger.e(TAG_UI, "rotateDeviceToken failed: ${e.javaClass.simpleName}")
                        }
                    }
                }) { Text("Rotate") }
            },
            dismissButton = {
                TextButton(onClick = { showRotateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * v2.5.4 Story 1: Format the time remaining until an alarm fires in compact "Xh Ym" format.
 * Examples: "7h 23m", "23m", "45s", "now!"
 */
private fun formatTimeUntil(triggerAtMillis: Long): String {
    val diff = triggerAtMillis - System.currentTimeMillis()
    if (diff <= 0) return "now!"
    val totalSeconds = diff / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}
