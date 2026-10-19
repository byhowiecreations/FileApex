# R8 rules for FileApex (Android). Libraries that ship their own consumer rules (Compose, kotlinx.serialization,
# credentials) are not repeated here; only what is loaded by name or from native code is kept.

-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod,*Annotation*,Signature
-renamesourcefileattribute SourceFile

# Manifest components are kept by AGP; these two are also referenced from code.
-keep class com.fileapex.MainActivity { *; }
-keep class com.fileapex.FileApexApplication { *; }

-dontwarn androidx.compose.**
-dontwarn org.jetbrains.compose.**

# Room 3 and the bundled SQLite driver (JNI)
-keep @androidx.room3.Database class * { *; }
-keep @androidx.room3.Dao interface * { *; }
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class * implements androidx.room3.RoomDatabaseConstructor { *; }
-keepclassmembers class * extends androidx.room3.RoomDatabase {
    <init>(...);
}
-keepclasseswithmembers class androidx.sqlite.driver.bundled.** { native <methods>; }
-keep class androidx.sqlite.driver.bundled.** { *; }

# Ktor (engines are found by name)
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

# Kotlin coroutines main dispatcher (service loader)
-keepdirectories META-INF/services
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepclassmembers class * implements kotlinx.coroutines.internal.MainDispatcherFactory {
    public <init>();
}

# QR code: the scanner view is inflated by name
-dontwarn io.github.alexzhirkevich.qrose.**
-keep class com.journeyapps.barcodescanner.** { *; }

# BouncyCastle: the providers load their algorithms by class name; everything else is called directly
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }

-dontwarn androidx.sharetarget.**
-keep class androidx.sharetarget.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**

# Shizuku (reached through Class.forName in ClipboardShizukuAccess)
-dontwarn moe.shizuku.**
-dontwarn rikka.shizuku.**
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }

# Userspace Tailscale (gomobile), loaded by name
-keep class go.** { *; }
-keep class tsnetbridge.** { *; }

-dontwarn org.slf4j.**
-dontwarn java.lang.instrument.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn java.beans.**

# Iroh Kotlin bindings reach Rust through JNA and uniffi, which look classes and fields up by name.
-keep class computer.iroh.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
