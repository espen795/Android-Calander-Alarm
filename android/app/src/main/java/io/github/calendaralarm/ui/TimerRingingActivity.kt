package io.github.calendaralarm.ui

import android.app.KeyguardManager
import android.content.Context
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
import io.github.calendaralarm.data.TimerAlarmHelper
import io.github.calendaralarm.ui.theme.CalendarAlarmTheme
import io.github.calendaralarm.ui.theme.CalRed

private const val TAG = "TimerRingingActivity"

/**
 * v2.5.3 Story 5: Full-screen timer ringing activity.
 *
 * Shows when the countdown timer finishes:
 * - Full-screen, over lock screen, turns screen on
 * - Shows "00:00", "⏰ Timer afgelopen", and a large "Stoppen" button
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
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    AppLogger.i(TAG, "Timer keyguard dismissed successfully")
                }
            })
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
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

    /**
     * v2.5.3: Back button = stop alarm (same as pressing Stoppen).
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        AppLogger.i(TAG, "Back pressed — stopping timer alarm")
        stopAndFinish()
    }

    override fun onDestroy() {
        // Safety: stop alarm if activity is destroyed without explicit action
        AppLogger.i(TAG, "TimerRingingActivity onDestroy")
        TimerAlarmHelper.stopSharedAlarm()
        super.onDestroy()
    }

    private fun stopAndFinish() {
        AppLogger.i(TAG, "Stop button pressed — stopping timer alarm")
        TimerAlarmHelper.stopSharedAlarm()
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
                text = "⏰ Timer afgelopen",
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
                Text("Stoppen", fontSize = 22.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Or press the back button to stop",
                fontSize = 12.sp,
                color = Color(0xFF8E8E93).copy(alpha = 0.7f)
            )
        }
    }
}
