# Keep rules for the minified release build.
#
# Scope note before adding to this file: v0.5.0 shipped minified with this file
# EMPTY and worked — playback, image loading and every JSON path. So R8 plus the
# consumer rules that ship inside the AARs already covers this app. Anything here
# is either defence in depth or a fix for something observed to break; a rule that
# is neither costs APK size and shrinking for nothing.
#
# In particular, do not re-add blanket `-keep class <library>.** { *; }` rules.
# media3, Coil and OkHttp each ship their own consumer-rules.pro, which R8 applies
# automatically. Keeping those libraries wholesale disables shrinking across the
# largest dependencies in the app to restate rules that were already applied.

# kotlinx.serialization — the generated serializers are reached reflectively
# through the companion, so R8 cannot see the link from the @Serializable class.
# The serialization Gradle plugin contributes rules of its own; these are narrowed
# to this app's models and kept as belt and braces, because a stripped serializer
# does not fail at build time — it fails at runtime, on a user's phone, the first
# time it parses a server response.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.engabd.sendpin.**$$serializer { *; }
-keepclassmembers class com.engabd.sendpin.** {
    *** Companion;
}
-keepclasseswithmembers class com.engabd.sendpin.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp reflects at optional TLS providers that are not on the classpath. These
# silence the build warnings for them; they keep nothing.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn androidx.media3.**
