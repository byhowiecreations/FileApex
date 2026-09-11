# ProGuard / R8 optimization and shrinking rules for FileApex (Android)

# -----------------------------------------------------------------------------
# Source attributes & Debugging: keep line numbers for Play Console de-obfuscation
# -----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod,*Annotation*,Signature
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# Android Framework, Services, and Receivers
# -----------------------------------------------------------------------------
-keep class com.fileapex.platform.** { *; }
-keep class com.fileapex.service.** { *; }
-keep class com.fileapex.MainActivity { *; }
-keep class com.fileapex.FileApexApplication { *; }

# -----------------------------------------------------------------------------
# Compose Multiplatform & Material3
# -----------------------------------------------------------------------------
-dontwarn androidx.compose.**
-dontwarn org.jetbrains.compose.**
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.runtime.** { *; }

# -----------------------------------------------------------------------------
# Room 3 & SQLite Bundled Driver (JNI)
# -----------------------------------------------------------------------------
-keep class com.fileapex.data.db.** { *; }
-keep class com.fileapex.data.bulletin.** { *; }
-keep @androidx.room3.Database class * { *; }
-keep @androidx.room3.Dao interface * { *; }
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class * implements androidx.room3.RoomDatabaseConstructor { *; }
-keepclassmembers class * extends androidx.room3.RoomDatabase {
    <init>(...);
}
-keepclasseswithmembers class androidx.sqlite.driver.bundled.** { native <methods>; }
-keep class androidx.sqlite.driver.bundled.** { *; }

# -----------------------------------------------------------------------------
# Ktor (Client & Server CIO Engine)
# -----------------------------------------------------------------------------
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.serialization.kotlinx.** { *; }
-keep class io.ktor.serialization.kotlinx.json.** { *; }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keepclassmembers class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider {
    public <init>(...);
}
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.client.cio.** { *; }
-keep class io.ktor.network.selector.** { *; }
-keep class io.ktor.network.sockets.** { *; }
-dontwarn io.ktor.**

# -----------------------------------------------------------------------------
# Kotlinx Serialization
# -----------------------------------------------------------------------------
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keep class kotlinx.serialization.json.** { *; }

# -----------------------------------------------------------------------------
# Kotlin Coroutines
# -----------------------------------------------------------------------------
-keepdirectories META-INF/services
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepclassmembers class * implements kotlinx.coroutines.internal.MainDispatcherFactory {
    public <init>();
}

# -----------------------------------------------------------------------------
# QRose & ZXing
# -----------------------------------------------------------------------------
-dontwarn io.github.alexzhirkevich.qrose.**
-keep class io.github.alexzhirkevich.qrose.** { *; }
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# -----------------------------------------------------------------------------
# BouncyCastle & Cryptography
# -----------------------------------------------------------------------------
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# -----------------------------------------------------------------------------
# AndroidX Share Target & Credentials / Google ID
# -----------------------------------------------------------------------------
-dontwarn androidx.sharetarget.**
-keep class androidx.sharetarget.** { *; }
-dontwarn androidx.credentials.**
-keep class androidx.credentials.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**
-keep class com.google.android.libraries.identity.googleid.** { *; }

# -----------------------------------------------------------------------------
# Shizuku (Standalone/GitHub distribution)
# -----------------------------------------------------------------------------
-dontwarn moe.shizuku.**
-keep class moe.shizuku.** { *; }

# -----------------------------------------------------------------------------
# General Suppression of Missing Optional Platform Dependencies
# -----------------------------------------------------------------------------
-dontwarn org.slf4j.**
-dontwarn java.lang.instrument.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn java.beans.**
