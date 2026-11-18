package io.github.calendaralarm.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import io.github.calendaralarm.BuildConfig
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.R
import io.github.calendaralarm.data.UpdateChecker

/**
 * Downloads and verifies an APK update, then posts a notification that the user
 * taps to install.
 *
 * H-06 fix: All download/install work happens in a WorkManager job, not in an
 * unowned coroutine launched from an API handler. The install intent is only
 * triggered by a user tap on the notification (never auto-launched from background).
 *
 * C-03 fix: The APK is verified (SHA-256 + signing certificate) BEFORE the
 * install notification is posted.
 */
class DownloadVerifiedUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadUpdateWorker"
        const val WORK_NAME = "calalarm_verified_update_download"
        private const val NOTIFICATION_ID_INSTALL = 4100  // C-01 fix: was 4001 (collision with NOTIFICATION_ID_RINGING)
        private const val NOTIFICATION_ID_DOWNLOADING = 4101   // v2.5.9: "Download started" ongoing notification
        private const val NOTIFICATION_ID_DOWNLOAD_FAILED = 4102 // v2.5.9: "Download failed" notification
        const val KEY_APK_PATH = "apk_path"       // v2.5.4 PM fix C-01: for UI install intent
        const val KEY_APK_VERSION = "apk_version"  // v2.5.4 PM fix C-01
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting verified update download")
        postDownloadStartedNotification()  // v2.5.9: Show "Download started"

        val updateChecker = UpdateChecker(applicationContext)

        return try {
            // Step 1: Check for update
            val checkResult = updateChecker.checkForUpdate()
            if (checkResult.error != null) {
                Log.w(TAG, "Update check failed")
                return Result.retry()
            }
            if (!checkResult.updateAvailable || checkResult.downloadUrl == null) {
                Log.i(TAG, "No update available")
                return Result.success()
            }

            // Step 2: Download with SHA-256 verification
            val apkFile = updateChecker.downloadUpdate(
                checkResult.downloadUrl,
                checkResult.sha256 ?: "",
                checkResult.size
            )
            if (apkFile == null) {
                Log.e(TAG, "Download or checksum verification failed")
                postDownloadFailedNotification("Download or verification failed")  // v2.5.9
                return Result.retry()
            }

            // Step 3: C-03 — Verify APK signing certificate
            if (!updateChecker.verifyApkSignature(apkFile)) {
                Log.e(TAG, "APK signature verification FAILED — deleting file")
                apkFile.delete()
                postDownloadFailedNotification("APK verification failed")  // v2.5.9
                return Result.failure()
            }

            Log.i(TAG, "APK verified: ${apkFile.absolutePath}")

            // v2.5.4 PM fix C-01: Save APK path in output data so the UI can start the install intent
            val outputData = Data.Builder()
                .putString(KEY_APK_PATH, apkFile.absolutePath)
                .putString(KEY_APK_VERSION, checkResult.latestVersion ?: "?")
                .build()

            // Step 4: Post notification for user to tap and install
            postInstallNotification(apkFile, checkResult.latestVersion ?: "?")

            Result.success(outputData)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Update download worker failed")
            Result.retry()
        } finally {
            updateChecker.close()
        }
    }

    // v2.5.9: Show ongoing "Download started" notification while downloading
    private fun postDownloadStartedNotification() {
        val notification = NotificationCompat.Builder(applicationContext, CalendarAlarmApp.CHANNEL_UPDATES)
            .setContentTitle("Update download started")
            .setContentText("Calendar Alarm is being downloaded…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)  // Can't swipe away while downloading
            .setProgress(0, 0, true)  // Indeterminate progress
            .setPriority(NotificationCompat.PRIORITY_LOW)  // Not urgent yet
            .build()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID_DOWNLOADING, notification)
    }

    // v2.5.9: Show "Download failed" notification on failure
    private fun postDownloadFailedNotification(reason: String) {
        // Cancel the "downloading" notification
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(NOTIFICATION_ID_DOWNLOADING)

        val notification = NotificationCompat.Builder(applicationContext, CalendarAlarmApp.CHANNEL_UPDATES)
            .setContentTitle("Update download failed")
            .setContentText(reason)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIFICATION_ID_DOWNLOAD_FAILED, notification)
    }

    private fun postInstallNotification(apkFile: java.io.File, version: String) {
        // v2.5.9: Cancel the "downloading" notification
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(NOTIFICATION_ID_DOWNLOADING)

        val uri = FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            100,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CalendarAlarmApp.CHANNEL_UPDATES)
            .setContentTitle("Calendar Alarm update ready")
            .setContentText("Version $version is verified. Tap to install.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID_INSTALL, notification)
    }
}
