# kotlinx.serialization keeps its generated serializers via @Serializable companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.ani.assistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.ani.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ani.assistant.**$$serializer { *; }

# Services and receivers are constructed by the platform, never by our code.
-keep class com.ani.assistant.voice.AniVoiceService { *; }
-keep class com.ani.assistant.notifications.AniNotificationListenerService { *; }
-keep class com.ani.assistant.platform.alarm.ReminderReceiver { *; }
-keep class com.ani.assistant.platform.alarm.BootReceiver { *; }

# OkHttp ships with optional dependencies it guards with reflection.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
