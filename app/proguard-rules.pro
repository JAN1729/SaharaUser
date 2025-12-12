# ===========================================
# PROGUARD RULES - SECURITY FOCUSED
# ===========================================

# ==================== SECURITY ====================

# Keep BuildConfig but obfuscate field names (doesn't hide values, but makes analysis harder)
-keepclassmembers class com.sahara.user.BuildConfig {
    public static ** *;
}

# Keep SecureConfig
-keep class com.sahara.user.config.SecureConfig { *; }

# ==================== KOTLIN SERIALIZATION ====================

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep model classes used with serialization
-keep,includedescriptorclasses class com.sahara.user.data.model.**$$serializer { *; }
-keepclassmembers class com.sahara.user.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.sahara.user.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep agent data classes
-keep,includedescriptorclasses class com.sahara.user.agents.**$$serializer { *; }
-keepclassmembers class com.sahara.user.agents.** {
    *** Companion;
}

# ==================== KTOR ====================

-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# ==================== SUPABASE ====================

-keep class io.github.jan.supabase.** { *; }
-keepclassmembers class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**

# ==================== HILT ====================

-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ==================== COROUTINES ====================

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ==================== COMPOSE ====================

-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ==================== SECURITY CRYPTO ====================

-keep class androidx.security.crypto.** { *; }
-keepclassmembers class androidx.security.crypto.** { *; }

# ==================== GENERAL ====================

# Keep line numbers for stack traces
-keepattributes SourceFile,LineNumberTable

# Hide original source file name (security through obscurity)
-renamesourcefileattribute SourceFile

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
