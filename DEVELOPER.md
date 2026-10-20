# Developer overview

A two-page map of how SpacePixels is built: the packages, the patterns and the rules the code follows. The detailed architecture is in [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md), and the planned work in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Current as of 2026-10-20.

## The packages

SpacePixels prepares the frames, runs the work, and presents the results. Detection itself is the **JTransient** library, a separate project. All packages are under `eu.startales.spacepixels`.

| Package | What it holds | Main classes |
|---|---|---|
| `gui` | The Swing window, its tabs, dialogs and frame viewers | [ApplicationWindow](src/main/java/eu/startales/spacepixels/gui/ApplicationWindow.java), [MainApplicationPanel](src/main/java/eu/startales/spacepixels/gui/MainApplicationPanel.java), [DetectionConfigurationPanel](src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java) |
| `jobs` | Runs long operations one at a time, with no Swing | [JobCoordinator](src/main/java/eu/startales/spacepixels/jobs/JobCoordinator.java), `Job`, `JobOutcome`, `JobFailures` |
| `tasks` | The long operations themselves, one class per job | `FitsImportTask`, `DetectionTask`, `PlateSolveTask`, `BlinkImagesTask`, … |
| `util` | Services and file formats: import and preparation, pipelines, plate solving, FITS and WCS | [ImageProcessing](src/main/java/eu/startales/spacepixels/util/ImageProcessing.java), `StandardDetectionPipelineService`, `PlateSolveService` |
| `util.reporting` | The HTML report | [DetectionReportGenerator](src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportGenerator.java), `ReportData`, the `…SectionWriter` classes |
| `util.skycatalog` | Fetching and storing the sky catalogue, and the plate-solution correction | `SkyCatalogueClient`, `SkyCatalogueStore`, `PlateCorrectionFitter` |
| `api`, `tools` | The public Java API and the command line | [DefaultSpacePixelsPipelineApi](src/main/java/eu/startales/spacepixels/api/DefaultSpacePixelsPipelineApi.java), `BatchDetectionCli` |
| `config` | The settings files and their reading and writing | `AppConfig`, `SpacePixelsDetectionProfileIO`, `SpacePixelsSettingsFiles` |

The dependencies point one way:
- **The GUI path:** `gui` → `jobs` and `tasks` → `util` → JTransient.
- **The headless path:** `api` and `tools` → `util` → JTransient.

Two exceptions are not fixed yet:
- `util` still uses a few GUI pieces (step S13 in the plan).
- `ImageProcessing` asks import questions with dialogs (step S12).

## How a user action runs

Every long operation is a **job**. One pattern serves all eleven: import, Convert to Mono, Batch Stretch, plate solve, writing the WCS header, Blink, Auto-Tune, Detect, Detect Iteratively, Manual Transient Inspection, and the Test Star Mask stack.

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
   - **Cancel:** `JobCoordinator.cancel()` makes the job's next progress report throw `JobCancelledException`, and the coordinator turns that into `Cancelled`. Progress at 100% never throws, so a finished result is kept. Each `JobKind` says whether it can be cancelled.
   - **Out of memory:** goes to the application's handler, which closes SpacePixels; the other jobs stop at their next progress report.
4. **The outcome, back on the EDT.** It arrives exactly once, after the window is unlocked. The handler that was passed in at the click shows what follows: the report question, the inspection window, the imported session, an error message.

**Example: Detect Moving Targets.**
1. `MainApplicationPanel` starts a `DetectionTask`.
2. The task calls `ImageProcessing.detectObjects(...)`, which runs `StandardDetectionPipelineService`.
3. That service loads the frames and runs `JTransientEngine.runPipeline(...)`.
4. `DetectionReportGenerator` writes the report.
5. On the EDT, `showDetectionOutcome(...)` offers to open it.

**One exception: Blink.** Loading the frames is the job. Once loaded, the blink window keeps the main window locked until it closes.

## The session

`ApplicationWindow` owns the current session: the [ImageProcessing](src/main/java/eu/startales/spacepixels/util/ImageProcessing.java) of the imported folder. It holds:
- the frames' `FitsFileInformation`;
- the application config;
- the pipeline services;
- the overlays of the last detection run.

The frame table, `FitsFileTableModel`, shows the same frame objects.

**Import:**
1. `FitsImportTask` returns the new session.
2. `ApplicationWindow.installSession(...)` swaps it in.
3. The window tells the panels through a [SessionListener](src/main/java/eu/startales/spacepixels/gui/SessionListener.java): `MainApplicationPanel`, `StretchPanel` and `DetectionConfigurationPanel`.

An import that is declined or fails keeps the open session.

Some session state still lives elsewhere, and a session object will collect it (step S7):
- the sky catalogue and its correction, in `SkyCatalogueModel.shared()`;
- the Test Star Mask cache;
- the Auto-Tune results on the Overview.

## Settings

- **The pages.** `DetectionConfigurationPanel` builds the settings pages from a few shared pieces:
  - `SettingsNavigator`: the page list, search, and the expert switch;
  - `SettingRow`: one setting, with its changed marker and reset button;
  - `CheckedNumberEditor`: accepts only a complete number within the setting's range.

  Each setting is core, basic, expert or hidden.
- **When edits apply.** Edits change the session's `DetectionConfig` at once. Save writes the profile and the visualization preferences together, through `SpacePixelsSettingsFiles`.
- **Runs use a copy.** Every run takes its own validated copy, so later edits never change a running job.
- **The astrometry settings** (ASTAP path, observing site) are an `AppConfig`, edited in `ConfigurationPanel`.

## Frame viewers

- **The shared base.** Preview Frame (`DetectionSequenceFrame`), Manual Transient Inspection (`TransientInspectionFrame`) and Show full size (`StretchedSequenceFrame`) all extend [SequenceViewerFrame](src/main/java/eu/startales/spacepixels/gui/SequenceViewerFrame.java). Each subclass prepares a frame in `prepare(...)`, on a `SwingWorker`; the newest request wins.
- **Blink** is its own window, `BlinkFrame`, built from the same parts:
  - `FrameView`: draws a frame;
  - `StretchControls`: shares the stretch with the Image Stretch tab;
  - `ViewerSupport`: keys and the status bar;
  - `SkyAnnotations`: catalogue labels.
- **Closing a viewer** detaches its listeners and releases its frames.

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
| Typed outcome | `JobOutcome` (completed, declined, failed) |
| Observer, with explicit listeners | `SessionListener`, `JobCoordinator.Listener`, `SkyCatalogueModel` |
| Facade | `ImageProcessing`, in front of the import, pipeline and plate-solve services |
| Immutable request and snapshot | `SpacePixelsPipelineRequest`, `checkedSettings(...)`, `ExportVisualizationSettings` |
| Template method | `SequenceViewerFrame.prepare(...)`, implemented by each viewer |
| Newest request wins | `SequenceViewerFrame`, `StretchPreviewRenderer` |
| One writer per section | the report's `…SectionWriter` classes |

## What comes next

The steps from the architecture review, in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md#architecture-adoption-review):
- **S4:** shared `Action`s, so a button, its shortcut and its menu item share one command and its availability.
- **S7:** a session object.
- **S10:** split `ImageProcessing`.
- **S12:** ask the import questions on the EDT.
- **S13 to S16:** no GUI in `util`, an explicit run context, one source of finding labels, and prepared report diagnostics.
