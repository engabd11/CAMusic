# kotlinx.serialization — R8 strips the generated serializers if not kept.
# The @Serializable classes carry the serializer as a companion getter;
# R8 needs to see the companion and the generated serializer class.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.engabd.sendpin.**$$serializer { *; }
-keepclassmembers class com.engabd.sendpin.** {
    *** Companion;
}
-keepclasseswithmembers class com.engabd.sendpin.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp uses reflection for platform-specific TLS setup.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coil loads image fetchers via reflection.
-keep class coil coil.** { *; }

# ExoPlayer / media3 — reflection-heavy for decoder discovery.
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Compose — keep the Composable function annotations.
-keep @androidx.compose.runtime.Composable class * { *; }