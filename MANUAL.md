# SpacePixels User Manual

## Table of contents

1. [Overview](#1-overview)
2. [Supported data and prerequisites](#2-supported-data-and-prerequisites)
3. [Installing and launching](#3-installing-and-launching)
4. [Main window layout](#4-main-window-layout)
5. [Importing a sequence](#5-importing-a-sequence)
6. [Main tab tools](#6-main-tab-tools)
7. [Astrometry Config tab](#7-astrometry-config-tab)
8. [Image Stretch tab](#8-image-stretch-tab)
9. [Detection Settings tab](#9-detection-settings-tab)
10. [Inspection workflows](#10-inspection-workflows)
11. [Detection pipelines](#11-detection-pipelines)
12. [HTML report guide](#12-html-report-guide)
13. [Plate solving and WCS behavior](#13-plate-solving-and-wcs-behavior)
14. [Command-line utilities](#14-command-line-utilities)
15. [Troubleshooting notes](#15-troubleshooting-notes)

---

## 1. Overview

SpacePixels is a Java desktop application for analyzing astronomical FITS sequences with a focus on moving-object detection. It is designed for aligned sub-exposures where the background star field is already registered and stable.

The application combines:

- automated transient detection and track linking
- deep-stack analysis for ultra-slow movers
- manual inspection tools such as blinking and transient browsing
- plate solving and WCS-aware viewers
- HTML report generation with diagnostics, maps, and animations

---

## 2. Supported data and prerequisites

### Input assumptions

SpacePixels works best when the input frames are:

- aligned or registered
- calibrated
- from the same camera setup and image dimensions
- part of a time-ordered observing sequence

SpacePixels is not a stacker. It expects the stars to stay fixed so that moving or transient objects can stand out against the stationary background.

### Supported FITS handling

The GUI import pipeline can work with:

- `.fit`, `.fits`, and `.fts`
- compressed `.fz` FITS files
- 16-bit and 32-bit FITS data
- monochrome and color sequences

Important distinction:

- the GUI can decompress `.fz` data into a new directory during import
- the GUI can standardize 32-bit FITS to 16-bit during import
- the detection engine itself runs on 16-bit monochrome frames
- if you import color data, detection buttons remain disabled until you convert the sequence to monochrome

### Recommended system requirements

- Operating system: Windows, macOS, or Linux
- Java runtime: Java 21 or newer
- Memory: 4 GB minimum, more for large datasets
- Optional software: ASTAP for local plate solving

---

## 3. Installing and launching

### Packaged release

Download the latest release from:

[SpacePixels Releases](https://github.com/ppissias/SpacePixels/releases)

Then run:

- Windows: `SpacePixels.bat`
- Linux/macOS: `SpacePixels`

### Running from source

From the project root:

- Windows: `gradlew.bat run`
- Linux/macOS: `./gradlew run`

To generate local distribution scripts:

- Windows: `gradlew.bat installDist`
- Linux/macOS: `./gradlew installDist`

---

## 4. Main window layout

The application is split into four tabs:

- `Main`
- `Astrometry Config`
- `Image Stretch`
- `Detection Settings`

Tabs other than `Main` stay disabled until a sequence is imported successfully.

### File menu

The menu bar currently exposes a single import action:

- `File -> Import aligned fits files`

### Main tab

The `Main` tab contains:

- a FITS metadata table
- top-row plate solving and blinking actions
- second-row preprocessing and detection actions
- a progress indicator and status area

### Other tabs

- `Astrometry Config` stores ASTAP and observer metadata.
- `Image Stretch` controls preview and export stretching.
- `Detection Settings` controls the JTransient detection profile plus visualization-only export settings.

---

## 5. Importing a sequence

### Basic import flow

1. Open `File -> Import aligned fits files`.
2. Select the directory containing your FITS sequence.
3. SpacePixels scans the folder and validates the frames.

### What SpacePixels may prompt you to do

Depending on the data, the import stage may prompt you to:

- decompress `.fz` files into a new uncompressed directory
- standardize 32-bit images down to 16-bit
- convert unsupported combinations into a supported working format

If decompression creates a new directory, SpacePixels can automatically redirect the import to that new directory.

### File table columns

The table shows per-file metadata such as:

- filename
- color space
- date and observation time
- exposure duration
- observing location if available
- solved state

### Important behavior for color data

If the imported sequence contains color images:

- `Batch Convert to Mono` is enabled
- detection actions are disabled

When the sequence is fully monochrome:

- single-frame preview
- manual transient inspection
- standard detection
- iterative detection

become available.

---

## 6. Main tab tools

### Plate Solve

Runs plate solving on the selected frame.

- You can choose `ASTAP` or `Astrometry.net (online)`.
- `Show Solved Preview` becomes available once a solve succeeds.

### Blink Selected

Plays the selected aligned frames as an animation.

- Select at least three rows in the table.
- This is useful for traditional visual hunting before or after automated detection.

### Batch Convert to Mono

Converts imported color FITS data into 16-bit monochrome.

- If stretching is enabled in the `Image Stretch` tab, the same stretch can be applied during export.
- This is the normal preparation step for color data before running detection.

### Batch Stretch

Applies the current stretch settings to all imported files and writes new FITS outputs.

### Detect on Selected Frame

Runs source extraction on the selected frame and opens the `Detection Sequence Viewer`.

Useful for:

- checking whether the current thresholds are sane
- stepping through the sequence with arrow keys
- inspecting WCS-aware cursor coordinates when available

### Manual Transient Inspection

Runs the transient detector across the sequence and opens a dedicated frame browser of the purified transients.

### Detect Moving Targets (Standard Pipeline)

Runs the full standard multi-frame detection and HTML export pipeline.

### Detect Iteratively (Large Datasets)

Runs the multi-pass iterative workflow intended for large datasets where the standard full run may be too memory-heavy.

---

## 7. Astrometry Config tab

The `Astrometry Config` tab stores application-level astrometry settings.

### External Tools

- `ASTAP Executable Path`

Use this to point SpacePixels at your local ASTAP installation.

### Detection and annotation metadata

- `IAU Observatory Code`
- `Site Latitude (N)`
- `Site Longitude (E)`

These values help SpacePixels build better report annotations and observer context when WCS is available.

### Solving parameter placeholders

The panel also contains fields such as:

- focal length
- pixel size
- approximate RA
- approximate DEC

In the current GUI these are present as placeholders and some remain disabled. The `Deduce from FITS header` button can populate them from the selected FITS header when the metadata exists.

### Saving

`Save Configuration` stores the application-level settings for future sessions.

---

## 8. Image Stretch tab

The `Image Stretch` tab controls preview and export-only stretch behavior.

### What the stretch tab affects

- blinking output
- batch-stretch export
- stretch previews

### Main controls

- `Enable Stretching (for blinking and batch export)`
- stretch algorithm selector
- intensity slider
- iteration slider

### Preview area

The tab shows:

- original image preview
- stretched preview

### Show full size

`Show full size` opens a dedicated stretched sequence viewer so you can inspect the imported sequence at larger scale.

---

## 9. Detection Settings tab

The `Detection Settings` tab controls the JTransient profile and SpacePixels-specific visualization settings.

### Buttons at the bottom

- `Apply Settings`
  - updates the current in-memory session
- `Save Configuration`
  - saves the JTransient profile used as the default on future startups
- `Preview Detection Settings`
  - runs a preview extraction on the selected frame
- `Auto-Tune Settings`
  - searches for a robust configuration automatically

### Auto-Tune behavior

Auto-Tune uses:

- the currently selected frames if you selected at least five frames, otherwise
- the full imported monochrome sequence

It requires at least five usable monochrome frames.

### Tab breakdown

#### Basic Tuning

Holds the most commonly used controls, including:

- detection sigma
- grow sigma
- minimum detection pixels
- streak elongation threshold
- star jitter and mask overlap limits
- anomaly rescue
- slow-mover stack detection
- export stretch preferences

#### Advanced Extractor

Contains lower-level extraction controls such as:

- edge margin
- void suppression
- master-map thresholds
- slow-mover extraction parameters
- single-streak and point-source thresholds
- background clipping controls

#### Advanced Kinematics

Controls the tracker and motion model, including:

- prediction tolerance
- angle tolerance
- maximum jump
- frame-ratio requirements
- rhythm consistency thresholds
- time-based velocity tolerance
- FWHM and surface-brightness ratios

#### Quality Control

Contains frame-quality rejection controls and single-frame quality extraction settings:

- minimum frames for analysis
- sigma-based star-count, FWHM, eccentricity, and background rejection
- absolute minimum tolerance envelopes
- dedicated quality-analysis extraction settings

#### Advanced Visualization

Contains visualization-only controls such as:

- streak line scale
- streak centroid box radius
- point-source box radius
- dynamic box padding
- track crop padding

It also contains a session-only checkbox:

- `Include AI Creative Report Sections`

This controls the optional AI-themed report sections and is intentionally not persisted when you save the detection configuration.

---

## 10. Inspection workflows

### Blink inspection

Use `Blink Selected` when you want a classic visual animation of a hand-picked subset of frames.

### Detection Sequence Viewer

Opened by `Detect on Selected Frame`.

Key behaviors:

- starts from the currently selected file
- uses arrow keys to move backward and forward through the sequence
- displays WCS cursor RA/Dec when available
- is ideal for checking whether the extractor settings are too aggressive or too conservative

### Manual Transient Inspection

Opened by `Manual Transient Inspection`.

Key behaviors:

- runs the transient detector against the sequence
- shows the purified transient footprints over the original frame
- uses arrow keys to navigate frame by frame
- shows cursor RA/Dec when WCS is available

This mode is useful for:

- validating noise rejection
- spotting faint movers that were not linked
- understanding why a later full report looks the way it does

---

## 11. Detection pipelines

### Standard pipeline

The standard pipeline:

1. loads the full monochrome sequence
2. builds the master stacks and masks
3. extracts per-frame transients
4. purifies them against the stationary background
5. links valid tracks
6. exports the HTML report and image assets

The output folder is created next to your data and named like:

`detections_YYYYMMDD_HHMMSS`

### Deep Stack Anomalies

If deep-stack detection is enabled, SpacePixels also searches for ultra-slow movers and elongated stack features that do not behave like ordinary stars.

This covers:

- candidates found in the slow-mover stack
- transient streaks found in the master maximum stack

### Iterative pipeline

The iterative pipeline is designed for:

- very large datasets
- datasets where the standard full-baseline run may be too memory-heavy

Workflow:

1. Click `Detect Iteratively (Large Datasets)`.
2. Enter a maximum frame limit, or leave it empty or zero to use the full range.
3. SpacePixels runs multiple temporally spaced passes.
4. A master iterative summary report is generated with links to the per-pass reports.

The iterative summary is an index page plus subfolders such as `5_frames`, `10_frames`, and so on.

---

## 12. HTML report guide

The standard session report is a dark-themed HTML dashboard. Depending on the dataset, it can include the following sections.

### Global diagnostics

- `Pipeline Summary`
- `Astrometric Context`
- `Quality Control: Rejected Frames`
- `Pipeline Configuration`
- `Master Shield & Veto Mask`
- `Dither & Sensor Drift Diagnostics`
- `Frame Extraction Statistics`
- `Phase 3: Stationary Star Purification`
- `Track Linking Diagnostics`

### Target sections

Under `Target Visualizations`, SpacePixels can produce:

- `Single Streaks`
- `Multi-Frame Streak Tracks`
- `Moving Target Tracks`
- `High-Energy Anomalies (Optical Flashes)`

These sections include combinations of:

- object-centric and star-centric GIFs
- shape maps
- tight pixel-evolution crops
- per-frame coordinate lists
- WCS-aware links and identification helpers when astrometry is available

### Deep-stack sections

If enabled and populated, the report can also include:

- `Deep Stack Anomalies (Ultra-Slow Mover Candidates)`
- `Master Maximum Stack Transient Streaks`

### Global map sections

- `Global Trajectory Map`
- `Global Transient Maps`

These sections summarize the full night in a single view and help reveal:

- track geometry
- hot columns and sensor defects
- unlinked transients
- clustered motion patterns

### Optional AI report sections

If you enable the session-only checkbox in `Detection Settings -> Advanced Visualization`, the report also includes:

- `The AI's Perspective: Skyprint of the Session`
- `The AI's Perspective: Hidden Rhythms`

These are optional visual summaries and are off by default.

### Iterative summary report

The iterative workflow exports a separate index report that lists each pass, the number of tracks found, the anomaly count, and a link to the corresponding sub-report.

When processing finishes, SpacePixels prompts you to open:

- the generated HTML report for the standard pipeline, or
- the results folder for the iterative pipeline

---

## 13. Plate solving and WCS behavior

SpacePixels supports both local and online plate solving.

### ASTAP

ASTAP is the preferred local solver when installed.

Setup:

1. Open `Astrometry Config`.
2. Set the ASTAP executable path.
3. Return to the `Main` tab.
4. Select a frame and click `Plate Solve`.

### Astrometry.net

If ASTAP is unavailable or you choose the online path, SpacePixels can use Astrometry.net.

Notes:

- it requires internet access
- it is slower than ASTAP
- a successful solve enables `Show Solved Preview`

### Where WCS is used inside SpacePixels

When WCS is available, SpacePixels uses it for:

- solved image previews
- cursor RA/Dec in the single-frame and transient viewers
- report astrometric context
- track and streak coordinate displays
- solar-system lookup and identification links

---

## 14. Command-line utilities

### Headless batch detection

`BatchDetectionCli` runs the standard pipeline without the GUI.

Usage pattern:

`batchDetect <fits_directory> <detection_config.json> [--auto-tune <conservative|balanced|aggressive>]`

Important limitations:

- the directory must contain uncompressed 16-bit monochrome FITS files
- the config file must be a valid exported `DetectionConfig` JSON

Gradle example:

- Windows:
  - `gradlew.bat batchDetect -PbatchArgs="\"C:\\astro\\sequence\" \"C:\\astro\\detection_config.json\" --auto-tune balanced"`
- Linux/macOS:
  - `./gradlew batchDetect -PbatchArgs="\"/data/sequence\" \"/data/detection_config.json\" --auto-tune balanced"`

### Artificial star injection

`ArtificialStarInjector` injects synthetic moving stars into a FITS sequence for testing.

Gradle example:

- Windows:
  - `gradlew.bat injectStars -PinjArgs="C:\\astro\\sequence 15 10.0 4500 4.0"`
- Linux/macOS:
  - `./gradlew injectStars -PinjArgs="/data/sequence 15 10.0 4500 4.0"`

Arguments:

- input directory
- number of stars
- total motion in pixels across the sequence
- peak ADU above background
- star FWHM in pixels

---

## 15. Troubleshooting notes

### Detection buttons are disabled

Most likely causes:

- you imported color frames and have not converted them to monochrome yet
- the sequence import failed

### Large datasets

Large datasets can consume significant memory, especially in the standard pipeline.

Recommendations:

- prefer the iterative pipeline for large runs
- limit the frame count when prompted
- close other memory-heavy applications

### Out of memory

SpacePixels has explicit out-of-memory handling and may exit if the JVM runs out of RAM.

If that happens:

- process fewer frames at once
- use the iterative pipeline
- increase available Java memory if you launch manually

### Plate solving problems

Check:

- ASTAP path is valid
- the selected image is appropriate for solving
- internet access is available if using Astrometry.net

### Saved settings behavior

- `Astrometry Config -> Save Configuration` stores app-level settings such as ASTAP and observer metadata.
- `Detection Settings -> Save Configuration` stores the JTransient detection profile.
- `Include AI Creative Report Sections` is session-only and is not saved intentionally.
