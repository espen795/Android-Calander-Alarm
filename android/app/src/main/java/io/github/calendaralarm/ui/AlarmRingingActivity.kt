package io.github.calendaralarm.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.receiver.OwnedAlarmReceiver
import io.github.calendaralarm.ui.theme.CalendarAlarmTheme
import io.github.calendaralarm.ui.theme.CalRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen alarm ringing activity.
 * Shows over the lock screen, with snooze and dismiss actions.
 *
 * v2.5.0 changes:
 * - Snooze button hidden when snooze is disabled (settings = 0/"Off")
 * - Power button dismisses alarm (or snoozes if snooze is enabled)
 * - Per-alarm snooze override support
 */
class AlarmRingingActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AlarmRingingActivity"
    }

    private var alarmId: Int = -1

    // v2.5.0: Track if snooze is available (settings or per-alarm override)
    private var snoozeAvailable: Boolean = true
    private var snoozeMinutes: Int = 5

    // v2.7.0: one volume-button press = action (setting). Short guard after opening against
    // accidental presses that still date from before the alarm fired.
    private var createdAt = 0L
    private var hardwareHandled = false

    // v2.6.13 H2: true once the user activated snooze/dismiss — onDestroy
    // must then NOT stop the service (the receiver already does that).
    private var actionFinished = false

    // v2.6.13 H2: back gesture = snooze (if snooze available) or dismiss — NEVER
    // silently stop the alarm via onDestroy.
    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            AppLogger.i(TAG, "v2.6.13 H2: back-gesture — snooze=${snoozeAvailable}")
            if (snoozeAvailable) snoozeAndFinish() else dismissAndFinish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v2.6.10: Simplified — ONLY showWhenLocked + turnScreenOn
        // v2.6.10 FIX: requestDismissKeyguard() REMOVED — this can trigger an auth screen
        // "Show over lockscreen" != "Dismiss keyguard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // M-08 fix: FLAG_SECURE — prevent screenshots of the alarm screen
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        alarmId = intent.getIntExtra(AlarmRepository.EXTRA_ALARM_ID, -1)
        createdAt = android.os.SystemClock.elapsedRealtime()

        // v2.5.0: Read snooze settings (per-alarm override takes precedence)
        // B2 (review): via settingsRepository — snooze_minutes is written there.
        // After B1 the regular prefs live in device-protected storage, so this is also
        // readable before the first unlock (directBootAware).
        val globalSnooze = try {
            io.github.calendaralarm.CalendarAlarmApp.settingsRepository.getSnoozeMinutes()
        } catch (e: Exception) { 5 }

        // Check for per-alarm snooze override
        val repo = AlarmRepository(this)
        val alarm = repo.store.get(alarmId)
        val perAlarmSnooze = alarm?.snoozeMinutesOverride // null = use global setting

        snoozeMinutes = perAlarmSnooze ?: globalSnooze
        snoozeAvailable = snoozeMinutes > 0

        // v2.6.13 H2: register the back callback (back = snooze or dismiss)
        onBackPressedDispatcher.addCallback(this, backCallback)

        setContent {
            CalendarAlarmTheme(darkTheme = true) {
                AlarmRingingScreen(
                    alarmId = alarmId,
                    snoozeAvailable = snoozeAvailable,
                    snoozeMinutes = snoozeMinutes,
                    onSnooze = { snoozeAndFinish() },
                    onDismiss = { dismissAndFinish() }
                )
            }
        }
    }

    /**
     * v2.6.10: Simplified key handling — ONLY dispatchKeyEvent
     * v2.6.10 FIX (GPT Sol): onKeyDown/onKeyUp removed — caused double counting
     * v2.6.10 FIX (GPT Sol): Power button does NOT work on Android 15 (system intercepts)
     * Only volume buttons as hardware fallback (3x within 2 sec = dismiss)
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        AppLogger.i(TAG, "dispatchKeyEvent: action=$action keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")

        // v2.7.0: Volume — one press = dismiss or snooze (setting "Hardware buttons").
        // Previously 3x within 2s: unreliable half-asleep and not discoverable.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                handleHardwareButton("volume")
            }
            return true // Consume both UP and DOWN so the volume does not change
        }

        // Power button: log only (not reliable on Android 15 — system intercepts)
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            AppLogger.i(TAG, "Power button event (action=$action) — possibly intercepted by system")
            // Do NOT consume — let the system turn the screen off where applicable
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * v2.6.10: onKeyDown/onKeyUp REMOVED (GPT Sol analysis)
     * These caused double counting of volume buttons (dispatchKeyEvent + onKeyDown)
     * and conflicted with dispatchKeyEvent.
     */

    /**
     * v2.7.0: Hardware button → snooze or dismiss according to setting (same rule as the
     * power button in AlarmForegroundService).
     */
    private fun handleHardwareButton(source: String) {
        if (hardwareHandled) return
        val sinceOpen = android.os.SystemClock.elapsedRealtime() - createdAt
        if (sinceOpen < 800) {
            AppLogger.i(TAG, "Hardware button ($source) within guard (${sinceOpen}ms) — ignored")
            return
        }
        hardwareHandled = true
        val action = OwnedAlarmReceiver.hardwareButtonAction(this, alarmId)
        AppLogger.i(TAG, "Hardware button ($source) → $action")
        if (action == OwnedAlarmReceiver.ACTION_SNOOZE) snoozeAndFinish() else dismissAndFinish()
    }

    override fun onResume() {
        super.onResume()
        // v2.6.10 FIX (GPT Sol): NO requestDismissKeyguard() — this can trigger an auth screen
        // "Show over lockscreen" leaves the device locked; "dismiss keyguard" asks for unlock
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Re-apply immersive mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }

        AppLogger.i(TAG, "onResume: full-screen re-applied (without keyguard dismiss)")
    }

    override fun onDestroy() {
        // v2.6.13 H2 FIX: onDestroy stops the alarm service ONLY on an explicit
        // user action (snoozeAndFinish/dismissAndFinish already called finish() + broadcast
        // — those stop the service themselves in the receiver). On rotation, dark-mode change,
        // font/density change or back gesture (isChangingConfigurations=true) the
        // service must NOT stop: the alarm must keep ringing. Otherwise the sound
        // stops silently without snooze/dismiss.
        if (!isChangingConfigurations && !actionFinished) {
            // Safety net: activity destroyed without explicit action and no config change
            // (e.g. system kill) — stop the service as a safety net.
            io.github.calendaralarm.service.AlarmForegroundService.stop(this)
        } else {
            AppLogger.i(TAG, "v2.6.13 H2 onDestroy: isChangingConfigurations=${isChangingConfigurations}, actionFinished=$actionFinished — service NOT stopped")
        }
        super.onDestroy()
    }

    private fun snoozeAndFinish() {
        actionFinished = true  // v2.6.13 H2: explicit action — onDestroy must not stop the service
        // v2.7.0: stop sound immediately; not dependent on the broadcast round trip
        io.github.calendaralarm.service.AlarmForegroundService.stop(this)
        if (alarmId != -1) {
            val intent = Intent(this, OwnedAlarmReceiver::class.java).apply {
                action = OwnedAlarmReceiver.ACTION_SNOOZE
                putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            }
            sendBroadcast(intent)
        }
        finish()
    }

    private fun dismissAndFinish() {
        actionFinished = true  // v2.6.13 H2: explicit action — onDestroy must not stop the service
        // v2.7.0: stop sound immediately — also when alarmId is missing (previously the
        // alarm kept going: only finish(), and onDestroy stopped nothing because of actionFinished)
        io.github.calendaralarm.service.AlarmForegroundService.stop(this)
        if (alarmId != -1) {
            val intent = Intent(this, OwnedAlarmReceiver::class.java).apply {
                action = OwnedAlarmReceiver.ACTION_DISMISS
                putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            }
            sendBroadcast(intent)
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hardwareHandled = false
        val newId = intent.getIntExtra(AlarmRepository.EXTRA_ALARM_ID, -1)
        if (newId != -1 && newId != alarmId) {
            alarmId = newId
        }
    }
}

@Composable
fun AlarmRingingScreen(
    alarmId: Int,
    snoozeAvailable: Boolean = true,
    snoozeMinutes: Int = 5,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { AlarmRepository(context) }
    val alarm = remember { repo.store.get(alarmId) }

    var currentTime by remember { mutableStateOf(getCurrentTime()) }

    // Update clock every second
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTime = getCurrentTime()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Alarm,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = CalRed
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = currentTime,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = alarm?.label ?: "Calendar Alarm",
                fontSize = 24.sp,
                color = Color(0xFF8E8E93)
            )

            if (alarm != null) {
                Text(
                    text = "Expected: ${alarm.timeLabel} — ${alarm.date}",
                    fontSize = 16.sp,
                    color = Color(0xFF8E8E93)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // v2.5.0: Snooze button — only shown when snooze is available
            if (snoozeAvailable) {
                Button(
                    onClick = onSnooze,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF)),
                    modifier = Modifier
                        .padding(8.dp)
                        .height(56.dp)
                ) {
                    Text("Snooze ($snoozeMinutes min)", fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Dismiss button — always shown
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CalRed),
                modifier = Modifier
                    .padding(8.dp)
                    .height(56.dp)
            ) {
                Text("Dismiss", fontSize = 18.sp)
            }

            // v2.6.10 FIX (GPT Sol): Hint text adjusted — Power button does NOT work on Android 15
            // (system intercepts KEYCODE_POWER before dispatch to the app)
            Spacer(modifier = Modifier.height(16.dp))
            val hwAction = remember { OwnedAlarmReceiver.hardwareButtonAction(context, alarmId) }
            Text(
                text = if (hwAction == OwnedAlarmReceiver.ACTION_SNOOZE) {
                    "Volume or power button = snooze"
                } else {
                    "Volume or power button = dismiss"
                },
                fontSize = 12.sp,
                color = Color(0xFF8E8E93).copy(alpha = 0.7f)
            )
        }
    }
}

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
