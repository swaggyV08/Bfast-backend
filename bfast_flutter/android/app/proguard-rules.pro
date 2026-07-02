# Flutter / Dart VM bridge
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-dontwarn io.flutter.**

# BFast native bridge
-keep class com.bfast.** { *; }

# Kotlin reflection (used by Kotlin coroutines and some plugins)
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# flutter_blue_plus BLE
-keep class com.boskokg.flutter_blue_plus.** { *; }

# flutter_secure_storage
-keep class com.it_nomads.fluttersecurestorage.** { *; }

# Gson / JSON model classes — keep field names intact for Dio deserialization
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp (used under Dio on Android)
-dontwarn okhttp3.**
-dontwarn okio.**

# General: keep all classes with native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
