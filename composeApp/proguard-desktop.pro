# Used by Mac desktop release. Windows release has ProGuard disabled.
-dontwarn **

-dontwarn io.github.alexzhirkevich.qrose.**
-keep class io.github.alexzhirkevich.qrose.** { *; }

-dontwarn com.jetbrains.SharedTextures
-dontwarn org.jetbrains.skiko.swing.JbrSharedTexturesAdapter

-dontwarn org.jetbrains.skia.**
-keep class org.jetbrains.skia.** { *; }
-keep class androidx.compose.ui.graphics.** { *; }

-dontoptimize

# Room *_Impl is KSP-generated; shrinker drops it otherwise.
-keep class com.fileapex.data.db.** { *; }
-keep @androidx.room3.Database class * { *; }
-keep @androidx.room3.Dao interface * { *; }
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class * implements androidx.room3.RoomDatabaseConstructor { *; }
-keepclassmembers class * extends androidx.room3.RoomDatabase {
    <init>(...);
}

# sqlite-bundled JNI registers natives by exact name.
-keepclasseswithmembers class androidx.sqlite.driver.bundled.** { native <methods>; }
-keep class androidx.sqlite.driver.bundled.** { *; }

-keepdirectories META-INF/services
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class kotlinx.coroutines.swing.** { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepclassmembers class * implements kotlinx.coroutines.internal.MainDispatcherFactory {
    public <init>();
}

-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.serialization.kotlinx.** { *; }
-keep class io.ktor.serialization.kotlinx.json.** { *; }

-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keepclassmembers class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider {
    public <init>(...);
}
