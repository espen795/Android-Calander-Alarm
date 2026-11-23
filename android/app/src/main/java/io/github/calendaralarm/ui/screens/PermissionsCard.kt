package io.github.calendaralarm.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.PermissionStatus
import io.github.calendaralarm.ui.theme.CalGreen
import io.github.calendaralarm.ui.theme.CalOrange
import io.github.calendaralarm.ui.theme.CalRed

/**
 * v2.7.0: Permission check (moved from the Status tab to Settings).
 *
 * - Recomputed on every ON_RESUME, so the status is correct after returning from the
 *   system settings (previously "DENIED" stayed until a recompose).
 * - Notifications (POST_NOTIFICATIONS) added: without this permission the
 *   full-screen notifications of alarm and timer are silently dropped.
 *
 * Phase 4: [PermissionStatus] now lives in data/ (io.github.calendaralarm.data) so
 * the HeartbeatReporter (worker) can reuse the same source — this file is
 * only the Compose UI.
 */

@Composable
fun PermissionsCard() {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val status = remember(refreshTick) { PermissionStatus.read(context) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusLine("Notifications", status.notifications, critical = true)
            StatusLine("Full screen", status.fullScreen, critical = true)
            StatusLine("Exact alarms", status.exactAlarms, critical = true)
            StatusLine("Display over other apps", status.overlay, critical = false)
            StatusLine("Unrestricted battery", status.batteryUnrestricted, critical = false)

            Spacer(modifier = Modifier.height(8.dp))

            if (!status.notifications) {
                FixButton("Allow notifications", CalRed) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            }
            if (!status.fullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                FixButton("Enable 'Full screen'", CalRed) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                }
            }
            if (!status.exactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                FixButton("Allow exact alarms", CalRed) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                }
            }
            if (!status.overlay) {
                FixButton("Enable 'Display over other apps'", CalOrange) {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                }
            }
            if (!status.batteryUnrestricted) {
                FixButton("Disable battery optimization", CalOrange) {
                    @Suppress("BatteryLife")
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                }
            }

            if (status.allOk) {
                Text(
                    "✅ All permissions OK for alarm and timer",
                    color = CalGreen,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, critical: Boolean) {
    val (text, color) = when {
        ok -> "✅ $label" to CalGreen
        critical -> "❌ $label (critical)" to CalRed
        else -> "⚠️ $label" to CalOrange
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FixButton(label: String, color: androidx.compose.ui.graphics.Color, intent: () -> Intent) {
    val context = LocalContext.current
    Button(
        onClick = {
            try {
                context.startActivity(intent())
            } catch (e: Exception) {
                AppLogger.w("PermissionsCard", "Could not open settings ($label): ${e.message}")
                // Fallback: app-info page
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(label)
    }
}
