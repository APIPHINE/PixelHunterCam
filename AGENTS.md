# PixelHunterCam — Android Camera App Development Checklist

> **Purpose:** Prevent the common errors that break camera apps (sideways photos, red-X placeholders, lifecycle crashes, permission denials, and scoped-storage failures).  
> **Scope:** Every code change that touches CameraX, MediaStore, the gallery, permissions, or image processing.

---

## 1. Image Loading & Display — The "Red-X / Sideways Photo" Rules

### 1.1 NEVER use `BitmapFactory.decodeFile()` directly on a user-facing path
- **Why:** Gallery paths are often `content://` URIs (MediaStore). `decodeFile()` silently fails on them, returning `null`, which triggers placeholder fallbacks (the red-X bug).
- **What to do instead:** Use the project's `ImageLoader.loadBitmap(context, path)` utility. It handles both `file://` and `content://` URIs and applies EXIF orientation.

### 1.2 ALWAYS apply EXIF orientation before displaying or thumbnailing
- **Why:** `BitmapFactory` ignores EXIF orientation tags. Without correction, photos shot in portrait appear sideways in the app gallery.
- **What to do:**
  - For full-size display → `ImageLoader.loadBitmap(context, path)` (orientation is applied automatically).
  - For thumbnails → generate the thumbnail from the **already-oriented** bitmap, or call `ImageLoader.applyExifOrientation(bitmap, orientation)` after decoding.

### 1.3 NEVER show `android.R.drawable.ic_delete` as a missing-image placeholder
- **Why:** It looks like a fatal error to the user.
- **What to do instead:** Use `R.drawable.placeholder_image` (neutral gray box) whenever a bitmap fails to load.

### 1.4 ALWAYS recycle intermediate bitmaps on the same thread they were created
- **Why:** Recycling on a different thread can race with `Bitmap.createBitmap()` and cause native crashes.
- **What to do:** Keep bitmap creation + recycling in the same `Dispatchers.Default` or `Dispatchers.IO` block.

---

## 2. CameraX Lifecycle & Threading — The "Crash on Capture" Rules

### 2.1 Bind use cases inside `cameraProviderFuture.addListener()` on the main executor
- **Why:** `ProcessCameraProvider.bindToLifecycle()` must run on the main thread.
- **Failure mode:** `IllegalStateException` or silent black preview if bound off-thread.

### 2.2 Keep a strong reference to `ImageCapture` and `Preview` instances
- **Why:** If they are local variables inside a setup method and not retained as class fields, `takePicture()` can be called on a garbage-collected or unbound use case.
- **Failure mode:** `ImageCaptureException: Camera is closed` or no callback fired at all.

### 2.3 NEVER call `takePicture()` while the lifecycle owner is stopping
- **Why:** CameraX detaches use cases in `onStop`. If a capture is in flight, it aborts and throws `CameraClosedException` on the main thread (fatal crash).
- **What to do:** Guard capture with `lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)` or disable the shutter button as soon as capture starts.

### 2.4 Use a single dedicated `ExecutorService` for camera operations and shut it down in `onCleared()` / `onDestroy()`
- **Why:** Leaked executors = memory leaks. Re-using the main looper for capture callbacks can jank the UI.
- **Pattern:**
  ```kotlin
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  
  override fun onCleared() / onDestroy() {
      cameraExecutor.shutdown()
  }
  ```

### 2.5 Update `ImageCapture.targetRotation` when the device rotates
- **Why:** CameraX writes EXIF orientation based on the target rotation at bind time. If the user rotates the phone after binding, photos will be oriented incorrectly.
- **Pattern:** Use `OrientationEventListener` to update `imageCapture.targetRotation = ...` in real time.

### 2.6 ALWAYS close `ImageProxy` in `ImageAnalysis` analyzers
- **Why:** `ImageAnalysis` uses a finite ring buffer. If `image.close()` is skipped, the pipeline stalls and the preview freezes.
- **Pattern:** `try { analyze(image) } finally { image.close() }`.

### 2.7 Handle camera-unavailable errors gracefully
- **Why:** Another app may hold the camera (e.g., video call, QR scanner). CameraX initialization can fail with `CameraUnavailableException`.
- **What to do:** Catch `InitializationException` / `CameraUnavailableException`, show a user-facing message ("Camera in use by another app"), and offer a retry button. Do not let the exception propagate uncaught.

---

## 3. Build & Gradle — The "Won't Compile / Won't Run on CI" Rules

### 3.1 Configure Room schema export directory when `exportSchema = true`
- **Why:** Room requires a schema JSON output directory. If it is missing, builds fail as soon as you bump the database version.
- **Pattern:** In `app/build.gradle`, add:
  ```gradle
  ksp {
      arg("room.schemaLocation", "$projectDir/schemas")
  }
  ```
- **Anti-pattern:** Setting `exportSchema = true` in `@Database` without defining the directory.

### 3.2 Keep NDK version pinned to a version available on CI machines
- **Why:** Hardcoding `ndkVersion "27.0.12077973"` breaks builds on GitHub Actions or teammates' machines if that exact NDK revision isn't installed.
- **Pattern:** Document the required NDK version in `README.md` and provide an `local.properties` fallback: `ndk.dir=...`. Consider removing the explicit `ndkVersion` if the project doesn't use native code directly.

### 3.3 Handle JDK 21 compiler workarounds with care
- **Why:** The `--add-opens` JVM args for JDK 21 are brittle and may break with future AGP or Kotlin updates.
- **Pattern:** Pin the project to a known-working JDK (e.g., JDK 17 or 21) via Gradle toolchain and document it. Avoid mixing JDK versions across the team.

### 3.4 Test release builds with ProGuard/R8 enabled (`minifyEnabled true`) before shipping
- **Why:** Obfuscation strips CameraX and Room classes that look unused but are accessed via reflection.
- **Pattern:** Run `./gradlew assembleRelease` and exercise core flows (capture, gallery, share) on a physical device.

### 3.5 Update `targetSdk` behavior-change checklist when bumping API levels
- **Why:** `targetSdk 35` (Android 15) introduces 16 KB page-size requirements, edge-to-edge enforcement, and restricted background starts.
- **Pattern:** Review the Android "Behavior changes: all apps" page for the target API and add explicit handling for each change.

---

## 4. Manifest & Build Configuration — The "Won't Install / Won't Share" Rules

### 3.1 Declare `<queries>` for camera and location intents on Android 11+ (API 30+)
- **Why:** Package visibility restrictions prevent implicit intent resolution unless the target package is declared in `<queries>`.
- **Failure mode:** `resolveActivity()` returns `null`, so share sheets or map apps silently fail to open.
- **Pattern:**
  ```xml
  <queries>
      <intent><action android:name="android.media.action.IMAGE_CAPTURE"/></intent>
      <intent><action android:name="android.intent.action.SEND"/></intent>
      <package android:name="com.google.android.apps.maps"/>
  </queries>
  ```

### 3.2 Handle configuration changes properly (`android:configChanges`)
- **Why:** Rotating the device during capture recreates the Activity by default, destroying the in-flight coroutine and leaking the bitmap.
- **Pattern:** Add `android:configChanges="orientation|screenSize|keyboardHidden"` to camera Activities, or use a `ViewModel` + retained fragment to survive rotation. Do **not** rely solely on `android:screenOrientation="portrait"`.

### 3.3 Exclude the Room database from automatic cloud backup
- **Why:** Restoring a database backup onto a newer app version with a changed schema causes `IllegalStateException` crashes on first launch.
- **Pattern:** Set `android:allowBackup="false"` (or use a `<full-backup-content>` / `<data-extraction-rules>` XML that excludes `*.db` and `*.db-shm` / `*.db-wal`).

### 3.4 Keep `FileProvider` authority stable and match it exactly in code
- **Why:** Changing `applicationId` or the authority string breaks every existing shared file link.
- **Pattern:** Use `${applicationId}.fileprovider` in both `AndroidManifest.xml` and Kotlin/Java code, never hardcode a specific package string.

### 3.5 Add ProGuard / R8 keep rules for CameraX, Room, and reflection-based serializers
- **Why:** CameraX and Room use annotations and reflection internally. R8 can strip classes that look unused.
- **Failure mode:** Obfuscated release builds crash on camera open with `ClassNotFoundException` or Room generates invalid SQL.
- **Pattern:** Include standard keeps in `proguard-rules.pro`:
  ```proguard
  -keep class androidx.camera.core.** { *; }
  -keep class androidx.exifinterface.media.** { *; }
  -keep class * extends androidx.room.RoomDatabase { *; }
  -keep class com.pixelhunter.cam.db.** { *; }
  ```

---

## 4. MediaStore & Scoped Storage — The "Save Failed / File Not Found" Rules

### 3.1 ALWAYS save public photos via `MediaStore` on Android 10+ (API 29+)
- **Why:** Direct file paths in `Environment.getExternalStorageDirectory()` are blocked by Scoped Storage.
- **Failure mode:** `FileNotFoundException`, `SecurityException`, or files invisible to the system Photos app.

### 3.2 Use `IS_PENDING = 1` during write, then flip to `0` on success
- **Why:** Prevents the system gallery from scanning a half-written JPEG.
- **Failure mode:** Corrupt thumbnails in the Photos app, or `FileNotFoundException` when another app tries to read too early.

### 3.3 ALWAYS store the returned URI string in the database, not a fabricated file path
- **Why:** For `content://` URIs the real file path is opaque. Converting `uri.toString()` back to a `File` path will fail.
- **Pattern:** Store `uri.toString()` (or the real path when it is a `file://` URI) and load it later with `ImageLoader.loadBitmap(context, storedPath)`.

### 3.4 Clean up the MediaStore entry if the write throws
- **Why:** An empty `ContentResolver` row will appear as a broken image in the gallery.
- **Pattern:** `try { write } catch { resolver.delete(uri, null, null); throw }`

### 3.5 Provide a legacy fallback for Android 9 (API 28) and below
- **Why:** Those devices still need direct file writes and `WRITE_EXTERNAL_STORAGE` permission.
- **Pattern:** Branch on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`.

---

## 5. EXIF Orientation Mapping — The "Sideways in System Gallery" Rules

### 4.1 Map `Surface` rotation to EXIF orientation correctly for the **sensor** coordinate system
- **Why:** The camera sensor is usually landscape. Portrait (`ROTATION_0`) requires a 90° rotation tag so viewers display it upright.
- **Correct mapping:**
  | Device rotation | EXIF orientation |
  |-----------------|------------------|
  | `ROTATION_0` (portrait) | `ORIENTATION_ROTATE_90` |
  | `ROTATION_90` (landscape right) | `ORIENTATION_NORMAL` |
  | `ROTATION_180` (portrait upside-down) | `ORIENTATION_ROTATE_270` |
  | `ROTATION_270` (landscape left) | `ORIENTATION_ROTATE_180` |
- **Anti-pattern:** Mapping `ROTATION_0 → ORIENTATION_NORMAL`. That produces landscape photos when the phone is upright.

### 4.2 Write EXIF via `ParcelFileDescriptor` or `ExifInterface(outputStream)`
- **Why:** `ExifInterface(path)` does not work on `content://` URIs.
- **Pattern for MediaStore:** `contentResolver.openFileDescriptor(uri, "rw")?.use { ExifInterface(it.fileDescriptor).apply { ... saveAttributes() } }`

---

## 6. Permissions — The "Denial / Crash at Launch" Rules

### 5.1 Request runtime permissions **in-context**, not at app startup
- **Why:** Upfront permission bombardment increases denial rates.
- **Pattern:** Ask for `CAMERA` when the user taps the capture button (or enters the camera screen). Ask for `ACCESS_FINE_LOCATION` when they enable location tagging.

### 5.2 Handle "Don't ask again" and permanent denials gracefully
- **What to do:** Provide a fallback (e.g., save without GPS) and a snackbar/button that deep-links to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

### 5.3 Check permissions every time before an action, not just once
- **Why:** Users can revoke permissions from Settings at any time.
- **Pattern:** `ActivityCompat.checkSelfPermission(...) == PERMISSION_GRANTED` inside the click handler or `lifecycleScope.launch` block.

### 5.4 For Android 10+ (API 29+), do **not** declare `WRITE_EXTERNAL_STORAGE` unless absolutely necessary
- **Why:** Scoped Storage makes it redundant for media files and triggers a scary "Allow access to all files" prompt.
- **Exception:** Only keep it for the legacy Android 9 branch if you must support direct file writes.

---

## 7. Memory Management — The "OOM / App Killed" Rules

### 6.1 Downsample large bitmaps before processing
- **Why:** A 12 MP photo decoded at full resolution costs ~48 MB RAM. Two of those trigger OOM on low-end devices.
- **Pattern:** Use `BitmapFactory.Options().apply { inJustDecodeBounds = true }` to read dimensions, compute `inSampleSize`, then decode.

### 6.2 Recycle the original capture bitmap after saving to MediaStore and DB
- **Why:** The camera-generated bitmap is usually the biggest single allocation in the app.
- **Pattern:** `finally { capturedBitmap?.recycle() }` in the capture coroutine.

### 6.3 Do not hold full-resolution bitmaps in ViewModel StateFlows
- **Why:** StateFlows retain the last emission forever. A full bitmap there leaks memory across configuration changes.
- **Pattern:** Store only the **path/URI** in the state object. Load the bitmap on demand in the UI layer.

---

## 8. Gallery & Database — The "Missing Photo / Stale Data" Rules

### 7.1 Reload the gallery from the database when returning from capture
- **Why:** A newly inserted `ShootImage` row won't appear unless the RecyclerView adapter refreshes.
- **Pattern:** Call `loadGallery()` in `onResume()` of `GalleryActivity`, or emit a navigation result that triggers a re-query.

### 7.2 Delete the physical file **and** the database row when the user deletes a photo
- **Why:** Orphaned files waste storage; orphaned DB rows show broken thumbnails.
- **Pattern:** `db.imageDao().deleteImage(image)` + `File(image.imagePath).delete()` + `File(image.thumbnailPath).delete()`.

### 7.3 Use `FileProvider` when sharing files on Android 9 and below
- **Why:** Direct `file://` URIs in intents are blocked on API 24+ (`FileUriExposedException`).
- **Pattern:** `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)` + `Intent.FLAG_GRANT_READ_URI_PERMISSION`.

---

## 10. Location Services — The "No GPS / Frozen Location" Rules

### 9.1 Verify Google Play Services availability before using `FusedLocationProviderClient`
- **Why:** On devices without GMS (e.g., some Chinese OEMs, emulators), `FusedLocationProviderClient` throws `ApiException`.
- **What to do:** Check `GoogleApiAvailability.isGooglePlayServicesAvailable(context)`. If unavailable, fall back to `android.location.LocationManager`.

### 9.2 ALWAYS request location updates on a background thread with a timeout
- **Why:** `fusedLocationClient.getLastLocation()` can return `null` (cold start). `getCurrentLocation()` or a custom timeout coroutine prevents the UI from freezing indefinitely.
- **Pattern:** `withTimeout(3000L) { locationDeferred.await() }` — and gracefully accept `null`.

### 9.3 Handle "Location services disabled" separately from permission denied
- **Why:** Users often disable GPS system-wide. A permission dialog won't help.
- **What to do:** Check `LocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)` before capture. If disabled, show a Snackbar that opens `Settings.ACTION_LOCATION_SOURCE_SETTINGS`.

### 9.4 Do not request `ACCESS_BACKGROUND_LOCATION` unless the feature truly needs it
- **Why:** Google Play policy strictly limits background location. For a camera app that only tags photos at capture time, foreground location is sufficient.

---

## 11. Coroutines & Cancellation — The "Leaked Job / Stale Callback" Rules

### 10.1 Launch capture work inside `viewModelScope` with a `SupervisorJob`
- **Why:** If a child coroutine (e.g., GPS) crashes, a regular `Job` cancels the entire capture pipeline, leaving the UI stuck in "Capturing...".
- **Pattern:** `viewModelScope.launch(SupervisorJob()) { ... }` for independent parallel tasks.

### 10.2 Cancel in-flight work when the user leaves the camera screen
- **Why:** A slow GPS query or heavy image analysis can complete after `onCleared()`, writing to a dead database context.
- **Pattern:** Use `viewModelScope` (auto-cancelled) or a custom `CoroutineScope` tied to the Activity lifecycle. Store `Job` references and `cancel()` them in `onCleared()` / `onDestroy()`.

### 10.3 Avoid `Dispatchers.Main` for decoding, compression, or EXIF writing
- **Why:** These are blocking I/O / CPU operations. Running them on Main causes ANRs.
- **Pattern:** `withContext(Dispatchers.IO)` for file writes; `withContext(Dispatchers.Default)` for bitmap rotation and analysis.

---

## 12. UI/UX & Responsiveness — The "Frozen Shutter / ANR" Rules

### 11.1 Disable the shutter button immediately when capture starts
- **Why:** Double-tapping the shutter launches overlapping `takePicture()` calls, which CameraX rejects or crashes on.
- **Pattern:** Expose `isCapturing` in UI state and set `button.isEnabled = !state.isCapturing`.

### 11.2 Keep the screen on during camera preview
- **Why:** If the device sleeps while the user is framing a shot, the camera session is destroyed and must re-initialize.
- **Pattern:** `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` in the camera Activity.

### 11.3 Provide indeterminate progress and haptic feedback
- **Why:** Users need confidence that the tap registered.
- **Pattern:** `vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))` on capture start, plus a subtle spinner or overlay.

### 11.4 Handle edge-to-edge / insets on devices with display cutouts
- **Why:** Camera controls can be obscured by notches or gesture bars.
- **Pattern:** Use `WindowInsetsCompat` to pad bottom controls so they sit above the gesture navigation bar.

---

## 13. OEM & Device Quirks — The "Works on Pixel, Fails on Samsung" Rules

### 12.1 Test on at least one Samsung, one Xiaomi/OPPO, and one low-end device
- **Why:** Samsung cameras often have slower initialization and stricter flash timing. Xiaomi/OPPO aggressively kill background apps and require `Battery Optimization` whitelisting for location services.
- **Pattern:** Maintain a physical device matrix. Use Firebase Test Lab for hardware-level diversity.

### 12.2 Handle `LEGACY` and `LIMITED` hardware levels
- **Why:** Not all devices support manual ISO, shutter speed, or tap-to-focus with regions.
- **What to do:** Query `CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL`. If `LEGACY`, disable manual controls and fall back to fully auto mode.

### 12.3 Expect camera rebind delays on some OEMs after permission grant
- **Why:** On Samsung devices, the first camera open after granting permission can take 500–1200 ms.
- **What to do:** Add a short `ViewStub` / skeleton screen or delay-sensitive UI state so the user doesn't tap repeatedly.

---

## 14. Error Handling & Resilience — The "Silent Failure / Crash" Rules

### 13.1 Catch `IOException` for "No space left on device"
- **Why:** Storage-full errors are common in the field. They must not crash the app.
- **Pattern:** Wrap MediaStore writes in `try/catch`. If storage is full, show "Storage full — free space to save photos".

### 13.2 Provide an emergency reset for stuck capture state
- **Why:** Complex async pipelines can deadlock if an exception is swallowed.
- **Pattern:** A hidden or settings-menu "Reset Camera" button that calls `unlockSession()` + `isCapturing = false`.

### 13.3 Log structured diagnostics for crashes
- **Why:** When a user reports "it freezes," logs are the only way to diagnose.
- **Pattern:** Use `android.util.Log` with consistent tags (`Capture`, `CameraX`, `Location`, `Storage`). Include image dimensions, device rotation, and exception messages.

---

## 15. Privacy, Accessibility & Thread Safety — The "Legal / Usability / Race Condition" Rules

### 15.1 NEVER log precise GPS coordinates, exact file paths, or PII in production builds
- **Why:** GPS coordinates in logs are personally identifiable information (PII). File paths can reveal usernames. In many jurisdictions this violates privacy regulations.
- **What is safe to log:** Accuracy radius ("12.5m"), coarse counts ("3 shots saved"), and exception types. 
- **What is NOT safe:** `Log.d(TAG, "Lat: ${location.latitude}, Lng: ${location.longitude}")` or `Log.d(TAG, "Saved to: ${userHome}/Pictures/...")`.
- **Pattern:** Strip or hash sensitive fields in release builds, or gate them behind `BuildConfig.DEBUG`.

### 15.2 Provide `android:contentDescription` on every interactive camera control
- **Why:** A camera app with no content descriptions is unusable for blind users with TalkBack. This is also a Google Play accessibility requirement.
- **Pattern:** Every button, slider, and image view in the capture screen needs a concise description:
  ```xml
  <ImageButton android:contentDescription="Capture photo" />
  <ImageButton android:contentDescription="Switch camera lens" />
  ```
- **Anti-pattern:** 0 content descriptions across the entire layout tree.

### 15.3 Do not use `SimpleDateFormat` from multiple coroutines without synchronization
- **Why:** `SimpleDateFormat` is **not thread-safe**. Calling it concurrently from `Dispatchers.Default` or `Dispatchers.IO` causes silent date corruption or `ArrayIndexOutOfBoundsException`.
- **Pattern:** Use `DateTimeFormatter.ISO_LOCAL_DATE_TIME` (Java 8+ with desugaring) or create a new `SimpleDateFormat` instance inside each coroutine block. Alternatively, synchronize access via `ThreadLocal<SimpleDateFormat>`.
- **Anti-pattern:** A shared top-level `val dateFormat = SimpleDateFormat(...)` invoked inside `withContext(Dispatchers.IO)`.

### 15.4 Add `dataExtractionRules` for Android 12+ (API 31+) even when `allowBackup="false"`
- **Why:** Android 12 ignores `allowBackup="false"` for ADB backups unless `android:dataExtractionRules` is explicitly set to deny all extraction.
- **Pattern:**
  ```xml
  <application
      android:allowBackup="false"
      android:dataExtractionRules="@xml/data_extraction_rules"
      ... >
  ```
  With `res/xml/data_extraction_rules.xml`:
  ```xml
  <data-extraction-rules>
      <cloud-backup>
          <exclude domain="root" path="."/>
      </cloud-backup>
      <device-transfer>
          <exclude domain="root" path="."/>
      </device-transfer>
  </data-extraction-rules>
  ```

### 15.5 Prefer the system Photo Picker over runtime storage permissions for read access
- **Why:** Android 13+ (API 33) provides the Photo Picker (`ActivityResultContracts.PickVisualMedia`). It requires **zero permissions** and gives the user full control.
- **When to use it:** Any feature that imports existing photos (e.g., profile picture, ghost overlay source) should use the Photo Picker instead of `READ_MEDIA_IMAGES`.
- **Pattern:**
  ```kotlin
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> ... }
  picker.launch(ActivityResultContracts.PickVisualMedia.ImageOnly)
  ```

---

## 16. Quick Pre-Commit Checklist

Before every PR that touches camera, storage, or gallery code, verify:

- [ ] `ImageLoader` is used for every bitmap decode that reads from storage.
- [ ] No `android.R.drawable.ic_delete` is used as a fallback image.
- [ ] `ImageCapture` instance is stored as a property (not a local variable).
- [ ] `takePicture()` is guarded against lifecycle stop.
- [ ] MediaStore writes use `IS_PENDING` and clean up on failure.
- [ ] EXIF orientation mapping matches the table in §5.1.
- [ ] Runtime permission checks exist before camera/location actions.
- [ ] Bitmaps are downsampled or recycled to avoid OOM.
- [ ] File paths are only treated as `File` objects when they actually start with `/` or `file://`; otherwise they are handled as URIs.
- [ ] Sharing uses `FileProvider` with `FLAG_GRANT_READ_URI_PERMISSION` for local files, or passes `content://` URIs through directly.
- [ ] No `SimpleDateFormat` instances are shared across coroutines without synchronization.
- [ ] No PII (GPS coords, exact paths) is logged outside `BuildConfig.DEBUG` blocks.
- [ ] Every new interactive view has a `contentDescription`.
- [ ] Room schema directory is configured in `build.gradle` when `exportSchema = true`.
- [ ] `allowBackup="false"` is paired with `dataExtractionRules` on Android 12+.

---

## 17. References

- Android Developers — Capture an image (CameraX): https://developer.android.com/media/camera/camerax/take-photo
- Android Developers — Configuration options (rotation, resolution, viewport): https://developer.android.com/media/camera/camerax/configuration
- Android Developers — Scoped Storage: https://developer.android.com/training/data-storage#scoped-storage
- Android Developers — Request app permissions: https://developer.android.com/training/permissions/requesting
