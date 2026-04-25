# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink / errorprone / google-http-client / joda (transitive via androidx.security:security-crypto)
# These optional dependencies aren't on the Android classpath but Tink references them
# behind feature gates we don't use.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sysadmin.lasstore.**$$serializer { *; }
-keepclassmembers class com.sysadmin.lasstore.** {
    *** Companion;
}
-keepclasseswithmembers class com.sysadmin.lasstore.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose
-keep class androidx.compose.runtime.** { *; }
