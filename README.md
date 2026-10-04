# Mosaic 🎨

**Mosaic** is a modern, high-performance Android application built with Jetpack Compose that creates photographic mosaics from a collection of your own images.

Stepping close reveals individual memories; stepping back reveals the overarching masterpiece.

---

## ✨ Features

- **High-Performance Color Matching Engine**:
  - Perceptually weighted Euclidean RGB color distance algorithm ($2\Delta R^2 + 4\Delta G^2 + 3\Delta B^2$) tailored for human visual sensitivity.
  - Spatial repetition penalty prevention to avoid repetitive clustering of tiles.
  - Configurable tile-to-color blending slider (0% to 100%) to preserve macro contrast.
- **Fast Low-Res Preview Mode**:
  - Rapid multi-threaded preview generation so users can evaluate composition and adjustments without wasting compute power or battery.
- **Seamless Media Ingestion**:
  - Modern Android Photo Picker (`PickVisualMedia` / `PickMultipleVisualMedia`) supporting batches of up to 100 images without requiring intrusive storage permissions.
  - Native system camera capture via secure `FileProvider`.
- **Local Project Library**:
  - Built-in persistent library preserving past creations.
  - Full-screen zoom and inspection dialog.
  - Project deletion and management.
- **Production & Play Store Ready**:
  - R8 minification and resource shrinking configured (resulting in an ultra-lean ~1.8 MB APK).
  - Pre-configured release signing and Android App Bundle (`.aab`) packaging.

---

## 🏗️ Architecture & Tech Stack

- **UI**: 100% Jetpack Compose with Material 3 Dark theme.
- **Language**: Kotlin.
- **Target SDK**: Android 16 (API 36).
- **Min SDK**: Android 10 (API 29).
- **Concurrency**: Kotlin Coroutines & Flow.

---

## 🚀 Building & Generating Releases

### Standalone Release APK
```bash
./gradlew :app:assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

### Play Store Release Bundle (AAB)
```bash
./gradlew :app:bundleRelease
```
Output: `app/build/outputs/bundle/release/app-release.aab`

---

## 📄 License
MIT License.
