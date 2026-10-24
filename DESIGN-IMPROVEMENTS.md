# Design improvements

This file lists design, refactoring and code-quality work for SpacePixels: changes to how the code is organised, not to what the application does. New or changed functionality is in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current design is described in [DEVELOPER.md](DEVELOPER.md) and [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md).

Every item here is a proposal until the maintainer decides on it. When an item is done, describe the result in DEVELOPER.md or HIGH_LEVEL_DESIGN.md and remove it from this file; git history keeps the record.

## Code review of 2026-10-23

A fresh review of the whole application (about 50,000 lines of main code and 17,000 of tests), against the principles in [DEVELOPER.md](DEVELOPER.md#principles): readable code, a clear design with clear boundaries, and a few chosen patterns applied consistently, with no abstraction that lacks a real need.

**Overall.** The architecture is sound. Jobs, commands, the session handover, the viewers and the public API are small, well-bounded and consistent, and should stay as they are. The over-engineering the maintainer suspected is real, but it is concentrated in three kinds of place:

1. **State with more than one owner.** The plate-solution correction, the rendering preferences and the session's frame list each live in two or three places. Each copy then needs machinery to stay in step: write barriers, version checks, rollback, static caches and "capture" steps. Giving each piece of state one owner removes that machinery rather than adding to it.
2. **Layers kept for compatibility after compatibility stopped mattering.** The libraries are now developed together with the application, and old signatures need not survive. Still left over are the `ImageProcessing` facade, which mostly forwards calls, and chains of overloads that exist for older callers.
3. **Mechanisms heavier than their use.** Examples are two near-identical write queues and an acknowledgement barrier for three small JSON files, and a binding framework whose application side writes to global static fields.

Line counts alone are not a problem. Large report section writers and panels are acceptable when they read top to bottom as one responsibility.

### What is good and should stay

- **Jobs** ([JobCoordinator](src/main/java/eu/startales/spacepixels/jobs/JobCoordinator.java), `Job`, `JobOutcome`): one admission rule, a sealed typed outcome, exactly one end per job, and no Swing. It is the right size.
- **Commands** ([CommandAvailability](src/main/java/eu/startales/spacepixels/gui/CommandAvailability.java), `CommandAction`): pure rules over a `Facts` record, one Swing `Action` per command, re-checked when invoked. This is a clear and testable pattern; use it for any new command.
- **Session handover** (`SessionOwner.replace(expected, candidate)`): simple, explicit and tested without windows.
- **Viewers** (`SequenceViewerFrame` template method, newest request wins, `FrameView`, `StretchControls`, `ViewerSupport`): good reuse by composition.
- **Typed detection setting definitions** (one table of `DetectionSetting` per settings page): explicit and searchable. Keep them; see SP-07 for the application-side bindings.
- **Staged file publication** (`StagedFile`, `AtomicReportFile`), and **boundary tests** (`DependencyRulesTest`, the opt-in GUI mechanics suite).
- **The public API** (`SpacePixelsPipelineRequest` / `Result`, `DefaultSpacePixelsPipelineApi`): a request object in, a result object out, with no GUI.

### Recommended changes

| ID | Change | Priority | Size |
|---|---|---|---|
| [SP-01](#sp-01-one-owner-for-the-plate-solution-correction) | One owner for the plate-solution correction | High | Medium |
| [SP-02](#sp-02-rendering-preferences-as-a-value-not-static-fields) | Rendering preferences as a value, not static fields | High | Medium |
| [SP-04](#sp-04-retire-the-imageprocessing-facade) | Retire the `ImageProcessing` facade; the session owns its services and data | Medium | Large |
| [SP-05](#sp-05-give-panels-their-collaborators-not-the-whole-window) | Give panels their collaborators, not the whole window | Medium | Medium |
| [SP-06](#sp-06-move-the-sky-catalogue-workflow-out-of-the-main-panel) | Move the sky-catalogue workflow out of the main panel | Medium | Small |
| [SP-07](#sp-07-one-binding-type-for-settings-rows) | One binding type for settings rows | Low | Small |
| [SP-08](#sp-08-one-report-entry-point-and-a-complete-report-context) | One report entry point and a complete report context | Medium | Medium |
| [SP-09](#sp-09-one-write-queue-for-small-files) | One write queue for small files | Low | Small |
| [SP-10](#sp-10-one-place-for-sky-math-and-statistics-helpers) | One place for sky-math and statistics helpers | Low | Small |
| [SP-11](#sp-11-fewer-fits-helper-classes-immutable-frame-metadata) | Fewer FITS helper classes; immutable frame metadata | Low | Medium |
| [SP-13](#sp-13-one-way-to-report-problems-and-diagnostics) | One way to report problems and diagnostics | Low | Small |
| [SP-14](#sp-14-package-names-that-say-what-they-hold) | Package names that say what they hold | Low | Medium, mechanical |
| [SP-15](#sp-15-pass-the-live-view-sink-with-the-run) | Pass the live-view sink with the run | Low | Small |
| [SP-16](#sp-16-check-the-ui-at-larger-display-scaling) | Check the UI at larger display scaling | Low | Small |
| [SP-18](#sp-18-upgrade-the-dependencies) | Upgrade the dependencies, in all three repositories | Medium | Small, plus a regression |
| [SP-19](#sp-19-finish-the-common-base-of-the-image-viewers) | Finish the common base of the image viewers | Medium | Medium |

**Suggested order.**
1. Start with the small, safe item SP-10. SP-18 is independent of the rest and can go at any time, as long as its regression runs on its own.
2. Then SP-01 and SP-02, which remove the most machinery.
3. Then SP-04 together with SP-09, then SP-05 and SP-06.
4. Then SP-08, then SP-07 and SP-15.
5. Do SP-14 last: it is mechanical, and renaming first would only make the other changes harder to review.

Every item preserves behaviour. Check each one with the unit tests and the GUI mechanics tests. Items that touch detection or reports also need byte-identical `report-data.json`, `detection_config.json` and CSVs on the reference datasets, using the method in DEVELOPER.md.

### SP-01. One owner for the plate-solution correction

**Problem.** The correction has three sources of truth:
- `SkyCatalogueModel` in memory;
- `plate_correction.json` on disk;
- a static cache in `PlateSolutionCorrection`, keyed by folder and file time (`CACHE`, `activeIn`, `forget`).

`WcsSolutionResolver.resolve(info, files)` reads the correction from disk through that static cache, without the caller knowing. This happens in `ViewerSupport`, `VariableStarOverlay` and `AstrometryContextFactory`. `SkyAnnotations`, by contrast, passes the model's correction explicitly. `ReportRunContext` loads the correction from disk again.

Because runs read the disk, every job waits for pending session writes first (`ApplicationWindow.sessionJob` → `SessionPersistence.awaitWrites`, a barrier that polls every 100 ms). `SkyCatalogueModel` also keeps a saved copy and a revision number, so it can roll the in-memory toggle back when a write fails. The catalogue is saved by `MainApplicationPanel` through `owner.getPersistence().save(() -> owner.getPersistence().storage().store().saveCatalogue(...))`, and the correction by `SkyCatalogueModel`: two save paths for the same session folder.

**Proposal.**
- The session's `SkyCatalogueModel` is the only source. Runs, reports and viewers receive the correction explicitly: `ReportRunContext` takes it from the session at admission, and `WcsSolutionResolver.resolve` always takes it as a parameter.
- Saving is fire-and-forget on the background writer. A failure shows a warning and leaves the in-memory value in use.
- Delete the static cache with `activeIn` and `forget`, the `awaitWrites` barrier, the acknowledgement set in `SessionPersistence`, the revision and rollback in `SkyCatalogueModel`, and the job wrapper in `ApplicationWindow.sessionJob`.
- Save catalogue and correction through one session method each.

**Benefit.** About 150–200 lines of synchronisation code go away. A viewer and a report can no longer disagree about which correction is in use.

**Verification.** Toggle the correction (C), then start a detection at once: the report uses the toggled state. Inject a failing write: a warning appears and the value stays in use. Reports stay byte-identical on a plate-solved reference dataset.

### SP-02. Rendering preferences as a value, not static fields

**Problem.** The rendering and export preferences live in public static mutable fields of the classes that render:
- `DetectionReportGenerator`: `gifBlinkSpeedMs`, `trackCropPadding`, `trackObjectCentricCropSize`, `targetCircleRadius`, `targetCircleStrokeWidth`;
- `DisplayImageRenderer`: `autoStretchBlackSigma`, `autoStretchWhiteSigma`;
- `RawImageAnnotator`: four marker sizes;
- `UnclassifiedTransientSectionWriter`: `maxAnimatedMarkers`.

`SpacePixelsVisualizationPreferences.apply()` writes them, the settings panel's `ApplicationSetting` bindings read and write them, and `DetectionReportGenerator.captureExportSettings` copies them into an `ExportVisualizationSettings` when a run starts. So the `config` package depends on the report generator. The same values exist both as globals and as a captured copy, and the capture step exists only because of the globals.

The variable-star report options travel the same way. They are held in a `transient` field of `AppConfig` (the solver and site preferences), in `ExportVisualizationSettings`, in `DetectionRunOptions`, in `SpacePixelsDetectionProfile` and in fields of the settings panel.

**Proposal.**
- One immutable `VisualizationSettings` value, which `ExportVisualizationSettings` can become, owned by the window together with the detection profile, and carried by each run in `DetectionRunOptions`.
- Renderers receive it as a parameter. Delete the static fields, `captureExportSettings` and `SpacePixelsVisualizationPreferences.apply()`.
- Remove `variableStarReportOptions` from `AppConfig`.

**Benefit.** No global mutable state in rendering. `config` no longer depends on `util.reporting`. One way for a preference to reach a picture.

**Verification.** Unit tests for the renderers with explicit settings. Save, Revert and startup reload of the visualization page in the GUI mechanics tests. Byte-identical reports with default and changed settings.

### SP-04. Retire the ImageProcessing facade

**Problem.** `ImageProcessing` (562 lines) began as the old monolith. It is now mostly a pass-through over `FitsImportService`, `FitsBatchService`, `PlateSolveService`, `StandardDetectionPipelineService`, `IterativeDetectionPipelineService` and `FitsVisualizationRenderer`. It also carries:
- nested types that belong elsewhere: `DetectionSummary` (about 100 lines), `PipelineExecutionData`, `RedirectImportException` and `DetectionSafetyPrompt`;
- static FITS helpers;
- three similarly named file accessors: `getImportedFiles`, `getFitsfileInformation` and `getFitsfileInformationHeadless`.

Its Javadoc says it keeps "the public processing API stable", but API.md marks it as internal. It also owns `SessionFrames`, a shared mutable holder of the file list and the last run's overlays. The session exposes that holder, and `StandardDetectionPipelineService` writes overlays into it as a side effect of a run. So session state is split between `ProcessingSession`, `ImageProcessing` and `SessionFrames`. Tasks still call the facade `preProcessing`.

**Proposal.**
- Tasks and the API call the services directly. The session (or one `SessionServices` holder it owns) creates them with the session's files and settings.
- The session owns its files and the last run's overlays. A run returns its overlays in its result, and the GUI stores them in the session.
- Move `DetectionSummary` and `PipelineExecutionData` to their own files.
- Then delete `ImageProcessing` and `SessionFrames`.

**Benefit.** One owner for session data and one obvious place for each operation, with no forwarding layer.

**Verification.** All tests, the GUI mechanics suite, and byte-identical reports on the reference datasets. This is the largest item. Do it in steps, one service at a time.

### SP-05. Give panels their collaborators, not the whole window

**Problem.** Every panel receives the whole `ApplicationWindow` and reaches through its getters:
- `MainApplicationPanel` does so 82 times, including `getSession` 26 times, `getStretchPanel` 12 times and `getDetectionConfigurationPanel` 7 times.
- `DetectionConfigurationPanel` does so 40 times, including `getMainApplicationPanel` 7 times.
- `StretchPanel` calls `getMainApplicationPanel` 7 times.

Panels call each other through the window, so `DetectionConfigurationPanel` and `MainApplicationPanel` depend on each other. For example, `publishActiveSettings` pushes eight spinner values, as positional parameters, into `MainApplicationPanel.showActiveDetectionSettings`.

**Proposal.**
- Each panel's constructor takes only what it uses: a session supplier, the `JobCoordinator`, the shared stretch settings, and narrow listener interfaces such as "settings changed" or "selection changed".
- The window creates the panels and wires them together, and nothing else.
- The settings page publishes the current `DetectionConfig`, not eight numbers.

**Benefit.** Each panel's dependencies are visible in its constructor. There are no cycles between panels, and smaller parts can be tested.

### SP-06. Move the sky-catalogue workflow out of the main panel

**Problem.** `MainApplicationPanel` holds about 300 lines of the sky-catalogue and correction workflow. They manage a raw `Thread` through a one-element array, check identity by thread and session, and run their own progress timer. `ProcessingSession` has three methods only for that thread (`ownSkyCatalogueWorker`, `ownsSkyCatalogueWorker`, `releaseSkyCatalogueWorker`). This is a second background-work mechanism inside a view.

**Proposal.** A `SkyCatalogueController` in `gui` with one `ExecutorService` and a `Future` per session: start, cancel, and progress and results delivered on the EDT. Closing the session cancels the future. The panel only shows status.

### SP-07. One binding type for settings rows

**Problem.**
- `DetectionSettingBinding` (engine fields) and `ApplicationSettingBinding` (application values) are two records of the same shape. With them come `ApplicationSetting`, client-property identity tags and three binding groups for twelve controls.
- `applySettingsToMemory` catches every `Exception`, prints it with `System.err` and continues. It also writes into `mainAppWindow.getImageProcessing().getAppConfig()` (see SP-02).

**Proposal.** After SP-02, the application values are fields of plain value objects. Use one generic `SettingBinding<V>`, with a read and write of the model and a read and write of the control, for both kinds. Let a binding failure surface as an error instead of being swallowed.

### SP-08. One report entry point and a complete report context

**Problem.**
- `DetectionReportGenerator` (1,523 lines) is an all-static class that holds global settings (SP-02), data types, image rendering (mask overlays, global maps, drift map, mosaics, cluster maps), text formatting and the export orchestration. 27 classes call its static helpers.
- The export has one entry, `exportTrackVisualizations`, with eight positional parameters. The API and the GUI reach it through two forwarding layers: `ImageProcessing` (`detectObjects`, `runDetectionPipeline`, `exportDetectionReport`) and `StandardDetectionPipelineService`.
- `DetectionReportContext` takes 20 constructor parameters, copied one by one from the `PipelineResult`. Six more fields are set after construction.
- The context also has fallback branches "for isolated section callers": `data == null ? category.id(..) : data.finding(..)` and `runContext == null ? defaults`. That is production code kept only so tests can build sections without a full context.

**Proposal.**
- One `ReportExporter` with a single `export(ReportRequest)`: the request holds the result, frames, files, run context and inclusion.
- A context factory that builds `DetectionReportContext` completely from the `PipelineResult` and `ReportData`. Tests use the same factory, so the fallback branches go.
- Move map rendering to a `ReportMaps` class and formatting to `ReportFormat`.

**Benefit.** One way into the report, a context that is always complete, and section writers that depend on two small helpers instead of the whole generator.

**Verification.** Byte-identical report folders, minus the generation time, on the reference datasets.

### SP-09. One write queue for small files

**Problem.** `SettingsStorage` and `SessionStorage` are two near-identical single-thread write queues. `SessionPersistence` adds a per-session acknowledgement layer on top (see SP-01).

**Proposal.** After SP-01, keep one application-owned background writer for preferences and session files. Keep its drain-on-close behaviour. Remove `SessionPersistence`.

### SP-10. One place for sky-math and statistics helpers

**Problem.** Small helpers are implemented many times:
- angular separation, about eight times: `SessionCatalogue`, `SkyField`, `VariableStarOverlay`, `PhotometryReportSectionWriter`, `PipelineDiagnosticsSectionWriter`, `SatCheckerQueryTargetFactory`, `DetectionReportAstrometry` and `SolarSystemQueryTargetFactory`;
- `normalizeDegrees`, four times;
- a median, about eight times;
- `clampFieldOfViewDegrees`, twice;
- `isJplCompatibleObservatoryCode` and `normalizeLongitudeDegrees`, in both `DetectionReportAstrometry` and `JplSbIdentUrlBuilder`.

**Proposal.** One `SkyMath` class and one `Statistics` class in `util`, with unit tests. Replace the copies.

### SP-11. Fewer FITS helper classes; immutable frame metadata

**Problem.**
- FITS input is spread over small static classes: `FitsFiles` (32 lines), `FitsImages`, `FitsFormatChecker`, `FitsSequenceValidator`, `FitsPreparationService` (all static, despite the name), `FitsPixelConverter` and `DetectionInputPreparation`. `ImageProcessing.getImageHDU` forwards to `FitsImages`.
- "Service" means an instance with collaborators in some classes and a static utility in others.
- `FitsFileInformation` (445 lines) is mutable. Callers protect themselves with `snapshot()` and array copies, for example in `ReportRunContext` and `ProcessingSession.getFiles()`.

**Proposal.**
- Merge discovery, HDU access, format check and sequence validation into one `FitsInput` class. Keep the converters separate.
- Name static helpers consistently, and keep "Service" for instances.
- Make `FitsFileInformation` immutable, with timestamps normalised when it is built. The defensive copies can then go.

### SP-13. One way to report problems and diagnostics

**Problem.** Problems are reported in four ways:
- `System.err.println`, in about 15 places, for example in `DetectionConfigurationPanel`, `OutputFolders` and several report writers;
- `printStackTrace`, in `FitsFormatChecker`;
- `java.util.logging`, in `PlateSolveService`, `ApplicationSettings` and `SessionOwner`;
- `DebugOutput`.

**Proposal.** Report problems with `java.util.logging` (WARNING or SEVERE), and send diagnostics only through `DebugOutput`. If JTransient adopts JT-04, diagnostics can become `java.util.logging` at FINE too, and `DebugOutput` shrinks to setting that level.

### SP-14. Package names that say what they hold

**Problem.** `util` (49 classes) is the application's core, not a set of utilities. It holds the services and the facade, domain types (`PlateSolutionCorrection`, `TrackOverlay`, `VariableStarOverlay`, `ExposureGroup`, `BlinkSequence`), FITS input/output and WCS. `util.reporting` (56 classes) is a subsystem, not a utility.

**Proposal.** Domain-named packages: `fits` (input, preparation, conversion, WCS), `detection` (pipeline services, run options, overlays), `platesolve`, `report` (with a `report.lookup` subpackage for the SatChecker/JPL/SkyBoT proxy and builders) and `skycatalog`. Do it last, as a mechanical move, and keep `DependencyRulesTest` in step.

### SP-15. Pass the live-view sink with the run

**Problem.** `RunPreviewScope` binds the live view's sink to the run's thread with a `ThreadLocal`, so the loading loop and report writers can offer pictures without a parameter. It was a reasonable shortcut when nothing else was passed explicitly. After S14, the run already carries explicit options.

**Proposal.** After SP-08, carry the sink in the run request, an empty sink for API and CLI runs, and remove the `ThreadLocal` scope. Low priority: the scope is small and contained.

### SP-16. Check the UI at larger display scaling

What remains of the earlier visual-rules item. Inspect the empty and loaded main window, the settings pages, the Astrometry Config tab, the viewers and the progress dialog at 100, 150 and 200 % display scaling and with a larger base font. Fix the local widths and font sizes found in the Overview status, the table HTML and the viewer controls. Layout tests cover forms and base fonts; display scaling of the whole window has not been inspected.

### SP-18. Upgrade the dependencies

**Request (2026-10-23):** move every library, in all three repositories, to its latest version unless there is a reason not to. The versions below were checked on 2026-10-23. JTransient ([JT-08](../JTransient/DESIGN-IMPROVEMENTS.md#jt-08-upgrade-the-dependencies)) and JPlateSolve ([JP-11](../JPlateSolve/DESIGN-IMPROVEMENTS.md#jp-11-upgrade-the-dependencies)) point here.

| Dependency | Used in | Now | Latest | Proposal |
|---|---|---|---|---|
| Gradle wrapper | SpacePixels, JTransient | 9.2.1 | 9.8.2 (2026-10-20) | Upgrade. |
| Gradle wrapper | JPlateSolve | 8.12 | 9.8.2 | Upgrade. Fix the deprecation warnings first (`--warning-mode all`), since Gradle 9 removed deprecated features. |
| FlatLaf | SpacePixels (desktop) | 3.4 | 3.7.2 (July 2026) | Upgrade. Check the behaviour changes listed in [F12](FEATURE-REQUESTS.md#f12-newer-flatlaf-features). |
| nom-tam-fits | SpacePixels, JPlateSolve | 1.21.2 | 1.23.0 (2026-09-15) | Upgrade, with the pixel check below. Not 1.23.1-rc1, which is a release candidate. |
| Gson | all three | 2.13.2 | 2.14.0 (2026-04-23) | Upgrade. |
| AssertJ Swing (`tokyo.northside`) | SpacePixels tests | 4.0.0-beta-3 | 4.0.0-beta-3 | Already the latest. |
| JUnit | all three | 4.13.2 | 4.13.2 is the last JUnit 4. JUnit 6 is the current line. | Keep, for the reason below. |
| Java | all three | 21 | 25 is the current LTS; 27 is the newest release. | Keep the libraries at 21. Decide the application's runtime with F06, as below. |

**Risks found in the release notes:**
- **Compressed FITS (nom-tam-fits 1.22 to 1.23).** The decoding of compressed images changed, so decoded values of `.fz` frames can differ. The changes cover quantization rounding, ZBLANK, ZQUANTIZ without scaling columns, GZIP buffering, PLIO and SUBTRACTIVE_DITHER_2. SpacePixels converts `.fz` frames at import (`FitsImages`, `FitsPreparationService`).
  - The notes mention no change to uncompressed images, BSCALE/BZERO or header parsing.
  - Since 1.22.1, a malformed numeric header value throws `HeaderCardException` or `FitsException` instead of `NumberFormatException`. Check the eight `catch (NumberFormatException …)` blocks, for example in `FitsFileInformation` and `WcsCoordinateTransformer`, for any that guard a nom-tam-fits call.
  - From 1.22.2 the library needs Java 11, which is no problem here.
- **Duplicate keys (Gson 2.14.0).** Gson now rejects duplicate keys when the first value is null. Settings files written by SpacePixels have no duplicates, but a hand-edited file could. Unknown fields are still ignored.

**Verification:**
- Build and run all tests in each repository, and the GUI mechanics suite.
- Run the real-data regression; the outputs must be byte-identical on the reference datasets.
- For nom-tam-fits, compare the converted pixels of a few `.fz` frames between 1.21.2 and 1.23.0. No reference dataset is compressed, so compress a reference frame with `fpack` for this.
- Check that saved settings files load unchanged.

**Java.** JTransient and JPlateSolve should stay at release 21: a library that runs on the older LTS reaches more users, which is a reason not to take the latest. The application could move to Java 25 (LTS) as its runtime, and as the runtime bundled by the installer, since the newest LTS gets security updates longest. Proposal: decide it together with [F06](FEATURE-REQUESTS.md#f06-windows-installer).

**JUnit.** JUnit 4.13.2 is final and still works. Moving 808 tests to JUnit 6 (SpacePixels 508, JTransient 262, JPlateSolve 38) would be a mechanical migration that changes nothing in the application. The GUI mechanics tests would also have to move to AssertJ Swing's Jupiter variant. Proposal: not now. If it is done later, run the JUnit 4 tests on the Vintage engine (deprecated in JUnit 6, but still supported) and migrate test classes as they are touched.

### SP-19. Finish the common base of the image viewers

**Request (2026-10-23):** "I have another idea, related to the preview windows. To create a common base for all of them. Maybe they already have common mechanisms, but please check what we can have once, common for all of them."

**What is shared already.**
- `SequenceViewerFrame` is the base of Preview Frame, Manual Transient Inspection and the Image Stretch tab's sequence viewer (`StretchedSequenceFrame`). It is a template method that provides the window, stepping through frames, background preparation where the newest request wins, the overlays, and closing and releasing.
- `FrameView` (zoom, pan, overlays and the cursor readout), `StretchControls` and `ViewerSupport` (key bindings, zoom controls, status bar and cursor readout) are used by those three windows and by Blink.

**What is not shared:**

| Window | Window class | Image component | Closed when the session changes |
|---|---|---|---|
| Preview Frame | `SequenceViewerFrame` | `FrameView` | Yes, by the main panel |
| Manual Transient Inspection | `SequenceViewerFrame` | `FrameView` | **No** |
| Image Stretch sequence | `SequenceViewerFrame` | `FrameView` | Yes, by the stretch panel |
| Blink | its own `JFrame` (764 lines) | `FrameView` | Yes, by the main panel |
| Star Mask Explorer | its own `JFrame` (443 lines) | `ZoomableImageView` | **No**; its Apply button checks the session |
| Image Stretch tab preview | a panel | `ZoomableImageView` | Not applicable |

**Findings and proposals:**
1. **Two image components.** `ZoomableImageView` (288 lines) repeats `FrameView`'s zoom, pan and image pyramid (`levelFor` and the halved copies) for the Star Mask Explorer and the Image Stretch tab. Proposal: use `FrameView` everywhere, with an image overlay layer added for the star mask, and remove `ZoomableImageView`. Every image view then has the same keys, cursor readout and fit/100 % toggle.
2. **Two ways to try settings.**
   - Preview Frame's spinners edit the Detection Settings directly.
   - The Star Mask Explorer keeps trial values, with an "Apply to Settings" button.

   Each re-runs 300 ms after a change, with its own spinner code, and F13 would add a third window of this kind. Proposal:
   - one small trial-settings row, used by all three windows: the spinners, the delayed re-run, the note about invalid text, Enter and Esc, and disabling while a job runs;
   - one rule for whether a window edits the settings or offers "Apply to Settings". This is a decision point; F13 proposes Apply.
3. **Each owner closes its own windows.** Preview Frame and Blink are closed by the main panel, and the Image Stretch sequence by the stretch panel. Manual Transient Inspection and the Star Mask Explorer are not closed at all: after a new import they stay open on the old session, and the inspection window keeps that session's frames in memory until it is closed by hand. Proposal: every viewer registers with one owner, the application window's session listeners, which closes them all on a session change and at exit.
4. **Small duplicates.** `bindStretch` is identical in `SequenceViewerFrame` and `BlinkFrame`, and the session's name (its folder's name) is computed in three places. Move both into `ViewerSupport`.
5. **Blink stays its own window.** It preloads every frame for smooth playback and has the frame list with its skip boxes, which does not fit the one-frame-at-a-time `SequenceViewerFrame`. Forcing it onto the base would add hooks for a single subclass. Blink keeps using the shared parts by composition, as now, and gains items 3 and 4. The Star Mask Explorer shows a single image and also stays its own window, gaining item 1.

**In short:** the common base exists already. The work is to finish it, not to add another layer.

**Order:**
1. Item 3 first, since it changes what the user sees.
2. Then items 1 and 4.
3. Item 2 together with F13.

## Removed from the previous plan (2026-10-23)

- **S16, prepared diagnostic states and re-export.** Dropped. No code path renders a saved result again, so nothing would read the prepared states twice.
- **The rest of D09 and D10.** Done. The selected-release check is part of the release procedure in DEVELOPER.md, and the engine side is JTransient JA-11.
- **D12, shared visual rules.** Reduced to SP-16.
- **The target architecture map, the pattern table, the ownership table and the "constraints" list.** What still applies is in DEVELOPER.md, as principles, patterns and things to avoid.
- **The completed-work archive and the request register.** Removed. Git history keeps them.
