# ---------------------------------------------------------------------------
# SuStream release keep rules
# ---------------------------------------------------------------------------

# kotlinx.serialization: the compiler plugin generates serializers as companion
# objects and $$serializer classes reached only reflectively by the runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sustream.tv.**$$serializer { *; }
-keepclassmembers class com.sustream.tv.** {
    *** Companion;
}

# Retrofit interfaces are proxied at runtime; keep their signatures and annotations.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepattributes Signature, Exceptions
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <1>

# OkHttp / Okio ship their own rules; these silence platform-only references.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 reflectively instantiates decoder extensions that we do not bundle.
-dontwarn androidx.media3.decoder.**
-dontwarn androidx.media3.exoplayer.ext.**

# Room generated implementations are referenced by name.
-keep class com.sustream.tv.data.local.SuStreamDatabase_Impl { *; }

# Keep enum values used by name in persisted data.
-keepclassmembers enum com.sustream.tv.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
