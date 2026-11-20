# Sotto ProGuard Rules

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sotto.app.**$$serializer { *; }
-keepclassmembers class com.sotto.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.sotto.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Crypto - keep all crypto engine implementations
-keep class com.sotto.app.crypto.** { *; }

# Bouncy Castle - algorithms loaded via JCA reflection (Ed25519, X25519, ML-KEM)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# WebRTC - native JNI callbacks require all classes to remain unobfuscated
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# The voice leveler's bridge: WebRTC's playback calls it from native code (ADR-23)
-keep class com.sotto.app.call.PlaybackProcessing { *; }

# jni_zero - required by WebRTC native library (JNI_OnLoad references org.jni_zero.JniInit)
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
