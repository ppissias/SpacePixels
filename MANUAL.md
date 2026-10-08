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
- maximum-stack morphology candidates for slow-mover review
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

**Window size and position:**

- On first start, the window opens at about 85 % of the usable screen area, centred, capped at 1500 × 950 on large screens and never larger than the screen. Its minimum size is 980 × 560.
- It can be resized or maximised, and SpacePixels remembers the size, position and maximised state for the next start.
- If the saved position no longer fits on any screen (for example, a monitor was removed), the default is used.
- The settings pages follow the window width instead of scrolling sideways. On narrow windows, the Overview's two columns go under each other.

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

- `Convert to Mono` is enabled
- detection actions are disabled

When the sequence is fully monochrome:

- single-frame preview
- manual transient inspection
- standard detection
- iterative detection

become available.

---

## 6. Main tab tools

The tools sit above the frame table in four groups, left to right in the order you normally use them: **1 Prepare**, **2 Astrometry**, **3 Inspect** and **4 Detect**. On a narrow window the groups wrap onto a second line.

- Each group ends with a status line that uses what is already known: "✓ Monochrome, 18 frames" or "Colour frames: convert first", "2 / 18 solved", the number of selected frames, and the next step for detection.
- When a button is disabled, its tooltip says why, for example "select at least 3 frames in the table" or "convert the frames to monochrome first".
- Right-click the frame table for `Blink Selected`, `Preview Frame` and `Plate Solve Selected`.
- Keyboard shortcuts: `Ctrl+D` Detect Moving Targets, `Ctrl+B` Blink Selected, `Ctrl+P` Preview Frame.
- The progress bar of a running task is at the right end of the status bar.

### 1 Prepare

#### Convert to Mono

Converts imported color FITS data into 16-bit monochrome.

- If `Write stretched copies` is ticked in the `Image Stretch` tab, it also writes stretched copies into a separate `_mono_stretched` folder; the linear mono files used for detection are unchanged.
- This is the normal preparation step for color data before running detection.
- Disabled when all frames are already monochrome.

#### Batch Stretch

Applies the current stretch settings to all imported files and writes new FITS outputs. Available when `Write stretched copies` is ticked in the `Image Stretch` tab.

### 2 Astrometry

#### Plate Solve Selected

Runs plate solving on the selected frame with the solver chosen below the button: `ASTAP` (local) or `Astrometry.net (online)`.

One solved frame is enough. The frames are aligned, so the report uses that solution for all of them. With it, the report gives sky coordinates, identifies moving objects with JPL and SkyBoT, and matches variable-star candidates against AAVSO VSX. Until a frame is solved, the group's status line reads "Solve one frame to identify objects".

### 3 Inspect

#### Blink Selected

Plays the selected aligned frames as an animation.

- Select at least three rows in the table.
- This is useful for traditional visual hunting before or after automated detection.

#### Preview Frame

Runs source extraction on the selected frame and opens the `Detection Sequence Viewer`.

Useful for:

- checking whether the current thresholds are sane
- stepping through the sequence with arrow keys
- inspecting WCS-aware cursor coordinates when available

#### Manual Transient Inspection

Runs the transient detector across the sequence and opens a dedicated frame browser of the purified transients.

### 4 Detect

#### Detect Moving Targets

The primary action (highlighted button). Runs the full standard multi-frame detection and HTML export pipeline.

#### Detect Iteratively (large datasets)

Runs the multi-pass iterative workflow intended for large datasets where the standard full run may be too memory-heavy.

#### Variable-star photometry

Switches the variable-star analysis on or off for the next `Detect Moving Targets` run. It is the same setting as `Enable Variable-Star Detection` on the `Variable Stars` page and on the Overview, so all three stay in step. The analysis measures the field stars in every frame and reports light curves and variable-star candidates. The iterative mode skips it. To match candidates against AAVSO VSX, plate-solve one frame first.

#### Edit settings…

Opens the `Detection Settings` tab, where you can run Auto-Tune or adjust the thresholds.

---

## 7. Astrometry Config tab

The `Astrometry Config` tab holds the plate solver and the observing site used to identify objects in the report.

### Plate Solving

- **`ASTAP Executable Path`**: points SpacePixels at your local ASTAP.
  - Below the path, a status line shows whether ASTAP was found and which star databases sit next to it, for example `✓ ASTAP found · star database D50`.
  - Without a star database ASTAP cannot solve. The status line then says so and links to the ASTAP download page; D50 is a good choice for most setups.
  - `Check` repeats the check.
- **`Astrometry.net (online)`** needs no installation. Images are submitted as **private**, so they do not appear in the public Astrometry.net gallery.

One plate-solved frame is enough for the whole aligned sequence (see `Plate Solve Selected`).

### Observing Site

- `IAU Observatory Code`
- `Site Latitude (N)`
- `Site Longitude (E)`

These values improve the report's object identification (SkyBoT, JPL) and observer context.

- **Checked as you type:** the code must be 3 letters or digits, the latitude within ±90 and the longitude within ±180. Coordinates are decimal degrees, optionally with a decimal comma or a hemisphere letter (`24.6272S`). A short note under each field shows a problem in orange, or confirms the value.
- **Which value is used:** if any imported frame has a site latitude or longitude in its FITS header (`SITELAT`, `OBSGEO-B`, `LAT-OBS`, `SITELONG`, `OBSGEO-L`, ...), the report uses the header value, and the note says so. Otherwise the field is used.
- **`Fill Site from FITS Header`** copies the header's latitude and longitude into the fields, so they stay available as the fallback for sessions without headers.

### Applying and saving

- Valid edits apply to the current session at once. While a field holds an invalid value, the last valid value stays in use.
- The footer shows `✓ Saved settings in use` or `● Unsaved changes`.
- `Save` keeps the values for the next start, and `Revert` returns to the last saved values.

The near-solve fields of earlier versions (focal length, pixel size, approximate RA/DEC) are no longer shown. Their stored values remain in the configuration file.

---

## 8. Image Stretch tab

The `Image Stretch` tab sets the display stretch used by `Blink Selected`, `Show full size` and the optional stretched copies.

- Detection always works on the linear data.
- Report images use their own stretch (`Detection Settings -> Report Visualization`).

### Controls

- **Algorithm** (hover an entry for a description):
  - `Asinh` (recommended): sets the black point at a percentile of the histogram and lifts faint signal with an arcsinh curve.
  - `Enhance Low`: brightens faint pixels.
  - `Enhance High`: boosts the brighter pixels.
  - `Extreme`: shows every pixel above the noise level at one bright value; harsh, but faint objects stand out when blinking.
- **Two sliders**, labelled per algorithm with their current values (for Asinh: `Black Point (%)` and `Stretch Strength`). `Reset to Defaults` restores the algorithm's defaults.
- The algorithm and each algorithm's slider values are **remembered between sessions**.
- **`Write stretched copies (Batch Stretch, Convert to Mono)`**: when ticked, `Batch Stretch` is available, and `Convert to Mono` also writes stretched copies into a separate `_mono_stretched` folder. The linear files used for detection are never changed.

### Preview

The preview appears whenever the tab is open and a frame is selected; no checkbox is needed.

- Original (linear) and stretched views of the **whole frame**. Frames larger than 2400 pixels on their long side are reduced for display.
- **Zoom and pan:** scroll to zoom around the cursor, drag to pan, double-click to fit. Both views zoom and pan together.
- **`◀ Previous` / `Next ▶`** step through the frames. The label shows the frame number and file name, and the zoom is kept.
- **Histograms** under both views, with logarithmic counts and the share of pixels clipped to black and to white. On the linear view, it shows where the sky background sits.

### Show full size

`Show full size` opens a dedicated stretched sequence viewer, so you can inspect the imported sequence at full resolution with the same stretch.

---

## 9. Detection Settings tab

The `Detection Settings` tab controls the JTransient profile and SpacePixels-specific visualization settings.

### Navigation, search and changed settings

A page list on the left replaces the old row of tabs:

| Group | Pages |
|---|---|
| (top) | `Overview` |
| DETECTION | `Object Detection`, `Streak Detection`, `Quality Control` |
| MOVING OBJECTS | `Track Linking`, `Anomaly Detection`, `Slow Movers`, `Residual Analysis` |
| VARIABLE STARS | `Variable Stars` |
| REPORT | `Report Visualization` |

**Search.** The search box above the list (`Ctrl+F`) filters every page at once by setting name, description or section. The list shows the number of matches per page. If the open page has none, the first page with a match opens.

**Show only changed.** Shows only the settings that differ from their built-in defaults. The checkbox shows how many there are. Without the filter, a dot after a page name marks a page with changed settings.

**Changed settings.**

- A setting that differs from its default shows its title in the accent colour.
- A `Reset` button next to it restores the default, and its tooltip shows the default value.
- Section headers count their changed settings.

**Expert sections.** These start collapsed, showing for example "▸ Advanced Settings · 8 settings":

- the `Advanced Settings` blocks;
- `Linearity Checks` and `Candidate Gates` on `Variable Stars`;
- `Absolute Minimum Tolerances` and `Single Frame Analytics` on `Quality Control`.

Click the header to open or close one. While a search or the changed filter is active, matching settings inside collapsed sections are shown anyway.

### Applying and saving

Every change applies to the current session as soon as you make it; there is no Apply button. The footer shows `✓ Saved settings in use` or `● Unsaved changes`.

- `Save` stores the JTransient detection profile and the visualization preferences as the defaults for future startups.
- `Revert` returns every setting to the last saved state.
- `Load Defaults` loads a fresh JTransient `DetectionConfig` into the current session. The saved profile is unchanged until you save, and `Revert` undoes it.

### Overview page

The first page is the starting point. It has three parts.

**Auto-Tune**

- `Tuner`, `Apply after run` and `Run Auto-Tune`.
- How one run covers every profile: the calibrated tuner measures every combination of settings once (noise detections and test stars found). A profile is only a budget of noise detections per megapixel per frame, so each profile then picks the most sensitive combination within its budget from the same measurements. Choosing the profile takes no extra measuring.
- A table with the measured result of every profile, filled by one calibrated run:
  - **Profile**, with its budget of noise detections, for example `Balanced (≤ 0.2)`;
  - **Detection σ / grow / min px**: the per-frame detection settings;
  - **Star mask σ / grow / min px** and **Mask overlap**: the master star mask and how much a detection may overlap it;
  - **Noise detections / MPix / frame**: detections that are not real objects (noise peaks and star leftovers the settings let through), with ⚠ when no setting met the profile's budget;
  - **Expected noise detections**: how many to expect in a full run on this session (measured rate × sensor megapixels × frames);
  - **Test stars found**: the share of synthetic test stars (added at peak SNR 2–15) that the settings still find;
  - **Detection limit (SNR)**: the peak signal-to-noise at which half of the test stars are found. Lower is more sensitive; halving it reaches objects about 0.75 mag fainter;
  - **Sky masked**: the share of the sky hidden by the star mask.
- When the run finishes, the profile chosen in `Apply after run` is applied. To switch, select another row and click `Use Selected Profile`, or double-click the row; no re-run is needed.
- The legacy tuner is different: its profile steers the search itself, so it tunes one profile per run and shows one row. With the legacy tuner selected, the box is labelled `Profile` and offers no `Maximum` (the legacy tuner would treat it like Aggressive).
- Each tuner keeps its own last result for the session: switching the `Tuner` box shows that tuner's table, or an empty one if it has not run yet. The ● marks the applied profile only in the table of the tuner that produced it.
- `Measurement Report…` shows the full tuner report.
- `Preview on Frame…` runs object detection on the frame selected in the Main tab and shows the detection mask.

**Core Settings**

- The settings the tuner chooses: detection sigma, grow sigma and minimum pixels for each frame, and master sigma, master grow sigma, master minimum pixels and mask overlap for the star mask.
- A blue ● marks a value set by Auto-Tune, and its tooltip shows the value before. Editing the value removes the mark.
- The main window's Detect group shows which settings are in use, for example "Settings: Balanced, auto-tuned, edited".

**Analyses in This Run**

What `Detect Moving Targets` looks for besides moving objects, each with a switch, a one-line purpose and a `Settings ›` link to its detailed tab:

- Moving objects & streaks: always on
- Slow movers
- Anomaly rescue
- Residual analysis
- Variable-star photometry: skipped in iterative mode; VSX matching needs a plate-solved frame

The switches are the same settings as the checkboxes on the detailed pages.

The tune result belongs to the imported session; importing another dataset clears the table.

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

The profile box sets the sensitivity: `Conservative` (low, fewest false detections), `Balanced` (medium), `Aggressive` (high, close to the noise level) and `Maximum` (as sensitive as possible; for small sensors or targeted searches for a faint object).

There are two tuners, selected with the `Tuner` box next to the profile box (and `--tuner` on the command line):

- `Calibrated (measured)`, the default. It leaves out blank or badly registered frames, cuts crops from your frames (the centre and corners, or a grid on large sensors; small sensors are measured on more frames), and tries every combination of settings on them. For each combination it measures how many false detections it produces (noise from a negative image of the frames; star leakage, including hot pixels that follow the drift; and other real-frame artefacts above what the strictest settings see), how much sky the star mask hides, and how many synthetic stars shaped like yours it recovers at peak signal-to-noise 2 to 15. Pieces of satellite trails are recognised by a more sensitive streak pass and not counted as false detections. Each profile is a budget of false detections per megapixel per frame (Conservative 0.05, Balanced 0.2, Aggressive 0.6, Maximum 3.5); within that budget it picks the most sensitive settings, so each profile is never less sensitive than the one before it. `Maximum` is meant for small sensors or targeted searches for a faint object: because the budget is per megapixel, on a large sensor it leaves many more candidates to review. It also tunes the master map (`Master Sigma`, `Master Grow Sigma`, `Master Min Pixels`), and derives the candidate object sizes from your measured star FWHM. It needs at least five usable frames. The Overview table and the report show the measured false-detection rate, the share of synthetic sources recovered, the signal-to-noise at which half are recovered, and the masked sky fraction.
- `Legacy (score-based)`, the original tuner, kept for comparison. Its steps are described below.

The legacy tuner:

1. evaluates frame quality using the dedicated quality-analysis thresholds
2. selects a representative sample from the candidate pool
3. extracts several interior crops from those frames
4. builds cropped median master stacks
5. calibrates `maxStarJitter` from measured star displacement
6. sweeps detection sigma, grow sigma, minimum detection pixels, and mask overlap
7. validates the winning configuration on the same frozen crops

The legacy tuner actively changes:

- `detectionSigmaMultiplier`
- `growSigmaMultiplier`
- `minDetectionPixels`
- `maxMaskOverlapFraction`
- `maxStarJitter`

Most other settings are preserved from your current base configuration. The `Conservative`, `Balanced`, and `Aggressive` profiles use the same search grid but different scoring policies (`Maximum` is treated like `Aggressive`):

- `Conservative` suppresses transient leakage more strongly
- `Balanced` is the default middle ground
- `Aggressive` allows more leakage to preserve faint-target sensitivity

### Page breakdown

#### Overview

Holds Auto-Tune and the core settings (see above). The per-frame detection sigma, grow sigma and minimum pixels are usually the first fields to adjust by hand:

- raise them when the report is flooded with noise
- lower them cautiously when faint real sources are missed

#### Object Detection

Controls the low-level extraction safeguards (the star-mask settings are on the Overview page):

- physical edge margin
- registration-void threshold and proximity radius
- histogram background clipping iterations and factor

The engine may raise `voidProximityRadius` during border-drift diagnostics if the measured registration padding requires a safer value. Frames where less than half of the pixels hold image data (blank frames, failed registrations) are left out of the drift analysis and rejected with the outlier frames; the report's drift section lists them. Frames that registration also rotated are measured correctly.

#### Streak Detection

Controls elongated-object classification and streak linking:

- minimum elongation and footprint size for streak classification
- minimum peak sigma for one-frame streak tracks
- trajectory angle tolerance
- timestamp-based streak time-consistency tolerance
- binary-star-like shape veto for unmatched one-frame streak candidates

Single-frame streaks that fail the peak-sigma or binary-star-like shape checks can still remain as standalone post-veto streak detections; they are just not promoted to one-point streak tracks.

#### Track Linking

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

Controls maximum-stack slow-mover morphology candidate selection:

- slow-mover branch enable switch
- maximum- and median-stack extraction sigma, grow sigma, and minimum pixels
- geometric minimum and maximum axis ratios, plus optional minimum fill factor
- exact median-object mask overlap bounds (zero minimum permits no median persistence)
- minimum frame support and maximum stationary likelihood, both on a 0–100 percentage scale

This branch is separate from ordinary frame-to-frame point linking. Its candidates are shape-based review aids, not confirmed moving tracks.

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

#### Variable Stars

Controls the optional stationary-star photometry stage, which looks for stars whose brightness changes during the session. It is off by default and is tuned to avoid false variables: a missed variable is preferred over a false one.

- `Common Settings`: the master switch, how many stars are measured (0 = every usable star), the minimum star SNR, the variability score sigma, the minimum amplitude, the minimum number of frames and the minimum time span
- `Apertures And Star Selection`: aperture and sky-ring radii in units of the measured FWHM, the elongation limit, the saturation fraction, the per-frame gradient fit and the registration-spread limit
- `Linearity Checks`: check A (quantised data, sky clipped at zero), check B (bright stars must have the same shape as faint ones in every frame) and check D (bright and faint stars must respond equally to transparency changes)
- `Candidate Gates`: the noise model size and the thresholds of the gates a candidate must pass: amplitude, persistence, split-half agreement, aperture consistency, systematics correlation and local consistency

The linearity checks can only detect non-linearity, not prove linearity: use the original, unstretched sub-frames. Photometry is skipped in the iterative pipeline, because its time-spaced subsets would only repeat it on partial data.

#### Report Visualization

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

Opened by `Preview Frame`.

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
8. optionally extract maximum-stack slow-mover morphology candidates
9. filter per-frame detections against the stationary-star veto mask
10. link fast streaks
11. link point-like movers with the time-based linker when timestamps are available
12. optionally run the geometric point linker, or force it when timestamps are missing
13. rescue strong one-frame anomalies
14. group collinear rescued anomalies into suspected same-frame streak tracks
15. consolidate streak tracks
16. analyze leftover residual point transients
17. optionally measure stationary-star photometry and score variable-star candidates
18. build the maximum stack for visualization
19. export the HTML report and image assets

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

### Maximum-stack slow-mover analysis

If slow-mover detection is enabled, SpacePixels searches the maximum stack for elongated raw-pixel footprints.

JTransient builds the maximum stack from quality-filtered frames and extracts an exact raw-pixel mask independently from the median stack.

Slow-mover candidates are filtered by:

- minimum connected pixels and a geometric axis-ratio window
- optional minimum oriented-box fill factor
- minimum and maximum fraction of candidate raw pixels in the exact median mask
- minimum frame support: the percentage of usable frames with significant localized signal inside the candidate footprint
- maximum stationary likelihood: the percentage of supported frame positions clustered near one location

Frame support and stationary likelihood are measured even with their default thresholds of 0% and 100%, respectively; those defaults reject no candidates. Raising the frame-support minimum or lowering the stationary-likelihood maximum enables the corresponding filter. A measurement can be unavailable when there are too few usable or supported frames; unavailable measurements do not reject candidates. Stationary likelihood is a heuristic, not a calibrated probability or confirmation of motion.

The standard report shows the maximum stack, median mask, an inspection-only maximum-minus-median crop, and available frame-evidence scores for each candidate. A one-frame elongated transient can pass the morphology filters when the frame-support minimum remains at its default.

### Main result categories

The standard pipeline can produce:

- confirmed moving-object tracks
- confirmed streak tracks
- one-frame streak tracks
- suspected same-frame streak tracks
- standalone peak- or integrated-sigma anomalies
- maximum-stack slow-mover morphology candidates
- residual local rescue candidates
- residual local activity clusters
- unclassified post-veto transients for diagnostics

### Iterative pipeline

The iterative pipeline is designed for:

- very large datasets
- datasets where the standard full-baseline run may be too memory-heavy
- very slow or sparse targets that may benefit from several temporally spaced passes

Workflow:

1. Click `Detect Iteratively (large datasets)`.
2. Enter a maximum frame limit, or leave it empty or zero to use the full range.
3. SpacePixels runs multiple temporally spaced pipeline passes.
4. A master iterative summary report is generated with links to the per-pass reports.

The iterative summary is an index page plus subfolders such as `5_frames`, `10_frames`, and so on.

Variable-star photometry is always disabled in iterative passes; run the standard pipeline to use it.

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

- `Maximum-Stack Slow-Mover Candidates`
- `Master Maximum Stack Transient Streaks`

Maximum-stack candidates are review aids for elongated or semi-persistent features, separate from confirmed frame-to-frame tracks.

### Variable-star photometry sections

When `Enable Variable-Star Detection` is on, the report includes:

- `Variable-Star Photometry`: the readiness verdict (Ready, Limited or Not ready) with plain-language reasons, and the session counts
- `Photometry: Readiness Checks`: the measurements behind checks A, B and D, star and frame exclusions, and flagged measurements (saturated, non-linear, edge or void, crossing objects, isolated outliers, and contaminated shape: a hot pixel or cosmic ray on a star)
- `Photometry: Session Diagnostics`: the noise model (scatter against brightness with candidates highlighted), per-frame charts of zero point, response slope, linear range, FWHM, registration spread and crossed stars, and the check B concentration profiles of every frame
- `Photometry: Variable-Star Candidates`: one card per high-confidence or possible candidate with its scores, gate results, light curve (with three constant comparison stars of similar brightness drawn below it) and cutouts of its brightest and faintest frame, plus a table of rejected candidates and the gates they failed. When the session is plate-solved, each card has a `Check VSX Here` button that looks the star up in the AAVSO International Variable Star Index (through CDS VizieR) and shows the catalogued variables nearby, with their type, range, period and separation, inside the report, plus `VSX in VizieR` and `SIMBAD` browser links. The in-report lookup needs SpacePixels to be running, like the other live lookups; results are saved into the report
- `Photometry: Per-Frame Measurements`: every per-frame value, with excluded frames and their reasons

Hover any chart mark for its details. The report folder also contains `photometry_stars.csv`, `photometry_lightcurves.csv` (candidates) and `photometry_frames.csv`. Magnitudes are instrumental and differential. Candidates are not matched against catalogues automatically; use the `Check VSX Here` button on each card.

### Global map sections

- `Global Trajectory Map`
- `Global Transient Maps`
- `Unclassified Transient Inspector`

These sections summarize the full night in a single view and help reveal:

- track geometry
- hot columns and sensor defects
- unlinked transients
- clustered motion patterns

The unclassified inspector uses the median stack as a quiet background and colors exact detected source footprints from blue (early frames) to red (late frames). Hover over a marker for basic source data; select it to inspect an enlarged local cutout with the footprint overlaid and detailed measurements. It shows detections left after tracks, anomalies, and local rescue candidates have been accounted for. Local activity clusters remain visible because they are review groupings rather than classified objects.

The enlarged cutout plays a short animation of the same 80 × 80 pixel region in the detection frame and the nearest two frames on each side that passed quality control. Rejected frames (blank frames, failed registrations, outliers) are skipped, as the detection skipped them, and the label shows the real frame numbers. All frames share the stretch of the detection frame, so a transient visibly appears and disappears, and the frame label marks the detection frame. Use `Pause` (then click the image to step through the frames) or switch to the median-stack cutout with its footprint overlay. Each animation is a small PNG strip in `unclassified_frames/`, loaded only when its marker is selected. At most 500 detections get one (the strongest by peak sigma), so busy sessions keep a moderate report size.

### Optional AI report sections

If you enable the checkbox in `Detection Settings -> Report Visualization`, the report also includes:

- `The AI's Perspective: Signal Weave`
- `The AI's Perspective: Hidden Rhythms`

These are optional visual summaries and are off by default.

The toggle is saved with SpacePixels visualization preferences when you click `Save`; it is not part of the JTransient detection profile.

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
4. Select a frame and click `Plate Solve Selected`.

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

`batchDetect <fits_directory> <detection_config.json> [--auto-tune <conservative|balanced|aggressive|maximum>] [--tuner <calibrated|legacy>]`

Important limitations:

- the directory must contain uncompressed 16-bit monochrome FITS files
- the config file must be a valid SpacePixels detection-profile JSON with flat JTransient `DetectionConfig` fields plus `autoTuneMaxCandidateFrames`
- packaged distributions include `config/default_detection_profile.json`
- if `--auto-tune` is supplied, the tuned configuration is used for the pipeline run and exported with the report
- `--tuner` selects the auto-tuner: `calibrated` (default) or `legacy`
- with the Gradle task, `-PbatchMaxHeap=8g` sets a fixed JVM heap (otherwise up to 80% of RAM); large sessions, for example 33 frames of 61 megapixels, need about 11 GB
- the CLI prints progress as `[Pipeline NN%]` lines; the percentage only moves forward

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
- `Detection Settings -> Save` stores the JTransient detection profile in `spacepixels_detection_profile.json`.
- Visualization-only report/export preferences are stored separately in `spacepixels_visualization.json`.
- `Include AI Creative Report Sections` is a visualization preference, not a detection-profile field.
- At startup, SpacePixels offers to migrate a saved detection profile with missing or unrecognized fields. Accepting keeps supported and renamed settings, fills remaining missing fields from current code defaults, removes unrecognized fields, and preserves the original as a backup. Declining leaves the saved file unchanged; decline if the profile came from a newer SpacePixels version.
- These fallback values come from `DetectionConfig` and SpacePixels, not from the packaged `config/default_detection_profile.json` example.
