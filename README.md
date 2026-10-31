# SEEMA — Sci-Fi HUD

An Iron Man JARVIS-style HUD dashboard for Android, drawn entirely with Jetpack Compose
`Canvas` (no images).

- **Radar (top-left):** concentric rings, rotating sweep, contacts that light up as the
  sweep passes, pulsing core. Tap to expand into a 4-second "SCANNING..." mode.
- **3D wireframe globe (centre):** latitude/longitude lines and grid dots projected with
  sine/cosine, spinning on its Y axis, far side drawn faintly. Tap for an amber
  "TARGET LOCKED" overlay.
- **Glass panels:** translucent panels top-right and bottom, ready for live data.

Package `com.seema.jarvis` · minSdk 26 · targetSdk 35 · Kotlin 2.0.21 · AGP 8.7.3 ·
Compose BOM 2024.12.01.

## Build

Open the folder in Android Studio and press Run, or from a terminal:

```
./gradlew installDebug
```

The HUD preview is in `ui/screen/HudScreen.kt` (`HudScreenPreview`).
