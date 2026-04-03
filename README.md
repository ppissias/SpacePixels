# SpacePixels 🌌

SpacePixels is a desktop and command-line FITS workflow for finding moving and transient objects in aligned astronomical image sequences. It is built around the JTransient detection engine and is aimed at asteroid, comet, satellite, and transient hunting from amateur data.

Instead of stacking away motion, SpacePixels analyzes the frame-to-frame changes, links detections across time, and exports a full HTML session report with diagnostics, crops, and animations.

More resources:
- [SpacePixels Website](https://startales.eu/SpacePixels/)
- [User Manual](MANUAL.md)
- [SpacePixels Wiki](https://github.com/ppissias/SpacePixels/wiki) 

Download the latest packaged release from:

[SpacePixels Releases](https://github.com/ppissias/SpacePixels/releases)

## 🔭 Why I Created SpacePixels: The Opposite of Stacking

In modern astrophotography, our primary goal is almost always to create a single, deep, noise-free image of a deep-sky object. To do this, we take dozens or hundreds of individual sub-exposures and run them through stacking software.

Those stacking algorithms, such as sigma clipping, are incredibly good at doing one specific thing: **throwing away anything that changes between frames**.


But what exactly are we throwing away? Those rejected pixels and artifacts are often real, dynamic events happening above our heads: asteroids, comets, meteors, artificial satellites, and unknown transients. By stacking, we are literally erasing the active universe to look at the static one.

**SpacePixels is the exact opposite of a stacking tool.** It was built to mine that discarded data. Instead of averaging out movement to hide it, SpacePixels actively hunts for movement across your sub-exposures to reveal the hidden transients you captured while you were focused on the deep-sky target.

## 📸 Screenshots & Output

Many thanks to [cloudynights user klangwolke](https://www.cloudynights.com/profile/481578-klangwolke/) for providing amazing data for this report.

Many thanks to cloudynights user TheStarsabove for providing data.

Many thanks to [Kumar](https://github.com/chvvkumar) for providing amazing data.

**Check out sample reports.**

<a href="https://startales.eu/various_files/detections_20260327_105248/detection_report.html" target="_blank">Sample SpacePixels report</a> <br/>
<a href="https://startales.eu/various_files/detections_20260327_104057/detection_report.html" target="_blank">Sample SpacePixels report (Apophis)</a> <br/>
<a href="https://startales.eu/various_files/detections_20260327_104830/detection_report.html" target="_blank">Sample SpacePixels report</a> <br/>
<a href="https://startales.eu/various_files/detections_20260327_104437/detection_report.html" target="_blank">Sample SpacePixels report (artificial slow mover injected)</a> <br/>

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

## Video Tutorial

<a href="https://youtu.be/7XBdYh0Wn7A?si=ymQmXHg92X651RmZ" target="_blank">
  <img src="https://img.youtube.com/vi/7XBdYh0Wn7A/mqdefault.jpg" alt="Watch the SpacePixels video tutorial on YouTube" width="320"/>
</a>

[Watch the video tutorial on YouTube](https://youtu.be/7XBdYh0Wn7A?si=ymQmXHg92X651RmZ)


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

<p><strong>Prerequisite:</strong> Java 21 must be installed on your system.</p>
<p>
  Please make sure you have Java 21 installed. Download Java: 
  <a href="https://www.oracle.com/java/technologies/downloads/" target="_blank" rel="noopener noreferrer">
    Oracle Java
  </a> or
  <a href="https://adoptium.net/temurin/releases/?version=21" target="_blank" rel="noopener noreferrer">
    Eclipse Temurin
  </a>
  

</p>

### Running a release build

Download the latest packaged release from:

[SpacePixels Releases](https://github.com/ppissias/SpacePixels/releases)

Then unzip / untar and run:

- Windows: `StartSpacePixels.bat`
- Linux/macOS: `./StartSpacePixels`

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


## Command-line utilities

`build.gradle` keeps the generated application launcher in `bin`, adds dedicated command-line tool launchers there, and ships top-level `StartSpacePixels` wrappers that check Java before delegating to the generated launcher.

After building or unpacking a distribution, you should find:

- Release root:
  - `StartSpacePixels.bat`
  - `StartSpacePixels`
- Windows:
  - `bin\\SpacePixels.bat`
  - `bin\\batchDetect.bat`
  - `bin\\injectStars.bat`
  - `config\\default_detection_profile.json`
- Linux/macOS:
  - `bin/SpacePixels`
  - `bin/batchDetect`
  - `bin/injectStars`
  - `config/default_detection_profile.json`

### Headless batch detection

Use either the packaged `batchDetect` launcher or the Gradle task.

Packaged launcher examples:

- Windows:
  - `bin\\batchDetect.bat "C:\\astro\\sequence" "config\\default_detection_profile.json"`
  - `bin\\batchDetect.bat "C:\\astro\\sequence" "config\\default_detection_profile.json" --auto-tune aggressive`
- Linux/macOS:
  - `bin/batchDetect "/data/sequence" "config/default_detection_profile.json" --auto-tune aggressive`

Gradle task examples:

- Windows:
  - `gradlew.bat batchDetect -PbatchArgs="\"C:\\astro\\sequence\" \"src\\dist\\config\\default_detection_profile.json\""`
  - `gradlew.bat batchDetect -PbatchArgs="\"C:\\astro\\sequence\" \"src\\dist\\config\\default_detection_profile.json\" --auto-tune aggressive"`
- Linux/macOS:
  - `./gradlew batchDetect -PbatchArgs="\"/data/sequence\" \"src/dist/config/default_detection_profile.json\" --auto-tune aggressive"`

`batchDetect` accepts a SpacePixels detection-profile JSON and can optionally run Auto-Tune with `conservative`, `balanced`, or `aggressive`.

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
