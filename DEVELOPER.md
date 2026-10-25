# Developer guide

How SpacePixels is built, how its code is organised, the principles it follows, and how to change it safely. This guide is for developers and coding agents. The implementation sections describe the current code; the principles guide changes. Planned design work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) and planned functionality in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md).

| Read | For |
|---|---|
| [MANUAL.md](MANUAL.md) | What the application does, for users |
| [API.md](API.md) | The supported Java API and its requests and results |
| [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md) | Components, processing flows and data contracts in detail |
| [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) | Pending design and refactoring work, and the latest code review |
| [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) | Pending functional requests and their decisions |
| [PUBLISHING.md](PUBLISHING.md), [docs/release-artifacts.md](docs/release-artifacts.md) | Releases and the selected-artifact check |
| [docs/features/](docs/features/) | Technical notes on individual features |

Machine-specific paths (tools, test datasets, scratch folders, memory limits) are not in this repository. Each machine keeps them in a git-ignored `LOCAL-ENVIRONMENT.txt` at the repository root (see [AGENTS.md](AGENTS.md)).

## Principles

Scientific correctness and preservation of valid results constrain every design change. Within that constraint, the maintainer's goals for the code, in order:

1. **Readable code.** A reader should understand a class from its name, its Javadoc and its fields. Prefer plain, direct code over clever generality.
2. **A clear application design with clear boundaries.** Each piece of mutable state has one authoritative owner. Each package has one job, and its dependencies point one way.
3. **No abstraction or engineering without a real need.** An interface normally needs two implementations or a test seam that is actually used; an explicit listener or ownership boundary can also justify one. A layer needs a responsibility of its own. A compatibility path needs a caller that still uses it. Remove unused machinery after identifying the contracts it protects. Fewer classes or files alone do not make a design simpler.
4. **A few chosen design patterns, applied consistently.** They are listed in the [table below](#design-patterns-in-use). Reuse them when their responsibilities fit; consistency does not justify an unnecessary abstraction.

The design rules that follow from them:

- **JTransient owns detection.** SpacePixels prepares data, runs the work and presents results; it never changes detection behaviour. Engine changes belong in JTransient.
- **One authoritative mutable owner, with explicit snapshots.** A run receives everything it uses (settings, options, correction, catalogue) when it is admitted, and never reads live controls, globals or files that another component may be rewriting. Live values, immutable run snapshots and saved baselines have distinct responsibilities; multiple representations are not by themselves duplicate ownership. Immutability of an element does not make its containing array immutable. No mutable static application state.
- **Constructor injection and explicit listeners.** No service locator, no global event bus, no DI framework.
- **The EDT is for the window.** Swing is touched only on the event dispatch thread; file, network and processing work run on owned workers. `DependencyRulesTest` enforces the package boundaries.
- **Every job ends exactly once,** with its result or a reason, and no failure leaves the window locked. Cancellation does not retract queued UI callbacks: delivery still checks the job and session identity. Preserve completed detection products separately from the outcome of report export.
- **Files are replaced only when complete** (`StagedFile`, `AtomicReportFile`).
- **The console shows only problems.** Diagnostics go through `DebugOutput` and appear with `-Dspacepixels.debug=true`.
- **Logic is testable without the window.** The GUI mechanics tests drive the real window separately.
- **The three repositories are one coordinated source set.** SpacePixels, JTransient and JPlateSolve change together; there is no promise to keep old library signatures, and no reflection or fallback code for older library versions. Changes to exposed API types still need an explicit compatibility decision, updated consumer checks and release documentation; updating sibling callers does not update external consumers.

**Enforcement.** Package dependency tests enforce selected structural boundaries, not runtime thread confinement or every ownership rule.

**Avoid:** rewriting the Swing UI in another toolkit to fix state problems; splitting classes by line count; replacing a large panel with an equally large controller; generic jobs with untyped results; making every object observable; keeping large image buffers for later convenience; changing the viewers' focus rules without real keyboard tests.

## Working rules

These apply to everyone, including coding agents.

- **Never overwrite the user's saved settings:** `spacepixels_app.json`, `spacepixels_detection_profile.json` and `spacepixels_visualization.json` in the user's home folder. To run anything that loads or saves settings, start the JVM with `-Duser.home=<scratch folder>` and copy a profile there.
- **Java preferences** under node `/eu/startales/spacepixels` hold the window placement, the stretch values, `blink.frameMillis`, `gui/showExpertSettings` and `gui/progressDialog.liveView`. A tool or test that changes them must back them up and restore them; isolating `user.home` does not isolate preferences. The GUI mechanics tests do this with `PreferenceSnapshot`.
- **Discuss a fix before implementing it**, and show its effect on real data (see [Real-data regression](#real-data-regression)). Engine fixes go one at a time.
- **Separate refactoring from behavior changes.** Changes to accepted inputs, save-failure behavior, viewer lifetime or public contracts need an explicit decision even when proposed in a design issue. Consider and evaluate each issue's review comments before implementation, and record the decision and relevant checks with the change.
- **Remove the observing site** (coordinates, site names) from images, reports and screenshots before publishing them.
- **Commits:** code commits have descriptive messages, with the validation in the body. Documentation-only commits use a generic message such as "Update docs". Never put private details or locations in a commit message. Don't rewrite history. Stage only your own changes, and leave unrelated working-tree edits alone.
- **Refer to the maintainer and other people as they/them.**
- **Document new Java types** (including nested types and tests) with their responsibility, and give methods a short Javadoc covering purpose and any ownership, units or threading contract. Update the Javadoc when behaviour changes.
- **Keep documentation current in the same change:** behaviour in MANUAL.md, design in this guide or HIGH_LEVEL_DESIGN.md, and pending work only in DESIGN-IMPROVEMENTS.md or FEATURE-REQUESTS.md. When an item is done, describe the result and remove the item; git history keeps the record.
- **Diagnostic programs** go in a scratch folder outside the repository, never in it.

## Build and run

Use Java 21 and the Gradle wrapper (`gradlew.bat` on Windows, `./gradlew` elsewhere) from the repository root.

```powershell
.\gradlew.bat compileJava compileDesktopJava compileTestJava
.\gradlew.bat test                 # unit tests; GUI mechanics are excluded
.\gradlew.bat guiMechanicsTest     # drives the real window; needs a desktop and moves the mouse
.\gradlew.bat check                # tests plus API/desktop packaging checks
.\gradlew.bat run                  # the desktop application
.\gradlew.bat installDist          # application and tool launchers under build/install/SpacePixels
```

Headless batch run of one dataset (it prints `Report file: ...` and any problems):

```bash
./gradlew -q batchDetect -PbatchMaxHeap=11g -PbatchArgs="\"<folder>\" \"<profile.json>\""
```

Add `-PspacepixelsDebug` (or `-Dspacepixels.debug=true` on a plain `java` command) for progress, per-frame statistics and the engine's debug output.

**Source sets.** `main` is the headless API and CLI artifact (`eu.startales.spacepixels:spacepixels`): `api`, `config`, `session`, `util` (with reporting and the sky catalogue) and `tools`, with no Swing and no FlatLaf. `desktop` holds `gui`, `jobs`, `tasks` and the icons, and is not published. `run`, `installDist` and the desktop launchers use both; the CLI launchers use only `main`. `check` verifies the published jar, its POM and Gradle metadata, and that the two source sets own disjoint classes.

**Sibling libraries.** During development, both libraries are built from their sibling checkouts. Set `useLocalJTransientBuild=true` and `useLocalJPlateSolveBuild=true` in the git-ignored `gradle.properties` (copy [gradle.properties.example](gradle.properties.example)). The environment variables `USE_LOCAL_JTRANSIENT_BUILD` and `USE_LOCAL_JPLATE_SOLVE_BUILD` also work; an explicit `-P` property wins. Without the flags, Gradle uses the published versions in `build.gradle`.

**Releases.** Select the exact JTransient and JPlateSolve versions for the release and pin them. With both composites disabled, build and test that exact combination and check the resolved versions ([docs/release-artifacts.md](docs/release-artifacts.md)). `mavenCentralBundle` also runs `apiConsumerTest`, a separate build that consumes only the staged `spacepixels` artifact. See [PUBLISHING.md](PUBLISHING.md).

### Dependencies

**SP-18 is complete (2026-10-24).** The coordinated dependency upgrade established Gradle 9.8.2, nom-tam-fits 1.23.0, Gson 2.14.0 and FlatLaf 3.7.2 across the repositories that use them. Reference outputs stayed byte-identical, saved settings files loaded unchanged, and compressed frames were compared with cfitsio through astropy. `UiStyle.spinner` preserves the earlier caret-on-focus behaviour. The upgrade commits record the checks.

FITS HDU creation uses the public `Fits.makeHDU(Object)` API in pixel conversion, XISF import, artificial-star injection and test fixtures (SP-23, completed 2026-10-25). It replaces the deprecated `FitsFactory.hduFactory(Object)` calls while preserving pixel and header handling.

Remaining dependency maintenance is tracked separately as SP-21 and SP-22 in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). The optional application runtime choice belongs to [F06, Windows installer](FEATURE-REQUESTS.md#f06-windows-installer); JTransient and JPlateSolve retain Java 21 compatibility.

## The packages

All packages are under `eu.startales.spacepixels`.

| Package | What it holds | Main classes |
|---|---|---|
| `gui` | Swing views, shared commands and their availability, settings pages, frame viewers | `ApplicationWindow`, `MainApplicationPanel`, `DetectionConfigurationPanel`, `CommandAction`, `CommandAvailability`, `SequenceViewerFrame`, `BlinkFrame` |
| `jobs` | Runs long operations one at a time; no Swing | `JobCoordinator`, `Job`, `JobOutcome`, `JobKind` |
| `tasks` | The long operations, one class per job | `FitsImportTask`, `DetectionTask`, `PlateSolveTask`, `AutoTuneTask`, … |
| `session` | The imported dataset: identity, owned state and handover; the application I/O queue; no Swing | `ProcessingSession`, `SessionOwner`, `SkyCatalogueModel`, `AutoTuneState`, `ApplicationStorage` |
| `config` | Application settings, detection profiles, visualization settings and their files; the typed setting definitions | `ApplicationSettings`, `AppConfig`, `SpacePixelsDetectionProfileIO`, `DetectionSetting`, `DetectionRunOptions`, `VisualizationSettings` |
| `util` | The processing services: import and preparation, FITS and XISF, WCS, the detection pipelines, plate solving | `FitsImportService`, `FitsBatchService`, `StandardDetectionPipelineService`, `IterativeDetectionPipelineService`, `PlateSolveService`, `StagedFile` |
| `util.reporting` | The HTML report, its model, section writers and the live lookup proxy | `ReportExporter`, `ReportRequest`, `ReportData`, `FindingCategory`, the `…SectionWriter` classes |
| `util.skycatalog` | Fetching and storing the sky catalogue; the plate-solution correction fit | `SkyCatalogueClient`, `SkyCatalogueStore`, `PlateCorrectionFitter` |
| `api`, `tools` | The public Java API and the command-line tools | `DefaultSpacePixelsPipelineApi`, `SpacePixelsPipelineRequest`, `BatchDetectionCli` |

`util` is the application core rather than a set of utilities; DESIGN-IMPROVEMENTS.md (SP-14) proposes domain-named packages. Other locations: `src/main/resources` (icons, build metadata), `src/dist/config` (the default detection profile), `src/launchers`, `src/consumerTest` (the isolated API consumer build).

The main paths through the code:

- **GUI:** a `gui` command → `JobCoordinator` runs a `tasks` job → `util` services → JTransient or JPlateSolve.
- **Headless:** `api` and `tools` → `util` services → JTransient.

**Input contract.** Detection consumes aligned, equally sized monochrome frames with unsigned-16-bit pixels (`BZERO=32768`, `BSCALE=1`) in `short[][]` buffers. `FitsFileInformation` carries each frame's immutable metadata, unmodifiable header map and construction-time normalised timing. Compressed FITS can have an empty primary HDU, so use the HDU helper (`FitsInput.getImageHDU`). Preparation keeps timing and WCS headers, the registration padding, and the sequence-wide offset recorded in `SPOFFSET`.

`FitsInput` owns discovery, image-HDU selection, format/readiness inspection and sequence validation. Its `Input` facts
come from one inspection per file, on the importer's optional owned executor. Strict headless loading checks readiness
before cohort consistency; interactive import preserves its sequence checks before conversion offers.
`FitsPreparation` writes prepared copies through `FitsPixelConverter`; `XisfImageConverter` remains separate.
These are static helpers; `FitsImportService` and `FitsBatchService` retain their instance-owned work.

## How a user action runs

Eleven workflows use the job pattern: import, Convert to Mono, Batch Stretch, plate solve, writing the WCS header, Blink, Auto-Tune, Detect, Detect Iteratively, Manual Transient Inspection and the Test Star Mask stack.

1. **The command, on the EDT.** `CommandAvailability` decides from session facts whether the command can run, and why not. `CommandAction` is the one Swing `Action` behind the command's button, shortcut, menu item and Overview button. The handler captures what the job needs: a validated copy of the settings (`DetectionConfigurationPanel.checkedSettings`), the files, the run options and the session.

The window creates the four panels and passes each one the collaborators it uses (`SessionAccess`, the job coordinator, the frame list, the settings readout, storage and the folder import). Panels do not take the window or call each other by type. The settings page publishes the current `DetectionConfig`; the main readout copies the eight Overview values during that EDT call and does not keep the configuration. The wiring lasts as long as the window, so there is no listener to detach. A job still copies the session and settings at admission.
2. **Admission.** `JobCoordinator.start(kind, session, job, handler)` reserves the single job slot or says why it cannot. On admission, `MainApplicationPanel` locks the window and shows `ProcessingProgressDialog`.
3. **The work, on a worker.** The task reports progress through `JobContext.progress` and returns a `JobOutcome`: `Completed`, `Declined`, `Cancelled` or `Failed`. Cancel makes the next progress report throw `JobCancelledException`; engine tasks also bind JTransient's `CancellationScope`, which stops computation between reports. An exception becomes `Failed`, except out-of-memory, which closes the application with a message.
4. **The outcome, on the EDT,** arrives exactly once after the window is unlocked, and is applied only if its session is still the current one (`ApplicationWindow.forCurrentSession`).

Blink is the exception: loading the frames is the job, and the blink window then keeps the main window locked until it closes. Work outside the job slot has its own owner: the sky-catalogue fetch and correction fit (`gui.SkyCatalogueController`, with one executor per session and a future per request), session and preference writes (single-thread writers owned by the window), ASTAP checks, stretch previews and viewer preparation (newest request wins).

The catalogue controller owns its progress timer and delivers model changes and status on the EDT. Delivery checks the request identity and the current session even after cancellation, replacement or shutdown. The session owns a non-blocking shutdown callback, with no dependency on the GUI; closing it cancels work, shuts down the executor, stops the timer and detaches the view. A catalogue completed before its correction remains available and saves independently of the fit outcome.

## The session

`SessionOwner` holds the current `ProcessingSession`. A session has a UUID and owns its frame metadata (`ProcessingSession.replaceFrameHeader` replaces a frame's `FitsFileInformation` after a WCS write while existing runs keep their snapshots), selection, sky catalogue and correction (`SkyCatalogueModel`), Auto-Tune state, master-stack cache, last report and last run's overlays, and the services that work on its frames: its plate solver (`PlateSolveService`) and the batch operations on its folder (`FitsBatchService`). Large image buffers are shared by ownership, never deep-copied.

- **Handover.** `FitsImportTask` reads the frames and the saved session data (catalogue, correction, Auto-Tune results) on a worker; each attempt closes its `FitsImportService` before it returns, so the result holds no resources. The window builds the session from that result. Only a completed import is installed, through `SessionOwner.replace(expected, candidate)`, which rejects a stale import and closes it. A failed, declined or cancelled import keeps the current session.
- **Closing a session** stops its catalogue worker, closes its plate solver and releases its frames, caches and selection, also when the solver cannot confirm its shutdown. Reusable viewers are cleared at handover; an independent inspection window may keep the old session's data deliberately, but never adopts the new catalogue.
- **Session files** (`sky_catalogue.json`, `plate_correction.json`, `auto_tune.json`) are written on the window's session writer. They are restored at import only if they match the frames (field, plate solution, frame count and size).
- **A run never reads the session files.** Detect and Detect Iteratively take the session's catalogue and correction when they are admitted (`SkyCatalogueModel.snapshot`, a `SkySnapshot`); an iterative pass on a prepared copy in another folder, and every headless run, read that folder's files once, at the start (`ReportRunContext.capture`). Jobs therefore never wait for session writes. When a save fails, the value stays in use until SpacePixels closes, in the viewers and in the reports, and the window says it was not saved.
- **Application settings** (`ApplicationSettings`, the Astrometry Config tab) are loaded once by the window, before any import. Each session's plate solver reads that one live object, so unsaved edits reach the next solve; an import never reads or reloads them.

## Settings

**Classification.** The maintainer classifies every detection setting as core, basic, expert or hidden. Preserve it when editing the settings pages.

- **Core:** the eight Overview values that Auto-Tune sets: per-frame sigma, grow sigma and minimum pixels; master sigma, master grow sigma and master minimum pixels; mask overlap; base star jitter radius.
- **Basic** (visible by default):
  - single-streak minimum peak sigma;
  - anomaly rescue switch and peak sigma;
  - slow movers: switch, sigma, grow sigma and minimum pixels;
  - residual-analysis and local-rescue switches;
  - variable-star switch;
  - Reject Camera-Fixed Sources;
  - report stretch black and white points and GIF speed;
  - the frame-rejection **Strictness** choice.
- **Expert** sections start with `createExpertSectionHeader`. They are shown only while "Show expert settings" is on, but search and "Show only unsaved changes" always find them. Each page says how many of its expert settings are hidden and how many of those are changed.
- **Hidden** settings have no row. They stay in `DetectionConfig` and in the profile, keep their loaded values on Apply, and are reset by Load Defaults and Revert. Do not add rows for them without asking the maintainer.

**The pages.**
- `DetectionConfigurationPanel` builds the pages. `SettingsNavigator` provides the page list, search, the unsaved-changes filter and the expert switch. Each `SettingRow` has a changed marker and a reset button, and `CheckedNumberEditor` accepts only a complete number in range. Create every spinner with `UiStyle.spinner`, which keeps the caret where the user puts it when the spinner gains the focus (FlatLaf would select the whole value).
- Every visible engine setting is described by a `DetectionSetting` (stable field ID, title, unit, guidance, expert flag, getter and setter) in one table per page, such as `CoreDetectionSettings` and `MovingObjectsSettings`. The binding to its control is registered where the row is built. Application-owned controls on those pages (`ApplicationSettingBinding`) carry their metadata, owner getter/setter and UI accessors directly, and `SettingRow` receives its setting ID and expert visibility directly at construction rather than through component client properties.
- **Strictness** (Lenient / Normal / Strict) sets the five frame-outlier limits together (`frameRejectionPresetValues`) and shows "Custom" when they match no preset.
- `showOnlyWhen(toggle, rows…)` shows dependent rows only while their switch is on: the geometric linker, activity clusters and the bright-star eccentricity limit.

**Edits and runs.** Edits apply to the session's `DetectionConfig` at once (`applySettingsToMemory` fails fast rather than swallowing binding or alignment errors). Save writes the profile and the visualization preferences on the settings writer, and only a successful write advances the saved baseline. Every run takes its own validated copy, so later edits never affect a running job. Preview Frame detects each frame with a fresh copy and shares the per-frame threshold spinners with the Overview.

**Rules.** JTransient's `DetectionConfigRules` owns the numeric checks: non-finite values are errors, and seven pairs must stay ordered (grow sigma ≤ sigma, and so on). The panel asks it to align a pair when one side is edited. The rhythm/jump pair is an old GUI policy, not an engine invariant. **Review rules…** shows advisory findings only.

**Profiles.** Profiles are read with Gson, which ignores unknown keys. `SpacePixelsDetectionProfileIO.needsMigration` compares key sets. When a `DetectionConfig` field is added or removed, the application offers a one-time "Migrate profile" step that keeps a backup. Also update `src/dist/config/default_detection_profile.json`. The old profile name `spacepixels_jtransient.json` is still read.

## Frame viewers

Preview Frame (`DetectionSequenceFrame`), Manual Transient Inspection (`TransientInspectionFrame`) and Show full size (`StretchedSequenceFrame`) extend `SequenceViewerFrame`. Each prepares one frame at a time in `prepare(...)` on a `SwingWorker`, and the newest request wins. Blink (`BlinkFrame`) is built from the same parts: `FrameView` draws, `StretchControls` shares the stretch with the Image Stretch tab, `ViewerSupport` provides the keys and status bar, and `SkyAnnotations` draws catalogue labels. Blink, Preview Frame and Manual Transient Inspection can show the last run's tracks (T) from the session's `TrackOverlay`; nothing is detected again.

**Closing a viewer** cancels pending preparation, drops its result, detaches the catalogue and shared stretch listeners (`StretchControls` must detach, or the shared models keep the window and its buffers), and releases its frames.

**Focus rules** (tested with real key and mouse events): only the frame view takes the focus, except the Preview Frame settings fields. Their spinners use `setRequestFocusEnabled(false)`, so an arrow click leaves the keys on the frame. Window keys bound through `ViewerSupport.bind` do not fire while a text field has the focus. Enter or Esc in such a field gives the focus back to the frame. A blink of selected frames resolves its WCS over the whole session (`BlinkFrame.open` takes `sessionFiles`).

## The report

`ReportExporter.export(ReportRequest)` is the single normal-report entry point. The API and the standard/iterative
detection services pass the engine result, shared image buffers, copied metadata/configuration, captured run context,
inclusion and progress in one request. Export resolves its folder, builds the complete context, writes the report and
removes unfinished output on cancellation. Detection overlays are delivered before the report decision and export.

The report has three stages:

1. **Model:** `DetectionReportContext.create` normalizes optional engine analyses and initializes every shared field, including `ReportData`, before section rendering. Tests use the same factory. Missing WCS, catalogue or optional analysis is a valid captured absence; it never triggers live settings or session-file reads. `ReportData` holds the finding identities and frame selection, and is also written as `report-data.json`. `FindingCategory` owns each category's prefix, label, anchor rule, colour and inclusion option, so cards, maps, CSVs, overlays and the report question agree.
2. **Sections:** one writer per section, such as `TargetVisualizationSectionWriter`, `AnomalySectionWriter` and `PhotometryReportSectionWriter`.
3. **Publish:** the HTML is published only when complete (`AtomicReportFile`).

`ReportMaps` draws the map and mask pictures, and `ReportFormat` formats report text. `DetectionReportGenerator` keeps the iterative index.
Internal Java callers use `ReportExporter.export(new ReportRequest(...))`; the supported `api` surface and report schemas are unchanged.

The report never changes the engine's results. When the engine stops after quality control because it kept fewer than three frames (`PipelineResult.tooFewFrames`), the report says so and shows the quality-control results. Its live lookups (SatChecker, JPL, SkyBoT) go through `ReportLookupProxyServer`, a small localhost server owned by the window. `report-data.json` is versioned. Its frame numbers are one-based, and its finding IDs are local to the report.

## Headless: the API and the command line

`DefaultSpacePixelsPipelineApi.run(request)` prepares the input according to the request's policy (no questions), validates the frames strictly, optionally runs Auto-Tune, detects, and optionally writes the report. It is synchronous, reads the frames with its own `FitsImportService`, which it closes, and never touches a GUI session or creates a plate solver. `BatchDetectionCli` is a thin client of it. The supported surface is the `api` package and the types it exposes ([API.md](API.md)); everything else is internal.

## Persistence and outputs

| Location | Contents |
|---|---|
| `user.home/spacepixels_app.json` | Application preferences: solvers, Astrometry.net account, observing site |
| `user.home/spacepixels_detection_profile.json` | Detection configuration, Auto-Tune frame limit, variable-star report options |
| `user.home/spacepixels_visualization.json` | Rendering and export preferences |
| Java preferences `/eu/startales/spacepixels` | Window placement, stretch and viewer preferences |
| Dataset folder | `sky_catalogue.json`, `plate_correction.json`, `auto_tune.json` |
| Run output folder | `detection_report.html`, `detection_config.json`, `report-data.json`, CSVs, images and GIFs; iterative runs add per-pass folders, `index.html`, `run-data.json` and input manifests |

Saving the profile and the visualization preferences stages both files, but replaces them independently; an error names the file that was not saved. A cancelled run removes its unfinished output folder; a cancelled iterative run keeps the passes it finished.

## Testing

Tests use JUnit 4.13.2. Start with the affected tests, then run the full suite.

**Framework decision.** Stay on JUnit 4; a migration to JUnit 6 is optional future maintenance, with no application behaviour change. If revisited, run existing JUnit 4 tests on the Vintage engine, migrate classes as they are touched, and account for the GUI mechanics tests' move to AssertJ Swing's Jupiter variant.

- **Boundaries and ownership:** `DependencyRulesTest` (no GUI in jobs, tasks or `util`; no AWT in session state), `JobCoordinatorTest`, `ProcessingSessionTest`, `ApplicationStorageSessionTest`, `ApplicationStorageSettingsTest`. The storage tests cover one queue's settings/session order, reimport reads behind writes, target failures and retries, shutdown drain, and session completions already queued when their owner closes.
- **Synthetic data:** `SyntheticDatasetFactory` builds disposable FITS datasets.
- **Preparation parity:** `FitsPreparationParityTest` compares GUI and API preparation across formats.
- **Iterative detection:** `IterativeDetectionPlanTest` and `IterativeDetectionRunTest`, which compares passes with ordinary runs on the same subsets.
- **GUI mechanics** (`guiMechanicsTest`): drive the real window by the visible text of its controls, with a scratch `user.home` and restored preferences. Viewer navigation tests must send real `KeyEvent`s to the focus owner; invoking actions directly misses controls that consume Space or the arrow keys.
- **Real-data harness:** `.\gradlew.bat realDataTest -PrealDataRoot=<folder>` runs the preparation, API and CLI checks on copies of real datasets. An existing data directory is required only when this opt-in task runs; there is no machine-specific default. Alternatively, set `realDataRoot` in the git-ignored `gradle.properties`; explicit `-P` wins. Relative paths are resolved against the project directory and passed to the test JVM as absolute paths.

`-PtestMaxHeap=6g` raises the heap of the test tasks.

### Real-data regression

Any change that can affect detection, preparation, reports or numeric diagnostics is checked on the reference datasets. Their location is in `LOCAL-ENVIRONMENT.txt`. Ignore older date-named folders there unless asked.

- **The original seven:**
  - klangwolke-1, and klangwolke-2 (use its `_16bit_converted` folder): good data with known asteroids; klangwolke-2 is the main dataset;
  - Iwamoto-huge, Eagle-noisy, whynot-nocalibration-wrongdrift, Apophis and Africano.
- **The variable-star batch:**
  - dc-reg, helix-reg, ic-5070-reg, m101-reg, m31-b-reg, m31-g-reg, m31-red-reg, m5-reg and m81-reg;
  - M81-kumar-L-dataset-registered, and "UG 6524-kumar-L-dataset-registeed" (spelled that way on disk).

To check a change:

1. Run every relevant dataset with `batchDetect` on the base commit and on the change, with the same profile and a scratch `user.home`.
2. Compare the rejected frames, the tracks and anomalies, the variable-star verdicts and candidates star by star, and the timings. For a pure refactoring, `report-data.json` (apart from its generation time), `detection_config.json` and the CSVs must be identical. When comparing full report folders, normalize only explicitly listed volatile fields, including the HTML's measured engine and stage durations; compare deterministic content and rendered assets exactly. Measure performance separately rather than requiring identical elapsed times.
3. Report the differences to the maintainer before committing.

Run large datasets one at a time; the memory available per JVM is in `LOCAL-ENVIRONMENT.txt`.

## Design patterns in use

| Pattern | Where |
|---|---|
| Command with a coordinator | `Job` and `JobCoordinator`: one admission rule, one outcome per job |
| Shared command (Swing `Action`) | `CommandAction`, enabled by `CommandAvailability` |
| Typed outcome | `JobOutcome` (completed, declined, cancelled, failed) |
| Explicit ownership and guarded handover | `ProcessingSession`, `SessionOwner` |
| Observer with explicit listeners | `JobCoordinator.Listener`, `SessionListener`, `SkyCatalogueModel` listeners |
| Interaction adapter | `FitsImportInteraction` / `GuiFitsImportInteraction`; `GuiDetectionReportPrompt`: decisions on the EDT, work on a worker |
| Request and result objects | `SpacePixelsPipelineRequest` / `Result`, `IterativeDetectionRequest`, `DetectionRunOptions` |
| Template method | `SequenceViewerFrame.prepare(...)` |
| Newest request wins | `SequenceViewerFrame`, `StretchPreviewRenderer`, `AstapInstallationCheck` |
| Staged publication | `StagedFile`, `AtomicReportFile` |
| One writer per report section | the `…SectionWriter` classes |
| Settings table | the `DetectionSetting` definitions, one table per settings page |

## Diagnostics

`-PspacepixelsDebug` on `run` or `batchDetect`, or `-Dspacepixels.debug=true` on the JVM, turns on `DebugOutput`: progress, per-frame statistics and JTransient's diagnostics. Without it, the console shows only warnings and errors. `FailureDialog` offers Copy details with the versions and the stack trace.
