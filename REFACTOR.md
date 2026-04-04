# Refactor Plan for `ImageProcessing` and `ImageDisplayUtils`

## Why these two classes should be refactored together

These classes are currently coupled around the same workflow:

1. `ImageProcessing` loads FITS files, builds JTransient inputs, runs detection, and decides what gets exported.
2. `ImageDisplayUtils` takes the pipeline result plus raw frames and emits all exported imagery and the HTML report.

That means a future cleanup is easier and safer if both are refactored as one coordinated effort instead of as isolated classes.

Current size:

- `ImageDisplayUtils.java`: about 2803 lines
- `ImageProcessing.java`: about 2446 lines

Both files now mix orchestration, data preparation, rendering, and policy.

## Main problems to solve

### `ImageProcessing`

`ImageProcessing` currently owns too many unrelated responsibilities:

- FITS metadata loading and validation
- compressed FITS handling
- batch mono conversion
- batch stretch export
- standard detection pipeline
- iterative slow-mover pipeline
- plate solving
- WCS header application and cleanup
- image preview and stretch rendering
- app-config loading and persistence

This makes it hard to test and hard to change one area without risking another.

### `ImageDisplayUtils`

`ImageDisplayUtils` also combines too many concerns:

- low-level image rendering
- crop calculations
- GIF generation
- track maps and diagnostics
- HTML/CSS generation
- report-section ordering and business rules
- export-time configuration via mutable static fields

It is effectively both a renderer and a full report-export feature module.

### Shared structural issues

- Both classes are utility/facade hybrids.
- Both rely on broad parameter lists instead of small request objects.
- Both expose static mutable runtime settings, which makes future testing and reuse harder.
- The report/export pipeline does not have a clear boundary between:
  - classification/preparation
  - rendering
  - HTML assembly
  - filesystem export

## Recommended refactor strategy

Do this in staged rounds, while keeping external behavior stable.

Important rule: keep `ImageProcessing` as a compatibility facade until the end.  
The GUI and tasks already call it from many places, so replacing it all at once would create unnecessary churn.

## Recommended first round

Refactor these together in round one:

- `ImageDisplayUtils`
- `ImageProcessing`
- `DetectionReportAstrometry`
- `SpacePixelsVisualizationPreferences`

### Why include `DetectionReportAstrometry`

It already contains report-specific astrometry/WCS logic and is the natural place for:

- sky-coordinate formatting
- Solar System lookup links
- moving-track RA/Dec rate calculations
- track astrometric summaries

If `ImageDisplayUtils` is split without also leaning on `DetectionReportAstrometry`, astrometry code will drift back into the report/export layer.

### Why include `SpacePixelsVisualizationPreferences`

`ImageDisplayUtils` still exposes runtime export settings as static mutable fields like:

- `autoStretchBlackSigma`
- `autoStretchWhiteSigma`
- `gifBlinkSpeedMs`
- `trackCropPadding`
- `includeAiCreativeReportSections`

Those settings should move behind an explicit immutable settings object used by the exporter.  
That requires a small companion refactor in `SpacePixelsVisualizationPreferences`.

## Target architecture

### 1. Detection pipeline layer

Extract from `ImageProcessing`:

- `DetectionPipelineService`
  - runs the standard pipeline
  - runs the iterative slow-mover pipeline
  - owns the JTransient handoff

- `FitsFrameLoader`
  - loads `ImageFrame` payloads from `FitsFileInformation`
  - centralizes timestamp/exposure extraction
  - optionally loads raw frames for export

- `DetectionExportCoordinator`
  - decides export directory
  - calls the report/export layer
  - keeps `ImageProcessing` free of report details

Suggested facade behavior:

- `ImageProcessing.detectObjects(...)` delegates to `DetectionPipelineService`
- `ImageProcessing.detectSlowObjectsIterative(...)` delegates to `DetectionPipelineService`

### 2. Report/export layer

Extract from `ImageDisplayUtils`:

- `DetectionReportExporter`
  - public entry point for report export
  - receives a single request object
  - coordinates image outputs + HTML outputs

- `DetectionReportHtmlWriter`
  - builds HTML sections only
  - should not render images or crop arrays

- `TrackVisualizationRenderer`
  - creates PNGs/GIFs/maps
  - owns crop/render utilities

- `DetectionClassification`
  - classifies tracks into:
    - moving targets
    - confirmed streak tracks
    - single streaks
    - suspected streak tracks
    - anomalies

- `ExportVisualizationSettings`
  - immutable export settings object
  - replaces mutable static fields in `ImageDisplayUtils`

Suggested facade behavior:

- keep `ImageDisplayUtils.exportTrackVisualizations(...)` temporarily
- make it build a request and delegate to `DetectionReportExporter`

### 3. Plate-solving and FITS writeback layer

Extract from `ImageProcessing`:

- `PlateSolveService`
  - `solve(...)`
  - `cleanupSolveArtifacts(...)`

- `WcsHeaderService`
  - `applyWCSHeader(...)`
  - header normalization
  - provenance writing
  - legacy cleanup

- `FitsBatchOperations`
  - mono conversion
  - batch stretch
  - decompress / bit-depth conversion helpers

These areas are logically separate from detection/report export and should not stay in the same class long term.

### 4. Preview/stretch layer

Extract from `ImageProcessing`:

- `StretchService`
  - `stretchFITSImage(...)`
  - `getImagePreview(...)`
  - `getStretchedImagePreview(...)`
  - `getStretchedImageFullSize(...)`
  - concrete stretch algorithm implementations

This code is a self-contained subsystem and can be split with relatively low risk once the detection/export refactor is underway.

## Concrete round-one decomposition

This is the round I would actually implement first.

### Step 1: introduce request/response models

Add small immutable models such as:

- `DetectionExportRequest`
- `DetectionExportResult`
- `ExportVisualizationSettings`
- `DetectionBuckets` or `TrackBuckets`

The goal is to stop passing long loose parameter lists around.

### Step 2: extract classification and report context preparation

Move out of `ImageDisplayUtils`:

- track bucketing/classification
- anomaly ordering
- pipeline summary precomputation
- astrometry context preparation handoff

These should become plain data-preparation code with no rendering side effects.

### Step 3: extract HTML writing from image rendering

Split `ImageDisplayUtils` into two internal collaborators:

- one for HTML section generation
- one for image/GIF/map rendering

This is the highest-value split inside the report exporter because it isolates filesystem/image work from report text/layout logic.

### Step 4: convert static export settings into an explicit object

Replace direct reads of `ImageDisplayUtils` static settings with a settings instance passed into export code.

This improves:

- testability
- future CLI reuse
- future headless export support
- easier per-run overrides

### Step 5: move pipeline orchestration out of `ImageProcessing`

Extract:

- standard detection flow
- iterative detection flow
- raw-frame loading for export

Keep `ImageProcessing` as a thin adapter for now.

## Proposed end-state class map

### Keep

- `ImageProcessing`
  - thin facade only
- `DetectionReportAstrometry`
  - astrometric helpers for the report/export domain

### Add

- `DetectionPipelineService`
- `FitsFrameLoader`
- `DetectionExportCoordinator`
- `DetectionReportExporter`
- `DetectionReportHtmlWriter`
- `TrackVisualizationRenderer`
- `DetectionClassification`
- `ExportVisualizationSettings`
- `StretchService`
- `PlateSolveService`
- `WcsHeaderService`
- `FitsBatchOperations`

## What not to do in round one

- Do not rewrite the HTML structure at the same time.
- Do not change output filenames unless necessary.
- Do not replace the GUI-facing `ImageProcessing` API yet.
- Do not merge plate-solving work into the detection/export refactor.

That would make the first round too large and too risky.

## Suggested migration order

1. Add new internal classes and delegate from existing methods.
2. Keep current public entry points stable.
3. Move logic in small chunks with compile/test checkpoints.
4. Delete dead code only after delegation is stable.
5. Shrink `ImageProcessing` to a facade last.

## Verification plan

For each stage, verify at least:

- `./gradlew.bat compileJava`
- standard detection report export
- iterative detection report export
- plate solve still compiles and runs
- stretch preview still renders
- batch stretch / mono conversion still compile

Manual smoke checks should confirm:

- the report still contains all main sections
- exported filenames remain stable
- moving track, streak track, anomaly, and slow-mover sections still render
- astrometry links and RA/Dec rates still appear where expected

## Practical first implementation target

If doing this soon, I would start with:

1. `DetectionReportExporter`
2. `TrackVisualizationRenderer`
3. `DetectionReportHtmlWriter`
4. `ExportVisualizationSettings`
5. minimal delegation from `ImageDisplayUtils`

Then I would do:

1. `DetectionPipelineService`
2. `FitsFrameLoader`
3. minimal delegation from `ImageProcessing`

That gives the best payoff without forcing a large API rewrite.

