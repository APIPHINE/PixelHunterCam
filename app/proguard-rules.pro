# PixelHunterCam ProGuard / R8 keep rules
# Prevents obfuscation crashes in release builds

# CameraX internals use annotations and reflection
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }

# EXIF interface
-keep class androidx.exifinterface.media.** { *; }

# Room database and entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.pixelhunter.cam.db.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Google Play Services location (used via FusedLocationProviderClient)
-keep class com.google.android.gms.location.** { *; }
