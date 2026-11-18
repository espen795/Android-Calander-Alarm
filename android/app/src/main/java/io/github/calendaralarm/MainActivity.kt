package io.github.calendaralarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.ui.screens.MainScreen
import io.github.calendaralarm.ui.theme.CalendarAlarmTheme

/**
 * Main entry point.
 *
 * Pull model: no foreground service needed! The app is a client that polls
 * the Calendar Alarm server via HTTPS. WorkManager handles background sync.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // v2.3.0: Refresh widget cache when the app opens
        try {
            AlarmRepository(this).refreshWidget()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Widget refresh failed: ${e.message}")
        }

        // v2.5.7 Story 2: Check battery optimization status
        // If battery optimization is enabled, background sync may be delayed/killed
        checkBatteryOptimization()

        setContent {
            CalendarAlarmTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val notificationLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (!granted) {
                            Toast.makeText(this, "Notifications disabled — alarms may not work fully", Toast.LENGTH_LONG).show()
                        }
                    }

                    LaunchedEffect(Unit) {
                        // Request notification permission on Android 13+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }

                        // v2.6.1: Check full-screen intent permission on Android 14+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            if (!nm.canUseFullScreenIntent()) {
                                AppLogger.w("MainActivity", "Full-screen intent permission NOT granted — alarm may not show full-screen")
                                Toast.makeText(
                                    this@MainActivity,
                                    "⚠️ Enable 'Full screen' for alarms in Settings > Apps > Calendar Alarm",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    MainScreen(
                        onRequestExactAlarmPermission = { openExactAlarmSettings() },
                        onRequestBatteryExemption = { requestBatteryOptimizationExemption() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // v2.3.0: Refresh widget every time the app resumes
        try {
            AlarmRepository(this).refreshWidget()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Widget refresh onResume failed: ${e.message}")
        }
    }

    /**
     * v2.5.7 Story 2: Check if battery optimization is disabled for this app.
     * If enabled, background alarm sync (WorkManager) may be delayed or killed by Doze mode.
     * We log a warning and show a toast suggesting the user disable battery optimization.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    AppLogger.w("MainActivity", "Battery optimization is ENABLED — alarm sync may be delayed")
                    Toast.makeText(
                        this,
                        "⚠️ Battery optimization is ON — alarms may be delayed. Disable it in Settings.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    AppLogger.i("MainActivity", "Battery optimization is disabled — good for alarm reliability")
                }
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "Battery optimization check failed: ${e.message}")
            }
        }
    }

    private fun openExactAlarmSettings() {
        val repo = AlarmRepository(this)
        startActivity(repo.openExactAlarmSettings())
    }

    /**
     * v2.5.7 PM fix MAJOR-04: Request battery optimization exemption.
     * Opens system dialog to whitelist this app from battery optimization.
     * Critical for reliable alarm delivery on Samsung devices.
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                AppLogger.i("MainActivity", "Battery optimization exemption requested")
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "Failed to request battery exemption: ${e.message}")
                // Fallback: open app details settings
                try {
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(fallback)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Open Settings → Apps → Calendar Alarm → Battery → Unrestricted", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
