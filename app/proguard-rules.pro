# WebRTC Proguard rules
-keep class org.webrtc.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Android Keystore and Crypto
-keepclassmembers class * extends java.security.Key { *; }
