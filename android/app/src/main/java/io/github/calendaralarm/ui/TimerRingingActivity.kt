package io.github.calendaralarm.ui

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
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.calendaralarm.data.AppLogger
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.calendaralarm.service.TimerRingService
import io.github.calendaralarm.ui.theme.CalendarAlarmTheme
import io.github.calendaralarm.ui.theme.CalRed

private const val TAG = "TimerRingingActivity"

/**
 * v2.5.3 Story 5: Full-screen timer ringing activity.
 *
 * Shows when the countdown timer finishes:
 * - Full-screen, over lock screen, turns screen on
 * - Shows "00:00", "⏰ Timer finished", and a large "Stop" button
 * - Stop button stops TimerAlarmHelper and finishes the activity
 * - Back button also stops the alarm
 * - FLAG_KEEP_SCREEN_ON while visible
 */
class TimerRingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.i(TAG, "TimerRingingActivity onCreate")

        // v2.6.4: Samsung-specific full-screen setup (same as AlarmRingingActivity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // v2.7.0: NO requestDismissKeyguard — that asks for unlocking (same fix as
            // AlarmRingingActivity v2.6.10). Showing over the lock screen is enough.
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )

        // Hide system UI for true full-screen (immersive mode)
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

        // v2.5.3 PM fix M-01: FLAG_SECURE — consistent met AlarmRingingActivity (M-08)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // v2.7.0: No ghost screen — when nothing is ringing (anymore), close directly.
        if (!TimerRingService.ringing.value) {
            AppLogger.i(TAG, "No active timer alarm — activity closes directly")
            finish()
            return
        }

        // v2.7.0: back = stop (via dispatcher instead of deprecated onBackPressed)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AppLogger.i(TAG, "Back — stop timer")
                stopAndFinish()
            }
        })

        // v2.7.0: close as soon as ringing stops elsewhere (notification action, power button)
        lifecycleScope.launch {
            TimerRingService.ringing.collect { ringing ->
                if (!ringing && !isFinishing) {
                    AppLogger.i(TAG, "Ringing stopped — activity closes")
                    finish()
                }
            }
        }

        setContent {
            CalendarAlarmTheme(darkTheme = true) {
                TimerRingingScreen(
                    onStop = { stopAndFinish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // v2.6.4: Re-apply full-screen on resume (Samsung sometimes loses it)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
    }

    /**
     * v2.6.4: dispatchKeyEvent — Samsung's preferred key handling method.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        AppLogger.i(TAG, "dispatchKeyEvent: action=$action keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")

        // v2.7.0: volume-DOWN consumed so the button does not change the volume
        if (action == KeyEvent.ACTION_DOWN &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }

        if (action == KeyEvent.ACTION_UP) {
            // Any key stops the timer (except back which has its own handler)
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                AppLogger.i(TAG, "Key UP (dispatch, keyCode=$keyCode) — stopping timer")
                stopAndFinish()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        // v2.7.0: onDestroy no longer stops the sound. Ringing is owned by
        // TimerRingService and only stops on an explicit action. Previously a
        // second launch / rotation / system recreate of this activity stopped the sound silently.
        AppLogger.i(TAG, "TimerRingingActivity onDestroy (ringing left untouched)")
        super.onDestroy()
    }

    private fun stopAndFinish() {
        AppLogger.i(TAG, "Stop — timer alarm off")
        TimerRingService.stop(applicationContext)
        finish()
    }
}

@Composable
fun TimerRingingScreen(
    onStop: () -> Unit
) {
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
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = CalRed
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "00:00",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "⏰ Timer finished",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF8E8E93)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Large stop button
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = CalRed),
                modifier = Modifier
                    .padding(8.dp)
                    .height(64.dp)
            ) {
                Text("Stop", fontSize = 22.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Or press a volume or power button",
                fontSize = 12.sp,
                color = Color(0xFF8E8E93).copy(alpha = 0.7f)
            )
        }
    }
}
