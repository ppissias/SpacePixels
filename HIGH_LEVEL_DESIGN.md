# High-Level Design

This document describes the implemented design. Pending design work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), and pending functionality in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md).

Purpose:
- Describe the current SpacePixels architecture at subsystem and facade level.
- Record the main runtime flows for GUI, API, CLI, reporting, and plate solving.
- Keep stable design context separate from user-facing behavior in `MANUAL.md` and from proposed work.

Related documents:
- User workflows and operational behavior: [MANUAL.md](MANUAL.md)
- Public embedding surface: [API.md](API.md)
- The developer guide, with the principles, working rules and patterns: [DEVELOPER.md](DEVELOPER.md)
- Pending design work: [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md); pending functionality: [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md)
- JTransient algorithm, pipeline, config, and Auto-Tune details live in the JTransient project docs.

## 1. System Summary

SpacePixels is one Gradle project with headless `main` and application-only `desktop` source sets, with three entry surfaces:

- Swing desktop application: `eu.startales.spacepixels.gui.ApplicationWindow`
- Public Java embedding API: `eu.startales.spacepixels.api`
- Command-line tools: `eu.startales.spacepixels.tools`

SpacePixels owns:

- FITS/XISF import and preparation
- GUI workflow orchestration
- persisted application and detection settings
- plate-solving integration and WCS header updates
- report generation and exported visual assets
- public API and batch CLI wrapping

SpacePixels delegates:

- core transient extraction, frame rejection, tracking, slow-mover analysis, anomaly rescue, residual analysis, and Auto-Tune to JTransient
- local/online plate solving primitives to JPlateSolve
- FITS parsing and writing to nom-tam-fits

The central architectural rule is that JTransient remains the detector and SpacePixels remains the application, preparation, visualization, and integration layer.

## 2. External Dependencies

Primary runtime dependencies:

- `io.github.ppissias.jtransient:jtransient`
  - detection engine, configuration object, Auto-Tune, pipeline result model
- `io.github.ppissias.jplatesolve:jplatesolve`
  - ASTAP and Astrometry.net solving integration
- `gov.nasa.gsfc.heasarc:nom-tam-fits`
  - FITS read/write and compressed FITS support
- `com.google.code.gson:gson`
  - JSON configuration and report lookup payloads
- `com.formdev:flatlaf`
  - Swing look and feel

## 3. Package Boundaries

### `eu.startales.spacepixels.api`

Supported public API for external JVM consumers.

Public contract:

- `SpacePixelsPipelineApi`
- `DefaultSpacePixelsPipelineApi`
- `SpacePixelsPipelineRequest`
- `SpacePixelsPipelineResult`
- `SpacePixelsPipelineException`
- `SpacePixelsProgressListener`
- `InputPreparationMode`
- `NegativeValueHandling`

Everything outside this package is internal unless explicitly exposed through the API result/request types.

### `eu.startales.spacepixels.config`

Persistence models and JSON IO for application-level and detection-level settings.

Owns:

- `AppConfig`
- `SpacePixelsAppConfigIO`
- `SpacePixelsDetectionProfile`
- `SpacePixelsDetectionProfileIO`
- `VisualizationSettings` (the immutable value of every rendering choice: display stretch, report animation and crops, detection markers, variable-star presentation; built-in defaults)
- `SpacePixelsVisualizationPreferences` (the saved form of the visualization page's values)
- `SpacePixelsVisualizationPreferencesIO`
- `SpacePixelsSettingsFiles` (Save on the detection settings tab)
- `DetectionSettingsPresentation` (application titles/messages for shared JTransient issues and adjustments)

The detection profile wraps JTransient `DetectionConfig` plus SpacePixels' Auto-Tune candidate-frame limit. Visualization preferences are intentionally separate because they do not affect detection results.

### `eu.startales.spacepixels.jobs`

The job coordinator, with no Swing or `gui` dependency. A `Job` runs on a worker and returns a `JobOutcome`: completed with its result, declined or cancelled by the user, or failed with a reason.

`JobCoordinator` behaves as follows:
- **Admission:** it admits one job at a time, on the UI thread, before the worker starts. It refuses a job while another runs, or while other work holds the window (the blink window, while a blink plays).
- **The window's lock:** its listener, in `MainApplicationPanel`, locks the window and shows the progress dialog on admission, and unlocks the window when the job ends.
- **Progress:** it reaches the window only while the job is current.
- **Outcome:** it is delivered exactly once, on the UI thread, after the unlock.
- **Detection products:** a finished detection's overlays are not part of the outcome. `DetectionTask` keeps the `DetectionOverlays` that `StandardDetectionPipelineService` hands over when the engine returns (`detectedOverlays()`, null when the job ended before that). At the outcome, before showing it, the window gives them to the job's session if that session is still the current one (`MainApplicationPanel.showDetectionOverlays`): after a written, declined or unwritable report, and after a cancel while the report was being written. A detection that stops before it finishes leaves the last run's overlays.
- **Cancel:** the progress dialog's Cancel (or closing it) calls `cancel()`.
  - The job's next progress report throws `JobCancelledException`. Detection, iterative detection, Auto-Tune, Manual Transient Inspection and the Test Star Mask task also bind `context.isCancelled()` to JTransient's `CancellationScope`, so computation can stop between progress reports. The job's own cleanup runs as it leaves; the outcome is `Cancelled`, also when an engine `CancellationException` is wrapped.
  - Progress at 100% never throws, so finished work is kept.
  - Plate solving uses JPlateSolve 1.0.1's `PlateSolveOperation`: Cancel requests a stop, and `PlateSolveTask` joins completion after process/HTTP and sidecar cleanup before releasing coordinator admission. Phase text and elapsed seconds are shown with indeterminate progress. The WCS write and Blink cannot be cancelled (`JobKind.cancellable()`); their dialog shows no Cancel.
  - The dialog offers to exit SpacePixels if a cancelled job has not stopped after 15 seconds.
  - `JobCancelledException` is a `java.util.concurrent.CancellationException`, so services in `util` recognise a cancel with `util.Cancellation.isCancellation(...)` (which looks through wrapping exceptions, as when the engine's worker threads carry it) without depending on `jobs`.
  - A cancelled detection removes the `detections_` folder it was writing. A cancelled iterative run removes the pass it was writing and writes the index of the finished passes with a note, or removes its folder when none finished. In that case it also removes the `_spacepixels_prepared_mono16_` copy its preparation converted (`PreparedDirectory.getCreatedDirectory`), never the reused XISF folder; a copy an index links to stays. A conversion that is stopped part-way removes its partial copy. `util.OutputFolders` creates every run's folder new (a number is added when two runs start in the same second), so only the run's own output is removed.
  - JTransient stops queued work and waits for running task bodies and cleanup before propagating cancellation or worker failure. Engines are closed after the operation returns. Calibrated Auto-Tune now propagates cancellation directly; `AutoTuneTask` no longer needs to infer cancellation from a failed tuning result.
- **Live view:** for detection and iterative runs the progress dialog shows a picture of the work under way, and while the engine runs, what it has found so far.
  - `MainApplicationPanel` gives each such task a sink from `ProcessingProgressDialog.previewSink()` (`DetectionTask.showingPreviews`, `IterativeDetectionTask.showingPreviews`). The task binds it to its thread with `util.RunPreviewScope`, as it binds cancellation.
  - The producers call `RunPreviewScope.offer(stageChange, maker)`. They are the loading loop of `StandardDetectionPipelineService` (each frame), the same service around the engine call (a caption-only "picture from loading" update, then the master stack) and `TrackVisualizationRenderer`'s PNG/GIF writers (report pictures; shape and mask diagrams are skipped). `IterativeDetectionPipelineService` clears the picture at each pass and before its Auto-Tune.
  - The sink answers `wantsPreview` before any picture is made: only while the view is shown, at most every 3 s, with stage changes at once. Without a scope (API, CLI, headless runs) no picture is made. A picture that cannot be made is skipped and never fails the run.
  - `util.PreviewThumbnails` makes detached thumbnails, at most 420 px on the long side. A frame or stack is block-averaged and stretched with `DisplayImageRenderer`'s levels (the report's automatic stretch, with the run's sigmas), taken from a sample of about 250,000 pixels; report pictures are scaled copies. The pipeline's pixels and the report are not changed.
  - The dialog keeps only the shown picture and at most the newest pending one, drops a finished job's late pictures by generation, wraps its status and caption text in a reserved two-line height that only grows during a job, uses `UiStyle` spacing and help colour, makes Hide/Show picture an `AccessibleControls` link, and saves the choice as the Java preference `progressDialog.liveView` (node `/eu/startales/spacepixels/gui`) through `session.ApplicationStorage`.
  - During the engine call, `detection.LiveFindings` is JTransient's `PipelineStageObserver`. `StandardDetectionPipelineService` creates it only when this thread has a sink (`LiveFindings.forCurrentRun`), so API, CLI and headless runs pass no observer. On the engine thread it copies positions, frame numbers, counts and light-curve points only while the sink wants a picture; one daemon worker renders them. In-progress snapshots show the current frame with source or photometry markers, or a candidate star crop beside its light curve. Photometry samples cycle through stars as work advances. Stage-completion pictures show the median reference, a slow-mover crop, the star mask (or a track/anomaly when the mask is unavailable), a local-rescue candidate, or a variable-star candidate and its curve. Each event selects one picture; there is no repeating slideshow. Candidate captions say "provisional" and give the counts so far, and a stage without findings says so. `report.LiveFindingPictures` crops and stretches as the report does. The frames are read where SpacePixels loaded them; the reference, maximum stack and mask are borrowed because the engine documents them as final. The worker stops when the engine returns, before the report.
  - A `RunPreview` can carry several slides; the dialog shows them in turn every 3 s (stopped while the picture is hidden) until the next preview replaces them. `RunPreviewScope.labelled` puts "Pass n of m" before every caption of an iterative pass.
- **Failure messages:** a failed job's dialog (`gui.FailureDialog`) has Copy details, which copies the message, the versions (`util.BuildInfo`) and the stack trace.
- **The last report:** `report.SessionReports.latest(...)` finds the newest finished report beside the frames when a session is imported (in the import job); a completed detection replaces it. The Main tab's Detect group offers it as **Open last report**.
- **Failures:** an exception or `Error` becomes a failure. Running out of memory leaves the worker for the application's uncaught-exception handler. The handler marks it in `JobFailures`, and every other running job then stops at its next progress report while SpacePixels closes.

Each `JobHandle` carries its explicit `ProcessingSession`, which has a UUID and owns its frame array, selection, catalogue/correction, tuning state, the master-stack cache, last report, the last detection run's overlays (`DetectionOverlays`, set on the EDT and refused once closed), and the services that work on its frames: its `PlateSolveService` and the `FitsBatchService` of its folder. `SessionOwner` guards handover and callback admission independently of Swing. Only a successful import replaces the current session; stale callbacks and stale imports cannot modify its replacement. A session whose close fails, for example a plate-solve client that cannot confirm its shutdown within 10 s, is logged, so neither the handover nor closing the window stops; `ProcessingSession.close` closes its solver and forgets its frames in a `finally` block, also when the solver's close fails. The window loads `ApplicationSettings` once and builds each session's `PlateSolveService` on that live `AppConfig`, so unsaved preferences survive imports and reach the next solve; an import never reads them. Import returns a fresh frame array and keeps no metadata cache. The session copies that array and returns copies from `getFiles`; each `FitsFileInformation` is immutable, and `ProcessingSession.replaceFrameHeader` replaces a frame's descriptor on WCS writes while admitted runs keep their snapshots. Detection and inspection jobs copy their admitted arrays instead of reading frames on the worker. The other jobs receive the session's services or immutable inputs when they are created: Convert to Mono and Batch Stretch its `FitsBatchService`, plate solving and the WCS write its `PlateSolveService`, and the Image Stretch previews a stateless `FitsVisualizationRenderer`. Job admission compares the captured `ProcessingSession` with the current one.

### `eu.startales.spacepixels.session`

The imported dataset and its handover, without Swing dependencies. `ProcessingSession` owns the session identity,
state and metadata-save completion lifetime; `SessionOwner` installs a candidate only for the expected current
session. `SkyCatalogueModel` shares catalogue/correction changes with viewers. `ApplicationStorage` is owned by the
window rather than an imported session: one worker orders settings writes, session writes and import metadata
reads, and it drains accepted work when the application closes.

### `eu.startales.spacepixels.gui`

Swing UI shell, panels, dialogs, viewers, and UI-side workflow control.

Main classes:

- `ApplicationWindow`
- `MainApplicationPanel`
- `ConfigurationPanel`
- `StretchPanel` (display stretch, remembered in Java preferences; whole-frame preview with two linked `FrameView`s and a histogram)
- `StretchPreviewRenderer` (renders the `StretchPanel` previews one request at a time and shows a result only while its request is the newest; a change of frame or stretch, hiding the tab or another import makes a render under way stale)
- `DetectionConfigurationPanel`
- `AutoTuneOverviewPanel`
- `SettingsNavigator`, `SettingRow`
- `CheckedNumberEditor` (the editor of every number setting: only text that is one complete number within the setting's range becomes its value; other text stays in the field, outlined. It shows every decimal of its value, so committing the shown text never rounds it)
- `WrapLayout`
- `WindowPlacement`
- `StarMaskExplorerFrame`
- `DetectionSequenceFrame`
- `TransientInspectionFrame`
- `ProcessingProgressDialog`
- `SessionListener` (how `ApplicationWindow` tells the main panel, Image Stretch and Detection Settings that a new session was imported)

The GUI layer currently combines view and controller behavior in several panels. That is an accepted current state and a refactor boundary.

### `eu.startales.spacepixels.tasks`

Background runnables launched by the GUI.

Tasks are the boundary between Swing actions and longer-running work. Each is a job under the `JobCoordinator`:
- **What a job returns:** its result. The window then opens what follows: the imported session, the report question, the inspection view, the Star Mask Explorer, the offer to import the mono folder, the offer to write the WCS solution (which starts `WriteWcsTask`), the tuned settings on the Overview, or the blink window.
- **Blink:** while a blink plays, the blink window holds the main window's lock until it closes.
- **Import:** an import that loads no frames, for example because a conversion was declined, keeps the open session.

Main tasks:

- `FitsImportTask`
- `BatchConvertMonoTask`
- `BatchStretchTask`
- `BlinkImagesTask`
- `DetectionTask`
- `IterativeDetectionTask`
- `ManualTransientInspectionTask`
- `PlateSolveTask`
- `AutoTuneTask`
- `StarMaskStackTask`
- `WriteWcsTask`
- `GeneratePreviewsTask`, which renders the Image Stretch previews of one frame and returns them to `StretchPreviewRenderer`; full-size sequence viewers load frames through `SequenceViewerFrame` and `BlinkSequence`

### `eu.startales.spacepixels.tools`

Command-line utilities.

Current tools:

- `BatchDetectionCli`
  - headless standard detection with optional Auto-Tune
- `ArtificialStarInjector`
  - synthetic moving-star injection for testing and validation

### Domain packages: `fits`, `detection`, `platesolve`, `skycatalog`, `report`, `util`

Processing services organized by domain.

Important classes by package:

- `fits`: `FitsImportService`, `FitsInput`, `FitsPreparation`, `FitsBatchService`, `FitsPixelConverter`, `FitsVisualizationRenderer`, `DisplayImageRenderer`, `DisplayStretch`, `StretchAlgorithm`, `BlinkSequence`, `ExposureGroup`, `WcsCoordinateTransformer`, `WcsSolutionResolver`, `XisfImageConverter`
- `detection`: `DetectionInputPreparation`, `StandardDetectionPipelineService`, `IterativeDetectionPipelineService`, `DetectionPipelineSupport`, `AutoTuneCandidatePoolBuilder`, `AutoTunerRunner`, `TrackOverlay`, `VariableStarOverlay`, `RunPreview`, `PreviewThumbnails`, `OutputFolders`
- `platesolve`: `PlateSolveService`, `PlateSolutionCorrection`, `AstrometryNetAccount`
- `skycatalog`: `SkyCatalogueClient`, `SkyCatalogueStore`, `PlateCorrectionFitter`, `SkySnapshot`
- `report`: `ReportExporter`, `ReportRequest`, `ReportData`, `FindingCategory`, the section writers, and lookup proxy/builders in `report.lookup` (`ReportLookupProxyServer`, `SatCheckerUrlBuilder`, `JplSbIdentUrlBuilder`, `SkyViewerHtmlBuilder`)
- `util`: `StagedFile`, `Statistics`, `SkyMath`, `BuildInfo`, `Cancellation`, `DebugOutput`

Callers use these services directly; there is no forwarding facade. All headless domain sources have no Swing/GUI dependencies; headless AWT image rendering remains permitted. Shared HDU discovery and directory listing are separate helpers. Each `FitsImportService` owns its bounded pool for one import attempt, and its caller closes it. Classes across GUI, session, FITS and reporting packages log non-fatal problems through their own `java.util.logging.Logger` instances (`WARNING` or `SEVERE`) and send optional diagnostic output through `DebugOutput`.

Compressed 32-bit and signed/scaled 16-bit import selects combined preparation using the actual decompressed image HDU metadata. The existing batch standardizer reads compressed pixels through `FitsPreparation`, writes final unsigned mono/color copies directly and preserves the shared sequence-wide offset/zero-padding policy. Already-standard unsigned 16-bit compressed input retains decompression-only behavior. GUI decisions stay in `FitsImportInteraction`; strict headless import continues to refuse non-ready input.

The window explicitly injects `gui.GuiFitsImportInteraction` through `FitsImportTask` into `FitsImportService` and its exposure-group decision (`FitsImportService.offerToImportLargestExposureGroup`). An importer without interaction validates strictly and never creates dialogs. `MainApplicationPanel` injects `gui.GuiDetectionReportPrompt` callbacks into normal/iterative jobs; only this GUI adapter builds the report-size question on the EDT. The threshold remains 50, and iterative callbacks use each pass's subset/WCS and effective config. Accepted choices replace only the summary's `ReportInclusion`, consumed by export, not the measurements/config. Default task constructors are noninteractive threshold guards; explicit null report callbacks permit export without asking. A declined import preserves the current session. These boundaries are protected by recursive dependency tests and dialog/job tests.

The build publishes only `eu.startales.spacepixels:spacepixels`: the headless API/config/session/domain implementation, CLI tools and reporting resources, with matching sources/Javadocs. There is no separate core project or desktop Maven publication. An unpublished `desktopJar` owns GUI/jobs/tasks and GUI icons; `run` and distributions combine it with the API and desktop-only FlatLaf. CLI launchers need only the API/runtime graph. Package names and source paths stay stable through complementary source sets. Binary/source/Javadoc and dependency-metadata checks plus an isolated Maven-resolved consumer protect this boundary. JTransient is compile/API-scoped for exposed DTOs; JPlateSolve/FITS/Gson remain runtime-transitive implementation dependencies. Headless graphics still require `java.desktop`. See [the developer guide](DEVELOPER.md#build-and-run) and [publishing](PUBLISHING.md).

### `eu.startales.spacepixels.report` (and `eu.startales.spacepixels.report.lookup`)

Report export, report-side rendering, WCS-aware report context, and live report lookup support.

Main facade:

- `ReportExporter.export(ReportRequest)`

Supporting groups:

- document and model:
  - `ReportRequest` (owned small input snapshots; shared image buffers and engine result)
  - `DetectionReportContext` (complete factory-built inputs and report model)
  - `DetectionReportSummary`
  - `ReportData` (stable finding identities and JSON/CSV exports)
  - `AtomicReportFile`
  - `DetectionReportDocumentWriter`
  - `ReportClientScriptWriter`
- section writers:
  - `SessionPictureSectionWriter` (median stack, data area, detections and catalogue layers)
  - `NightEquipmentSectionWriter`
  - `PipelineDiagnosticsSectionWriter`
  - `TargetVisualizationSectionWriter`
  - `DeepStackReportSectionWriter`
  - `ResidualReviewSectionWriter` (local rescue and activity clusters with the results; camera-fixed and common-motion groups in the collapsed Likely Artifacts group)
  - `GlobalMapsSectionWriter`
  - `PhotometryReportSectionWriter` (variable-star photometry: readiness, noise model, per-frame diagnostics, candidate light curves, CSV exports, VSX lookup buttons)
  - `CatalogueComparisonWriter` and the embedded `catalogue-comparison.js` (read-only catalogue comparison notes for saved/live matches, separate from detection confidence; see [catalogue comparison technical note](docs/features/catalogue-comparison.md))
- rendering helpers:
  - `TrackVisualizationRenderer`
  - `PhotometrySvgChart`
  - `TrackCropGeometry`
  - `GifSequenceWriter`
- astrometry and lookup:
  - `DetectionReportAstrometry`
  - `AstrometryContextFactory`
  - `SolarSystemQueryTargetFactory`
  - `SatCheckerQueryTargetFactory`
  - URL and HTML builders
  - `ReportLookupProxyServer`
  - report lookup cache and upstream client classes

## 4. Main Facades

### `ApplicationWindow`

Desktop composition root.

Owns:

- main Swing frame and tab composition (resizable; size and position from `WindowPlacement`: screen-based default, restored from Java preferences, saved on close)
- `MainApplicationPanel`
- `ConfigurationPanel`
- `StretchPanel`
- `DetectionConfigurationPanel`
- `BlinkFrame`
- the `JobCoordinator` and the session listeners
- the shared `ApplicationStorage` queue and its background shutdown drain
- the current `ProcessingSession` (through `SessionOwner`), built from each completed import with a `PlateSolveService` on the window's settings
- `ReportLookupProxyServer` lifecycle

Responsibilities:

- start the application
- wire the four top-level tabs, passing each panel its collaborators (session access, jobs, frame list, settings readout) rather than the window
- launch import (the Import Aligned Frames… button in the Main tab or a folder dropped on the window; there is no menu bar), and show the "new version" notice in the Main tab's status bar
- enable tabs after successful import
- install the session an import job returns, and tell the panels through their `SessionListener`s
- stop the report lookup proxy on window close

### `MainApplicationPanel`

Primary GUI workflow facade.

Owns:

- a workflow strip of four groups (1 Prepare, 2 Astrometry, 3 Inspect, 4 Detect) laid out by `WrapLayout`; each group has a status line
- FITS metadata table, with a right-click menu for the frame actions
- plate solve controls
- blink controls
- batch conversion and stretch controls
- single-frame preview and manual transient inspection actions
- standard and iterative detection launch actions
- tooltips that explain why a button is disabled, and keyboard shortcuts
- progress state and modal progress dialog

Launches most GUI background tasks.

**Commands.** Every workflow button of the Main tab is a command: Import, Convert to Mono, Batch Stretch, Plate Solve, Fetch Sky Catalogue, Blink, Preview Frame, Manual Transient Inspection, Auto-Tune, Detect Moving Targets and Detect Iteratively. Several have more than one entry point: a shortcut (Ctrl+D, Ctrl+T, Ctrl+P, Ctrl+B), the table's right-click menu, the Overview's buttons, Ctrl+D on the Detection Settings tab, or a folder dropped on the window.
- **One action per command.** Each command is one `CommandAction` (a Swing `Action`) that all its entry points use, so they show the same caption and tooltip, are enabled together, and run the same code.
- **One rule set.** `CommandAvailability` decides from facts, never from other controls:
  - the frames, whether they are monochrome, and how many are solved;
  - the selection, and the selected frame's solution;
  - the batch-stretch switch;
  - a job or blink holding the window;
  - a sky fetch running.

  It gives a reason for each unavailable command, which the tooltip shows.
- **Refreshing.** `refreshCommands()` runs with every workflow refresh (import, selection, table changes, sky fetch), when the window locks or unlocks, and when the batch-stretch switch changes.
- **Running.** An action checks availability again when it runs, so a late shortcut or drop does nothing.
- **Commands that stop their own work.** While a blink loads or plays, Blink reads **Stop Blinking** and stays available to stop it. While the sky catalogue is fetched, its command reads **Cancel Sky Catalogue**.
- **The lock.** While a job runs or a blink plays, `lockUI` disables the panel's other controls (the table, the solver choice) and closes the session tabs. `unlockUI` enables them again and opens the session tabs only when there is a session. Nothing is saved and restored, so a second lock cannot leave controls disabled.
- **Tabs by panel.** Tabs are addressed by their panel (`ApplicationWindow.setSessionTabsEnabled`, `setSelectedComponent`), not by position.

### `DetectionConfigurationPanel`

Detection settings, Auto-Tune, preview, and persistence facade.

Owns:

- UI binding for JTransient `DetectionConfig`
- `SpacePixelsDetectionProfile` load/save
- `SpacePixelsVisualizationPreferences` load/save
- the live `VisualizationSettings` (the visualization page and the variable-star report presentation), which each Detect and Detect Iteratively job captures when it is created; Preview Frame and Manual Transient Inspection read its marker sizes when they paint
- current Auto-Tune candidate-frame limit, which each Detect and Auto-Tune job captures when it is created
- the Overview page (`AutoTuneOverviewPanel`) and the page navigation (`SettingsNavigator`)
- the saved value of every setting row (`SettingRow`) and of the Overview core settings, taken after loading and after each Save; changes are marked against it
- the Overview's Preview Frame button, which opens the Main tab's Preview Frame (`DetectionSequenceFrame`)
- the detached per-frame trial input specifications used by Preview Frame (`perFrameDetectionSettings()`), and the copy of the settings in use it detects each frame with (`settingsInUse()`)
- Auto-Tune launch through `AutoTuneTask`
- applying every edit to the session right away, the unsaved-changes indicator, and Revert to the last saved state
- binding paired controls to JTransient rule IDs, consuming `LEGACY_GUI` alignment while applying an edit, and explaining the returned changes in the footer
- **Review rules…**, configuration-only diagnostics and explicit `REVIEWED_CONSISTENCY` alignment for the additional policies, including hidden quality grow
- `checkedSettings(...)`, the one step behind Save and every run: Detect Moving Targets, Detect Iteratively, Manual Transient Inspection, Preview Frame, Auto-Tune and Test Star Mask

`checkedSettings` returns a copy of the session `DetectionConfig` (hidden settings included), so editing the tab while a run works cannot change that run. It returns null instead while an applicable editor holds invalid text or shared validation reports an error. Invalid editor text is focused through `SettingsNavigator.reveal`; backend errors are explained in the footer. Advisory issues do not block Save or execution. A setting whose feature is switched off is hidden and unused, so its editor text does not stop a run; persisted numeric configuration still must be finite. Profile loading and API/iterative request construction use the same engine validator. The engine captures and validates its own operation-scoped snapshot, so callers outside SpacePixels receive the same structured errors without Swing dependencies.

Design notes:

- detection fields and visualization fields are intentionally stored in separate JSON files even though the same panel edits both.
- the settings spinners stay owned by `DetectionConfigurationPanel`; `AutoTuneOverviewPanel` only lays out the core ones and talks back through a small `Host` interface.
- the visualization settings are one immutable `VisualizationSettings` value, replaced with each edit; a run keeps the value it captured, and no renderer reads a global preference.

### `AutoTuneOverviewPanel`

The Overview page of Detection Settings.

Owns:

- the profile choice and the run button
- the profile table, built from `AutoTunerResult.calibration` (one calibrated run covers all profiles; `CalibratedAutoTuner.configFor(...)` applies a chosen row)
- the expected false detections for the imported session (measured rate × megapixels × frames)
- the eight core fields: detection sigma/grow/min pixels, master sigma/grow/min pixels, mask overlap and jitter; the tuner supplies all eight
- markers showing which displayed values still equal the applied tune
- the settings summary shown in the main window's Detect group

A tune result belongs to the imported session and is cleared when another dataset is imported.

### `StarMaskExplorerFrame`

The Star Mask Explorer, opened from the Overview.

- `StarMaskStackTask` loads the frames (with `SessionFrameLoader`, shared with Manual Transient Inspection) and calls `JTransientEngine.generateMasterStackWithDetails(...)`, so the stack comes from the pipeline's own frame selection.
- `DetectionConfigurationPanel` keeps the stack for the session. The key is the imported files plus every setting except the star mask values.
- For trial values the frame calls `MasterReferenceAnalyzer.analyzeFromMasterStack(...)` and `MasterVetoMask.build(...)`, the same code as the pipeline, in a background worker. It shows the mask as a one-bit overlay on `FrameView`.

### `SettingsNavigator` and `SettingRow`

`SettingsNavigator` replaces the settings tabs with:

- a grouped page list and the pages themselves (a `CardLayout`);
- a search box and a "show only unsaved changes" filter that work across all pages;
- a "Show expert settings" switch. Expert sections are hidden while it is off, but search and the unsaved-changes filter still find them, and each page says how many expert settings it hides.

Expert sections are found from the header labels that `DetectionConfigurationPanel` marks with a client property.

`SettingRow` is one setting on a page: its input, its setting ID and expert visibility (passed directly when the row is built), its saved value and a `Reset` button. It marks itself when its value differs from the saved configuration and tells the navigator to refresh the filters and counts.

### `FitsImportService`

Reads one folder's frames for one import attempt. The caller owns it and closes it, which stops its bounded metadata pool.

Owns or coordinates:

- sequence metadata loading through `readFiles()`, which returns a fresh caller-owned array and keeps nothing
- GUI import-time FITS validation and prompting, through an explicit `FitsImportInteraction`
- decompression and 32-bit/color conversion through `FitsBatchService`, and the redirect to the converted folder (`RedirectImportException`, handled by the import task's loop)
- the offer to import the largest exposure group

Without interaction, readiness is checked before sequence consistency, preserving the API/CLI error order; interactive import checks consistency before offering preparation. `FitsImportTask` converts XISF input with `XisfImageConverter` before it reads the folder.

The other services have their own owners. A desktop `ProcessingSession` owns its `PlateSolveService` and the `FitsBatchService` of its folder; `FitsVisualizationRenderer` keeps no state. Detection runs `StandardDetectionPipelineService` directly: `DetectionTask` on frames admitted from `ProcessingSession.getFiles()`, each iterative pass, and `DefaultSpacePixelsPipelineApi`. Headless callers read the metadata once and keep the returned array.

### `StandardDetectionPipelineService`

Standard JTransient run orchestrator. It keeps no state: each run receives its frames, settings, options and sky data, and returns its results, so its callers create one where they run it.

Responsibilities:

- load validated FITS frames into JTransient `ImageFrame` objects
- preserve raw frame arrays needed by report export
- create an effective config through `DetectionPipelineSupport`
- call `JTransientEngine.runPipeline(...)`; when it keeps fewer than three frames, the engine stops after quality control and marks the result `tooFewFrames`, which the report presents as it is
- hand the run's `DetectionOverlays` (tracks and variable-star candidates, no image buffers) to the caller as soon as the engine returns, before the report decision; Detect Moving Targets keeps them, iterative passes pass no receiver, and the API, which calls `runDetectionPipeline` and `ReportExporter.export` itself, does not ask for them
- optionally run the UI detection-count safety prompt (`DetectionSafetyPrompt`, given a `DetectionSummary`) before report generation
- export the standard report through `ReportExporter.export(ReportRequest)`

### `IterativeDetectionPipelineService`

Iterative large-dataset/slow-target workflow orchestrator.

Responsibilities:

- validate/prepare a directory independently of the GUI session using the shared input services
- use `IterativeDetectionPlan` for Start/Increase/Maximum sizes, exact final maximum and endpoint-preserving time/index sampling
- optionally run calibrated Auto-Tune once with a bounded candidate pool, then freeze the resulting settings
- call `StandardDetectionPipelineService.run(...)` for each subset, with its own master, ordinary analyses/readiness, local frame indices and subset export metadata
- release each pass's private processing state and pixel buffers before starting the next
- publish lightweight outcomes and counts through `IterativeDetectionIndex`, with separate input manifests on disk and links to ordinary per-pass reports

`IterativeDetectionDialog` runs its directory scan off the EDT, previews the pass sizes and explains manual tuning through normal import and Preview Frame. `IterativeDetectionRequest` captures unsaved validated settings, application/site values, export options and the source/plan. The job is admitted even without a GUI session; its completion does not change an existing session's frames, selection, catalogue, overlays or last-report reference.

### `DefaultSpacePixelsPipelineApi`

Public headless pipeline facade.

Responsibilities:

- optionally normalize input through `DetectionInputPreparation`
- validate metadata with the headless import path
- optionally run SpacePixels Auto-Tune candidate-pool selection plus JTransient Auto-Tune
- run the standard pipeline without Swing
- optionally export the HTML report
- return original/prepared directories, configs, metadata, raw `PipelineResult`, telemetry, and report paths

Design constraint:

- Auto-Tune has no shared state, so API calls can run concurrently in one JVM, memory permitting.

### `ReportExporter` and the report context

`ReportExporter.export(ReportRequest)` owns normal report orchestration. A request snapshots configuration,
frame metadata and the frame-list container; the large image buffers and engine result are shared by ownership.
The context factory normalizes optional outputs and initializes its final model and fields before any section uses
them. Each export owns its progress counter. Section fixtures use this same construction path, so finding identities
and quality-filtered frame choices always come from the prepared model.

`ReportMaps` draws the map and mask pictures. `ReportFormat` formats report text. `DetectionReportGenerator` writes the iterative index.

Responsibilities:

- generate PNG/GIF assets
- build global maps, masks, crops, shape maps, and diagnostic imagery
- coordinate section writers
- generate standard `detection_report.html`
- generate iterative index reports
- build `ReportData`: report-local finding IDs/anchors, included variables and common-motion groups, and one shared quality-filtered frame selection
- export versioned `report-data.json` without input paths or observing-site coordinates
- publish HTML, JSON and CSV text artifacts through `AtomicReportFile` (built on `StagedFile`), checking write errors before replacing final files
- emit testable `report-lookups.js` and `report-interactions.js` resources; GIF exports include PNG posters for pause/reduced-motion controls

`ReportData` describes report presentation; it never changes the JTransient result. `DetectionReportContext` uses the catalogue and WCS captured once by `ReportRunContext` at run admission, including their valid absence. The run's `VisualizationSettings` applies throughout image rendering. Long moving/streak GIFs stream images one at a time. Motion/lookup calculations use exposure midpoint times, while observing-span duration remains a separate metric. Detection-quality rejections are shared by all inspection animations.

The HTML places the results first. After the variable stars, a collapsed Likely Artifacts group holds the camera-fixed groups and then the common-motion groups, in pipeline order; local rescue candidates stay with the results. Diagnostics also starts collapsed, and links into a collapsed group open it. The HTML links maps to object cards, separates measured variable candidates from catalogue layers, and exposes navigation, filters and animation controls. Photometry exports append report labels and sky/catalogue identification columns. Lookup responses are validated before normalization; only successful responses are cached, refresh bypasses saved results, and save failures remain visible.

Consumes:

- JTransient `PipelineResult`
- raw frame arrays or on-demand frame access
- `FitsFileInformation[]`
- effective `DetectionConfig`
- `AppConfig`

### `DetectionReportAstrometry`

Report-side astrometry facade.

Responsibilities:

- derive report astrometry context from FITS metadata and app config
- transform pixel detections into sky coordinates when WCS exists
- build JPL/SatChecker/Sky Viewer links
- generate report-ready identification markup

### `ReportLookupProxyServer`

Optional localhost helper for live report enrichment.

Responsibilities:

- listen on loopback port `47831`
- proxy trusted JPL, SatChecker and AAVSO VSX (through the CDS VizieR TAP service) lookup requests for static HTML reports
- normalize and cache JSON lookup responses
- persist lookup responses back into report-side cache data

The report remains a static disk artifact; this server only supports live enrichment when the desktop app is running.

## 5. Runtime Flows

### Desktop Import Flow

```text
ApplicationWindow
-> FitsImportTask
-> XisfImageConverter.prepareDirectoryForFitsImport(...)
-> FitsImportService(importDirectory, interaction).readFiles(), closed before the task goes on
-> JobOutcome (Completed with the frames, the last report and the saved session data; Declined, Cancelled or Failed)
-> ApplicationWindow builds the ProcessingSession, with a PlateSolveService on its settings
-> ApplicationWindow.installSession(...) and the SessionListeners
```

A declined, cancelled or failed import keeps the open session.

GUI import may prompt for:

- compressed FITS decompression
- XISF-to-FITS conversion
- signed/scaled 16-bit or 32-bit to unsigned-16-bit standardization
- treatment of negative converted pixels: one sequence-wide offset (recommended) or clipping to zero
- importing the largest exposure group into a working subdirectory when exposure times differ
- color-to-mono conversion through the main UI

`FitsInput` supplies the same sequence inspection, readiness facts and copied header metadata to interactive import, strict headless loading and automatic API preparation. Interactive and strict imports use the existing bounded import pool; automatic preparation inspects serially. `FitsInput` checks those facts before conversion: uniform dimensions, channel layout/sample type, all-or-none usable timestamps, and one exposure group. FITS also compares `BZERO`/`BSCALE` and compression. `XisfImageConverter.validateSequence` performs the corresponding pre-conversion XISF checks. Exposure durations may differ within 2% (at least 0.1 s); missing durations form a separate group. The GUI can copy the largest exposure group to a working subdirectory; headless runs reject the mixed sequence.

`FitsPreparation.standardizeFile` owns normalization, optional colour output, luminance extraction and staged FITS writes for both `FitsBatchService` and `DetectionInputPreparation`. GUI conversions preserve their `_16bit_converted`, `_16bit_mono` and `_16bit_color` folders and suffixes; automatic preparation preserves its `_spacepixels_prepared_mono16_*` layout. Interaction and negative-value policy remain with the caller. Compressed input that is already unsigned 16-bit is only decompressed (to `_uncompressed`); other compressed input is converted in one step, as described for the `util` package above.

The engine receives unsigned-16-bit-equivalent pixels (`BZERO` 32768, `BSCALE` 1), represented in signed Java shorts. A conversion offset is shared across frames and recorded in `SPOFFSET`, while row-end zero padding stays zero. The GUI path is interactive and may redirect the active import directory to a generated working directory.

### Headless/API Input Flow

```text
DefaultSpacePixelsPipelineApi
-> DetectionInputPreparation.prepareInputDirectory(...)
-> FitsImportService(preparedDirectory, null).readFiles(), closed at once
```

`FAIL_IF_NOT_READY` keeps the original directory and requires detection-ready files.

`AUTO_PREPARE_TO_16BIT_MONO` can create a new detection-ready FITS directory from supported FITS or XISF inputs.

### Standard Detection Flow

```text
DetectionTask (GUI) / DefaultSpacePixelsPipelineApi (API, CLI)
-> StandardDetectionPipelineService.run(...), or runDetectionPipeline(...) then exportDetectionReport(...)
-> DetectionPipelineSupport.createEffectiveDetectionConfig(...)
-> JTransientEngine.runPipeline(...)
-> PipelineResult (PipelineExecutionData), DetectionOverlays to the caller
-> DetectionSafetyPrompt
-> ReportExporter
```

The effective config is a clone of the caller config. JTransient builds the slow-mover maximum stack from quality-filtered frames.

### Auto-Tune Flow

```text
DetectionConfigurationPanel (AutoTuneTask), DefaultSpacePixelsPipelineApi or BatchDetectionCli
-> AutoTuneCandidatePoolBuilder
-> AutoTunerRunner.run(..., profile, ...)
   -> CalibratedAutoTuner.tune(...)
-> AutoTunerResult.optimizedConfig, summary, telemetryReport
-> standard pipeline effective config
```

SpacePixels owns candidate-pool preparation:

- selected GUI frames when enough are selected
- otherwise the full imported sequence
- for large sets, a deterministic mix of best-quality, median-quality, and evenly spaced frames

JTransient owns the actual tuning search and validation. `AutoTunerRunner` passes the profile (`LOW`, `MEDIUM`, `HIGH`, `MAXIMUM`; the earlier names remain parsing aliases) and opens the engine diagnostics.

### Iterative Detection Flow

```text
MainApplicationPanel
-> IterativeDetectionDialog and IterativeDetectionRequest
-> IterativeDetectionTask
-> IterativeDetectionPipelineService
-> shared input preparation/validation and IterativeDetectionPlan
-> optional one-time Auto-Tune
-> repeated StandardDetectionPipelineService.run(...) on subset metadata
-> per-pass reports
-> IterativeDetectionIndex (index.html, run-data.json, per-pass input manifests)
```

Each pass builds its own master and preserves the normal configured analyses, including variable-star analysis when ready. Settings stay fixed between passes, and only selected input files change. Report counts and frames refer to the subset. A failed or report-declined pass is recorded and later passes continue; cancellation retains completed reports and marks remaining passes. The index links separate per-pass results; it does not consolidate findings across passes.

### Plate Solving and WCS Flow

```text
MainApplicationPanel
-> PlateSolveTask, with the session's PlateSolveService
-> PlateSolveService.startSolve(...)
-> JPlateSolve
-> (the user agrees to write the solution) WriteWcsTask, with the same service
-> PlateSolveService.updateFitsHeaderWithWCS(...)
-> WCS-aware viewers and reports
```

An online solve logs in with the account chosen on the Astrometry Config tab. It uploads a temporary copy made by `util.FitsUploadCopy`, named `spacepixels-solve-*.fits`, deleted when the operation completes or fails to start. In every header of that copy the site and observer cards (`FitsUploadCopy.PRIVATE_KEYWORDS`) are blanked, and `CHECKSUM` too where a card was blanked. Pixels and all other cards, the pointing hints included, are copied byte for byte. The anonymous login is one account shared by every anonymous user. `PlateSolveService` keeps one Astrometry.net client for the account in use, made at the first online solve. It replaces (and closes) the client when the key changes, and `ProcessingSession.close` closes it with the session. **Check** (`AstrometryNetAccount.checkLogin`) needs no imported session: it logs in with a short-lived client on a `SwingWorker`, uploads nothing, is cancelled when the panel closes, and ignores its answer if the account changed meanwhile. `PlateSolveTask` words a failed online solve through `AstrometryNetAccount.failureMessage`: a lost connection, a time-out, an unknown key for the account in use, and any other refused login with Astrometry.net's own reason.

Writing a solution into a frame changes the user's own file:
- **Safe replacement:** `PlateSolveService` writes the updated frame to a temporary file beside it. It reads the copy back to check the image size and that all of its data is present, and only then replaces the original in one step. A failure leaves the original unchanged.
- **Off the Swing thread:** the write rewrites the whole frame, so it runs as a job (`WriteWcsTask`), with the window locked and an indeterminate progress dialog. The solver's temporary files are removed after it either way.
- **The table and viewers:** `ProcessingSession.replaceFrameHeader` replaces the frame's immutable `FitsFileInformation` in the session and selection on the Swing thread once the write is done, and `MainApplicationPanel` updates the table and any open viewers.
- **Input metadata (`FitsFileInformation`):** immutable descriptor with an unmodifiable header map and construction-time normalised observation timestamp; `withFitsHeader` creates an updated copy when a WCS solution is written.

Report export later resolves WCS through:

```text
ReportExporter
-> DetectionReportAstrometry
-> WcsSolutionResolver
-> WcsCoordinateTransformer
```

### Inspection and Preview Flow

Detection sequence viewer:

```text
MainApplicationPanel
-> DetectionSequenceFrame (a SequenceViewerFrame; prepares one frame at a time on a SwingWorker)
-> JTransient SourceExtractor.extractSources(...)
-> detection markers drawn by FrameView
```

The Overview's Preview Frame button opens the same window. Its Detection Sigma, Grow Sigma and Min Detection Pixels fields are independent trial values; changing them detects the shown frame again. Apply to Settings merges only explicitly edited fields into the current settings page.

Manual transient inspection:

```text
MainApplicationPanel
-> ManualTransientInspectionTask
-> SessionFrameLoader (shared with the Star Mask Explorer)
-> JTransient transient filtering path
-> TransientInspectionFrame
```

**The last run's tracks.** Blink, Preview Frame and Manual Transient Inspection draw the tracks of the session's last Detect Moving Targets run from the same `TrackOverlay` (`ProcessingSession.getTrackOverlay()`). The run hands it over when the engine returns, and the window stores it in the session when the job ends, whatever became of the report (see the jobs section). `SequenceViewerFrame.setTrackOverlay` gives the two sequence viewers Blink's **Show tracks** switch (T): it is disabled with a reason when no track of that run is in the window's frames, and `FrameView` draws the positions the run measured, by file name. Nothing is detected again, and changed settings do not change the overlay. Preview Frame takes the overlays when it opens and again when a run's overlays reach the session (`DetectionSequenceFrame.showLastRun`, from `MainApplicationPanel.showDetectionOverlays`), so a run that ends while it is open shows its tracks. Closing a viewer clears the frame view, which drops the overlay; the overlay itself holds no listeners.

### Report Export Flow

```text
PipelineResult + raw frames + metadata + config + app config
-> ReportExporter
-> DetectionReportContext / DetectionReportSummary
-> section writers
-> renderers and GIF writer
-> detection_report.html plus assets
```

Report categories are driven by JTransient result data:

- moving-object tracks
- streak tracks
- anomalies
- slow-mover candidates
- camera-fixed groups (`FC`), removed from point detections and `allTransients` before linking
- variable-star candidates and photometric readiness
- common-motion groups, filtered from unclassified observations before optional residual analysis
- residual local rescue candidates
- residual local activity clusters
- diagnostic transients, masks, drift, and telemetry

Same-frame groups of rescued anomalies are offered to confirmed-streak consolidation; an unmerged group remains separate anomalies. There is no suspected-streak result category.

`AnomalySectionWriter` presents these as unconfirmed single-frame events: exposure timing and coordinates, plain-language detection reasons, a static before/event/after comparison and a shareable GIF. Both views use the same event-derived display stretch and nearest kept neighbours; labels sit outside the sky crops. Raw measurements, footprints and mask overlap are collapsed under Detection details. Anomaly SatChecker queries use the event position and the whole exposure, with metadata fallback from the FITS frame; they do not use the shortened streak-query window. They reuse the report's live lookup and cache/sidecar flow and present satellite results as possible associations without changing detection classifications.

An anomaly is marked as likely part of a moving-object track when all of these hold:

- The track has no detection in the anomaly's frame, and that frame lies within the track's frames or just before or after them.
- The track's predicted position lies within `predictionTolerance` of the anomaly. The prediction interpolates between the neighbouring detections at the exposure midpoints, or uses frame order without timestamps. Next to the track it extends the first or last step.
- The anomaly passes the linker's profile check (`maxFwhmRatio`, `maxSurfaceBrightnessRatio`) against the track's nearest detection.

The card gets a title badge and a note, both linking to the track card. `DeepStackReportSectionWriter` marks a slow-mover candidate the same way when the detections of a reported track lie on its smear. These marks change only the presentation: the findings keep their categories, numbers and JSON entries.

Camera-fixed filtering (`enableCameraFixedRejection`) uses registrations inferred from fill edges. At least three frames must map detections within 2 px of the same camera position while their registered-frame positions spread at least 4 px. Unusable registrations are excluded. `ReportInclusion.cameraFixed` controls presentation, independently of the engine filter.

Common-motion filtering has its own switch and runs before local rescue and activity clustering. It assigns each observation once, preserves ambiguous associations for review, and removes accepted groups from the residual input while retaining them in the report. The residual-analysis switch controls only local rescue and clustering.

`PipelineTelemetry.stageTimeMs` provides ordered, non-overlapping major-phase wall-clock durations, and `processingTimeMs` includes the full engine run through photometry. `TrackerTelemetry.faintStreakFragmentTimeMs` is a substep already included in tracking (-1 when not run). `PipelineDiagnosticsSectionWriter` displays these durations under Diagnostics. `PhotometryTelemetry.framesExcludedSpatial` supplies the spatial-exclusion summary and diagnostic count. Engine timing excludes application image loading, Auto-Tune and report export.

### Sky catalogue and plate correction

- `SkyCatalogueClient` queries SIMBAD, VSX and Gaia for the solved field. `SkyCatalogueStore` persists results in `sky_catalogue.json`; restore requires a matching field. Catalogue errors are retained as problems. A session-owned `gui.SkyCatalogueController` runs fetch and correction fitting on one executor, with a future and identity per request. It owns the progress timer and publishes results/status on the EDT only while both the request and session remain current. A session shutdown callback cancels work and releases the executor, timer and view listener without a dependency from `session` to `gui`. Completed catalogue results remain in use independently of fitting and save failures.
- Each session owns its own `session.SkyCatalogueModel`; `SkyAnnotations` binds to that model explicitly. Every viewer closes on handover (a fresh start) and at exit, through `ViewerRegistry`; disposed windows are unregistered and their workers and listeners released. Existing field/solution guards remain. `FrameView` clips and thins marks to manage density.
- `WcsCoordinateTransformer` handles TAN-SIP distortion. `PlateCorrectionFitter` matches isolated Gaia/extracted stars, fits a global displacement of order 1–3 plus per-frame terms, and uses held-out frames to choose the lowest order within 3% of the best. It skips already-good header solutions (90% within 0.5 px) or solutions already within 3% of the best fit.
- A correction is saved in `plate_correction.json`. A new fit starts enabled when median error falls by more than 1 px and the 90th-percentile error also falls. The main status shows the improvement. An optional `userEnabled` records an explicit viewer choice and preserves it across compatible refits; import restores the saved state. `WcsSolutionResolver.resolve` applies only a compatible correction with the same header-solution identity and dimensions. A concrete frame uses its per-frame correction; report-wide resolution uses the global part.
- `SessionCatalogue` supplies field-matched report context. Saved and live VSX matches enrich reports without changing engine confidence. Unmatched candidates may show a SIMBAD proper name or Gaia DR3 designation from the session catalogue within at most 5 arcsec, including source and separation, without counting as VSX identifications. Gaia matches retain the existing proper-motion correction. Gaia source IDs are stored as optional strings to preserve all digits; older catalogues without them remain readable and prompt a re-fetch for names. `VariableStarMapWriter` uses the same VSX-first/general-star naming policy for its field maps, preserving V IDs in metadata and hover text. Full-size curves are compact translucent sparklines with observed delta magnitude, no axes, and gaps for unusable measurements. The table keeps this fallback during unsuccessful VSX lookups and gives positive VSX results precedence. The detailed comparison rules, provenance exceptions and validation are in [catalogue comparison](docs/features/catalogue-comparison.md).

- `PhotometryReportSectionWriter` separates the cached in-report VSX lookup from **Research this star** browser links. Coordinate searches target general Gaia DR3 (`I/355/gaiadr3`, without the downloaded magnitude limit), unrestricted VizieR catalogues and SIMBAD. They need a plate solution but no saved catalogue or running proxy; no extra data is fetched or cached during export. Cards show the search radius, and a shared collapsible follow-up note distinguishes candidate confidence and positional associations from confirmed variability or novelty. Measurements, classifications and Known As values are unchanged by external searches.

- `VariableStarReportOptions` stores the card limit (default 40) and presentation order in the SpacePixels profile, separately from JTransient configuration. GUI, API requests and batch profiles pass immutable choices in the run's `VisualizationSettings`. `VariableStarPresentation` prepares one order/card selection while retaining the original engine-order V identities. Table/cards and report navigation use the chosen order; maps, comparison payloads, CSVs and live viewer overlays retain the same identities. Unknown catalogue periods sort after known ones within a tier. `ReportData` uses actual selected-card anchors and records the presentation options in JSON. No engine setting or global rendering default is introduced.

### Shared frame viewers

Blink, Preview Frame, Manual Transient Inspection and Show full size share `FrameView`, `StretchControls` and `ViewerSupport`. `SequenceViewerFrame` prepares one frame at a time on a `SwingWorker`, rejects obsolete worker results, and releases listeners on disposal. `BlinkSequence` holds 16-bit frames, histograms and half-size display copies. Stretch controls share their models with the main stretch panel and must detach on disposal. Detection overlays use the last standard-run results, including variable-star positions when available. Preview Frame prepares each frame with `DetectionConfigurationPanel.settingsInUse()`, a copy taken on the EDT just before (`SequenceViewerFrame.preparing`). It takes detached specifications for Detection Sigma, Grow Sigma and Min Detection Pixels (`perFrameDetectionSettings()`), and TrialSettingsControls detects the shown frame again 300 ms after a trial edit. The same controls serve Star Mask Explorer: checked text, Enter/Escape, Reset and selective Apply. Apply uses current settings and normal pair rules, and changes no saved baseline. Trial pair alignment does not mark untouched fields edited. ViewerRegistry pauses every trial window during application jobs; closing stops delayed work and reopening Preview Frame reloads current values. Window keys bound through `ViewerSupport.bind` do not fire while a text field has the focus.

The shared **Stretch display** button model selects stretched or full-range linear display, defaults to on, and is saved as `stretch.enabled` in the existing Java preferences node. Off disables the stretch controls while retaining their values; viewers use `DisplayStretch.linearLookupTable`, preserving RGB channels even when Extreme is selected. Preview requests capture this flag so obsolete stretched results cannot replace a newer linear request. **Enable batch stretch** remains a separate disk-output option and uses the chosen algorithm/parameters independently of the display flag.

Settings rows apply valid edits to the loaded config in place so hidden fields survive. Core fields live on Overview; basic fields are visible by default; expert sections disclose through the navigator, search or unsaved-changes filter. Strictness presets set the five frame-outlier limits together and show Custom for other combinations. Unknown JSON keys are ignored by Gson, and key-set differences trigger the backed-up migration offer. No edge/void fields remain; single-frame preview, tuning-pool and correction extraction use `DataArea.ofFrame`.

### Report diagnostics

`DataAreaPresentation` draws the kept-frame intersection on the Overview picture and explains where sky/star measurements are made. `StarShape`, `NightEquipment` and `FieldDepth` measure diagnostics during export; these calculations currently sit in the reporting layer.

`NightEquipment` measures isolated stars across kept frames for seeing, relative transparency, field shape/brightness maps, and sky estimates. It accounts for `SPOFFSET`, uses PIERSIDE to orient minority-side frames, and requires catalogue/linearity support for calibrated sky estimates. Optical interpretations remain diagnostic hints. `FieldDepth` compares Gaia stars with detections in catalogue depth patches, using the run's thresholds and cut-out data areas; crowded or inadequately recovered patches are excluded. These measurements do not change engine detections.

## 6. Main Data Contracts

### Input metadata

`FitsFileInformation` is SpacePixels' per-file metadata record. It carries:

- file path and display name
- image format and color-space facts
- dimensions and bit-depth facts
- observation timestamp and diagnostics
- exposure duration
- location and solved state metadata

This is the bridge between FITS import, GUI table display, JTransient `ImageFrame` construction, WCS/report logic, and API results.

### Detection configuration

JTransient `DetectionConfig` is the engine configuration contract.

SpacePixels wraps it in `SpacePixelsDetectionProfile` to add:

- `autoTuneMaxCandidateFrames`

Visualization preferences are separate and must not be treated as detection inputs.

### Pipeline result

JTransient `PipelineResult` is the raw detection result contract.

SpacePixels uses it for:

- report generation
- detection-count safety summaries
- API return values
- iterative pass summaries

SpacePixels should not reinterpret `PipelineResult` by duplicating engine logic. It should aggregate, render, and expose it.

### Effective config

Pipeline runs return or export the effective config actually used. This may differ from the base config because:

- Auto-Tune may produce an optimized clone.
- SpacePixels may apply runtime-safe adjustments before engine execution.

## 7. Persistence and Outputs

User-level JSON files under `user.home`:

- `spacepixels_app.json`
  - ASTAP path and observer/astrometry metadata
  - the Astrometry.net account: anonymous (JPlateSolve's guest key) or the observer's own API key, stored in plain text and kept while Anonymous is chosen
- `spacepixels_detection_profile.json`
  - JTransient `DetectionConfig` plus `autoTuneMaxCandidateFrames`
- `spacepixels_jtransient.json`
  - legacy detection-profile filename read for compatibility
- `spacepixels_visualization.json`
  - report and visualization preferences

Generated working directories:

- XISF conversion output
- decompressed FITS output
- prepared mono-16 FITS output for API auto-preparation
- 32-bit-to-16-bit conversion output
- mono conversion output
- stretch output

Detection report output:

- `detections_YYYYMMDD_HHMMSS/detection_report.html`
- image and GIF assets beside the report
- effective settings in `detection_config.json`, stable finding data in `report-data.json` and CSV exports
- session catalogue/correction files (`sky_catalogue.json`, `plate_correction.json`) in the imported dataset folder, guarded by field and plate-solution identity; a GUI run takes them from the session when it is admitted (`SkySnapshot`), a headless run reads them once at its start
- iterative root directory containing per-pass report directories, `index.html`, versioned `run-data.json`, per-pass `<count>_inputs.json` and optional `auto_tune.txt`; manifests use filenames rather than private absolute paths and do not store observing-site coordinates

Replacing files:

- The settings files, the session files, report text files, plate-solved frames and the converted FITS copies are written through `util.StagedFile`. (Report images, which go into a new folder per run, and plate-solver downloads are written directly.) The new contents are written to a temporary file in the same folder (`.<name>.<number>.tmp`) and moved over the target in one step only once complete. The previous file is never deleted first, and a failed write or move removes the temporary file and leaves the previous file as it was.
- Without an atomic move (not expected for a file in the same folder), the previous file is renamed aside and put back if the new one cannot take its place.
- Save on the detection settings tab writes both settings files completely before replacing either. The two files are independent, so if the second replacement fails the new profile stays saved; the error names the file that was not saved, and the tab keeps showing unsaved changes.

## 8. Threading and Progress Model

Desktop:

- **Every long job** runs under `JobCoordinator`:
  - admission and locking happen on the EDT before the worker starts;
  - progress and the single outcome come back on the EDT.
- **Previews:** the frame viewers and the Image Stretch previews render on `SwingWorker`s; the newest request wins.
- **Sky catalogue:** the fetch and the plate-solution check run on their own thread. Their results are dropped when another session has been imported meanwhile.
- **One application I/O queue:** the window owns `session.ApplicationStorage`, whose single worker serializes application-default saves, detection-profile/visualization saves, migration, window placement, session metadata writes and import metadata reads. A reimport reads after earlier accepted writes. Closing stops admission immediately; accepted work drains off the EDT with the existing timeout/retry dialog before exit. Settings failures are tracked by target and cleared only by a successful retry of that target. Session write failures retain the existing warning at shutdown, even after a later successful write.
- **Session metadata:** import prepares saved catalogue/correction/Auto-Tune data before EDT handover, retaining field and frame guards. `ProcessingSession` owns save-completion lifetime using its existing closed flag; it logs/records failures and delivers completion on the UI executor only while open, including completions queued before close. `SkyCatalogueModel` receives its correction writer from that owner; catalogue and Auto-Tune callers use the owner's save methods. There is no separate `SessionPersistence` lifecycle. A correction switch takes effect immediately and a GUI run captures the in-memory catalogue/correction at admission without waiting for saves. Failed edits remain in use for the session with a visible unsaved explanation. Replacing the session suppresses callbacks but does not discard its queued writes.
- **Application preferences:** UI handlers capture snapshots before submission to the same queue; only successful EDT completion updates the corresponding saved baseline. Editing during a write does not change its contents or become falsely marked saved. Failures preserve edits and partial-write explanations. A slow session write can delay a settings save and vice versa, while the window remains responsive. Initial preference/profile reads remain in startup initialization. File formats and names are unchanged.
- **ASTAP checks:** `AstapInstallationCheck` performs executable/directory inspection and the Browse selection's help launch off the EDT. It permits one active check and only the newest pending request; obsolete completions cannot replace a newer selection, and closure cancels/detaches the UI callback. It does not start a foreground detection job or lock the workspace.

Headless/API:

- progress is reported through `SpacePixelsProgressListener`
- pipeline progress is scaled across preparation, Auto-Tune, engine execution, and report export phases, and only moves forward

Progress ranges:

- JTransient `runPipeline(...)` reports 0-100%: extraction up to 40%, frame rejection 42%, master stack 45-49%, track linking 50-90%, common-motion filtering and optional residual analysis 92%, photometry 93-99%
- the standard pipeline shows frame loading at 0-20% and the engine at 20-100% of the detection run; the GUI shows the detection run as 0-90% and report export as 90-99%, then 100% when done
- the report reports each step and each item of its slow steps (`ReportProgress`: tracks and anomalies, unclassified-transient cutouts, likely-artifact cards); the shares follow the time each step took on a 33-frame 61-megapixel session
- the API shows input preparation up to 15%, Auto-Tune 15-35%, the detection run up to 90% and report export 90-100%
- the iterative pipeline reserves 0-10% for preparation and, when requested, 10-30% for one-time tuning; the passes share the remaining progress range and use the ordinary detection/export progress internally

JTransient:

- the engine performs parallel extraction internally
- SpacePixels treats engine lifecycle as run/shutdown scoped per pipeline pass

## 9. Design Constraints

- Keep `eu.startales.spacepixels.api` small and stable.
- Treat non-API packages as internal implementation details.
- Keep JTransient algorithm details in JTransient; do not fork detector behavior into SpacePixels.
- Keep report generation as rendering and aggregation over `PipelineResult`.
- Add backend capabilities as focused services with explicit owners; do not reintroduce a forwarding facade.
- Keep detection config and visualization preferences separate.
- Keep GUI-only prompting out of headless/API paths.
- Keep `jobs` and `tasks` free of Swing and the `gui` package; `DependencyRulesTest` checks this.
- Replace files through `StagedFile`; never delete a user's file before its replacement is complete.
- Start every GUI run and save from `DetectionConfigurationPanel.checkedSettings(...)`; never hand a task the panel's live `DetectionConfig`.
- Do not add concurrent Auto-Tune API execution without removing the shared JTransient sample-size mutation.

## 10. Current Design Debt and Refactor Boundaries

Accepted current debt:

- `DetectionConfigurationPanel` and `MainApplicationPanel` still combine Swing view logic and workflow control.
- `DetectionReportGenerator` now keeps the iterative index. Map pictures and text formatting live in `ReportMaps` and `ReportFormat`.

Preferred refactor direction:

- extract behavior into focused services first
- avoid changing user workflows while moving internals
- preserve `SpacePixelsPipelineApi` request/result semantics
- preserve report output shape unless the change is explicitly user-facing
- update `MANUAL.md`, `API.md`, and this document when architectural behavior changes

Use this document for:

- stable facade inventory
- subsystem ownership
- runtime flow reference
- integration boundaries

Use [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) for pending design and refactoring work, with its priorities and order, and [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) for pending functionality.
