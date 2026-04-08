# PixelHunterCam — Development Roadmap

> **Vision:** A camera app that feels like a professional tool but operates with the simplicity of a point-and-shoot. Every photo is automatically organized, richly annotated, and export-ready for multimodal AI training pipelines.

---

## Part 1: Design Philosophy — "Beauty in Function"

### Core Principles

| Principle | What it means in practice |
|-----------|---------------------------|
| **High Contrast** | Deep matte black backgrounds (`#0A0A0A`) so the camera preview pops. White text at 90%+ opacity. Accent colors (shutter red, lock gold, GPS cyan) are saturated but used sparingly — one per state. |
| **Crisp & Clear** | Typography is large enough to read in sunlight. Buttons are 56dp minimum. Icons are outlined (not filled) to avoid blocking the preview. Every control has a text label *and* an icon — no guessing. |
| **Simple yet Powerful** | The main screen shows **only** the preview, shutter, and 3 essential controls. Everything else (histogram, grids, metadata) is one tap away — but never forced on the user. |
| **Logical Organization** | Photos are not a flat roll. They are grouped by **Location → Session → Shot**. The gallery is a spatial map first, a grid second. |
| **Feedback First** | Every action has immediate haptic or visual feedback. Capture button depresses 4dp. Focus lock pulses. Saving shows a micro-animation, not a blocking dialog. |

### Color System

```
Background:     #0A0A0A (pure dark)
Surface:        #141414 (controls, cards)
Primary Text:   #FFFFFF (100%)
Secondary Text: #B3B3B3 (70%)

Shutter Active: #FF3B30 (iOS red — urgency, action)
Session Locked: #FFCC00 (gold — value locked in)
GPS Excellent:  #34C759 (green)
GPS Weak:       #FF9500 (orange)
Warning/Drift:  #FF3B30 (red)
Ghost Overlay:  #FFFFFF at 35% opacity
```

### Typography Scale

- **Status text** (top bar): 14sp, medium weight
- **Metadata overlay** (ISO/shutter): 16sp, semibold, monospace numeral font
- **Button labels**: 12sp, medium, ALL CAPS
- **Location name**: 18sp, bold
- **Empty state**: 20sp, light weight, centered

### Layout Rules

1. **Camera preview occupies 85% of the screen.** Controls float on a scrim, never push the preview into a letterbox.
2. **Thumb-friendly zones:** Primary action (shutter) sits at the bottom-center, 72dp from the edge. Secondary actions stack vertically on the right edge.
3. **No hamburger menus.** Every feature is reachable in 2 taps or fewer.
4. **Gallery map view first.** When the user opens the gallery, they see a clustered map of shoot locations. Tapping a cluster opens that location's session grid.

---

## Part 2: Data Architecture — "Organize by Place, Link by Session"

### The Hierarchy

PixelHunterCam organizes data in a **3-tier tree**:

```
Location (Place)
    └── Session (Time block)
            └── Shot (Individual image + metadata)
```

#### **Location** — The Spatial Anchor
- Created automatically when a new GPS coordinate is captured.
- Has a label: auto-generated (`"Location 49.2481°N 122.9805°W"`) or user-renamed (`"Kelowna Bridge - East Side"`).
- Stores the *best* thumbnail (sharpest, best-exposed shot from the location).
- Remembers the last successful settings (ISO, shutter, WB) as **suggested defaults**.

#### **Session** — The Temporal Container
- A session starts when the user opens the camera or taps **"New Session"**.
- All shots in a session share the same **session ID**, **base settings**, and **weather/lighting context**.
- Sessions are bounded by time: auto-close after 30 minutes of inactivity, or when the user explicitly starts a new one.
- A session can be **locked** (settings frozen) or **unlocked** (auto mode).

#### **Shot** — The Atomic Unit
- One JPEG + one JSON sidecar + one thumbnail.
- Contains: image data, EXIF, GPS accuracy rating, blur score, exposure zones, face bounding boxes, flags, and the active settings at capture time.

### Linking Strategy

Photos are linked in **four dimensions**:

| Link Type | How it's created | User value | ML value |
|-----------|------------------|------------|----------|
| **Spatial** | Same Location ID | "Show me everything from the bridge" | Training data from the same scene/geometry |
| **Temporal** | Same Session ID | "What did I shoot this morning?" | Consistent lighting/weather conditions |
| **Visual** | Ghost overlay selection | "Match this framing exactly" | Pixel-aligned pairs for super-resolution or segmentation |
| **Semantic** | Auto-generated tags (sky, water, portrait, etc.) | "Find all portraits" | Tagged image-text pairs for CLIP-style training |

### Gallery Navigation Model

```
Gallery Screen (Map View)
    ├── Map pins cluster by Location
    ├── Tap pin → Location Detail
    │       ├── Header: location name + shot count + best thumbnail
    │       ├── "Restore Settings" button (ghosts last locked settings)
    │       └── Grid of sessions (newest first)
    │               └── Tap session → Session Grid
    │                       └── Grid of shots
    │                               └── Tap shot → Fullscreen viewer with metadata panel
    └── Search bar: filter by location name, date range, or tag
```

---

## Part 3: Multimodal Training Data Strategy

### The Goal

Every capture should produce a **dataset-ready bundle** without manual post-processing. A "bundle" is:

```
PH_20240404_143022_123/
    ├── PH_20240404_143022_123.jpg          # Original image
    ├── PH_20240404_143022_123.json         # Rich metadata sidecar
    ├── PH_20240404_143022_123_thumb.jpg    # 200x150 thumbnail
    └── PH_20240404_143022_123_mask.png     # (Future) auto-segmentation mask
```

### Metadata Standards

The JSON sidecar must be schema-versioned and contain fields useful for **computer vision, NLP, and multimodal model training**:

#### **Provenance**
- `app_version`, `schema_version`, `session_id`, `timestamp_iso`

#### **Spatial**
- `latitude`, `longitude`, `gps_accuracy_meters`, `altitude_m`
- `location_label` (human-readable place name)

#### **Camera Physics**
- `iso`, `shutter_speed_seconds`, `focal_length_mm`, `aperture_f`, `zoom_ratio`
- `device_orientation_deg`, `flash_fired`

#### **Image Quality Metrics**
- `blur_score`, `is_globally_blurry`, `blurry_tile_ratio`
- `luminance`, `highlight_clipping`, `shadow_clipping`
- `is_properly_exposed`, `exposure_quality` (good/marginal/overexposed/underexposed)

#### **Content Analysis**
- `face_count` + `faces[]` with `{x, y, width, height, confidence, is_in_focus}`
- `is_portrait`, `composition_score`
- `histogram` zones (blacks, shadows, midtones, highlights, whites)

#### **Pipeline Hints**
- `usable_for_training` (boolean composite of quality gates)
- `needs_exposure_review`, `needs_wb_review`
- `has_faces`, `needs_privacy_review`

#### **Caption / Text Pair (Future)**
- `auto_caption`: "Outdoor landscape, sunny, mountain background, slight highlight clipping."
- `settings_sentence`: "ISO 200, 1/500s, 24mm, daylight white balance."

### Export Formats for ML Pipelines

The app should support **batch export** to standard formats:

| Format | Use Case | Implementation |
|--------|----------|----------------|
| **COCO JSON** | Object detection, segmentation | Images + bounding boxes + categories |
| **YOLO `.txt`** | YOLO training | Normalized bbox per image |
| **CSV manifest** | Tabular ML pipelines | One row per image with all metrics |
| **WebDataset (TAR)** | Large-scale model training | Groups of (image, json, caption) into shards |
| **Hugging Face `datasets` script** | Easy sharing | Auto-generated Python loading script |

### Privacy-First Data Collection

- **Faces are detected but never sent to remote APIs.** All analysis is on-device.
- **Images with faces are flagged** (`needs_privacy_review = true`) so the dataset curator can choose to blur or exclude them.
- **Location precision is configurable:** Default is full GPS for research, but users can downgrade to ~100m accuracy for public datasets.

---

## Part 4: Development Phases

### Phase 1: Foundation — "Capture without crashing" ✅ (Current)
**Status:** Mostly complete. Focus is stability and correctness.

- [x] CameraX integration with lifecycle safety
- [x] MediaStore saving with EXIF orientation
- [x] Location memory + Room database
- [x] Session lock/unlock
- [x] Basic ghost overlay
- [x] `AGENTS.md` checklist for preventing regressions
- [x] ProGuard rules and build configuration

**Next immediate fixes:**
- [ ] Thread-safe date formatting (replace shared `SimpleDateFormat`)
- [ ] Accessibility labels on all camera controls
- [ ] Add `inSampleSize` downsampling to ghost overlay load path

### Phase 2: Organization — "Find any shot in 3 taps"
**Goal:** Transform the flat gallery into a location-aware browsing experience.

- [ ] **Map View Gallery:** Integrate Google Maps or MapLibre to show shoot locations as clustered pins
- [ ] **Location Detail Screen:** Replace `GalleryActivity` with a 2-level navigation: Map → Location → Session → Shot
- [ ] **Smart Search:** Filter by location name, date range, or exposure quality
- [ ] **Session auto-naming:** "Morning session at Kelowna Bridge" instead of generic IDs
- [ ] **Quick compare:** Side-by-side view of two shots from the same session

### Phase 3: Intelligence — "The app sees what I see"
**Goal:** Rich on-device analysis that makes the dataset valuable.

- [ ] **Auto-tagging:** Run a lightweight on-device classification model to tag scenes (sky, water, building, portrait, vehicle, etc.)
- [ ] **Depth estimation:** Use CameraX `CameraExtension` or ARCore to capture depth maps alongside color images
- [ ] **Segmentation masks:** Generate pixel-level masks for the top 3 detected classes
- [ ] **Auto-caption generation:** Combine tags, settings, and GPS context into a natural language caption
- [ ] **Quality gating:** Reject or flag shots that fail training-quality thresholds (too blurry, clipped, or unstable)

### Phase 4: Export & Integration — "Dataset ready in one click"
**Goal:** Seamless handoff from phone to ML pipeline.

- [ ] **Batch export UI:** Select a location or session → choose format (COCO, YOLO, CSV, WebDataset) → export to USB or cloud
- [ ] **Cloud upload:** Optional SFTP / S3 / Google Drive sync for backing up bundles
- [ ] **Dataset versioning:** Tag exported batches so experiments can reference exact image sets
- [ ] **Training split helper:** Auto-generate `train/val/test` splits based on location (prevent data leakage)

### Phase 5: Polish — "It feels like a native camera app"
**Goal:** UI refinement and power-user features.

- [ ] **Custom themes:** High-contrast light mode for outdoor visibility; OLED black for night
- [ ] **Widget / Quick Settings tile:** Launch directly into capture from the home screen
- [ ] **Wear OS companion:** Remote shutter + preview for tripod shots
- [ ] **RAW capture (DNG):** For users who need maximum dynamic range for HDR training pairs

---

## Part 5: UI Roadmap — Specific Screens

### Main Capture Screen
```
┌─────────────────────────────┐
│  📍 Kelowna Bridge    🔒Locked  │  ← Status bar
├─────────────────────────────┤
│                             │
│      Camera Preview         │  ← 85% of screen
│                             │
│    [Ghost overlay at 35%]   │
│                             │
├─────────────────────────────┤
│  ⚡Auto  📐Grid  👤Ghost  🔍1.4x │  ← Secondary controls
│                             │
│        [  SHUTTER  ]        │  ← 72dp circular button
│                             │
│      📁 Gallery  ⚙️ Settings   │  ← Tertiary actions
└─────────────────────────────┘
```

### Gallery Map Screen
```
┌─────────────────────────────┐
│  Search locations...    🔍  │
├─────────────────────────────┤
│                             │
│      [  World Map  ]        │
│    ● 12    ● 8              │
│         ● 3                 │  ← Clustered location pins
│                             │
├─────────────────────────────┤
│  Recent Locations ▼         │
│  [Thumb] Kelowna Bridge     │
│  [Thumb] Downtown Studio    │
└─────────────────────────────┘
```

### Shot Detail Screen
```
┌─────────────────────────────┐
│  ← Back              Share  │
├─────────────────────────────┤
│                             │
│      Full Image             │
│                             │
├─────────────────────────────┤
│  📍 Kelowna Bridge          │
│  🕒 Apr 4, 14:30            │
│  📷 ISO 200 · 1/500s · 24mm │
│  📊 Exposure: Good          │
│  🧠 Tags: landscape, sky    │
│  [Export] [Set as Ghost]    │
└─────────────────────────────┘
```

---

## Part 6: Success Metrics

| Metric | Target |
|--------|--------|
| Capture-to-save time | < 800ms on Pixel 9 |
| Gallery load time (1000 shots) | < 1.5s |
| App crash rate | < 0.5% |
| GPS accuracy (outdoor) | < 10m for 90% of shots |
| Dataset export success rate | 100% (no broken bundles) |
| User task completion (find a shot) | < 3 taps from gallery open |

---

## Part 7: Tech Stack Decisions

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Camera | CameraX 1.4.0 | Lifecycle-safe, multi-lens, orientation-aware |
| Database | Room 2.6.1 | Reliable, KSP-compatible, migration support |
| Location | FusedLocationProvider + `LocationManager` fallback | Accuracy + GMS-less device support |
| Maps | Google Maps SDK (or MapLibre for FOSS builds) | Clustering, custom styling |
| Image loading | `ImageLoader` (custom) + Coil for grid thumbs | EXIF awareness + memory safety |
| On-device ML | TensorFlow Lite + MediaPipe | Lightweight, offline, privacy-safe |
| Export | Kotlinx Serialization + Zip4j | Fast JSON + compressed batch export |

---

*Last updated: April 2026*
