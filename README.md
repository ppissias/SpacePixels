# SpacePixels 🌌

SpacePixels is a desktop FITS workflow for finding moving and transient objects in aligned astronomical image sequences. It is built around the JTransient detection engine and is aimed at asteroid, comet, satellite, and transient hunting from amateur data.

Instead of stacking away motion, SpacePixels analyzes the frame-to-frame changes, links detections across time, and exports a full HTML session report with diagnostics, crops, and animations.

Please check the [SpacePixels Wiki](https://github.com/ppissias/SpacePixels/wiki) or the [User Manual](MANUAL.md) for detailed instructions.

## 🔭 Why I Created SpacePixels: The Opposite of Stacking

In modern astrophotography, our primary goal is almost always to create a single, deep, noise-free image of a deep-sky object. To do this, we take dozens or hundreds of individual sub-exposures and run them through stacking software.

Those stacking algorithms, such as sigma clipping, are incredibly good at doing one specific thing: **throwing away anything that changes between frames**.

But what exactly are we throwing away? Those rejected pixels and artifacts are often real, dynamic events happening above our heads: asteroids, comets, meteors, artificial satellites, and unknown transients. By stacking, we are literally erasing the active universe to look at the static one.

**SpacePixels is the exact opposite of a stacking tool.** It was built to mine that discarded data. Instead of averaging out movement to hide it, SpacePixels actively hunts for movement across your sub-exposures to reveal the hidden transients you captured while you were focused on the deep-sky target.

## JTransient engine documentation

SpacePixels uses the [JTransient Engine](https://github.com/ppissias/JTransient) for the core extraction, purification, and track-linking pipeline.

For the underlying detection logic and the meaning of the engine configuration options, see:

- [JTransient repository](https://github.com/ppissias/JTransient)
- [JTransient algorithm overview](https://github.com/ppissias/JTransient/blob/main/ALGORITHM.md)
- [JTransient configuration reference](https://github.com/ppissias/JTransient/blob/main/CONFIG.md)

## What SpacePixels does

- Runs a standard multi-frame detection pipeline for moving targets, streak tracks, single-frame streaks, and bright anomalies.
- Searches the deep integrated stacks for ultra-slow movers and other elongated deep-stack candidates.
- Provides an iterative detection mode for large datasets and very slow targets.
- Generates an HTML report with diagnostics, GIFs, geometric overlays, global maps, and optional AI-themed summary sections.
- Supports manual transient inspection and frame-by-frame single-image detection preview.
- Blinks aligned frames for traditional visual inspection.
- Plate-solves images through ASTAP or Astrometry.net and uses WCS for RA/Dec overlays and report links.
- Batch-converts color FITS to monochrome and batch-stretches imported datasets.
- Includes headless utilities for batch detection and artificial star injection.

## Input requirements

- Best results come from aligned, calibrated sub-exposures where the star field is already registered.
- All frames in a sequence should share the same dimensions.
- The GUI can import `.fit`, `.fits`, and `.fts` files. Compressed `.fz` inputs are detected and can be decompressed into a new directory during import.
- The GUI can also standardize 32-bit FITS data down to 16-bit during import when needed.
- Automated detection requires 16-bit monochrome frames. If you import color images, use `Batch Convert to Mono` before running the detection pipelines.
- The headless batch detector is stricter than the GUI: it expects uncompressed 16-bit monochrome FITS files.

## Installation and launch

SpacePixels requires Java 21 or newer.

### Running a release build

Download the latest packaged release from:

[SpacePixels Releases](https://github.com/ppissias/SpacePixels/releases)

Then run:

- Windows: `SpacePixels.bat`
- Linux/macOS: `SpacePixels`

### Running from source

From the project root:

- Windows: `gradlew.bat run`
- Linux/macOS: `./gradlew run`

To generate a local distribution with launch scripts:

- Windows: `gradlew.bat installDist`
- Linux/macOS: `./gradlew installDist`

## Typical GUI workflow

1. Import a directory of aligned FITS files from `File -> Import aligned fits files`.
2. If the sequence is compressed or 32-bit, let SpacePixels decompress or standardize it first.
3. If the sequence is color, run `Batch Convert to Mono`.
4. Optionally configure ASTAP and observatory metadata in the `Astrometry Config` tab.
5. Optionally enable stretching in the `Image Stretch` tab for previews, blinking, and batch export.
6. Adjust detection settings or use `Auto-Tune Settings` in the `Detection Settings` tab.
7. Run either:
   - `Detect Moving Targets (Standard Pipeline)`, or
   - `Detect Iteratively (Large Datasets)` for large datasets where the standard full run may be too heavy.
8. When the run finishes, SpacePixels prompts you to open the generated HTML report or, for iterative runs, the results folder containing the per-pass reports.

## Report output

The standard pipeline exports an HTML session report plus PNG and GIF assets. Depending on the data and configuration, the report can include:

- Pipeline summary and astrometric context
- Rejected-frame diagnostics
- Full detection configuration export (`detection_config.json`)
- Master shield and veto-mask overlays
- Dither and drift diagnostics
- Frame extraction statistics and stationary-star purification diagnostics
- Track linking diagnostics
- Target visualizations for moving tracks, streak tracks, single-frame streaks, and anomalies
- Deep-stack anomalies and maximum-stack streak hints
- Global trajectory and transient maps
- Optional AI creative report sections

The AI creative sections are controlled by a session-only checkbox in `Detection Settings -> Advanced Visualization -> Optional Report Sections`. They are off by default and are not persisted with the saved detection profile.

## 📸 Screenshots & Output

Many thanks to [cloudynights user klangwolke](https://www.cloudynights.com/profile/481578-klangwolke/) for providing amazing data for this report.

Many thanks to cloudynights user TheStarsabove for providing data.

Many thanks to [Kumar](https://github.com/chvvkumar) for providing amazing data.

**Check out a sample report.**

<a href="https://startales.eu/various_files/detections_20260324_125211/detection_report.html" target="_blank">==&gt; Sample SpacePixels report</a>

![Main Interface](https://startales.eu/various_files/Screenshot2026-03-20215405.png)
*The SpacePixels Main Workspace.*

![Sample Detection](https://startales.eu/various_files/aptrack_1_star_centric.gif)
*Sample Detection - Comet Africano 2019*

![Sample Detection](https://startales.eu/various_files/aptrack_1_object_centric.gif)
*Sample Detection - Comet Africano 2019*

![Sample Detection](https://startales.eu/various_files/track_5_object_centric.gif)
*Sample Detection - Apophis asteroid 2021 (raw images credit Duncan Warren), object-centric view.*

![Sample Detection](https://startales.eu/various_files/track_5_star_centric.gif)
*Sample Detection - Apophis asteroid 2021 (raw images credit Duncan Warren), star-centric view.*

![Sample Detection](https://startales.eu/various_files/streak_track_3_star_centric.gif)
*Sample Detection - streak track.*

![Sample Detection](https://startales.eu/various_files/anomaly_17_context.gif)
*Sample Detection - high-energy anomaly, data credit cloudynights user TheStarsabove.*

## Command-line utilities

`build.gradle` generates dedicated launcher scripts for the command-line tools and adds them to the distribution `bin` folder alongside the main `SpacePixels` launcher.

After building or unpacking a distribution, you should find:

- Windows:
  - `bin\\SpacePixels.bat`
  - `bin\\batchDetect.bat`
  - `bin\\injectStars.bat`
- Linux/macOS:
  - `bin/SpacePixels`
  - `bin/batchDetect`
  - `bin/injectStars`

### Headless batch detection

Use either the packaged `batchDetect` launcher or the Gradle task.

Packaged launcher examples:

- Windows:
  - `bin\\batchDetect.bat "C:\\astro\\sequence" "C:\\astro\\detection_config.json"`
  - `bin\\batchDetect.bat "C:\\astro\\sequence" "C:\\astro\\detection_config.json" --auto-tune balanced`
- Linux/macOS:
  - `bin/batchDetect "/data/sequence" "/data/detection_config.json" --auto-tune balanced`

Gradle task examples:

- Windows:
  - `gradlew.bat batchDetect -PbatchArgs="\"C:\\astro\\sequence\" \"C:\\astro\\detection_config.json\""`
  - `gradlew.bat batchDetect -PbatchArgs="\"C:\\astro\\sequence\" \"C:\\astro\\detection_config.json\" --auto-tune balanced"`
- Linux/macOS:
  - `./gradlew batchDetect -PbatchArgs="\"/data/sequence\" \"/data/detection_config.json\" --auto-tune balanced"`

`batchDetect` accepts an exported `DetectionConfig` JSON and can optionally run Auto-Tune with `conservative`, `balanced`, or `aggressive`.

### Artificial star injection

Use either the packaged `injectStars` launcher or the Gradle task.

Packaged launcher examples:

- Windows:
  - `bin\\injectStars.bat C:\\astro\\sequence 15 10.0 4500 4.0`
- Linux/macOS:
  - `bin/injectStars /data/sequence 15 10.0 4500 4.0`

Gradle task examples:

- Windows:
  - `gradlew.bat injectStars -PinjArgs="C:\\astro\\sequence 15 10.0 4500 4.0"`
- Linux/macOS:
  - `./gradlew injectStars -PinjArgs="/data/sequence 15 10.0 4500 4.0"`

This utility injects synthetic moving stars into a FITS sequence for testing and validation.
