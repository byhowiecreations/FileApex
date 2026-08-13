# Disable bytecode preverification on desktop to prevent IncompleteClassHierarchyException on optional types
-dontpreverify

# Ignore unresolved references for optional third-party/Android/server dependencies
-dontwarn **

# qrose QR draws via Compose/Skia shader APIs; signatures drift across Compose/Skiko versions.
-dontwarn io.github.alexzhirkevich.qrose.**
-keep class io.github.alexzhirkevich.qrose.** { *; }

# Optional Skiko IntelliJ bridge not present on the packaged classpath.
-dontwarn com.jetbrains.SharedTextures
-dontwarn org.jetbrains.skiko.swing.JbrSharedTexturesAdapter

# Skia/Compose graphics interop referenced from libraries.
-dontwarn org.jetbrains.skia.**
-keep class org.jetbrains.skia.** { *; }
-keep class androidx.compose.ui.graphics.** { *; }

# Avoid over-aggressive optimization breaking kotlinx/Compose runtime (CMP #4391).
-dontoptimize

# Room KSP output — shrinker drops *_Impl unless kept; @ConstructedBy resolves at runtime.
-keep class com.fileapex.data.db.** { *; }
-keep @androidx.room3.Database class * { *; }
-keep @androidx.room3.Dao interface * { *; }
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class * implements androidx.room3.RoomDatabaseConstructor { *; }
-keepclassmembers class * extends androidx.room3.RoomDatabase {
    <init>(...);
}

# sqlite-bundled JNI — native methods are registered by exact name; obfuscation breaks load.
-keepclasseswithmembers class androidx.sqlite.driver.bundled.** { native <methods>; }
-keep class androidx.sqlite.driver.bundled.** { *; }

# Preserve ServiceLoader implementations for kotlinx-coroutines (MainDispatcherFactory & Swing)
-keepdirectories META-INF/services
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class kotlinx.coroutines.swing.** { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepclassmembers class * implements kotlinx.coroutines.internal.MainDispatcherFactory {
    public <init>();
}

# Preserve ServiceLoader implementations for Ktor & kotlinx serialization
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.serialization.kotlinx.** { *; }
-keep class io.ktor.serialization.kotlinx.json.** { *; }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keepclassmembers class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider {
    public <init>(...);
}


