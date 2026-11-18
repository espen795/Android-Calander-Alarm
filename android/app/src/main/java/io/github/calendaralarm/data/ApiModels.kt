package io.github.calendaralarm.data

import kotlinx.serialization.Serializable

// ============== Server-side version.json model (for updater) ==============

@Serializable
data class VersionInfo(
    val version: String,
    val versionCode: Int,
    val url: String,
    val changelog: String = "",
    val minSdk: Int = 26,
    val sha256: String = "",       // SHA-256 of the APK file (REQUIRED)
    val size: Long = 0,            // APK file size in bytes (REQUIRED)
    val releasedAt: String = ""
)

// ============== App Settings ==============

data class AlarmSettings(
    val officeWakeTime: String = "07:00",
    val homeWakeTime: String = "08:30",
    val offWakeTime: String = "09:00",
    val officeDays: List<Int> = listOf(1, 2, 3, 4, 5),  // Mon-Fri
    val homeDays: List<Int> = listOf(1, 2, 3, 4, 5),
    val offDays: List<Int> = listOf(6, 7),               // Sat-Sun
    val updateCheckIntervalHours: Int = 6,
    val autoUpdateEnabled: Boolean = false,
    val snoozeMinutes: Int = 5,  // F6: configurable snooze duration (0=off, 5/10/15/30)
    // v2.5.7 Story 3: Alarm Freeze — block alarm changes during configured hours
    val wakkerFreezeEnabled: Boolean = false,
    val wakkerFreezeStart: String = "22:00",  // "HH:mm" 24-hour format
    val wakkerFreezeEnd: String = "07:00"     // "HH:mm" 24-hour format
) {
    fun isValid(): Boolean {
        val timeRegex = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        return listOf(officeWakeTime, homeWakeTime, offWakeTime).all { timeRegex.matches(it) } &&
            listOf(officeDays, homeDays, offDays).all { days ->
                days.size <= 7 && days.distinct().size == days.size && days.all { it in 1..7 }
            } &&
            updateCheckIntervalHours in 1..168 &&
            snoozeMinutes in listOf(0, 5, 10, 15, 30) &&  // v2.5.0: 0 = off
            // v2.5.7 Story 3: Validate freeze times (HH:mm format)
            timeRegex.matches(wakkerFreezeStart) &&
            timeRegex.matches(wakkerFreezeEnd)
    }
}

enum class WorkLocation(val value: String) {
    OFFICE("office"),
    HOME("home"),
    OFF("off");

    companion object {
        fun fromString(s: String): WorkLocation? = entries.find { it.value == s.lowercase() }
    }
}
