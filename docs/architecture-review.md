# Architecture review: inventory and path traces

This is the working record of the [architecture adoption review](../DESIGN-IMPROVEMENTS.md#architecture-adoption-review). It holds the class inventory, the traced paths and the evidence. The steps the review leads to, and the decisions about them, live in [DESIGN-IMPROVEMENTS.md](../DESIGN-IMPROVEMENTS.md).

| Part | Packages | Status |
|---|---|---|
| 1. Workflow core | `gui`, `tasks`, `events` | Done 2026-10-20, against `9a87c74` |
| 2. Services and boundaries | `util` (outside reporting and sky catalogue), `api`, `tools`, `config` | Done 2026-10-20, against `b52d64d` |
| 3. Presentation | `util.reporting`, `util.skycatalog` | Done 2026-10-20, against `ee80fa5` |

All three parts are done. See [Review summary](#review-summary) at the end.

**Classifications**, as defined in DESIGN-IMPROVEMENTS:

- **retain:** the class fits the target as it is.
- **move responsibility:** part of what the class does belongs elsewhere.
- **adapt boundary:** the class stays, but how it is called or what it returns changes.
- **investigate:** not enough is known yet.

Step numbers refer to:
- [Steps from part 1](#steps-from-part-1): S1–S9;
- [Steps from part 2](#steps-from-part-2): S10–S14;
- [Steps from part 3](#steps-from-part-3): S15–S16.

## Part 1: workflow core

**Scope and method.**
- **Read in full:**
  - all 12 task classes and 18 event classes;
  - `ApplicationWindow` and `MainApplicationPanel`;
  - the lifecycle, persistence and job code of `DetectionConfigurationPanel` and `AutoTuneOverviewPanel`.
- **Read for responsibilities, dependencies, threads and persistence:** the other `gui` classes.
- **How:** no code was changed or run. Paths were followed from the GUI to the point where they call a service. The service side (`ImageProcessing` and the rest of `util`) is part 2.

**Size:** 60 classes, about 13,600 lines:
- `gui`: 30 classes, 12,320 lines;
- `tasks`: 12 classes, 996 lines;
- `events`: 18 classes, 243 lines.

### How jobs run today

Every long operation is a new thread. Its lifecycle reaches the window through the Guava event bus that `ApplicationWindow` owns. The bus is synchronous: each subscriber runs on the posting thread and then moves its work to the EDT with `invokeLater`.

| Job | Started from | Worker | UI lock | Outcome reaches the UI as | Cancel |
|---|---|---|---|---|---|
| Import | Import button, folder drop, after Convert to Mono | `new Thread(FitsImportTask)` | Import button and tabs disabled when the start event arrives; modal progress dialog | `FitsImportFinishedEvent` (4 subscribers) | none |
| Convert to Mono | button | `new Thread(BatchConvertMonoTask)` | `lockUI` on the start event | `BatchConvertFinishedEvent`, then an offer to import the result | none |
| Batch Stretch | button | inline thread in `MainApplicationPanel` (`BatchStretchTask` is unused) | `lockUI` on the start event | `BatchStretchFinishedEvent` | none |
| Plate solve | button, table menu | `new Thread(PlateSolveTask)` | `lockUI` on the start event | `SolveFinishedEvent` with the view row; the WCS header write then runs on the EDT | none |
| Sky catalogue and plate-solution check | button | thread `sky-catalogue` | none; its own status indicator | direct `invokeLater` callbacks that check the job's thread identity | interrupt (the button toggles) |
| Blink (loading) | button, Ctrl+B, menu | thread `blink-loader` with a shared `AtomicBoolean` | `lockUI` from the start event until the blink window closes | `BlinkSequenceLoadedEvent`, `BlinkFinishedEvent`, `BlinkFrameClosedEvent` | the flag |
| Preview Frame | button, Ctrl+P, menu, Overview | `SwingWorker` in `SequenceViewerFrame` | none | in its window | the newest request wins |
| Image Stretch previews | frame or stretch change | `SwingWorker` in `StretchPreviewRenderer` | none | in the tab | the newest request wins |
| Manual Transient Inspection | button | `new Thread(ManualTransientInspectionTask)` | `lockUI` on `DetectionStartedEvent` | `DetectionFinishedEvent`; the task opens the window itself | none |
| Star mask stack (Test Star Mask) | Overview | `new Thread(StarMaskStackTask)` | `lockUI` on `DetectionStartedEvent` | `DetectionFinishedEvent`, plus a callback on the EDT | none |
| Auto-Tune | Overview, main button | `new Thread(AutoTuneTask)` | `lockUI` on `AutoTuneStartedEvent` | `AutoTuneFinishedEvent` (2 subscribers) | none |
| Detect Moving Targets | button, Ctrl+D (in two panels), Overview | `new Thread(DetectionTask)` | `lockUI` on `DetectionStartedEvent` | `DetectionFinishedEvent`, then the open-report question | the report-size question can abort |
| Detect Iteratively | button | `new Thread(IterativeDetectionTask)` | `lockUI` on `DetectionStartedEvent`, after the frame-limit question | `DetectionFinishedEvent` | that question's Cancel |
| Release check | start-up | daemon thread | none | `invokeLater` | none |

### Paths followed

- **Import.**
  - The worker converts XISF and other formats when needed, and builds a new `ImageProcessing` for the folder.
  - Two outcomes re-run the task on another folder, inside the same thread:
    - a redirect to a converted folder;
    - the choice of one exposure group.
  - Each re-run posts a second start event.
  - Cancelling the exposure-group question posts a *successful* import with no files. `ApplicationWindow` then closes the current session (D06 evidence, confirmed).
  - On success, `ApplicationWindow` swaps the session processor and sets the table model. `setTableModel` then restores the sky catalogue and correction from the session folder, on the EDT.
- **Session replacement.**
  - **Handled well:**
    - The sky job is interrupted, and its late results are dropped by the thread-identity check.
    - The star-mask cache and the Overview's tuning results are reset.
    - The Image Stretch previews are cleared.
  - **Not handled:**
    - Preview Frame is one reused window. If it is open, it keeps the old session's files until it is opened again.
    - Unsaved Astrometry Config edits are lost silently. The new `ImageProcessing` reloads the application config from disk, and the tab is refreshed from it.
- **Plate solve.**
  - The result names the table row it was started for.
  - The question, the FITS rewrite (`updateFitsHeaderWithWCS`) and the artifact cleanup all run on the EDT (D08, confirmed).
- **Detection, iterative detection, inspection and star mask.**
  - All four share `DetectionStartedEvent`/`DetectionFinishedEvent`, and so the progress dialog and the lock.
  - Settings are a validated copy taken at the click (D05, done).
  - The report-size question and the iterative frame-limit question are asked from the worker with `invokeAndWait`.
- **Auto-Tune.**
  - The frames come from the table selection, or the whole session.
  - The result is applied on the EDT by the Overview, which saves it to the session folder there (`AutoTuneSessionStore`).
- **Failures.** Several failure paths leave the UI locked; see P1-2 below.
- **Closing the window.**
  - `windowClosing` closes the session processor and stops the lookup proxy. `EXIT_ON_CLOSE` then ends the process.
  - Running jobs are not asked to stop. Staged writes (D01) leave at most a temporary file.

### Findings

**P1-1. Admission is asynchronous and no job has an identity (confirms D02).**
- Every job is a raw `new Thread`, started from a listener without any admission check.
- The UI lock is set only when the worker's start event reaches the EDT.
- No event carries a job or session identity.
- In practice the modal progress dialog prevents most double starts. But Ctrl+D is bound in two panels, and the Overview has its own Detect button, so the guard is only the button state at the moment of the click.

**P1-2. Some failures never reach a finished event, and the UI stays locked (new; D04).**
- `ManualTransientInspectionTask` and `StarMaskStackTask` catch `OutOfMemoryError` and only log it. Their comment says the global handler will act, but the catch keeps the error from reaching it. So the modal progress dialog stays up, and the application neither recovers nor exits.
- `IterativeDetectionTask` and `FitsImportTask` catch only `Exception`. An `Error` ends the thread without a finished event. `IterativeDetectionTask` also ignores `OOM_FLAG`.
- `DetectionTask` logs a `Throwable` that is neither an `Exception` nor an out-of-memory error, and posts nothing.
- `ManualTransientInspectionTask` shuts its engine down only on success; it is not in a `finally`.

**P1-3. One "detection finished" event serves four different jobs, and half of it is unused (D02/D04).**
- `DetectionFinishedEvent` closes detection, iterative detection, inspection and the star-mask stack.
- A boolean `isQuickDetection` decides whether to ask about a report.
- Four of its eight fields (`annotatedImage`, `starCount`, `streakCount`, `fileName`) are always empty, and their getters are never called.

**P1-4. The session is spread across nine owners (confirms D06).** What belongs to the imported folder lives in:
- `ApplicationWindow.imagePreProcessing`, which also holds the application config and the last run's overlays;
- the table model, which is where `getImportedFiles()` reads the files from;
- `SkyCatalogueModel.shared()`, a process-wide singleton;
- `DetectionConfigurationPanel`'s master-stack cache, keyed by the identity of the files array;
- `AutoTuneOverviewPanel`'s session fields and its saved results;
- `StretchPanel`'s previews;
- the reused Preview Frame window;
- the single `BlinkFrame`, created with the window;
- `MainApplicationPanel`'s sky-job fields.

Each does its own reset on import. No single object says "this is the session"; tests would need the whole window to build one.

**P1-5. Application preferences belong to the session processor (confirms D06/D11).**
- The ASTAP path and observing site are an `AppConfig` held by the session's `ImageProcessing`.
- The Astrometry Config tab edits that object in place. It is disabled until a folder is imported.
- A new import reloads the config from disk, which drops unsaved edits.

**P1-6. Panels reach each other through the window (D07 and new).** `ApplicationWindow`'s getters act as a service locator:
- `MainApplicationPanel` reads the stretch sliders of `StretchPanel` and asks `DetectionConfigurationPanel` for settings.
- `DetectionConfigurationPanel` reads the table selection, refreshes the workflow status, writes the active-settings card, and binds its buttons to the main panel's.
- `StretchPanel` and `ConfigurationPanel` read the selection.

The construction order matters: `DetectionConfigurationPanel` binds to buttons that must already exist. `lockUI`/`unlockUI` address tabs 1–3 by number.

**P1-7. The task classes depend on the GUI (new).**
- Ten of the twelve task classes import `ApplicationWindow`, for its logger and its static `OOM_FLAG`. Only `BlinkImagesTask` and `GeneratePreviewsTask` do not.
- `ManualTransientInspectionTask` takes the `StretchPanel` and opens `TransientInspectionFrame` itself.
- `IterativeDetectionTask` asks its question with its own dialog.
- `DetectionTask` shows the out-of-memory dialog and calls `System.exit`.

So `tasks` are GUI workers, not application services, and the API and CLI do not use them. `ImageProcessing`, `PlateSolveService` and `XisfImageConverter` in `util` also import `gui`; that is part 2.

**P1-8. Out-of-memory handling is global state (new).**
- `ApplicationWindow.OOM_FLAG` is a static flag. It is set by the default uncaught-exception handler and by `DetectionTask`, and read by progress listeners and `SessionFrameLoader`.
- The exit sequence exists twice: a message on the EDT, plus a ten-second fallback `System.exit`.

**P1-9. File I/O on the EDT (confirms and extends D08).**
- **The large write:** the WCS header rewrite of a whole FITS file.
- **Smaller reads and writes:**
  - restoring the sky catalogue and correction on import;
  - loading and saving the Auto-Tune results;
  - saving the correction's on/off switch;
  - saving a fetched catalogue;
  - saving the settings and the application config.

**P1-10. The batch-stretch worker exists twice (known, Stage B).** The button runs an inline copy of `BatchStretchTask`, and the class itself is never used.

**P1-11. Import-finished handlers depend on running in a safe order (low).**
- Four classes subscribe to `FitsImportFinishedEvent`. `ApplicationWindow`'s handler installs the new session; the other three must not read it first.
- Today they don't: they use only the event's data, or read the session later through a debounce timer. Nothing states or tests this.

**P1-12. Good patterns to keep.** These are the shapes the target asks for:
- `GeneratePreviewsTask`: a `Callable` that returns data and knows nothing of the bus.
- The sky job: it checks its thread identity when results arrive, and is interrupted on import.
- `SequenceViewerFrame` and `StretchPreviewRenderer`: the newest request wins.
- `checkedSettings`: a validated copy of the settings for each run.
- `AutoTuneOverviewPanel.Host`: a narrow callback interface.

**P1-13. Few tests reach the workflow (confirms the verification strategy).**
- **Untested:** `ApplicationWindow`, `MainApplicationPanel`, `DetectionConfigurationPanel`, the job lifecycle and the events.
- **Tested in `tasks`:** only `GeneratePreviewsTask` and `SessionFrameLoader`.
- **Tested in `gui`:** eight view classes.

### The event bus in part 1

| Events | Subscribers | What they carry |
|---|---|---|
| 14 of the 18 | one each: `MainApplicationPanel` | the start, progress and end of one job |
| `FitsImportStartedEvent` | 2: the window and the main panel | an import began |
| `FitsImportFinishedEvent` | 4: the window, main panel, settings and Image Stretch tab | the session changed |
| `AutoTuneStartedEvent`, `AutoTuneFinishedEvent` | 2 each: the main panel and the settings | tuning state |

The bus is a one-way channel from each job to the main window. Only two kinds of message have more than one listener: "the session changed" and "tuning started or ended".

Each kind has a natural replacement in the target:
- **Job lifecycle:** a job handle (D02/D04).
- **Session change:** a notification of the session model (D06).
- **Tuning state:** job state.
- **Blink window closed:** a listener on `BlinkFrame`.

This supports option 2 (retire the bus gradually) in DESIGN-IMPROVEMENTS, which the maintainer accepted on 2026-10-20.

### Class inventory: `tasks` and `events`

| Class | Lines | Role now | Classification | Step |
|---|---|---|---|---|
| `AutoTuneTask` | 89 | Builds the candidate pool and runs the tuner; posts lifecycle events | adapt boundary | S2, S3, S6 |
| `BatchConvertMonoTask` | 62 | Converts to mono through `ImageProcessing` | adapt boundary | S2, S3 |
| `BatchStretchTask` | 63 | Batch stretch; **unused** | retain, then use it | S5 |
| `BlinkImagesTask` | 60 | Loads frames for Blink; cancellable through a flag | adapt boundary | S2, S3 |
| `DetectionTask` | 115 | Full detection and report; out-of-memory dialog and exit | move responsibility | S1, S2, S3, S6 |
| `FitsImportTask` | 93 | Import, conversion redirect, exposure-group choice | adapt boundary | S1, S3, S7 |
| `GeneratePreviewsTask` | 128 | Image Stretch previews as a `Callable` | retain | — |
| `IterativeDetectionTask` | 113 | Iterative detection; asks the frame limit from the worker | move responsibility | S1, S2, S6 |
| `ManualTransientInspectionTask` | 81 | Runs the engine and opens the inspection window | move responsibility | S1, S6 |
| `PlateSolveTask` | 59 | Solves one frame; identifies it by view row | adapt boundary | S3, S4 |
| `SessionFrameLoader` | 59 | Reads the session's frames for the engine | retain (drop the `OOM_FLAG` read) | S6 |
| `StarMaskStackTask` | 74 | Builds the master stack for Test Star Mask | adapt boundary | S1, S3 |
| 18 event classes in `events` | 243 | Job lifecycle, progress and the blink window's close | adapt boundary, then retire | S9 |

### Class inventory: `gui`

| Class | Lines | Role now | Classification | Step |
|---|---|---|---|---|
| `AppIcon` | 82 | Application icon sizes | retain | — |
| `ApplicationWindow` | 559 | `main`, the global out-of-memory handler, the bus, the session processor, import start, tab enabling, release check | move responsibility | S6, S7, S9 |
| `AutoTuneChartDialog` | 376 | Auto-Tune charts | retain | — |
| `AutoTuneIcon` | 108 | Auto-Tune icon | retain | — |
| `AutoTuneOverviewPanel` | 1282 | Overview page; Auto-Tune results, saved to and loaded from the session folder on the EDT | adapt boundary | S7, S8 |
| `BlinkFrame` | 750 | Blink viewer; one instance for the application; posts its close on the bus | adapt boundary | S7, S9 |
| `CheckedNumberEditor` | 132 | Validated number editor | retain | — |
| `ConfigurationPanel` | 653 | Astrometry Config tab; edits the application config held by the session processor | move responsibility | S7 |
| `DetectionConfigurationPanel` | 1708 | Settings pages, profile load/save, run snapshots; starts Auto-Tune and the star-mask job; master-stack cache | move responsibility | S2, S4, S7 (D10 for the settings bindings) |
| `DetectionSequenceFrame` | 98 | Preview Frame viewer; reads the last overlays from the session processor | retain | S7 (overlays from the session) |
| `FitsFileTableModel` | 111 | Frame table; also where the imported files are read from | adapt boundary | S7 |
| `FrameView` | 856 | Frame display component | retain | — |
| `MainApplicationPanel` | 1829 | Workflow strip, table, availability, lock, starts nine kinds of job, 18 subscribers, sky job, WCS write | move responsibility | S2, S3, S4, S5, S7, S8 |
| `ProcessingProgressDialog` | 72 | Modal progress dialog, fixed size | adapt boundary | S2 (one per job); D12 |
| `SequenceViewerFrame` | 410 | Viewer base with the newest-request-wins worker | retain | — |
| `SettingRow` | 136 | One setting row | retain | D13 |
| `SettingsNavigator` | 515 | Settings navigation, search, expert switch | retain | — |
| `SkyAnnotations` | 362 | Catalogue labels in the viewers; listens to `SkyCatalogueModel` | retain | S7 (session instead of singleton) |
| `SkyCatalogueModel` | 117 | Process-wide catalogue, correction and session folder; saves the correction switch on the EDT | move responsibility | S7, S8 |
| `SkyMark` | 44 | A catalogue mark | retain | — |
| `StarMaskExplorerFrame` | 391 | Test Star Mask window | retain | — |
| `StretchControls` | 164 | Shared stretch controls of the viewers | retain | — |
| `StretchedSequenceFrame` | 60 | Show full size viewer | retain | — |
| `StretchPanel` | 561 | Stretch settings (Java preferences), previews; passed into tasks as the stretch model | adapt boundary | S6 (a stretch model apart from the panel) |
| `StretchPreviewRenderer` | 130 | Renders the stretch previews, newest wins | retain | — |
| `TransientInspectionFrame` | 107 | Inspection viewer | retain | S6 (opened by the UI, not the task) |
| `ViewerSupport` | 177 | Shared viewer controls and keys | retain | D13 |
| `WindowPlacement` | 143 | Main window size and position | retain | — |
| `WrapLayout` | 99 | Wrapping flow layout | retain | — |
| `ZoomableImageView` | 288 | Zoomable image component | retain | — |

Of the 60 classes:
- 23 are retained;
- the 18 event classes retire over time;
- 19 change.

Most of the change concentrates in four places:
- `MainApplicationPanel`;
- `DetectionConfigurationPanel`;
- `ApplicationWindow`;
- the task classes.

### Steps from part 1

| Step | Stage | Work | Links | Compatibility | Verification |
|---|---|---|---|---|---|
| S1 | A | Make every job end with a finished event, or with the out-of-memory exit: stop swallowing `OutOfMemoryError` in inspection and the star-mask job, treat `Error` alike in every task, shut the engine down in `finally` | D04, P1-2 | No change for successful runs | A forced `Error` and `OutOfMemoryError` in each task closes the progress dialog or exits with the message |
| S2 | B | A job coordinator on the EDT: admit before the worker starts; job and session identity; one progress display per job. Start with detection, iterative detection, inspection and the star mask, which share the lock today | D02, P1-1 | Same workflow policy; relaxing locks is a separate decision | Fake jobs: double start, late events from an older job, session replaced mid-job |
| S3 | B | Typed outcomes per job kind instead of the shared finished event; separate "cancelled" from "no files"; drop the four unused fields | D04, P1-3, P1-4 | — | Each outcome is handled once; a cancelled import leaves the session as agreed |
| S4 | B | Shared `Action`s with an availability model; one Detect action behind the button, both Ctrl+D bindings and the Overview button; tabs by identity, not number; solve results by file, not view row | D07, P1-6 | Same availability as today | Button, shortcut and menu agree after import, errors and job end |
| S5 | B | Use `BatchStretchTask` and remove the inline copy | P1-10 | — | Batch stretch output unchanged |
| S6 | B–C | Take the GUI out of `tasks`: their own logger, an out-of-memory policy the application supplies, results returned instead of windows opened, the iterative frame-limit question asked before the job starts, a stretch model instead of the `StretchPanel` | P1-7, P1-8 | Same behaviour for the user | A dependency rule test: `tasks` imports nothing from `gui` |
| S7 | C | A session object that owns the nine pieces of P1-4; the application config loaded once for the application | D06, D11, P1-4, P1-5 | Settings formats unchanged | Two sessions in a test do not affect each other; an import keeps unsaved Astrometry Config edits |
| S8 | C | Move the WCS header write and the session-folder reads and writes off the EDT | D08, P1-9 | — | A slow file store does not stop repainting |
| S9 | B–C | Retire the event bus as S2, S3 and S7 replace each event; then remove Guava | EventBus decision (option 2, accepted 2026-10-20) | — | No `@Subscribe` left; the build has no Guava |

S1 is a correctness fix that can come first. Per the maintainer's rules it is discussed before it is implemented.

## Part 2: services and boundaries

**Scope and method.**
- **Read in full:**
  - the public API: `DefaultSpacePixelsPipelineApi` and the other `api` types;
  - `BatchDetectionCli`;
  - the standard and iterative pipeline services;
  - the import, preparation and plate-solve parts of `ImageProcessing` and `PlateSolveService`;
  - `AutoTunerRunner` and `AppConfig`.
- **Read for responsibilities, dependencies, threads, static state and console use:** the other classes of `util` (outside reporting and the sky catalogue), `config` and `tools`.
- **How:** no code was changed or run.

**Size:** 49 classes, about 10,500 lines:
- `util`: 30 classes, 8,726 lines;
- `api`: 9 classes, 784 lines;
- `tools`: 2 classes, 518 lines;
- `config`: 8 classes, 515 lines.

### Paths followed

- **Interactive import, the service half.**
  - `ImageProcessing.getFitsfileInformation`, on the import worker, checks the sequence. It then asks about compression, 16-bit scaling, 32-bit data, negative values and mixed exposures, and offers to load converted folders.
  - Each question is a `JOptionPane` opened directly from the worker. Conversions show a `gui.ProcessingProgressDialog` that is built on the worker and updated with `invokeLater`.
  - A redirect to a converted folder is an exception (`RedirectImportException`). Most other endings are an empty array.
- **API run** (`DefaultSpacePixelsPipelineApi.run`):
  1. `DetectionInputPreparation` prepares the folder by the request's policy.
  2. `ImageProcessing.getFitsfileInformationHeadless` checks it again, strictly: 16-bit mono only.
  3. Optional Auto-Tune.
  4. `runDetectionPipeline`.
  5. Optional report.

  The `ImageProcessing` built for the run also loads the user's application config from the home folder.
- **CLI** (`BatchDetectionCli`):
  1. Reads the profile.
  2. Sets two process-wide values: the Auto-Tune frame limit, and the rendering preferences read from the home folder.
  3. Calls the API, with preparation set to "fail if not ready".
- **GUI detection** (`StandardDetectionPipelineService.detectObjects`):
  1. Loads every frame and runs the engine.
  2. Keeps the run's track and variable-star overlays for the viewers.
  3. Asks the report-size question.
  4. Exports the report.
- **Iterative detection.** One master stack from time-spaced frames, then passes of 5, 10, 15… frames, each with its own report, and an index page.
- **Plate solve.**
  - ASTAP or Astrometry.net returns a `Future`; the task waits for it.
  - The WCS write (`updateFitsHeaderWithWCS`) stages the new file (D01). It can download the WCS from the solver's reference.
- **Batch convert and stretch.** File loops in `ImageProcessing` that report progress through a listener.

### Findings

**P2-1. `ImageProcessing` is five classes in one (move responsibility).** Its 1,909 lines hold:
1. **The session processor:**
   - the folder and its imported files (the same objects the table shows);
   - a thread pool;
   - the application config;
   - the three services.
2. **The interactive import,** with its dialogs and progress windows.
3. **The headless import check.**
4. **The batch conversions and preview rendering.**
5. **The report-size question:** about 450 lines of Swing panel code in `DetectionSummary` (`confirmReport`, `warningPanel`), next to `PipelineExecutionData`.

Two public methods have no callers: `applyWCSHeader` and `onlyStretch`. Their two private writers, and `PlateSolveService.applyWCSHeader`, which only they call, are unused too.

**P2-2. Swing is used from worker threads (confirms and widens D08).**
- 12 of the 13 dialogs in `ImageProcessing` are opened straight from the import worker. Only the report-size question uses `invokeAndWait`.
- `PlateSolveService` opens its Astrometry.net error dialog on the solve worker, with a new `JFrame` as its parent. That frame is never disposed.

**P2-3. An empty file list means several different things (new; extends D06).** `getFitsfileInformation` returns an empty array, after showing its own message, when:
- a conversion is declined;
- the first file cannot be read;
- the files are inconsistent;
- the format is unsupported.

`FitsImportTask` turns that into a *successful* import with no files, and the window then closes the current session. So declining to convert folder B discards session A. D06 named only the exposure-group case.

**P2-4. Some failures are reported as something else (new; D04).**
- `StandardDetectionPipelineService.detectObjects` prints a report-export failure to the console and returns null. `DetectionTask` reads null as "aborted by the user", and shows: *"Report generation aborted by user due to high track count. Try raising your thresholds."* A full disk, for example, looks like a settings problem.
- In iterative detection, a pass whose report fails is printed to the console, but its row is still added to the index page, linking to a report that does not exist.
- An Astrometry.net failure shows its own dialog and returns null. `PlateSolveTask` then calls `get()` on that null, and a second dialog says "Internal Error".

**P2-5. There are three import and preparation flows (confirms D08 and F07).**

| Flow | Used by | What it does |
|---|---|---|
| Interactive (`getFitsfileInformation`, `batchDecompress`, `batchStandardizeTo16Bit`) | the GUI | Asks questions; also writes 16-bit colour copies; decompression is a separate step |
| `DetectionInputPreparation` | the API | Follows the request's policy; mono output; decompresses as it converts |
| `getFitsfileInformationHeadless` | the API and CLI, after preparation | A strict check: compressed or non-16-bit data is refused |

They share the conversion and validation code: `FitsPixelConverter`, `FitsSequenceValidator` and `XisfImageConverter`. Each has its own decision flow, and they behave differently.

**P2-6. The API and CLI depend on hidden process state (new; D09/D10).**
- **Application config:** the API reads the user's config from the home folder through `ImageProcessing`. It sets the observing site and observatory code used by the report's lookups, and the request cannot supply it.
- **Rendering preferences:** these are static fields in `DisplayImageRenderer`, `RawImageAnnotator` and `DetectionReportGenerator`. `applyToRuntime` sets them; the GUI and the CLI load them from the home folder. An API caller gets whatever the process holds.
- **Auto-Tune frame limit:** loading any detection profile sets a static "active Auto-Tune frame limit", and the report's diagnostics print it. So the report shows the value of the profile loaded last, which need not be the run's.
- **Engine statics:** `DebugOutput.applyToEngine` sets the engine's static debug switch. `AutoTunerRunner` changes a JTransient static during a legacy tune (D10, known).

Two runs in one process are therefore not independent. The API documents a concurrency restriction, but the report's site and look also depend on the process.

**P2-7. `util` depends on `gui` (new; pairs with P1-7).**
- `ImageProcessing` uses the `gui` logger, `ProcessingProgressDialog` and Swing dialogs.
- `PlateSolveService` uses the logger and `JOptionPane`.
- `XisfImageConverter` uses the logger.

So the public API loads `gui` classes, and headless use relies on those paths not being reached.

**P2-8. Session state lives inside services (confirms D06).**
- `StandardDetectionPipelineService` keeps the last run's overlays, which Blink and Preview Frame read.
- `ImageProcessing` keeps the imported `FitsFileInformation` objects. The WCS write updates their headers in place, deliberately, so the table, the session and later runs all see the solution.

**P2-9. The application config is shared and mutable (low).** `AppConfig` has public fields:
- the Astrometry Config tab edits them on the EDT;
- the solve worker reads the ASTAP path from them;
- the report export reads the site from them.

No run takes a copy. Capturing it when a run starts would fit D09's "resolved context at a documented point".

**P2-10. Good patterns to keep.**
- **The `api` package:** an immutable request with defensive copies, and a result, exception and progress interface. The CLI is a thin client of it.
- **The `config` I/O classes:** small and tested, with staged writes (D01).
- **The data helpers have no UI or global state:**
  - the WCS classes;
  - `FitsPixelConverter`, `FitsSequenceValidator` and `ExposureGroup`;
  - `DisplayStretch`;
  - `AutoTuneCandidatePoolBuilder` and `DetectionPipelineSupport`.
- **Tests:** 19 in `util`, 5 in `config`, the API, the CLI and real-data integration tests.
- **Console output:** it follows the console rule. Diagnostics go through `DebugOutput`, and the few console prints report problems. P2-4 covers the prints that hide failures from the GUI.

### Class inventory: `util`

| Class | Lines | Role now | Classification | Step |
|---|---|---|---|---|
| `AutoTuneCandidatePoolBuilder` | 316 | Frame pool for Auto-Tune | retain | — |
| `AutoTuneSessionStore` | 204 | Auto-Tune results in the session folder | retain | S8 (called off the EDT) |
| `AutoTunerRunner` | 80 | Runs either tuner; changes a JTransient static for the legacy one | retain | D10 |
| `BlinkSequence` | 201 | Blink frames and their histograms | retain | — |
| `DebugOutput` | 68 | Console policy; sets the engine's debug switch | retain | — |
| `DetectionInputPreparation` | 288 | Headless preparation for the API | adapt boundary (becomes the shared flow) | S12 |
| `DetectionPipelineSupport` | 204 | Helpers shared by both pipelines | retain | — |
| `DisplayImageRenderer` | 118 | Grey display rendering; stretch limits in static fields | adapt boundary | S14 |
| `DisplayStretch` | 185 | Stretch lookup tables | retain | — |
| `ExposureGroup` | 149 | Exposure grouping | retain | — |
| `FitsFileInformation` | 438 | Mutable per-file metadata, shared on purpose | retain | — |
| `FitsFormatChecker` | 60 | Format probe | retain | — |
| `FitsPixelConverter` | 341 | 16-bit conversions | retain | — |
| `FitsSequenceValidator` | 156 | Sequence checks and sorting | retain | — |
| `FitsVisualizationRenderer` | 562 | Previews and stretch of kernels | retain | — |
| `ImageProcessing` | 1909 | Session processor, interactive and headless import, batch operations, report-size dialog | move responsibility | S10, S12, S13 |
| `IterativeDetectionPipelineService` | 305 | Iterative passes and index | adapt boundary | S11 |
| `MixedExposureException` | 87 | Mixed exposures with their groups | retain | S12 (an outcome rather than an exception) |
| `PlateSolutionCorrection` | 210 | Fitted plate-solution correction | retain | — |
| `PlateSolveService` | 544 | Solving, WCS write, artifact cleanup; a Swing dialog; one dead method | adapt boundary | S10, S11, S13 |
| `RawImageAnnotator` | 151 | Overlay drawing; sizes in static fields | adapt boundary | S14 |
| `ReportInclusion` | 47 | Report group choice | retain | — |
| `StagedFile` | 137 | Safe file replacement | retain | — |
| `StandardDetectionPipelineService` | 174 | Standard run and report; keeps the last overlays | adapt boundary | S7, S11 |
| `StretchAlgorithm` | 88 | Stretch presets | retain | — |
| `TrackOverlay` | 124 | Track overlay data | retain | — |
| `VariableStarOverlay` | 126 | Variable-star overlay data | retain | — |
| `WcsCoordinateTransformer` | 381 | WCS transforms | retain | — |
| `WcsSolutionResolver` | 133 | Session WCS choice | retain | — |
| `XisfImageConverter` | 940 | XISF to FITS | retain (drop the `gui` logger) | S13 |

### Class inventory: `api`, `tools` and `config`

| Class | Lines | Role now | Classification | Step |
|---|---|---|---|---|
| `api.DefaultSpacePixelsPipelineApi` | 181 | The pipeline API; builds an `ImageProcessing` per run | adapt boundary | S10, S14 |
| `api.SpacePixelsPipelineRequest` | 257 | Immutable request | retain (optional new fields in S14) | S14 |
| `api.SpacePixelsPipelineResult` | 176 | Result | retain | — |
| `api.SpacePixelsPipelineApi`, `SpacePixelsPipelineException`, `SpacePixelsProgressListener`, `InputPreparationMode`, `NegativeValueHandling`, `package-info` | 170 | Interface, exception, listener, options, package docs | retain | — |
| `tools.BatchDetectionCli` | 251 | CLI over the API; sets process-wide preferences | retain | S14 (pass preferences with the run) |
| `tools.ArtificialStarInjector` | 267 | Test-data tool | retain | — |
| `config.AppConfig` | 26 | Application config with public fields | retain | S7 (application scope), S14 (copied per run) |
| `config.DetectionSettingsRules` | 103 | Cross-field rules | retain | — |
| `config.SpacePixelsAppConfigIO` | 59 | Application config I/O | retain | — |
| `config.SpacePixelsDetectionProfile` | 42 | Profile wrapper | retain | — |
| `config.SpacePixelsDetectionProfileIO` | 151 | Profile I/O; loading sets a static frame limit | adapt boundary | S14 |
| `config.SpacePixelsSettingsFiles` | 50 | Saves the two settings files together | retain | — |
| `config.SpacePixelsVisualizationPreferences` | 44 | Rendering preferences; `applyToRuntime` writes statics | adapt boundary | S14 |
| `config.SpacePixelsVisualizationPreferencesIO` | 40 | Their I/O | retain | — |

Of the 49 classes:
- 39 are retained (a few with small follow-ups);
- 10 change.

The main work is splitting `ImageProcessing`, and making the run's context explicit.

### Steps from part 2

| Step | Stage | Work | Links | Compatibility | Verification |
|---|---|---|---|---|---|
| S10 | C | Split `ImageProcessing`: the session part goes into the session object (S7); batch operations and import become their own services. Keep `ImageProcessing` as a compatibility facade. Remove the dead WCS and stretch methods | D06, P2-1, P2-8 | Public API and output folders unchanged | Existing `ImageProcessingTest` and API tests; same outputs on a real dataset |
| S11 | A–B | Report failures as failures: a report-export failure, a failed iterative pass and an Astrometry.net failure each reach the user with the real reason. The first fix can be just the message (stage A); typed outcomes follow with S3 | D04, P2-4 | Successful runs unchanged | A forced export failure says so, not "aborted by user"; a failed pass is marked in the index; one dialog per solve failure |
| S12 | C | One preparation flow with two decision policies: the GUI's questions move into a decision adapter asked on the EDT and reuse `DetectionInputPreparation`. Import outcomes become typed (ready, prepared, declined, failed) instead of empty arrays and exceptions. Folds in F07 | D08, D06, F07, P2-2, P2-3, P2-5 | Same converted pixels and headers; the headless refusal stays | Conversion outputs compared with today's; declining a conversion keeps the current session |
| S13 | B–C | No `gui` in `util`: a logger of its own, progress through listeners, the report-size dialog moved to `gui`, no Swing in `PlateSolveService` | P2-7, P1-7 | — | A dependency rule test: `util` imports nothing from `gui` and no Swing |
| S14 | D | An explicit run context: the application config (site), rendering options and the Auto-Tune frame limit go with the run, captured when it starts, instead of being read from the home folder and static fields | D09, D10, P2-6, P2-9 | New request fields are optional; their defaults keep today's behaviour | Two API runs in one process with different sites write different reports; the report's frame limit is the run's |

S11's message fix is the second small correctness fix, after S1. Like S1, it is discussed before it is implemented.

## Part 3: presentation

**Scope and method.**
- **Read in full:**
  - the export orchestration (`DetectionReportGenerator.exportTrackVisualizationsInternal`);
  - the lookup proxy's lifecycle;
  - how field depth and night & equipment are measured and fail.
- **Read for responsibilities, dependencies, threads, network use, static state, writes and failure handling:**
  - every other class of `util.reporting` and `util.skycatalog`;
  - the four web resources, for their role only.
- **How:** no code was changed or run.

**Size:** 57 classes, about 20,600 lines, plus 4 web resources:
- `util.reporting`: 49 classes, 18,811 lines;
- `util.skycatalog`: 8 classes, 1,774 lines.

### Paths followed

- **Report export** (on the detection worker):
  1. Snapshot the rendering settings (`ExportVisualizationSettings`).
  2. Sort the tracks into single streaks, streak tracks and moving objects.
  3. Build the report context and `ReportData`, and write `report-data.json`.
  4. Open the HTML as an `AtomicReportFile`.
  5. Measure field depth and night & equipment from the frames.
  6. Write the sections in order.
  7. Publish the HTML in one move. Images and GIFs are written directly beside it.
- **Context read during export:**
  - the WCS, through `WcsSolutionResolver`, which also reads the plate-solution correction from the session folder;
  - the site, from `AppConfig`;
  - the sky catalogue, read lazily from the session folder by `SessionCatalogue`;
  - the Auto-Tune frame limit, from the static of P2-6.
- **Live lookups:**
  - The window starts the proxy on `127.0.0.1:47831` when it is built.
  - A report's buttons call the proxy. It validates the target, queries SatChecker, JPL or SkyBoT with timeouts, normalises the reply, and stores it in a sidecar and in the report itself (`ReportLookupCacheStore`, staged).
- **Sky catalogue:**
  - `SkyCatalogueClient` queries the TAP services. Each query has a timeout, and an interrupt cancels the fetch.
  - `SkyCatalogueStore` saves the result staged.
  - `PlateCorrectionFitter` fits the correction.

  The GUI's sky job (part 1) drives all three.

### Findings

**P3-1. Reporting is well contained (keep).**
- **No upward dependencies:** no `gui`, no Swing, no event bus.
- **Two entry points:** outside code uses only `DetectionReportGenerator`, for export and the rendering statics, and `ReportLookupProxyServer`, from the window. Everything else is package-private.
- **Atomic text output:** the report text files are published atomically (D01).
- **Tests:** 29 test classes cover reporting and the sky catalogue.

**P3-2. Finding labels and categories are worked out in many places (confirms D09).** The numbering T1, ST1, A1, DS1… is computed separately in:
- `ReportData`, for the finding list and the JSON;
- the card counters of `TargetVisualizationSectionWriter`;
- `DeepStackReportSectionWriter`'s track references;
- `AnomalySectionWriter`'s track match;
- the global map (`DetectionReportGenerator`);
- `SessionPictureSectionWriter`;
- `TrackOverlay`, for the viewers.

Sorting a track into single streak, streak track or moving object happens in three places: `DetectionReportGenerator`, `TrackOverlay` and `DetectionPipelineSupport`. They agree today because each walks the tracks in the same order; nothing makes them agree. `ReportData` exists, but the writers do not take their labels from it.

**P3-3. Measurements run during export, and their failures look like "not measured" (confirms D09).**
- `FieldDepth` and `NightEquipment` measure the frames while the report is written; `NightEquipment` uses its own parallel pool.
- If either throws, it prints to the console and returns an empty result. The report then reads as if there was nothing to measure.

Each section fails in its own way:

| Section | What a failure does |
|---|---|
| Session picture | Leaves a note (tested) |
| Catalogue map | Draws no labels |
| Field depth, night & equipment | Turn empty |
| Any other section | Aborts the whole report. The GUI then shows either P2-4's misleading message or "Detection failed". |

**P3-4. The report reads the session's state from disk at export time (confirms D09).**
- The catalogue and the plate-solution correction come from the session folder when the report is written.
- The site comes from `AppConfig`, and the frame limit from a static (P2-6).

So a report written later, or re-rendered, would use whatever is on disk then. Capturing them with the run belongs to the run context (S14).

**P3-5. The rendering settings are static fields, snapshotted at export (partly done; S14).**
- `DetectionReportGenerator` has six public static fields; four of them are set from the preferences.
- `UnclassifiedTransientSectionWriter` has one more.
- The `ExportVisualizationSettings` snapshot is the right pattern, but the statics are still its source.

**P3-6. `DetectionReportGenerator` is also a box of helpers (low).**
- About 250 of its 1,483 lines orchestrate the export.
- The rest builds images: mask overlays, maps, the drift map, the corner mosaic and the cluster maps. It also holds the formatting and HTML-escaping helpers that 15 writers call.

This matches the retained plan: extract helpers when feature work needs them, not as a project of their own.

**P3-7. The lookup proxy is as the plan describes (no new defect).**
- It runs on a fixed loopback port with an unbounded cached thread pool, and starts with the window.
- A second SpacePixels window cannot bind the port, so its reports use the first instance's proxy.
- The plan already says to give it an owner and a bounded I/O policy when it is expanded.

**P3-8. The sky catalogue package is clean (keep).**
- Data and network classes only.
- Requests have timeouts, and an interrupt cancels a fetch.
- Writes are staged.

### Class inventory: `util.reporting`

Grouped by role. Each class is named once.

| Group | Classes | Lines | Classification | Step |
|---|---|---|---|---|
| Orchestration | `DetectionReportGenerator` | 1483 | adapt boundary (options instead of statics, labels from one model, one failure policy) | S14, S15, S16 |
| Report model | `ReportData` | 142 | adapt boundary (the single source of labels) | S15 |
| Report model and output | `DetectionReportContext`, `DetectionReportSummary`, `ExportVisualizationSettings`, `AtomicReportFile`, `DetectionReportDocumentWriter`, `ReportPresentation`, `ReportClientScriptWriter` | 589 | retain | — |
| Section writers | `AnomalySectionWriter`, `DeepStackReportSectionWriter`, `TargetVisualizationSectionWriter`, `ResidualReviewSectionWriter`, `GlobalMapsSectionWriter`, `UnclassifiedTransientSectionWriter`, `PhotometryReportSectionWriter`, `PipelineDiagnosticsSectionWriter`, `NightEquipmentSectionWriter`, `SessionPictureSectionWriter`, `VariableStarMapWriter`, `CatalogueComparisonWriter`, `SignalWeaveSectionWriter`, `ClaudePerspectiveSectionWriter` | 8535 | retain; their labels come from `ReportData` with S15 | S15 |
| Measurements | `FieldDepth`, `NightEquipment` | 1666 | move responsibility (measured before export, with explicit states) | S16 |
| Measurements | `SessionCatalogue` | 180 | adapt boundary (catalogue captured with the run) | S14, S16 |
| Measurements | `StarShape`, `FrameRejectionExplanation`, `DataAreaPresentation` | 399 | retain | — |
| Rendering helpers | `TrackVisualizationRenderer`, `TrackCropGeometry`, `GifSequenceWriter`, `PhotometrySvgChart`, `CatalogueMapPainter`, `CreativeTributeRenderer` | 2399 | retain | — |
| Astrometry and identification | `DetectionReportAstrometry`, `AstrometryContextFactory`, `AstrometryIdentificationHtmlBuilder`, `SkyViewerHtmlBuilder`, `JplSbIdentUrlBuilder`, `SatCheckerUrlBuilder`, `SatCheckerQueryTarget`, `SatCheckerQueryTargetFactory`, `SolarSystemQueryTarget`, `SolarSystemQueryTargetFactory` | 2193 | retain (the site comes from the run context with S14) | S14 |
| Live lookups | `ReportLookupProxyServer`, `ReportLookupUpstreamClient`, `ReportLookupPayloadNormalizer`, `ReportLookupCacheStore` | 1225 | retain (owner and bounded pool when the proxy grows, as planned) | — |

### Class inventory: `util.skycatalog`

| Classes | Lines | Classification |
|---|---|---|
| `CsvTable`, `PlateCorrectionFitter`, `PolynomialFit`, `SkyCatalogue`, `SkyCatalogueClient`, `SkyCatalogueStore`, `SkyField`, `SkyProjection` | 1774 | retain |

Of the 57 classes:
- 52 are retained;
- 5 change: `DetectionReportGenerator`, `ReportData`, `FieldDepth`, `NightEquipment` and `SessionCatalogue`.

### Steps from part 3

| Step | Stage | Work | Links | Compatibility | Verification |
|---|---|---|---|---|---|
| S15 | D | One source of finding identities and categories: a category descriptor (prefix, label, colour, inclusion option, anchor rule) and `ReportData`'s numbering. The section writers, maps, session picture, viewer overlay and JSON all use it | D09, P3-2 | Labels, anchors, file names and JSON unchanged | The report consistency tests compare labels across the HTML, JSON, maps and overlays on standard and iterative bundles |
| S16 | D | Prepared diagnostics with explicit states: field depth, night & equipment and the catalogue snapshot measured as a step of the run, each *measured*, *unavailable* or *failed* with a reason. One failure policy for optional sections: a failing section leaves a note, as the session picture does, instead of aborting the report or vanishing | D09, P3-3, P3-4 | Same measurements and report for a successful run | A forced failure in each optional section leaves a note and a complete report; same values on a real dataset |

## Review summary

| Part | Classes | Retained | Event classes retired | Changed |
|---|---|---|---|---|
| 1. Workflow core | 60 | 23 | 18 | 19 |
| 2. Services and boundaries | 49 | 39 | — | 10 |
| 3. Presentation | 57 | 52 | — | 5 |
| **All production classes** | **166** | **114** | **18** | **34** |

**What the review shows.**
- The data, rendering and reporting code mostly fits the target already.
- The work concentrates in four areas:
  - the job lifecycle and the window's panels (S2–S5, S9);
  - the session (S7, S10);
  - the GUI dependencies of `tasks` and `util` (S6, S13);
  - explicit run context and prepared results (S12, S14–S16).
- Two small correctness fixes can come first and stand alone:
  - S1: no job leaves the UI locked;
  - S11: failures are reported with their real reason.

**Coverage and exceptions.**
- **Every production class** in the eight packages is classified.
- **Entry paths:** every GUI, API and CLI entry path named in DESIGN-IMPROVEMENTS was followed.
- **Build wiring:**
  - One Gradle module holds the application, the API and the CLI. So API users also receive FlatLaf and Guava, and the package boundaries are not enforced by modules.
  - The dependency rule tests of S6 and S13 are the intended enforcement. Splitting modules is not proposed.
- **Not reviewed line by line:**
  - the report's JavaScript resources, beyond their role;
  - the launcher and distribution scripts;
  - test internals.
- **Out of scope:** JTransient and JPlateSolve, as external contracts.
- **Deliberate exceptions:**
  - `FitsFileInformation` stays mutable, so a WCS write reaches every holder (P2-8);
  - the lookup proxy stays application-scoped (P3-7);
  - the AI perspective sections and `CreativeTributeRenderer` stay as cohesive optional writers.
