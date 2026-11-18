# ProGuard / R8 rules for Calendar Alarm
# H-08 fix: Narrow keeps — only what Kotlinx Serialization actually needs.
# No blanket -keep for data classes, Ktor, or coroutines.

# ==================== WIDGET PROVIDER (v2.3.0) ====================
# KRITISCH: AppWidgetProvider subclasses MOETEN behouden blijven.
# Het manifest verwijst naar de class naam — als R8 deze hernoemt, faalt de widget.
-keep class io.github.calendaralarm.receiver.AlarmWidgetProvider { *; }
-keep class io.github.calendaralarm.receiver.AlarmWidgetProvider$* { *; }

# v2.3.0: WidgetCache — gebruikt door widget, moet behouden blijven
-keep class io.github.calendaralarm.data.WidgetCache { *; }
-keep class io.github.calendaralarm.data.WidgetCache$* { *; }

# Algemene regel: alle AppWidgetProvider subclasses behouden
-keep public class * extends android.appwidget.AppWidgetProvider { *; }

# BroadcastReceiver subclasses die in manifest staan (extra zekerheid)
-keep class io.github.calendaralarm.receiver.OwnedAlarmReceiver { *; }
-keep class io.github.calendaralarm.receiver.PreAlarmReceiver { *; }
-keep class io.github.calendaralarm.service.BootReceiver { *; }

# ==================== KOTLIN SERIALIZATION ====================
# Keep Kotlin serialization generated serializers (REQUIRED for JSON)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class io.github.calendaralarm.data.**$$serializer { *; }
-keepclassmembers class io.github.calendaralarm.data.** { *** Companion; }
-keepclasseswithmembers class io.github.calendaralarm.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the AlarmStore.OwnedAlarm for serialization (used in JSON persistence)
-keep,includedescriptorclasses class io.github.calendaralarm.data.AlarmStore$OwnedAlarm$$serializer { *; }
-keepclassmembers class io.github.calendaralarm.data.AlarmStore$OwnedAlarm { *** Companion; }
-keepclasseswithmembers class io.github.calendaralarm.data.AlarmStore$OwnedAlarm {
    kotlinx.serialization.KSerializer serializer(...);
}

# ==================== KTOR ====================
# Ktor server needs some reflection for routing — keep only the routing DSL entry points
# (narrower than keeping all of io.ktor.**)
-keep class io.ktor.server.routing.** { *; }

# ==================== MISSING CLASSES (dontwarn) ====================
# Missing javax.annotation classes (from Tink/Security crypto)
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.checkerframework.**

# Tink (used by security-crypto)
-dontwarn com.google.crypto.tink.**

# Conscrypt / network security
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# SLF4J (used by Ktor/Netty)
-dontwarn org.slf4j.**

# Reactor BlockHound (referenced by Netty, not used at runtime)
-dontwarn reactor.blockhound.**

# Netty optional dependencies (not used at runtime on Android)
-dontwarn io.netty.internal.tcnative.**
-dontwarn io.netty.handler.ssl.**
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.channel.kqueue.**
-dontwarn io.netty.channel.unix.**
-dontwarn io.netty.util.internal.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
-dontwarn io.ktor.util.**
-dontwarn io.ktor.http.**
-dontwarn io.ktor.events.**
-dontwarn io.ktor.network.**
-dontwarn io.ktor.utils.**
-dontwarn io.netty.**
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.atomicfu.**

# ==================== APP LOGGER ====================
# AppLogger wordt gebruikt door widget — keep voor in-app log viewer
-keep class io.github.calendaralarm.data.AppLogger { *; }
-keep class io.github.calendaralarm.data.AppLogger$* { *; }

# ==================== LOGGING ====================
# H-07 fix: Strip logging in release (not just d/v/i)
# LET OP: Widget debug logging gebruikt AppLogger (in-memory) + Log (gestript in release)
# Voor widget debugging in release: gebruik de in-app Logs viewer (AppLogger)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
