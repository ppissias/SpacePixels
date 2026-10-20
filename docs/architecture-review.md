# Architecture review: inventory and path traces

This is the working record of the [architecture adoption review](../DESIGN-IMPROVEMENTS.md#architecture-adoption-review). It holds the class inventory, the traced paths and the evidence. The steps the review leads to, and the decisions about them, live in [DESIGN-IMPROVEMENTS.md](../DESIGN-IMPROVEMENTS.md).

| Part | Packages | Status |
|---|---|---|
| 1. Workflow core | `gui`, `tasks`, `events` | Done 2026-10-20, against `9a87c74` |
| 2. Services and boundaries | `util` (outside reporting and sky catalogue), `api`, `tools`, `config` | To do |
| 3. Presentation | `util.reporting`, `util.skycatalog` | To do |

**Classifications**, as defined in DESIGN-IMPROVEMENTS:

- **retain:** the class fits the target as it is.
- **move responsibility:** part of what the class does belongs elsewhere.
- **adapt boundary:** the class stays, but how it is called or what it returns changes.
- **investigate:** not enough is known yet.

Step numbers (S1, S2…) refer to [Steps from part 1](#steps-from-part-1).

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

This supports option 2 (retire the bus gradually) in DESIGN-IMPROVEMENTS. The decision stays with the maintainer.

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
| S9 | B–C | Retire the event bus as S2, S3 and S7 replace each event; then remove Guava | EventBus question | — | No `@Subscribe` left; the build has no Guava |

S1 is a correctness fix that can come first. Per the maintainer's rules it is discussed before it is implemented.
