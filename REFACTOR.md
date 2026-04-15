# Refactor Candidates by Class Size

Snapshot date: 2026-04-15

Method used:
- Count Java source lines under `src/main/java`
- Sort descending by line count
- Review the top classes for responsibility clusters and likely extraction seams

## Top 5 largest classes

| Rank | Class | Lines | Why it matters |
| --- | --- | ---: | --- |
| 1 | `ImageDisplayUtils` | 5160 | Report export, image rendering, HTML/CSS/JS generation, and export settings still live together, and the file has grown further with report-side lookup rendering |
| 2 | `ImageProcessing` | 2989 | FITS I/O, pipeline orchestration, stretch, plate solving, WCS writeback, and config persistence are mixed |
| 3 | `DetectionReportAstrometry` | 1781 | Astrometry math, query target construction, URL building, and HTML fragments are mixed |
| 4 | `DetectionConfigurationPanel` | 1227 | Swing form layout, config binding, persistence, constraint logic, preview, and auto-tune actions are mixed |
| 5 | `MainApplicationPanel` | 967 | Main UI composition, task launching, event handling, table behavior, and report opening are mixed |

## Common themes across these classes

- Utility/facade classes have grown into full subsystems.
- UI classes act as both views and controllers.
- HTML generation is embedded directly in business logic.
- Mutable static settings make export behavior harder to reason about and harder to test.
- Reflection-based compatibility logic is useful, but it is currently spread into UI classes instead of being isolated behind adapters.

## 1. `ImageDisplayUtils` (5160 lines)

### Current responsibility clusters

- Low-level image display rendering and auto-stretch
- Crop/footprint geometry and mask overlays
- GIF and PNG generation
- Global maps, kinematic compass, and creative tribute rendering
- Report section ordering and per-card HTML generation
- Embedded CSS and JavaScript generation for live lookup rendering
- Top-level detection report export orchestration in `exportTrackVisualizations(...)`
- Export-time configuration via mutable static fields

### Status check since the previous snapshot

- Some duplication has already been reduced inside the class.
  - `CropBounds` now delegates to shared crop-extents helpers.
  - Footprint sizing is partially centralized via `computeFootprintRadius(...)` and `computeSquareCropSize(...)`.
  - Representative-frame sampling now shares `addEvenlySpacedIndices(...)`.
- Several rendering concerns are already partially broken into helper methods.
  - Global trajectory map drawing uses dedicated marker/track helpers.
  - Detection/timing HTML has a growing set of focused `build...Html(...)` helpers.
- This is helpful, but it is still intra-class cleanup.
  - The architectural problem remains: one 5000-line class still owns export policy, raster rendering, report assembly, and report client-side behavior.

### Main problems

- `exportTrackVisualizations(...)` is still the real subsystem boundary, and it spans report shell creation, section ordering, asset export, and file output.
- HTML, CSS, JavaScript, image rendering, and export policy are tightly coupled.
- The file now contains both report document chrome and domain-specific section logic, which makes even small report edits high-risk.
- Static mutable configuration (`autoStretchBlackSigma`, `gifBlinkSpeedMs`, `trackCropPadding`, etc.) makes testing and per-run overrides harder.
- Report changes still require touching a class that also owns image math and export filesystem behavior.

### Refactor plan

1. Introduce an explicit export request/settings model.
   - Suggested types: `DetectionReportExportRequest`, `DetectionReportExportResult`, `ExportVisualizationSettings`
   - Goal: stop passing large loose argument lists into the exporter and stop mutating renderer globals directly

2. Extract report chrome before touching section logic.
   - Suggested classes:
     - `DetectionReportChromeWriter`
     - `ReportClientScriptWriter`
   - Move the HTML document shell, CSS block, persisted lookup cache markup, and embedded live-render JS here
   - This is the cleanest low-risk seam because it depends on little image math

3. Extract shared geometry and pure rendering helpers.
   - Suggested classes:
     - `TrackCropGeometry`
     - `TrackVisualizationRenderer`
     - `GlobalMapRenderer`
   - Move crop bounds, footprint sizing, PNG/GIF rendering, and map drawing here
   - Keep HTML generation out of these classes

4. Split specialized report sections out of `exportTrackVisualizations(...)`.
   - Suggested classes:
     - `DetectionSummarySectionWriter`
     - `SingleStreakReportSectionWriter`
     - `StreakTrackReportSectionWriter`
     - `MovingTargetReportSectionWriter`
     - `SlowMoverReportSectionWriter`
     - `ResidualAnalysisSectionWriter`
     - `GlobalMapsSectionWriter`

5. Isolate optional “creative” rendering.
   - Suggested class: `CreativeTributeRenderer`
   - This code is visually and structurally different enough to be its own module

6. Introduce `DetectionReportExporter` as the orchestration boundary.
   - `ImageDisplayUtils.exportTrackVisualizations(...)` should become a compatibility facade that delegates to it
   - Delete the facade only after call sites and tests are stable

### Good first extraction order

1. `ExportVisualizationSettings`
2. `ReportClientScriptWriter`
3. `DetectionReportChromeWriter`
4. `TrackCropGeometry`
5. `TrackVisualizationRenderer`
6. `DetectionReportExporter`

### Migration notes

- Do not start by moving every renderer at once.
  - The leaf seams are the report chrome and crop/geometry helpers.
- Do not mix creative tribute extraction into the first pass.
  - It is self-contained, but it is also optional and noisy.
- Keep file names and report layout stable during the first split.
  - The goal is to reduce coupling first, not redesign the report.

### Expected payoff

- Report changes become safer and faster
- Rendering code becomes testable in isolation
- Client-side report behavior stops being embedded in an image utility class
- Future report features do not require editing a 5000+ line file

## 2. `ImageProcessing` (2989 lines)

### Current responsibility clusters

- FITS file discovery and metadata loading
- Headless metadata validation
- Standard detection pipeline orchestration
- Iterative detection pipeline orchestration
- Batch mono conversion and batch stretch
- Plate solving
- WCS header writeback and cleanup
- Preview/stretch rendering helpers
- App-config loading and saving

### Main problems

- This is effectively multiple services hidden behind one class.
- FITS I/O, astrophotography preprocessing, JTransient orchestration, and plate-solving persistence are unrelated change axes.
- The class is stateful and broad, which makes targeted testing difficult.
- Public methods expose a convenient facade, but internally there is not a clear subsystem boundary.

### Refactor plan

1. Extract FITS metadata and sequence loading.
   - Suggested classes:
     - `FitsSequenceLoader`
     - `FitsMetadataService`
   - Move metadata loading, consistency checks, and raw-frame preparation here

2. Extract detection pipeline orchestration.
   - Suggested classes:
     - `DetectionPipelineService`
     - `IterativeDetectionService`
   - Move `runDetectionPipeline(...)`, sampling logic, and iterative orchestration here

3. Extract export coordination.
   - Suggested class: `DetectionExportCoordinator`
   - This class should decide when and how to call the report exporter

4. Extract plate-solving behavior.
   - Suggested classes:
     - `PlateSolveService`
     - `WcsHeaderService`
   - Move solve invocation, header card resolution, provenance writing, and cleanup here

5. Extract batch file operations and stretch code.
   - Suggested classes:
     - `FitsBatchOperations`
     - `StretchService`
   - Keep raw stretch algorithms out of the orchestration facade

6. Keep `ImageProcessing` as a facade during migration.
   - Existing UI code can keep calling `ImageProcessing`
   - Internally, the class should delegate almost everything

### Good first extraction order

1. `DetectionPipelineService`
2. `DetectionExportCoordinator`
3. `PlateSolveService`
4. `WcsHeaderService`
5. `StretchService`
6. `FitsSequenceLoader`

### Expected payoff

- Cleaner testing around the detection pipeline
- Plate-solving changes no longer risk detection behavior
- Easier future support for non-GUI or batch workflows

## 3. `DetectionReportAstrometry` (1781 lines)

### Current responsibility clusters

- Build astrometry context from FITS metadata and app config
- Estimate query radii and track timing windows
- Compute sky rates and motion classification
- Build SkyBoT, JPL, SatChecker, Stellarium, and sky-viewer URLs
- Format report-facing HTML fragments for identification sections
- Resolve observer-site and observatory-code rules

### Main problems

- It mixes astrometry math, URL generation, context creation, and HTML formatting.
- HTML decisions are coupled to the underlying query-building rules.
- Changes to query strategy and changes to report wording currently hit the same class.

### Refactor plan

1. Extract astrometry context creation.
   - Suggested class: `AstrometryContextFactory`
   - Move WCS/observer-site/session-midpoint construction here

2. Extract query-target building.
   - Suggested classes:
     - `SolarSystemQueryTargetFactory`
     - `SatCheckerQueryTargetFactory`
   - Move track midpoint, time-window, and FOV calculations here

3. Extract URL builders.
   - Suggested classes:
     - `SkybotUrlBuilder`
     - `JplSbIdentUrlBuilder`
     - `SatCheckerUrlBuilder`
     - `SkyViewerUrlBuilder`

4. Extract report-facing HTML builders.
   - Suggested classes:
     - `AstrometryIdentificationHtmlBuilder`
     - `SkyCoordinateHtmlFormatter`

5. Leave `DetectionReportAstrometry` as a thin package-private facade initially.
   - This reduces churn inside `ImageDisplayUtils`
   - The facade can later disappear when call sites use the extracted components directly

### Good first extraction order

1. `AstrometryContextFactory`
2. `SolarSystemQueryTargetFactory`
3. `JplSbIdentUrlBuilder` and `SatCheckerUrlBuilder`
4. `AstrometryIdentificationHtmlBuilder`

### Expected payoff

- Safer changes to external lookup strategies
- Cleaner separation between astrometry rules and report text
- Easier reuse if lookup rendering expands outside the report

## 4. `DetectionConfigurationPanel` (1227 lines)

### Current responsibility clusters

- Build all settings tabs and rows
- Define inter-spinner constraints
- Load and save detection/visualization preferences
- Apply UI values into `DetectionConfig` and export globals
- Reflection-based support for optional `DetectionConfig` fields
- Preview button and auto-tune flow
- EventBus subscribers for import/auto-tune state

### Main problems

- The class is both a large Swing form and the binding/persistence layer for that form.
- A long list of individual field members makes it hard to reason about sections.
- Reflection-based compatibility logic is mixed directly into UI behavior.
- The panel writes directly to mutable static export settings, which couples UI state to renderer globals.

### Refactor plan

1. Extract binding between Swing widgets and config objects.
   - Suggested class: `DetectionConfigBinder`
   - Own all `applySettingsToMemory()` and `updateSpinnersFromConfig()` mapping logic

2. Extract optional-field compatibility logic.
   - Suggested class: `DetectionConfigCompatibilityAdapter`
   - Own reflection-based get/set helpers for optional JTransient fields

3. Extract persistence logic.
   - Suggested class: `DetectionSettingsPersistenceService`
   - Own loading/saving of detection profile and visualization preferences

4. Extract form-section builders.
   - Suggested classes:
     - `BasicTuningTabBuilder`
     - `SourceExtractionTabBuilder`
     - `StreakDetectionTabBuilder`
     - `MovingObjectsTabBuilder`
     - `AnomalyDetectionTabBuilder`
     - `SlowMoversTabBuilder`
     - `ResidualAnalysisTabBuilder`
     - `QualityTabBuilder`
     - `VisualizationTabBuilder`

5. Extract UI-only coordination logic.
   - Suggested classes:
     - `DetectionConfigConstraintController`
     - `AutoTunePanelController`
     - `PreviewPanelController`

6. Long-term: stop writing directly to static renderer globals.
   - Move visualization/export settings behind `ExportVisualizationSettings`
   - The panel should edit a model, not mutate utility classes directly

### Good first extraction order

1. `DetectionConfigCompatibilityAdapter`
2. `DetectionConfigBinder`
3. `DetectionSettingsPersistenceService`
4. `DetectionConfigConstraintController`
5. Tab builders

### Expected payoff

- Faster iteration on settings UI
- Safer addition of new config fields
- Less risk of breaking persistence when changing widget layout

## 5. `MainApplicationPanel` (967 lines)

### Current responsibility clusters

- Main toolbar and control layout
- Table setup and custom rendering
- Task launching for import, detection, stretching, blinking, and solving
- EventBus subscriptions for progress and completion handling
- UI locking/unlocking and progress dialog management
- Report opening and external URL opening

### Main problems

- The class is both the main view and the operational controller for the application.
- Event subscriber methods repeat the same progress/status patterns.
- Task creation is mixed directly into button handlers.
- Table behavior and action wiring are coupled to the same class.

### Refactor plan

1. Extract toolbar and table subviews.
   - Suggested classes:
     - `MainToolbarPanel`
     - `FitsFileTablePanel`

2. Extract UI state transitions.
   - Suggested class: `ProcessingUiStateController`
   - Own lock/unlock, progress dialog updates, status text, and button enable/disable rules

3. Extract task launching.
   - Suggested class: `MainPanelTaskLauncher`
   - Own creation of `BatchConvertMonoTask`, `BlinkImagesTask`, detection tasks, solve tasks, etc.

4. Extract event handling.
   - Suggested class: `MainPanelEventSubscriber`
   - Keep EventBus subscribers out of the view class

5. Extract external opening helpers.
   - Suggested classes:
     - `ExternalLinkOpener`
     - `ReportOpenService`

6. Keep `MainApplicationPanel` as the composition root.
   - It should assemble subviews/controllers, not own all behavior directly

### Good first extraction order

1. `ProcessingUiStateController`
2. `ReportOpenService`
3. `MainPanelTaskLauncher`
4. `FitsFileTablePanel`
5. `MainPanelEventSubscriber`

### Expected payoff

- Cleaner event-handling code
- Easier UI evolution without destabilizing task orchestration
- Better separation between Swing widgets and application workflow

## Recommended overall sequence

This is the order I would use for actual implementation.

1. `ImageDisplayUtils`
   - Highest LOC
   - Highest report-change friction
   - Strongest payoff from separating HTML from rendering

2. `DetectionReportAstrometry`
   - Refactor together with the report export split so the report layer stops accumulating astrometry logic

3. `ImageProcessing`
   - Once the report/export side is clearer, split pipeline orchestration, plate solving, and FITS operations

4. `DetectionConfigurationPanel`
   - After export settings have an explicit model, the panel can stop mutating static globals directly

5. `MainApplicationPanel`
   - Best done after service boundaries are clearer, so the panel can compose controllers instead of building everything inline

## What not to do in the first round

- Do not rename output files or report paths unless necessary.
- Do not change the public GUI-facing behavior while splitting internals.
- Do not refactor every large class in one branch.
- Do not mix feature work with the first architectural extraction pass unless the feature directly funds the refactor.

## Verification expectations for each refactor round

- `./gradlew.bat compileJava`
- `./gradlew.bat test`
- `./gradlew.bat test --tests eu.startales.spacepixels.util.ImageDisplayUtilsTest`
- `./gradlew.bat test --tests eu.startales.spacepixels.util.DetectionReportAstrometryTest`
- Standard detection report export still works
- Iterative report export still works
- Plate solving still writes WCS headers correctly
- Settings load/save still works
- Existing report links and live-render actions still work
