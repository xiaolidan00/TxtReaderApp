# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Android/Sdk/gradle/default.properties.

# R8 rules for Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# R8 rules for Room
-keep class com.xld.txtreader.data.db.** { *; }
-keepclassmembers class com.xld.txtreader.data.db.** { *; }

# Keep model classes
-keep class com.xld.txtreader.core.** { *; }

# Keep LiveData and Flow observers
-keepclassmembers class * {
    @androidx.lifecycle.* <methods>;
}

# Keep annotations for reflection
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations

# Keep event emitter and TTS classes
-keep class com.xld.txtreader.tts.** { *; }
-keep class com.xld.txtreader.eventEmitter { *; }