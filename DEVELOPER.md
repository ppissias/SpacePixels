# Developer overview

A two-page map of how SpacePixels is built: the packages, the patterns and the rules the code follows. The detailed architecture is in [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md), and the planned work in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Current as of 2026-10-20.

## Local library development

By default, Gradle uses the published JTransient and JPlateSolve versions in `build.gradle`. Each library can independently use a sibling checkout through the existing composite-build flags in `settings.gradle`:

| Gradle property | Local checkout | Alternative environment variable |
|---|---|---|
| `useLocalJTransientBuild=true` | `../JTransient` | `USE_LOCAL_JTRANSIENT_BUILD=true` |
| `useLocalJPlateSolveBuild=true` | `../JPlateSolve` | `USE_LOCAL_JPLATE_SOLVE_BUILD=true` |

For example, on Windows:

```powershell
.\gradlew.bat run -PuseLocalJPlateSolveBuild=true
.\gradlew.bat run -PuseLocalJTransientBuild=true -PuseLocalJPlateSolveBuild=true
```

Gradle builds the enabled library from its checkout and substitutes it for the published dependency, so changes are available without publishing a jar. The enabled checkout must exist. For persistent local choices, copy [gradle.properties.example](gradle.properties.example) to `gradle.properties` and set the flags you need to `true`; that local file is ignored by Git. Reload the Gradle project in IntelliJ after changing the flags.

An explicit Gradle property takes precedence over its alternative environment variable. Use `-PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false` to select both published dependencies even when local flags are enabled. Validate that combination separately before a release.

## The packages

SpacePixels prepares the frames, runs the work, and presents the results. Detection itself is the **JTransient** library, a separate project. All packages are under `eu.startales.spacepixels`.

| Package | What it holds | Main classes |
|---|---|---|
| `gui` | The Swing window, its tabs, dialogs and frame viewers | [ApplicationWindow](src/main/java/eu/startales/spacepixels/gui/ApplicationWindow.java), [MainApplicationPanel](src/main/java/eu/startales/spacepixels/gui/MainApplicationPanel.java), [DetectionConfigurationPanel](src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java) |
| `jobs` | Runs long operations one at a time, with no Swing | [JobCoordinator](src/main/java/eu/startales/spacepixels/jobs/JobCoordinator.java), `Job`, `JobOutcome`, `JobFailures` |
| `tasks` | The long operations themselves, one class per job | `FitsImportTask`, `DetectionTask`, `PlateSolveTask`, `BlinkImagesTask`, … |
| `util` | Services and file formats: import and preparation, pipelines, plate solving, FITS and WCS | [ImageProcessing](src/main/java/eu/startales/spacepixels/util/ImageProcessing.java), `FitsImportService`, `FitsBatchService`, `StandardDetectionPipelineService`, `PlateSolveService` |
| `util.reporting` | The HTML report | [DetectionReportGenerator](src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportGenerator.java), `ReportData`, the `…SectionWriter` classes |
| `util.skycatalog` | Fetching and storing the sky catalogue, and the plate-solution correction | `SkyCatalogueClient`, `SkyCatalogueStore`, `PlateCorrectionFitter` |
| `api`, `tools` | The public Java API and the command line | [DefaultSpacePixelsPipelineApi](src/main/java/eu/startales/spacepixels/api/DefaultSpacePixelsPipelineApi.java), `BatchDetectionCli` |
| `session` | Session identity, owned state and guarded handover (no Swing/`gui` dependencies) | `ProcessingSession`, `SessionOwner`, `SessionFrames`, `SessionStorage`, `SessionPersistence`, `SkyCatalogueModel`, `AutoTuneState` |
| `config` | The settings files and their reading and writing | `AppConfig`, `SpacePixelsDetectionProfileIO`, `SpacePixelsSettingsFiles` |

The dependencies point one way:
- **The GUI path:** `gui` → `jobs` and `tasks` → `util` → JTransient.
- **The headless path:** `api` and `tools` → `util` → JTransient.

`util` still uses a few GUI pieces (S13), including the facade's compatibility factory for interactive decisions and its report-size dialog. The extracted FITS import/batch services have no Swing/`gui` references; `GuiFitsImportInteraction` creates questions and progress windows on the EDT. Sharing their preparation orchestration with the API remains S12.

## How a user action runs

Every long operation is a **job**. One pattern serves all eleven: import, Convert to Mono, Batch Stretch, plate solve, writing the WCS header, Blink, Auto-Tune, Detect, Detect Iteratively, Manual Transient Inspection, and the Test Star Mask stack.

Plate solve uses JPlateSolve **1.0.1**, resolved from the local sibling during development. `PlateSolveService.startSolve` returns a cleanup-aware `PlateSolveOperation`. `PlateSolveTask` forwards cancellation, reports phase/elapsed text, and joins its completion even when interrupted; it never releases the job slot just because a legacy future was cancelled. Budgets are the library defaults (ASTAP 2 minutes, Astrometry.net 15 minutes). Remote cancellation is local-only: an accepted server job may continue. Failed solves already own their cleanup, so the GUI must not delete sidecars restored by JPlateSolve. Successful solution cleanup after WCS import stays in SpacePixels. `ImageProcessing.close()` closes the cloud client with its session.

1. **The click, on the Swing thread (EDT).** A button listener captures everything the job needs:
   - a validated copy of the settings, from `DetectionConfigurationPanel.checkedSettings(...)`;
   - the selected files;
   - the current session.
2. **Admission.** The listener calls `JobCoordinator.start(kind, session, job, handler)`. The coordinator refuses the job if another one is running.
   - If it admits the job, its listener in `MainApplicationPanel` locks the window and shows the `ProcessingProgressDialog` at once. The texts for each kind of job are in `MainApplicationPanel.JobTexts`.
3. **The work, on a worker thread.** The task (for example `DetectionTask`) implements `Job<R>`. It reports progress through `JobContext.progress(...)` and returns a `JobOutcome`:
   - `Completed`, with the result;
   - `Declined`, when the user said no during the job;
   - `Cancelled`, when the user pressed Cancel in the progress dialog;
   - `Failed`, with a reason.

   An exception or `Error` becomes `Failed`.
   - **Cancel:** `JobCoordinator.cancel()` makes the job's next progress report throw `JobCancelledException`. Engine tasks also bind the job's cancellation flag through JTransient's `CancellationScope`, which reaches its computation and worker waits between progress messages. The engine joins running work before propagating cancellation; the coordinator recognises any wrapped `CancellationException` and returns `Cancelled`. Final job progress at 100% never throws. Each `JobKind` says whether it can be cancelled, and services remove their unfinished output through `Cancellation.isCancellation(...)`.
   - **Out of memory:** goes to the application's handler, which closes SpacePixels; the other jobs stop at their next progress report.
4. **The outcome, back on the EDT.** It arrives exactly once, after the window is unlocked. The handler that was passed in at the click shows what follows: the report question, the inspection window, the imported session, or an error message (`FailureDialog`, with Copy details).

**Example: Detect Moving Targets.**
1. `MainApplicationPanel` starts a `DetectionTask`.
2. The task calls `ImageProcessing.detectObjects(...)`, which runs `StandardDetectionPipelineService`.
3. That service loads the frames and runs `JTransientEngine.runPipeline(...)`.
4. `DetectionReportGenerator` writes the report.
5. On the EDT, `showDetectionOutcome(...)` offers to open it.

**One exception: Blink.** Loading the frames is the job. Once loaded, the blink window keeps the main window locked until it closes.

## The session

`ApplicationWindow` loads `ApplicationSettings` once, before import. Its `AppConfig` is shared with each imported processor; importing never reloads it or discards unsaved Astrometry Config edits. The tab is available before import. The existing `ImageProcessing.getInstance(File)` entry point still loads standalone preferences for API/CLI callers; desktop import injects the window's settings explicitly.

`session.SessionOwner` owns the current `ProcessingSession`. Each session has a UUID, shared frame metadata references, a protected selection, its own `SkyCatalogueModel` (catalogue/correction), `AutoTuneState`, master-stack cache and last-report reference. The processing facade delegates import to `FitsImportService` and derivative operations to `FitsBatchService`. `SessionFrames` is shared by the session, import and pipeline services; it holds metadata and the last track/variable overlays, not a second copy of their buffers. Arrays of metadata are copied defensively, not image buffers.

**Import and handover:**
1. `FitsImportTask` prepares a processor and frames, using the application's settings even across conversion redirects.
2. Only a completed import can transfer them into a new session. `SessionOwner.replace(expected, candidate)` rejects stale imports and closes their processors; failed, declined or cancelled imports preserve the existing session.
3. The window refreshes the table and notifies its panels. Configuration validation refreshes without replacing edited text or saved-value markers.
4. Replacing/closing a session interrupts its owned sky worker, closes its processor and releases the stack cache, selection and tuning results. Closing the window also rejects later import completion.

**Session metadata I/O:** the window owns one serialized `SessionStorage` queue. `FitsImportTask` loads and validates saved catalogue, correction and Auto-Tune data there before handover; panels only apply the prepared state. Saves capture detached correction/tuning snapshots and use the existing atomic stores through per-session `SessionPersistence`. Reimports read behind earlier writes. Correction edits apply in memory immediately, with version-guarded rollback to the last successful save on failure. Viewers resolve that in-memory correction without touching disk; processing jobs wait on their worker for pending writes and UI acknowledgements before reading saved context. Closing a session drops its callbacks, not its queued writes. Normal window close drains the queue off the EDT before exiting and reports failed writes.

**Compatibility:** `applyWCSHeader` and `onlyStretch` remain deprecated thin adapters because public APIs are preserved. Their implementations live in the batch service; the unused private close helper is removed. Output folders, suffixes and conversion mathematics are unchanged.

**Asynchronous work:** job handles now carry `ProcessingSession`, not a processor standing in for identity. Outcome handlers use `forCurrentSession`; sky completion also checks worker identity and session. Old work cannot change the replacement's status, catalogue, tuning result or cache. `JobCoordinator` retains its current-job checks, so old progress/end events cannot unlock another job.

**Viewers:** annotations bind to an explicit session catalogue. Reusable Preview Frame/Full Size viewers are closed and cleared on import; Blink is reset. An independent inspection viewer can retain its old session's model and buffers deliberately, but never adopts the new catalogue. Old Star Mask Explorer Apply callbacks cannot edit a replacement session. Viewer buffers/listeners remain viewer-owned, not deep-copied into the session.

The profile IO/report writer's legacy static candidate-limit fallback is unchanged; replacing legacy run-context defaults remains S14. Session tuning measurements and the GUI's session limit are separately owned already.

## Settings

- **The pages.** `DetectionConfigurationPanel` builds the settings pages from a few shared pieces:
  - `SettingsNavigator`: the page list, search, and the expert switch;
  - `SettingRow`: one setting, with its changed marker and reset button;
  - `CheckedNumberEditor`: accepts only a complete number within the setting's range.

  Each setting is core, basic, expert or hidden.
- **When edits apply.** Edits change the session's `DetectionConfig` at once. Save writes the profile and the visualization preferences together, through `SpacePixelsSettingsFiles`.
- **Runs use a copy.** Every run takes its own validated copy, so later edits never change a running job. Preview Frame takes a fresh copy for each frame it detects, and edits the per-frame thresholds on the tab's own spinner models.
- **The astrometry settings** (ASTAP path, observing site) belong to application-scoped `ApplicationSettings`, edited in `ConfigurationPanel` before or after import. Save/Revert and the JSON format are unchanged.

## Frame viewers

- **The shared base.** Preview Frame (`DetectionSequenceFrame`), Manual Transient Inspection (`TransientInspectionFrame`) and Show full size (`StretchedSequenceFrame`) all extend [SequenceViewerFrame](src/main/java/eu/startales/spacepixels/gui/SequenceViewerFrame.java). Each subclass prepares a frame in `prepare(...)`, on a `SwingWorker`; the newest request wins.
- **Blink** is its own window, `BlinkFrame`, built from the same parts:
  - `FrameView`: draws a frame;
  - `StretchControls`: shares the stretch with the Image Stretch tab;
  - `ViewerSupport`: keys and the status bar;
  - `SkyAnnotations`: catalogue labels.
- **Closing a viewer** cancels pending preparation, drops its result, detaches catalogue and shared stretch listeners, and releases its frames. Reusable hidden viewers bind fresh stretch controls when reopened. Closing Star Mask Explorer also stops its debounce timer, cancels obsolete mask work and clears its stack and image buffers.

## The report

`DetectionReportGenerator.exportTrackVisualizations(...)` writes the report:
1. **Snapshot:** it takes a snapshot of the display settings (`ExportVisualizationSettings`).
2. **Model:** it builds the report model:
   - `DetectionReportContext`, the inputs;
   - `ReportData`, the finding identities, also written as `report-data.json`.
3. **Sections:** it writes the HTML through one writer per section: `TargetVisualizationSectionWriter`, `AnomalySectionWriter`, `DeepStackReportSectionWriter`, and the others.
4. **Publish:** it publishes the HTML only once complete, with `AtomicReportFile`.

The report never changes the engine's results. The report's live lookups (SatChecker, JPL) go through a small localhost server, `ReportLookupProxyServer`.

## Headless: the API and the command line

`DefaultSpacePixelsPipelineApi.run(...)` runs the whole pipeline without a window:
1. **Prepare:** prepare the input with `DetectionInputPreparation`, following the request's policy; it asks no questions.
2. **Check:** check the frames strictly (`getFitsfileInformationHeadless`).
3. **Tune:** optionally run Auto-Tune.
4. **Detect:** run the detection.
5. **Report:** optionally write the report.

`BatchDetectionCli` is a thin client of the API.

## Principles

1. **JTransient owns detection.** SpacePixels does not change detection behaviour; engine changes belong in that project.
2. **The EDT is for the window.** Swing is touched only on the EDT, and long work runs as a job. `DependencyRulesTest` keeps `jobs` and `tasks` free of `gui` and Swing.
3. **Every job ends once,** with its result or a reason. No failure leaves the window locked.
4. **A run uses a copy of its inputs.** Settings, files and display options are captured when the run starts.
5. **Files are replaced only when complete.** `StagedFile` and `AtomicReportFile` write a new file beside the old one, then swap them in one step.
6. **Explicit listeners,** not a global event bus: `JobCoordinator.Listener`, `SessionListener`, `BlinkFrame.setOnClose(...)`, and the listeners of `SkyCatalogueModel`.
7. **The console shows only problems.** Diagnostics go through `DebugOutput`, and appear with `-Dspacepixels.debug=true`.
8. **Logic is tested without the window.** The opt-in `guiMechanicsTest` task drives the real window.

## Design patterns in use

| Pattern | Where |
|---|---|
| Command with a coordinator | `Job` and `JobCoordinator`: one admission rule, one outcome per job |
| Shared command (Swing `Action`) | `CommandAction`, enabled by `CommandAvailability`: one action for a command's button, shortcut, menu item and Overview button |
| Typed outcome | `JobOutcome` (completed, declined, cancelled, failed) |
| Observer, with explicit listeners | `SessionListener`, `JobCoordinator.Listener`, `SkyCatalogueModel` |
| Facade | `ImageProcessing`, in front of the import, pipeline and plate-solve services |
| Immutable request and snapshot | `SpacePixelsPipelineRequest`, `checkedSettings(...)`, `ExportVisualizationSettings` |
| Template method | `SequenceViewerFrame.prepare(...)`, implemented by each viewer |
| Newest request wins | `SequenceViewerFrame`, `StretchPreviewRenderer` |
| One writer per section | the report's `…SectionWriter` classes |

## What comes next

The open steps from the architecture review, in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md#open-steps-from-the-architecture-review):
- **S12:** share the GUI/API preparation flow, retaining the existing EDT decision adapter.
- **S13 to S16:** no GUI in `util`, an explicit run context, one source of finding labels, and prepared report diagnostics.
