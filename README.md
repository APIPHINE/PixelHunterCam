# PixelHunter Cam — v0.1.0 Beta

Smart camera app for consistent multimodal training data capture.

---

## Developer Guide

> **New:** See [`AGENTS.md`](AGENTS.md) for the comprehensive development checklist covering CameraX, MediaStore, EXIF orientation, permissions, memory management, and common failure patterns.

## What's in this build

- **Session lock** — first shot sets your baseline. Settings freeze for the session.
- **Local frame analysis** — blur detection (global + tiled), exposure drift, white balance drift. All runs on-device, no API calls.
- **Flag system** — when something's off, a banner appears. You decide if it warrants a settings review.
- **Location memory** — GPS tracks where you've shot. Return to a location and it offers to restore your last settings.
- **Ghost overlay** — load a past image as a semi-transparent layer over the viewfinder to replicate framing.

---

## Setup (step by step)

### 1. Install Android Studio
Download from: https://developer.android.com/studio
Install it. This is your IDE — where you'll build and run the app.

### 2. Open the project
- Open Android Studio
- Click "Open" and select this folder (PixelHunterCam)
- Wait for Gradle sync to finish (progress bar at the bottom)

### 3. Connect your Pixel
- On your Pixel: Settings → About Phone → tap "Build Number" 7 times → Developer Options appear
- Settings → Developer Options → turn on "USB Debugging"
- Connect USB to your computer
- Accept the "Trust this computer?" prompt on your phone

### 4. Run the app
- Click the green Play button (▶) in Android Studio
- Select your Pixel from the device list
- App installs and launches on your phone

### 5. Grant permissions
On first launch, tap Allow for:
- Camera
- Location
- Photos/Media

---

## How to use

| Action | What happens |
|---|---|
| Open app | GPS checks for nearby known locations |
| First shutter tap | Photo taken, baseline set, session locks |
| Subsequent shots | Each frame checked against baseline |
| Flag banner appears | Issue detected — tap OK to dismiss or Review for API help |
| Tap Ghost | Browse past images from this location to overlay |
| Opacity slider | Adjust how visible the ghost image is |
| Tap 🔒 Lock button | Unlock session, return to auto |

---

## Project structure

```
app/src/main/java/com/pixelhunter/cam/
├── analysis/
│   └── FrameAnalyzer.kt       ← blur, luminance, color temp detection
├── camera/
│   └── CameraController.kt    ← CameraX wrapper + manual settings
├── db/
│   └── Database.kt            ← Room DB for locations + images
├── location/
│   └── LocationMemory.kt      ← GPS + scene memory
├── session/
│   └── SessionManager.kt      ← lock state + drift detection
│   └── SessionSettings.kt     ← settings data model
└── ui/
    └── MainActivity.kt        ← main camera screen
    └── MainViewModel.kt       ← glues everything together
```

---

## What's coming next

- [ ] Read actual Camera2 metadata (ISO, shutter speed) from capture result to lock real values
- [ ] API review call — send flagged frame thumbnail to Claude for settings advice
- [ ] Manual settings panel — override ISO/shutter/WB manually
- [ ] Location label editor — rename locations from coordinates to real names
- [ ] Thumbnail grid in overlay picker
- [ ] Export session log (settings + flags + GPS) as JSON

---

## Tuning the flag sensitivity

In `SessionManager.kt`, adjust these constants:

```kotlin
const val LUMINANCE_DRIFT_THRESHOLD = 0.15f   // 15% = medium, raise to reduce flags
const val COLOR_TEMP_DRIFT_THRESHOLD = 500f    // 500K, raise if WB flags too often
const val BLUR_GLOBAL_THRESHOLD = 80.0         // Lower = less sensitive to blur
const val BLUR_LOCAL_THRESHOLD = 60.0          // Per-tile blur sensitivity
const val BLUR_TILE_FAIL_RATIO = 0.4f          // 40% tiles blurry = global flag
```

---

## License

App code: Apache 2.0

Photos captured through this app: Users retain full ownership.
By using this app, users grant PixelHunter a non-exclusive, royalty-free, worldwide,
perpetual license to use captured images for AI/ML model training, including the right
to sublicense those training rights to third parties. PixelHunter may not sell, publish,
or commercially exploit the photographs themselves. Users may opt out via Settings → Privacy.

*Note: This is a draft license. Consult a lawyer before public release.*
