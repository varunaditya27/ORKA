-keep class com.orka.app.OrkaApplication { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.orka.**Hilt* { *; }
-keep class dagger.hilt.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn javax.annotation.**

# LiteRT-LM ships no consumer proguard rules of its own (checked the AAR directly), and it's a
# JNI-backed library — native code resolves its Kotlin/Java classes and callback methods
# (Engine, Conversation, LiteRtLmJni, JniMessageCallback, ...) by exact name. Without this,
# R8 renaming/stripping them in a release build would break on-device Gemma inference silently
# (or crash) despite working fine in debug, where minification is off.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# kotlinx.serialization: the library's own consumer rules only cover its package, not the app's
# own @Serializable DTOs (LlmTaskDraft, LlmTaskSplit, LlmReschedule, LlmSnoozeMinutes,
# LlmClarificationDeadline in data/parser — used to parse every Gemma JSON response). Without
# these, R8 can strip/rename their members in a release build and break Gemma-backed features
# (parsing, split, reschedule, snooze, clarification) while debug builds work fine.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.orka.**$$serializer { *; }
-keepclassmembers class com.orka.** {
    *** Companion;
}
-keepclasseswithmembers class com.orka.** {
    kotlinx.serialization.KSerializer serializer(...);
}
