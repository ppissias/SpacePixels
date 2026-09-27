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
- plate solving, WCS-aware viewers, and object-identification links
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

### Supported image handling

The GUI import pipeline can work with:

- `.fit`, `.fits`, and `.fts`
- compressed `.fz` FITS files
- XISF-only directories
- 16-bit and 32-bit FITS data
- monochrome and color sequences

Important distinction:

- the GUI can decompress `.fz` data into a new directory during import
- the GUI can convert XISF-only directories into detection-ready FITS files during import
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

- Windows: `StartSpacePixels.bat`
- Linux/macOS: `./StartSpacePixels`

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

- `File -> Import aligned FITS/XISF files`

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

1. Open `File -> Import aligned FITS/XISF files`.
2. Select the directory containing your aligned FITS or XISF sequence.
3. SpacePixels scans the folder and validates the frames.

### What SpacePixels may prompt you to do

Depending on the data, the import stage may prompt you to:

- decompress `.fz` files into a new uncompressed directory
- convert a XISF-only directory into a FITS working directory
- standardize 32-bit images down to 16-bit
- convert unsupported combinations into a supported working format

If decompression, XISF conversion, or format standardization creates a new directory, SpacePixels can automatically redirect the import to that generated working directory.

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
  - saves the JTransient detection profile and visualization preferences used as defaults on future startups
- `Preview Detection Settings`
  - runs a preview extraction on the selected frame
- `Auto-Tune Settings`
  - searches for a robust configuration automatically
- `Load Defaults`
  - loads a fresh JTransient `DetectionConfig` into the panel and current session without overwriting your saved profile unless you save afterward

### Auto-Tune behavior

SpacePixels Auto-Tune is a fast configuration search, not a full detection run. It does not link tracks, run the slow-mover branch, or export a final `PipelineResult`.

SpacePixels chooses the frame pool this way:

- the currently selected frames if you selected at least four frames, otherwise
- the full imported monochrome sequence

It requires at least four usable monochrome frames in the SpacePixels GUI. JTransient's standalone default sample size is five, but SpacePixels temporarily lowers the sample size when a valid smaller pool is available.

For long sequences, the `Max Frames For Auto-Tuner` setting limits the candidate pool. When the sequence is longer than that limit, SpacePixels builds a deterministic pool from:

- best-quality frames
- median-quality frames
- evenly spaced sequence coverage

JTransient then:

1. evaluates frame quality using the dedicated quality-analysis thresholds
2. selects a representative sample from the candidate pool
3. extracts several interior crops from those frames
4. builds cropped median master stacks
5. calibrates `maxStarJitter` from measured star displacement
6. sweeps detection sigma, grow sigma, minimum detection pixels, and mask overlap
7. validates the winning configuration on the same frozen crops

Auto-Tune actively changes:

- `detectionSigmaMultiplier`
- `growSigmaMultiplier`
- `minDetectionPixels`
- `maxMaskOverlapFraction`
- `maxStarJitter`

Most other settings are preserved from your current base configuration. The `Conservative`, `Balanced`, and `Aggressive` profiles use the same search grid but different scoring policies:

- `Conservative` suppresses transient leakage more strongly
- `Balanced` is the default middle ground
- `Aggressive` allows more leakage to preserve faint-target sensitivity

### Tab breakdown

#### Basic Tuning

Holds the core per-frame extraction controls:

- detection sigma
- grow sigma
- minimum detection pixels

These are usually the first fields to adjust manually:

- raise them when the report is flooded with noise
- lower them cautiously when faint real sources are missed

#### Object Detection

Controls master-star masking and low-level extraction safeguards:

- master sigma and master minimum pixels for the stationary-star veto map
- mask-overlap tolerance before an object is rejected as a stellar residual
- physical edge margin
- registration-void threshold and proximity radius
- histogram background clipping iterations and factor

The engine may raise `voidProximityRadius` during border-drift diagnostics if the measured registration padding requires a safer value.

#### Streak Detection

Controls elongated-object classification and streak linking:

- minimum elongation and footprint size for streak classification
- minimum peak sigma for one-frame streak tracks
- trajectory angle tolerance
- timestamp-based streak time-consistency tolerance
- binary-star-like shape veto for unmatched one-frame streak candidates

Single-frame streaks that fail the peak-sigma or binary-star-like shape checks can still remain as standalone post-veto streak detections; they are just not promoted to one-point streak tracks.

#### Moving Objects

Controls point-source track construction:

- strict exposure kinematics
- optional geometric track linking when timestamps are present
- base star-jitter radius
- prediction-line tolerance
- minimum track-length ratio and absolute cap
- maximum geometric jump
- FWHM and surface-brightness consistency ratios
- time-based velocity tolerance
- geometric rhythm checks

When valid timestamps are available, JTransient tries the time-based point linker first. If timestamps are missing, the geometric linker is forced because it is the only point-track path. If timestamps are available, `Enable Geometric Track Linking` controls whether the geometric fallback also runs.

#### Anomaly Detection

Controls the final one-frame rescue stage:

- anomaly rescue master switch
- minimum peak sigma
- minimum integrated sigma
- minimum footprint sizes
- peak-sigma floor for broad diffuse anomalies
- suspected same-frame streak line tolerance

Rescued anomalies can remain as standalone peak- or integrated-sigma anomalies. Rescued anomalies from the same frame can also be grouped into suspected streak tracks when their centroids form a convincing line.

#### Slow Movers

Controls the deep-stack branch for ultra-slow movers:

- slow-mover branch enable switch
- slow-mover stack extraction sigma, grow sigma, and minimum pixels
- slow-mover stack middle fraction
- dynamic elongation baseline multiplier
- median-stack support overlap bounds
- residual-footprint filtering in `slowMoverStack - medianStack`

This branch is separate from ordinary frame-to-frame point linking. It is designed for objects that move so slowly that they are better revealed in a specialized stack than as isolated per-frame points.

#### Residual Analysis

Controls the final pass over leftover point detections that were not consumed by confirmed tracks, streak tracks, suspected streak groupings, or standalone anomalies:

- residual transient analysis master switch
- local rescue candidates
- local activity clusters
- local activity cluster radius
- minimum unique frames for activity clusters

Local rescue candidates can surface weak patterns such as micro-drift, sparse local drift, or local repeaters. Local activity clusters are broader review groups, not confirmed moving objects.

#### Quality Control

Contains frame sampling, session rejection, and quality-extraction settings:

- maximum candidate frames for Auto-Tune
- minimum frames for analysis
- sigma-based star-count, FWHM, eccentricity, and background rejection
- bright-star eccentricity filtering
- absolute minimum tolerance envelopes
- dedicated quality-analysis extraction thresholds

These controls affect both pipeline frame rejection and the quality-based candidate pool that SpacePixels prepares for Auto-Tune. The quality-side grow sigma is deliberately separate from the main detection grow sigma, so previous tuning output does not feed back into frame sampling.

#### Advanced Visualization

Contains visualization-only controls such as:

- automatic report stretch black and white sigma
- GIF blink speed
- streak line scale
- streak centroid box radius
- point-source box radius
- dynamic box padding
- track crop padding

It also contains:

- `Include AI Creative Report Sections`

These settings do not change detection results. They are saved as SpacePixels visualization preferences, separately from the JTransient detection profile.

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

The standard pipeline uses JTransient's full `runPipeline(...)` entry point. It runs the detector end to end and then exports SpacePixels report assets.

High-level workflow:

1. load the monochrome sequence in chronological order
2. measure border drift and registration padding
3. extract sources from each frame in parallel
4. measure per-frame quality in parallel
5. reject outlier frames for the session
6. build or reuse the median master stack
7. extract the stationary master-star map
8. optionally run the slow-mover stack analysis
9. filter per-frame detections against the stationary-star veto mask
10. link fast streaks
11. link point-like movers with the time-based linker when timestamps are available
12. optionally run the geometric point linker, or force it when timestamps are missing
13. rescue strong one-frame anomalies
14. group collinear rescued anomalies into suspected same-frame streak tracks
15. consolidate streak tracks
16. analyze leftover residual point transients
17. build the maximum stack for visualization
18. export the HTML report and image assets

The output folder is created next to your data and named like:

`detections_YYYYMMDD_HHMMSS`

### How source extraction works

For each frame, JTransient estimates the background with histogram sigma clipping. It then uses two thresholds:

- a strict seed threshold from `detectionSigmaMultiplier`
- a lower grow threshold from `growSigmaMultiplier`

Pixels above the seed threshold start a blob. The blob grows through neighboring pixels while they remain above the grow threshold. After that, JTransient measures centroid, flux, peak sigma, integrated sigma, elongation, angle, and approximate FWHM.

Objects smaller than `minDetectionPixels` are discarded. Elongated objects whose footprint is large enough become streak candidates. Other detections remain point-like candidates. Edge and registration-void filters remove objects likely caused by physical borders or black padding from alignment.

### Frame quality and rejection

The quality analyzer runs a stricter extraction pass using the `Quality Control` thresholds. It measures:

- star count
- background median and noise
- median FWHM
- median eccentricity
- bright-star median eccentricity when enough bright stars are available

The session evaluator compares each frame against the session median using MAD-derived sigma estimates. Frames can be rejected for low star count, high FWHM, high eccentricity, high bright-star eccentricity, or background deviation. The absolute minimum tolerance fields keep these envelopes from becoming unrealistically tight on very stable data.

Rejected frames are excluded from stacking and tracking, and the report records the rejection telemetry.

### Stationary-star veto

The median master stack represents the stable sky. JTransient extracts master stars from that stack and builds a veto mask. Detections whose footprints overlap that mask too strongly are treated as stationary-star residuals and removed from the transient pool.

The veto mask is dilated from `maxStarJitter`, which represents the expected residual star wobble after registration. Auto-Tune measures this value from sampled stars.

### Track linking

JTransient separates streak-like and point-like detections before linking.

Streak handling:

- links multi-frame streak fragments by angle, direction, line consistency, and time or rhythm consistency
- promotes high-confidence one-frame streaks when they pass the single-streak significance and shape checks
- preserves unmatched post-veto streak detections for reporting even when they are not promoted to tracks

Point-source handling:

- uses timestamp-aware velocity linking first when usable timestamps are present
- applies strict exposure kinematics when enabled and exposure durations are available
- can run a geometric fallback when timestamps are present and `Enable Geometric Track Linking` is enabled
- always uses the geometric linker when timestamps are missing
- checks FWHM and surface-brightness consistency between linked detections

The geometric linker also prunes near-stationary steps and validates steady motion rhythm so random residuals are less likely to become tracks.

### Anomalies and residuals

After confirmed tracks are removed, the anomaly rescue pass scans remaining transient detections.

It can rescue:

- compact high-peak-sigma events
- broader high-integrated-sigma events

Rescued same-frame anomalies can be grouped into suspected streak tracks if their centroids are collinear. After that, residual analysis can mine leftover non-streak point detections for weaker local patterns and optional broader activity clusters. Residual-analysis results are review aids; they are exported separately from confirmed tracks and standalone anomalies.

### Slow-mover and deep-stack analysis

If deep-stack detection is enabled, SpacePixels also searches for ultra-slow movers and elongated stack features that do not behave like ordinary stars.

The slow-mover branch builds a specialized stack from the upper end of a middle band of sorted pixel values. This is different from a plain maximum stack: it favors semi-persistent weak structure while avoiding many one-frame flashes.

Slow-mover candidates are filtered by:

- dynamic elongation relative to the field baseline
- minimum and maximum median-stack support overlap
- optional positive residual flux in `slowMoverStack - medianStack`

The standard report can also use the maximum stack for visual diagnostics and elongated transient hints.

### Main result categories

The standard pipeline can produce:

- confirmed moving-object tracks
- confirmed streak tracks
- one-frame streak tracks
- suspected same-frame streak tracks
- standalone peak- or integrated-sigma anomalies
- slow-mover stack candidates
- residual local rescue candidates
- residual local activity clusters
- unclassified post-veto transients for diagnostics

### Iterative pipeline

The iterative pipeline is designed for:

- very large datasets
- datasets where the standard full-baseline run may be too memory-heavy
- very slow or sparse targets that may benefit from several temporally spaced passes

Workflow:

1. Click `Detect Iteratively (Large Datasets)`.
2. Enter a maximum frame limit, or leave it empty or zero to use the full range.
3. SpacePixels runs multiple temporally spaced pipeline passes.
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
- slow-mover and residual-analysis diagnostics when populated

### Target sections

Under `Target Visualizations`, SpacePixels can produce:

- `Single Streaks`
- `Multi-Frame Streak Tracks`
- `Moving Target Tracks`
- suspected same-frame streak tracks
- `High-Energy Anomalies (Optical Flashes)`
- residual local rescue or activity review sections when populated

These sections include combinations of:

- object-centric and star-centric GIFs
- shape maps
- tight pixel-evolution crops
- per-frame coordinate lists
- WCS-aware links and identification helpers when astrometry is available

The identification helpers are split by target type:

- Moving-object tracks get SkyBoT cone searches and JPL Small-Body Identification links for asteroid, comet, and NEO candidate checks.
- Confirmed streak tracks get SatChecker satellite-pass lookups using the measured streak midpoint, time window, observing site, and field-of-view geometry.
- Moving-object tracks and streak tracks both get Stellarium Web links so you can inspect the same sky position and observing time in external sky context.
- When supported by the generated report, SatChecker and JPL results can also be rendered inside the report for quick inspection before opening the raw service response.

### Deep-stack sections

If enabled and populated, the report can also include:

- `Deep Stack Anomalies (Ultra-Slow Mover Candidates)`
- `Master Maximum Stack Transient Streaks`

Deep-stack candidates should be treated as review candidates. They are useful for surfacing ultra-slow or semi-persistent features, but they are separate from ordinary frame-to-frame confirmed tracks.

### Global map sections

- `Global Trajectory Map`
- `Global Transient Maps`

These sections summarize the full night in a single view and help reveal:

- track geometry
- hot columns and sensor defects
- unlinked transients
- clustered motion patterns

### Optional AI report sections

If you enable the checkbox in `Detection Settings -> Advanced Visualization`, the report also includes:

- `The AI's Perspective: Signal Weave`
- `The AI's Perspective: Hidden Rhythms`

These are optional visual summaries and are off by default.

The toggle is saved with SpacePixels visualization preferences when you use `Save Configuration`; it is not part of the JTransient detection profile.

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
- SkyBoT and JPL Small-Body Identification links for moving-object tracks
- SatChecker satellite-identification links for streak tracks
- Stellarium Web links for both moving-object and streak sky context

### Report identification links

For moving-object tracks, SpacePixels builds SkyBoT and JPL Small-Body Identification queries from the measured track sky position, observation time, search radius, and available observer metadata. SkyBoT is useful for solar-system cone searches, while JPL Small-Body Identification provides a second small-body check and a wider NEO-recovery fallback.

For confirmed streak tracks, SpacePixels builds SatChecker queries from the measured streak midpoint, the time window covered by the track, the observing site, and a tight field-of-view radius. These links are intended for satellite candidate identification.

For both moving tracks and streak tracks, SpacePixels also adds Stellarium Web links. These open an external sky-context view at the relevant observing time and target position, using WCS-derived coordinates when available.

---

## 14. Command-line utilities

### Headless batch detection

`BatchDetectionCli` runs the standard pipeline without the GUI.

Usage pattern:

`batchDetect <fits_directory> <detection_config.json> [--auto-tune <conservative|balanced|aggressive>]`

Important limitations:

- the directory must contain uncompressed 16-bit monochrome FITS files
- the config file must be a valid SpacePixels detection-profile JSON with flat JTransient `DetectionConfig` fields plus `autoTuneMaxCandidateFrames`
- packaged distributions include `config/default_detection_profile.json`
- if `--auto-tune` is supplied, the tuned configuration is used for the pipeline run and exported with the report

Gradle example:

- Windows:
  - `gradlew.bat batchDetect -PbatchArgs="\"C:\\astro\\sequence\" \"src\\dist\\config\\default_detection_profile.json\" --auto-tune aggressive"`
- Linux/macOS:
  - `./gradlew batchDetect -PbatchArgs="\"/data/sequence\" \"src/dist/config/default_detection_profile.json\" --auto-tune aggressive"`

Internally, `BatchDetectionCli` reuses the same public Java pipeline API described below. It keeps strict input behavior by using `FAIL_IF_NOT_READY`, but it still uses SpacePixels' candidate-pool builder when Auto-Tune is enabled.

### Embedded Java pipeline API

SpacePixels also exposes a public Java API for running the same standard pipeline from another application without going through the GUI.

Primary types:

- `eu.startales.spacepixels.api.SpacePixelsPipelineApi`
- `eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi`
- `eu.startales.spacepixels.api.SpacePixelsPipelineRequest`
- `eu.startales.spacepixels.api.SpacePixelsPipelineResult`
- `eu.startales.spacepixels.api.InputPreparationMode`

Typical usage:

```java
import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineApi;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;

import java.io.File;

SpacePixelsPipelineApi api = new DefaultSpacePixelsPipelineApi();

SpacePixelsPipelineRequest request = SpacePixelsPipelineRequest.builder(new File("C:\\astro\\sequence"))
        .detectionConfig(new DetectionConfig())
        .autoTuneProfile(JTransientAutoTuner.AutoTuneProfile.BALANCED)
        .inputPreparationMode(InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO)
        .generateReport(true)
        .progressListener((percentage, message) -> System.out.println(percentage + "% " + message))
        .build();

SpacePixelsPipelineResult result = api.run(request);
```

Behavior summary:

- `inputDirectory` is required and points at the source sequence directory.
- `detectionConfig` is optional. If omitted, SpacePixels uses a new default `DetectionConfig`.
- `autoTuneProfile` is optional. If provided, Auto-Tune runs before the pipeline and the tuned configuration is exposed as `effectiveConfig` in the result.
- `autoTuneMaxCandidateFrames` is optional and limits the Auto-Tune candidate pool size.
- `generateReport` controls whether the standard HTML report and export assets are written to disk.
- `progressListener` is optional and receives coarse `0..100` progress updates.

Input preparation modes:

- `FAIL_IF_NOT_READY`
  - requires the input directory to already contain uncompressed 16-bit monochrome FITS files
- `AUTO_PREPARE_TO_16BIT_MONO`
  - accepts supported FITS or XISF inputs and creates a new detection-ready 16-bit monochrome FITS directory first

What the result contains:

- `originalInputDirectory`
- `preparedInputDirectory`
- `inputWasPrepared`
- validated `FitsFileInformation[]`
- `baseConfig`
- `effectiveConfig`
- optional Auto-Tune profile and telemetry
- raw `PipelineResult`
- optional `exportDirectory` and `reportFile`

Practical notes:

- report generation is optional, but the raw `PipelineResult` is always returned on success
- if Auto-Tune is requested and fails, the API throws `SpacePixelsPipelineException`; it does not silently fall back to the base configuration
- the prepared directory is kept on disk and returned to the caller when automatic preparation is used
- `BatchDetectionCli` keeps its current strict behavior by using `FAIL_IF_NOT_READY`
- Auto-Tune-enabled API runs should not be executed concurrently in the same JVM because the implementation temporarily adjusts shared JTransient Auto-Tune sample-size state

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
- `Detection Settings -> Save Configuration` stores the JTransient detection profile in `spacepixels_detection_profile.json`.
- Visualization-only report/export preferences are stored separately in `spacepixels_visualization.json`.
- `Include AI Creative Report Sections` is a visualization preference, not a detection-profile field.
