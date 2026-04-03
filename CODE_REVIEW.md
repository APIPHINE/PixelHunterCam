# PixelHunter Cam - Code Review & Requirements Analysis

## Executive Summary

| Feature | Status | Priority |
|---------|--------|----------|
| User-selected save folder | ❌ Not implemented | HIGH |
| EXIF data preservation | ❌ Not implemented | HIGH |
| High-accuracy GPS | ❌ Stale location only | HIGH |
| Dataset annotation export | ⚠️ Partial (DB only) | HIGH |
| Settings reset UI | ⚠️ Unclear | MEDIUM |
| Session drift warnings | ✅ Basic implementation | LOW |

---

## 1. 🚨 CRITICAL: Image Save Location

### Current Implementation
```kotlin
// MainViewModel.kt - Line 315-318
private fun getOutputDir(): File {
    val dir = File(getApplication<Application>().filesDir, "captures")
    if (!dir.exists()) dir.mkdirs()
    return dir
}
```

### Problems
1. **Private app storage** - Images saved to `/data/data/com.pixelhunter.cam/files/captures/`
2. **User cannot access** - Files invisible to user without root
3. **Not backed up** - Files lost on app uninstall
4. **No Photos app integration** - Images don't appear in gallery

### Required Solution
- Use **MediaStore API** (Android 10+) for public `Pictures/PixelHunter/` folder
- Request **MANAGE_EXTERNAL_STORAGE** or use **Storage Access Framework (SAF)** for custom folders
- Preserve original timestamp and metadata
- Optional: Allow user to select custom folder via folder picker

---

## 2. 🚨 CRITICAL: EXIF Data

### Current Implementation
- CameraX captures image but EXIF is not explicitly handled
- No GPS coordinates written to EXIF
- No camera settings (ISO, shutter) written to EXIF

### Required EXIF Tags
| Tag | Source | Purpose |
|-----|--------|---------|
| GPSLatitude | FusedLocationProvider | Geographic position |
| GPSLongitude | FusedLocationProvider | Geographic position |
| GPSAltitude | FusedLocationProvider | Elevation data |
| GPSDateTime | System clock | Sync timestamp |
| DateTimeOriginal | System clock | Capture timestamp |
| Make | Build.MANUFACTURER | Device info |
| Model | Build.MODEL | Device info |
| ISOSpeedRatings | Camera2 API | Camera settings |
| ExposureTime | Camera2 API | Shutter speed |
| FNumber | CameraCharacteristics | Aperture |
| FocalLength | CameraCharacteristics | Lens info |
| ImageDescription | Session label | Dataset annotation |
| UserComment | JSON metadata | Extended dataset info |

### Implementation Approach
- Use `androidx.exifinterface:exifinterface` library
- Read existing EXIF from CameraX output
- Add GPS and camera metadata
- Write back to final image

---

## 3. 🚨 CRITICAL: High-Accuracy GPS

### Current Implementation
```kotlin
// LocationMemory.kt - Line 125-131
suspend fun getCurrentLocation(): Location? {
    return try {
        fusedLocationClient.lastLocation.await()  // ⚠️ May be stale!
    } catch (e: Exception) {
        null
    }
}
```

### Problems
1. **`lastLocation` may be stale** - Could be hours old if GPS wasn't active
2. **No accuracy information** - Can't determine if location is reliable
3. **No high-accuracy request** - Using cached location only
4. **No timeout handling** - May wait indefinitely

### Required Solution
```kotlin
// Request fresh high-accuracy location
val request = LocationRequest.create().apply {
    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    interval = 0  // One-time request
    numUpdates = 1
    expirationDuration = 10000  // 10 second timeout
}
```

### Accuracy Requirements
- **Target**: < 5 meters accuracy for dataset geo-tagging
- **Fallback**: Accept < 15 meters with warning
- **Reject**: > 50 meters accuracy (retry or warn user)

---

## 4. 📊 DATASET ANNOTATION DATA

### Current Database Schema
```kotlin
// ShootImage entity - Basic data only
val blurScore: Double
val luminance: Float
val colorTempK: Float
val hadFlags: Boolean
val iso: Int
val shutterNs: Long
```

### Missing Dataset Fields
| Field | Type | Purpose |
|-------|------|---------|
| gpsAccuracy | Float | Location confidence radius |
| altitude | Double | Elevation above sea level |
| deviceOrientation | Int | Phone rotation (0/90/180/270) |
| cameraFacing | String | Front/back/external |
| focalLength35mm | Float | Equivalent focal length |
| sceneType | String | Indoor/outdoor/night |
| faceCount | Int | Number of detected faces |
| dominantColors | List<Int> | Primary scene colors |
| annotationsJson | String | Custom ML labels |

### JSON Sidecar Export
Each image should have companion `.json` file:
```json
{
  "image_id": "PHC_20240329_143022",
  "capture_info": {
    "timestamp": "2024-03-29T14:30:22.123Z",
    "timezone": "America/Los_Angeles"
  },
  "gps": {
    "latitude": 34.052234,
    "longitude": -118.243685,
    "altitude": 89.5,
    "accuracy_meters": 3.2,
    "provider": "fused"
  },
  "camera_settings": {
    "iso": 400,
    "shutter_speed_ns": 8333333,
    "aperture": 1.8,
    "focal_length_mm": 25.0,
    "zoom_ratio": 1.0,
    "flash_mode": "auto",
    "white_balance_k": 5500
  },
  "analysis": {
    "blur_score": 145.2,
    "luminance": 0.65,
    "color_temp_k": 5200,
    "shadow_clipping": 0.02,
    "highlight_clipping": 0.01,
    "composition_score": 0.78,
    "face_regions": [
      {"x": 120, "y": 200, "width": 80, "height": 100}
    ]
  },
  "session": {
    "session_id": "sess_abc123",
    "locked": true,
    "location_label": "Golden Gate Park"
  },
  "device": {
    "manufacturer": "Google",
    "model": "Pixel 9",
    "android_version": "14"
  }
}
```

---

## 5. ⚠️ SETTINGS RESET UI/UX

### Current Issues
1. **"🔒 Lock" button is confusing** - Shows even when already locked
2. **No visual indicator** for what "unlock" will do
3. **Settings drift warnings** appear in small text banner
4. **No quick reset action** - Must navigate through dialogs

### Required Improvements

#### A. Clear Session Status Indicator
```
┌─────────────────────────────────┐
│ 🔒 SESSION LOCKED               │  ← Green when locked
│ ISO 400 · 1/120s · 5500K       │
│ 📍 Golden Gate Park            │
│                                 │
│ [⚠️ Tap to reset settings]     │  ← Clear reset CTA
└─────────────────────────────────┘
```

#### B. Settings Drift Visualization
When drift detected:
- Flashing border or background
- Large banner: "⚠️ LIGHTING CHANGED - Reset recommended"
- "Reset Now" button directly in banner

#### C. One-Tap Reset
- Single button press to return to auto mode
- No confirmation dialog for minor drifts
- Optional: "Reset & Retake" for immediate correction

---

## 6. 🔧 ADDITIONAL REQUIREMENTS

### A. Session Management
- **Session ID**: Unique identifier for grouping shots
- **Session notes**: User-added description
- **Session export**: Export all images + JSON as ZIP

### B. Image Quality Options
- **RAW capture** (if supported by device)
- **JPEG quality** selector (80/90/95/100)
- **Resolution** options (Full/12MP/8MP/4MP)

### C. Batch Operations
- **Select multiple** for export/delete
- **Batch re-tag** location info
- **Bulk JSON export** for datasets

### D. Backup & Sync
- **Google Drive sync** option
- **Automatic backup** when WiFi connected
- **Export session** as organized folder structure

---

## 7. 🐛 BUGS & EDGE CASES

### Known Issues
1. **Focus overlay doesn't sync** with actual camera focus state
2. **Histogram data not actually displayed** - Generated but not shown
3. **Zoom slider rotation** may not work on all devices
4. **No handling** for camera disconnection (USB camera, etc.)

### Edge Cases
- **GPS unavailable**: Currently handled with sentinel location
- **Storage full**: No error handling implemented
- **Permission revoked mid-session**: App may crash
- **Orientation change**: May restart camera

---

## 8. 📋 IMPLEMENTATION PRIORITY

### Phase 1: Critical (Must Have)
1. ✅ MediaStore integration for public folder saving
2. ✅ EXIF writing with GPS and camera settings
3. ✅ High-accuracy location request with timeout
4. ✅ JSON sidecar export for dataset annotation

### Phase 2: High Priority
5. Settings reset UI redesign
6. Session drift visualization improvements
7. Save location picker UI

### Phase 3: Nice to Have
8. RAW capture support
9. Cloud backup integration
10. Batch operations

---

## Summary

The app has a solid foundation but lacks critical features for professional dataset collection:

1. **Images are trapped** in private storage
2. **No EXIF metadata** for downstream processing
3. **GPS may be inaccurate** by hundreds of meters
4. **Dataset export** requires manual DB extraction

These issues must be resolved before the app is suitable for serious ML training data collection.
