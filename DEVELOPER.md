# Developer overview

An onboarding map for developers and agents: how to run SpacePixels, where responsibilities live, which patterns protect its workflows, and how to validate changes. Current as of 2026-10-22.

Update this overview in the same change whenever package responsibilities, entry points, ownership, threading, persistence, development commands or design patterns change. Describe implemented behavior here; keep proposals and acceptance criteria in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Check the implementation and build files when older design records disagree.

Document new Java types (including nested types and tests) with their responsibility, and add concise method/constructor Javadocs covering purpose and important ownership, units or compatibility contracts. Update those descriptions with behavior changes. Both repositories' ignored local `AGENTS.md` files carry this instruction; this guide and JTransient's README preserve the convention in version control.

## Start here

Read any applicable `AGENTS.md` before changing code. Local tool paths, sibling-build requirements and test-data locations can differ between machines. On Windows, consult the machine's `setpaths.bat` as directed there; it changes the working directory, so return to this repository before running project commands.

Use **Java 21** for development and the checked-in Gradle wrapper (`gradlew.bat` on Windows, `./gradlew` on Linux/macOS). SpacePixels is one Gradle Java module containing the desktop application, public API and command-line tools. Configure the sibling libraries below before building.

| Read | For |
|---|---|
| [MANUAL.md](MANUAL.md) | User workflow, input requirements, settings and report behavior |
| [API.md](API.md) | Supported embedding contract, requests, results and preparation policy |
| [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md) | Detailed components, processing flows and data contracts |
| [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) | Open design decisions, remaining architecture work and proposed features |
| [PUBLISHING.md](PUBLISHING.md) | Release credentials, signed artifacts and Maven Central bundles |

From the repository root, after setting up local dependencies:

```powershell
.\gradlew.bat test
.\gradlew.bat run
.\gradlew.bat installDist
```

`run` starts `gui.ApplicationWindow`; `installDist` assembles the application and tool launchers under `build/install/SpacePixels`. ASTAP and Astrometry.net are configured through Astrometry Config when plate solving is needed. See [README.md](README.md) for packaged launchers and CLI examples.

## Local library development

Active development uses both sibling checkouts: keep `useLocalJTransientBuild=true` and `useLocalJPlateSolveBuild=true` in the ignored `gradle.properties`, following `AGENTS.md`. Without those flags, Gradle resolves the published versions declared in `build.gradle`. Each library has its own composite-build flag in `settings.gradle`:

| Gradle property | Local checkout | Alternative environment variable |
|---|---|---|
| `useLocalJTransientBuild=true` | `../JTransient` | `USE_LOCAL_JTRANSIENT_BUILD=true` |
| `useLocalJPlateSolveBuild=true` | `../JPlateSolve` | `USE_LOCAL_JPLATE_SOLVE_BUILD=true` |

For example, on Windows:

```powershell
.\gradlew.bat run -PuseLocalJTransientBuild=true -PuseLocalJPlateSolveBuild=true
```

Gradle builds the enabled library from its checkout and substitutes it for the published dependency, so changes are available without publishing a jar. The enabled checkout must exist. For a fresh checkout without `gradle.properties`, copy [gradle.properties.example](gradle.properties.example) and change both flags from `false` to `true`. Preserve any existing local configuration. Reload the Gradle project in IntelliJ after changing the flags.

An explicit Gradle property takes precedence over its alternative environment variable. Use `-PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false` to select both published dependencies even when local flags are enabled. Validate that combination separately before a release.

## The packages

SpacePixels prepares the frames, runs the work, and presents the results. Detection itself is the **JTransient** library, a separate project. All packages are under `eu.startales.spacepixels`.

JTransient owns extraction, masks, quality filtering, linking, photometry and Auto-Tune algorithms; JPlateSolve owns local/cloud solver execution and unsuccessful-solve cleanup. SpacePixels owns their orchestration, FITS preparation, session state, settings, viewers and reports.

| Package | What it holds | Main classes |
|---|---|---|
| `gui` | Swing views, shared commands and their availability, settings editors and frame viewers | [ApplicationWindow](src/main/java/eu/startales/spacepixels/gui/ApplicationWindow.java), [MainApplicationPanel](src/main/java/eu/startales/spacepixels/gui/MainApplicationPanel.java), [DetectionConfigurationPanel](src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java), `CommandAction`, `CommandAvailability` |
| `jobs` | Runs long operations one at a time, with no Swing | [JobCoordinator](src/main/java/eu/startales/spacepixels/jobs/JobCoordinator.java), `Job`, `JobOutcome`, `JobFailures` |
| `tasks` | The long operations themselves, one class per job | `FitsImportTask`, `DetectionTask`, `PlateSolveTask`, `BlinkImagesTask`, … |
| `util` | Processing facade, import/preparation, pipelines, FITS/WCS, solver adapters and file replacement | [ImageProcessing](src/main/java/eu/startales/spacepixels/util/ImageProcessing.java), `FitsImportService`, `FitsPreparationService`, `FitsBatchService`, `StandardDetectionPipelineService`, `IterativeDetectionPipelineService`, `PlateSolveService`, `StagedFile` |
| `util.reporting` | The HTML report and iterative result index | [DetectionReportGenerator](src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportGenerator.java), `ReportData`, `IterativeDetectionIndex`, the `…SectionWriter` classes |
| `util.skycatalog` | Fetching and storing the sky catalogue, and the plate-solution correction | `SkyCatalogueClient`, `SkyCatalogueStore`, `PlateCorrectionFitter` |
| `api`, `tools` | The public Java API and command-line clients | [DefaultSpacePixelsPipelineApi](src/main/java/eu/startales/spacepixels/api/DefaultSpacePixelsPipelineApi.java), `SpacePixelsPipelineRequest`, `SpacePixelsPipelineResult`, `BatchDetectionCli`, `ArtificialStarInjector` |
| `session` | Session identity, owned state and guarded handover (no Swing/`gui` dependencies) | `ProcessingSession`, `SessionOwner`, `SessionFrames`, `SessionStorage`, `SessionPersistence`, `SkyCatalogueModel`, `AutoTuneState` |
| `config` | Application settings, ordered preference writes, detection profiles, visualization preferences, compatibility I/O and validation rules | `ApplicationSettings`, `SettingsStorage`, `AppConfig`, `SpacePixelsDetectionProfileIO`, `SpacePixelsSettingsFiles`, `DetectionSettingsRules` |

The main processing paths are:

- **The GUI path:** a `gui` command → `jobs.JobCoordinator` executing a `tasks` job → `util` services → JTransient/JPlateSolve.
- **The headless path:** `api` and `tools` → `util` → JTransient.

`ApplicationWindow` owns application settings, session handover and storage; `MainApplicationPanel` wires commands and job outcomes. `session` holds dataset state and lifetime; `config` holds configuration contracts and persistence. Dependencies are supplied through constructors and explicit callbacks/listeners. There is no global event bus or DI framework.

The entire `util` tree, including reporting and sky-catalogue helpers, has no Swing/`gui` dependencies; headless AWT image/graphics rendering is allowed. `PlateSolveService` and `XisfImageConverter` use their own `java.util.logging` loggers, never `ApplicationWindow.logger`. `FitsPreparationService` shares sequence inspection, readiness facts, header metadata loading and normalized FITS writes between interactive import, strict headless loading and automatic API preparation. Imports use the bounded worker pool; automatic preparation uses the same inspection serially. GUI choices and API refusal/automatic policy retain their existing output layouts and negative-value decisions. Both paths use `XisfImageConverter` for XISF discovery/conversion. Combining compressed-32-bit GUI preparation into one operation remains F07.

**Explicit interaction adapters (S13).** `ApplicationWindow` supplies `GuiFitsImportInteraction` to `FitsImportTask`; the task forwards it to `ImageProcessing.getInstance(folder, settings, interaction)` and exposure-group decisions. Questions/progress components live on the EDT; file inspection, conversion and copying remain on the worker. Existing factories without interaction now use strict headless validation, including when `getFitsfileInformation()` is called implicitly. They never open a conversion dialog; unsupported input must be prepared first. The deprecated exposure-group overload without interaction declines without copying.

`MainApplicationPanel` supplies `GuiDetectionReportPrompt` callbacks to normal and iterative detection jobs. The adapter asks only above 50 classified detections, using each iterative pass's actual subset/WCS and effective settings. It accepts worker or EDT callers, declines dismissal/failure/interruption, and preserves interruption. An interrupted wait cannot apply a later answer; an already visible modal question still needs to be answered/closed. Accepted checkboxes update only `DetectionSummary.includeInReport(ReportInclusion)`, never detection settings/results; the service exports the chosen groups. Task constructors without a callback are noninteractive and decline individual reports above 50; an explicit null callback exports without asking, as the pipeline API already does. The deprecated `DetectionSummary.confirmReport(...)` signature remains a noninteractive threshold guard, not a hidden GUI entry point. Use the explicit adapters for desktop workflows; do not add reflection, a global service locator or a GUI fallback in services.

Other useful locations are `src/main/resources` (icons and build-version metadata), `src/dist/config` (the distributed default detection profile), `src/launchers` (distribution wrappers), and `src/test/java` (JUnit tests, GUI fixtures and opt-in diagnostics).

**Input contract:** detection consumes aligned, equally sized monochrome frames with unsigned-16-bit-equivalent pixels (`BZERO=32768`, `BSCALE=1`), held in Java `short[][]` buffers. `FitsFileInformation` carries frame metadata and timing. Reuse the HDU helpers behind `ImageProcessing.getImageHDU(...)`: compressed FITS can have an empty primary HDU. Preparation must preserve timing/WCS metadata, registration padding and the sequence-wide offset policy recorded in `SPOFFSET`.

## How a user action runs

Eleven foreground workflows use the **job** pattern: import, Convert to Mono, Batch Stretch, plate solve, writing the WCS header, Blink, Auto-Tune, Detect, Detect Iteratively, Manual Transient Inspection, and the Test Star Mask stack.

`CommandAvailability` derives enabled state and an explanation from session facts, selection and work in progress. `CommandAction` shares that policy and execution across buttons, shortcuts, table menus and Overview controls, and checks availability again when invoked. Add availability rules there rather than deriving them from another button's enabled flag.

Plate solve uses JPlateSolve **1.0.1**, resolved from the local sibling during development. `PlateSolveService.startSolve` returns a cleanup-aware `PlateSolveOperation`. `PlateSolveTask` forwards cancellation, reports phase/elapsed text, and joins its completion even when interrupted; it never releases the job slot just because a legacy future was cancelled. Budgets are the library defaults (ASTAP 2 minutes, Astrometry.net 15 minutes). Remote cancellation is local-only: an accepted server job may continue. Failed solves already own their cleanup, so the GUI must not delete sidecars restored by JPlateSolve. Successful solution cleanup after WCS import stays in SpacePixels. `ImageProcessing.close()` closes the cloud client with its session.

1. **The command, on the Swing thread (EDT).** Its handler captures everything the job needs:
   - a validated copy of the settings, from `DetectionConfigurationPanel.checkedSettings(...)`;
   - the relevant files (selection or full sequence, according to the command);
   - the current session.
2. **Admission.** The handler calls `JobCoordinator.start(kind, session, job, handler)`. The coordinator reserves the slot before starting the worker and refuses overlapping foreground work.
   - If it admits the job, its listener in `MainApplicationPanel` locks the window and shows the `ProcessingProgressDialog` at once. The texts for each kind of job are in `MainApplicationPanel.JobTexts`.
3. **The work, on a worker thread.** The task (for example `DetectionTask`) implements `Job<R>`. It reports progress through `JobContext.progress(...)` and returns a `JobOutcome`:
   - `Completed`, with the result;
   - `Declined`, when the user said no during the job;
   - `Cancelled`, when the user pressed Cancel in the progress dialog;
   - `Failed`, with a reason.

   An exception or `Error` becomes `Failed`, except for the fatal out-of-memory policy below.
   - **Cancel:** `JobCoordinator.cancel()` makes the job's next progress report throw `JobCancelledException`. Engine tasks also bind the job's cancellation flag through JTransient's `CancellationScope`, which reaches its computation and worker waits between progress messages. The engine joins running work before propagating cancellation; the coordinator recognises any wrapped `CancellationException` and returns `Cancelled`. Final job progress at 100% never throws. Each `JobKind` says whether it can be cancelled, and services remove their unfinished output through `Cancellation.isCancellation(...)`.
   - **Out of memory:** goes to the application's fatal-error handler, which closes SpacePixels. Further job progress checks stop work after a fatal memory failure.
4. **The outcome, back on the EDT.** It arrives exactly once, after the window is unlocked. The handler that was passed in at the click shows what follows: the report question, the inspection window, the imported session, or an error message (`FailureDialog`, with Copy details).

**Example: Detect Moving Targets.**
1. `MainApplicationPanel` starts a `DetectionTask`.
2. The task calls `ImageProcessing.detectObjects(...)`, which runs `StandardDetectionPipelineService`.
3. That service loads the frames and runs `JTransientEngine.runPipeline(...)`.
4. `DetectionReportGenerator` writes the report.
5. On the EDT, `showDetectionOutcome(...)` offers to open it.

**One exception: Blink.** Loading the frames is the job. Once loaded, the blink window keeps the main window locked until it closes.

**Other background work has separate ownership:** sky-catalogue fetching and correction fitting use a session-owned worker with their own progress and cancellation, and can overlap a foreground job. Session metadata uses the window's serialized storage queue. Application preferences use the separate window-owned `SettingsStorage` writer. ASTAP installation checks use `AstapInstallationCheck`, with one active inspection and at most the newest pending request; filesystem checks and the selected executable's help launch run off the EDT. Viewer preparation uses `SwingWorker`; stretch previews use `StretchPreviewRenderer`, with one active render and at most the newest pending request. These paths use explicit identity checks before applying results.

**Standard and iterative detection share one run/export path.** `IterativeDetectionDialog` chooses an independent directory and previews Start/Increase/Maximum pass sizes using a background directory scan. `IterativeDetectionRequest` defensively captures the current validated detection settings, application/site configuration and immutable export settings. `IterativeDetectionPlan` selects each size across the full observing interval, including endpoints and falling back to index spacing when necessary; it includes the exact capped maximum.

`IterativeDetectionPipelineService` validates/prepares inputs through the shared services, optionally runs calibrated Auto-Tune once, and sequentially calls `StandardDetectionPipelineService.run(...)` with subset metadata and a separate output directory. Every pass builds its own master, retains configured analyses and normal readiness checks, and uses local frame indices throughout detection and export. Each pass has a private `SessionFrames` store that is cleared afterwards. Large buffers and results are not retained across passes; complete input manifests live on disk, while the index retains lightweight summaries.

Directory-based iteration is available before import. Its job uses the existing coordinator and captures the current session identity for UI callback guards, but its inputs and processing state are independent. It never installs a session, changes the current catalogue/selection/overlays, or replaces the current session's last-report reference. Manual experimentation stays in the normal workspace; the configuration dialog explains how to import normally and use Preview Frame before running with Auto-Tune disabled. The legacy `ImageProcessing.detectSlowObjectsIterative(...)` entry point remains, using start/increase 5 and the supplied maximum, and delegates to the same independent passes.

`IterativeDetectionIndex` publishes `index.html`, versioned `run-data.json` and separate per-pass input manifests with filenames, dimensions, exposure and timestamps. It records completed, report-declined, failed, cancelled and unstarted passes. A failed or declined pass does not prevent later passes; cancellation removes unfinished output while retaining completed work. Counts describe individual passes, with no cross-pass identity consolidation. Future [F09 enhancements](DESIGN-IMPROVEMENTS.md#directory-based-iterative-detection-f09) cover a combined findings report.

## The session

An imported dataset has an explicit identity, owns its state and has a defined lifetime. This boundary delivers several practical benefits:

- **Dataset isolation:** catalogue, plate correction, selection, tuning results, caches and report references belong to their session. Late progress and results from session A cannot overwrite session B after an import.
- **Safe dataset switching:** a failed, declined or cancelled import preserves the current session; only a successful, still-current import replaces it.
- **Stable preferences:** application settings are available before import, and unsaved preference edits survive subsequent imports.
- **Consistent viewers:** reusable viewers release old-session data during handover. An independent inspection viewer retaining the old dataset keeps its matching catalogue, and its callbacks cannot edit the replacement session.
- **Responsive storage and predictable recovery:** session metadata reads and writes run off the EDT. Ordered saves and guarded acknowledgements prevent obsolete failures from undoing newer edits; correction-save failures can restore the last saved correction.
- **Controlled resource lifetime:** session closure stops its catalogue worker and releases owned processing resources and caches. Large image buffers are shared by ownership rather than deep-copied, and queued saves survive session closure.
- **Testable ownership:** session transitions, stale callbacks, isolation and cleanup can be checked without constructing Swing windows. Dependency rules keep the session package free of Swing and `gui` references.

This also provides a foundation for persistent run history, reopening saved detections and session-aware agent services. Those remain proposed features in [D11](DESIGN-IMPROVEMENTS.md#d11-make-the-main-window-a-clear-analysis-workspace) and [F04](DESIGN-IMPROVEMENTS.md#mcp-agent-services-f04). Implementation and validation records are archived under [S7](docs/archive/design-improvements-completed-2026-10.md#s7-explicit-session-ownership-and-application-preferences-2026-10-21) and [S8/S10](docs/archive/design-improvements-completed-2026-10.md#s8-and-s10-background-session-storage-and-fits-service-boundaries-2026-10-21).

`ApplicationWindow` loads `ApplicationSettings` once, before import. Its `AppConfig` is shared with each imported processor; importing never reloads it or discards unsaved Astrometry Config edits. The tab is available before import. The existing `ImageProcessing.getInstance(File)` entry point still loads standalone preferences for API/CLI callers; desktop import injects the window's settings explicitly.

`session.SessionOwner` owns the current `ProcessingSession`. Each session has a UUID, shared frame metadata references, a protected selection, its own `SkyCatalogueModel` (catalogue/correction), `AutoTuneState`, master-stack cache and last-report reference. The processing facade delegates import to `FitsImportService` and derivative operations to `FitsBatchService`. `SessionFrames` is shared by the session, import and pipeline services; it holds metadata and the last track/variable overlays, not a second copy of their buffers. Arrays of metadata are copied defensively, not image buffers.

Session handover and mutable selection/catalogue updates run on the EDT. Workers prepare data and return through explicit completion adapters; the `session` package's lack of Swing imports does not imply general thread safety.

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
- **When edits apply.** Edits change the session's `DetectionConfig` at once. Save captures the validated profile, visualization options and row baselines on the EDT, then queues `SpacePixelsSettingsFiles` on `SettingsStorage`. Only successful completion advances the saved baseline to that snapshot: later edits stay unsaved, and Revert/row Reset use the written values. Save is disabled while pending, but editing remains available; failure retains edits and the existing partial-save explanation.
- **Runs use a copy.** Every run takes its own validated copy, so later edits never change a running job. Preview Frame takes a fresh copy for each frame it detects, and edits the per-frame thresholds on the tab's own spinner models.
- **The astrometry settings** (ASTAP path, observing site) belong to application-scoped `ApplicationSettings`, edited in `ConfigurationPanel` before or after import. Site Save and ASTAP/star-depth autosaves queue detached `AppConfig.copy()` snapshots on the same writer. Successful writes update saved markers from those snapshots, not live fields; failed writes leave valid values in use and visibly unsaved. Newer ASTAP requests supersede stale results, and closing drops check callbacks. The JSON format is unchanged.

`SettingsStorage.submit(target, action)` serializes preference writes and migration backups independently of session metadata; the worker must receive captured data, never read Swing controls or live mutable configuration. Completions return through the EDT and are suppressed after window closure. Failures are retained by target until a successful retry; window close drains both settings and session writes on a background shutdown thread before disposal/exit, with failure feedback and an explicit wait-or-exit choice if draining times out. Window-placement writes/flush also use this queue. The standalone synchronous `ApplicationSettings.save()` compatibility method remains available outside GUI callbacks. Startup preference/profile reads still use the existing initialization path.

JTransient's `config.DetectionConfigRules` owns engine-setting metadata, numeric diagnostics and cross-field policies. Its metadata covers all 107 `DetectionConfig` fields, including hidden fields, with types, defaults, stages and recommended domains. `validate` returns structured issues without mutation; `snapshot` clones and rejects error-level issues; `align` returns a detached copy and structured adjustments with field/rule IDs, direction and old/new values. Only non-finite values are new hard errors; finite legacy combinations and sentinels remain accepted. Operation scopes follow actual consumers, including different calibrated/legacy tuner read sets. Profile/API boundaries use `CONFIGURATION`, requiring all persisted numeric values to be finite even for disabled stages.

`checkedSettings(...)` validates editor text and consumes the shared engine rules while preserving hidden fields. Profile loading, API request construction and iterative requests use the same service. The panel binds the original six pairs to engine rule IDs and requests `LEGACY_GUI` alignment, including the historical rhythm/jump policy; Preview Frame shares those spinner models. No numeric pair comparisons belong in Swing code. `DetectionSettingsPresentation` owns titles and messages; deprecated `DetectionSettingsRules` is only a compatibility presentation adapter. Optional JTransient fields still use compatibility reflection in the panel (remaining D10 work).

**Review rules…** shows configuration-only diagnostics, not a dataset-readiness verdict. New quality-grow, geometric jitter/jump, photometry-cap and limited-amplitude conflicts are advisory. Its explicit `REVIEWED_CONSISTENCY` choice can lower hidden quality grow or raise the limited amplitude; Keep settings leaves those preferences unchanged. This policy deliberately excludes rhythm/jump, which is not a universal engine invariant after point pruning. It never rewrites jitter, star caps or annulus factors. Keep validation, Save, Revert, defaults and Auto-Tune updates consistent, including hidden values.

The engine and tuners capture their caller's primitive config once at entry and derive private stage copies. This isolates later edits, not simultaneous multi-field writes during capture; callers must synchronize those. SpacePixels uses the new `tuneSnapshot` APIs, whose fallback config is detached. Legacy `tune` entry points intentionally retain the original base reference on unsuccessful results. Global tuning options remain separate JA-04 work.

JTransient's `PhotometryGeometry.resolve(config, fwhm)` owns effective pixel radii: the 1 px aperture floor, fixed comparison apertures through 2.5 FWHM, inner sky at least 1 px outside the largest aperture and at least 2 px sky width. Selection, measurement, detection/track/slow-mover crossing checks use that geometry. `adjustments(config, fwhm)` exposes structured pixel-radius expansions for diagnostics without changing persisted factors. See the [implemented rule-review decisions](docs/detection-settings-rule-review.md#implementation-decisions-ja-01-2026-10-22).

The calibrated Auto-Tuner is the default; the legacy tuner remains available through `AutoTunerRunner`. Legacy tuning temporarily changes shared JTransient state, so concurrent legacy tuning runs in one JVM are unsupported.

These APIs require the adjacent JA-01 JTransient source (declared `1.0.5`, still unreleased). A published `1.0.5` coordinate alone is not evidence that the required API exists; publish and validate the supported dependency pair before distributing without composite builds.

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

`report-data.json` is a versioned findings summary; its frame numbers are one-based and finding IDs are local to a report. It is not yet a complete replay record. Report category selection controls presentation independently of the analyses and filters that produced the measurements. Some diagnostics still measure frames during export, and category/label rules are not yet fully consolidated (D09, S15/S16).

## Headless: the API and the command line

`DefaultSpacePixelsPipelineApi.run(...)` runs the whole pipeline without a window:
1. **Prepare:** prepare the input with `DetectionInputPreparation`, following the request's policy; it asks no questions.
2. **Check:** check the frames strictly (`getFitsfileInformationHeadless`).
3. **Tune:** optionally run Auto-Tune.
4. **Detect:** run the detection.
5. **Report:** optionally write the report.

The API is synchronous and closes its processor in `finally`; it does not drive desktop commands or install a GUI session. `FAIL_IF_NOT_READY` rejects inputs needing preparation; `AUTO_PREPARE_TO_16BIT_MONO` creates detection-ready output according to the request's negative-value policy. `BatchDetectionCli` is a thin client of the API; Gradle exposes it as `batchDetect` with `-PbatchArgs=...` (examples in [README.md](README.md)).

The supported embedding surface is `eu.startales.spacepixels.api`, with the request/result types it exposes as documented in [API.md](API.md). The distributed JAR also contains GUI and internal services; their presence does not make them supported embedding contracts.

S13 makes API execution Swing/GUI-independent, not its publication API-only: `build.gradle` still publishes the entire Java component with desktop code and the FlatLaf dependency. [F11](DESIGN-IMPROVEMENTS.md#headless-apicore-maven-central-artifact-f11) proposes a minimal headless API/core publication with a complete supporting-code/resource closure and separate dependency metadata. Headless AWT rendering remains necessary; do not simply package `api` alone or remove `java.desktop`.

## Persistence and outputs

| Location | Contents |
|---|---|
| `user.home/spacepixels_app.json` | Application preferences: solver configuration, observing site and astrometry metadata |
| `user.home/spacepixels_detection_profile.json` | Detection configuration, Auto-Tune frame limit and variable-star report presentation options |
| `user.home/spacepixels_visualization.json` | Visualization/export preferences |
| Imported dataset folder | `sky_catalogue.json`, `plate_correction.json`, `auto_tune.json`; restored with field, solution or frame/dimension compatibility checks |
| Per-run output folder | `detection_report.html`, `detection_config.json`, `report-data.json`, CSVs, images and GIFs; iterative output also has per-pass folders, `index.html`, `run-data.json`, `<count>_inputs.json`, and optional `auto_tune.txt` |
| Java user preferences | Window placement and display/stretch preferences, separate from the JSON files |

The legacy profile name `spacepixels_jtransient.json` remains readable. Profile migration preserves a legacy source or makes a backup before replacement. Use the existing I/O classes so migrations and hidden settings survive changes.

`StagedFile` writes beside the destination and publishes only complete files; `AtomicReportFile` uses that boundary for report text. Saving a detection profile and visualization preferences stages both first, but the two replacements are independent: if the second fails, the new profile remains saved and the error identifies the unsaved file. Report assets go into a new directory per run; cancellation removes unfinished output owned by that run and preserves completed iterative reports.

## Testing and diagnostics

Use the Gradle wrapper from the repository root. Tests use **JUnit 4**. The ordinary `test` task excludes `gui/mechanics`; real-data checks are opt-in and skip without their required configuration. Start with the affected tests, then run the normal suite when the change warrants it.

```powershell
.\gradlew.bat test --tests "eu.startales.spacepixels.session.*" --tests "eu.startales.spacepixels.jobs.*"
.\gradlew.bat guiMechanicsTest
.\gradlew.bat realDataTest -PrealDataRoot="C:/path/to/test-data"
```

- **Logic and boundaries:** `JobCoordinatorTest`, `ProcessingSessionTest`, `SessionStorageTest` and `SessionMetadataStoreTest` exercise ownership, delayed callbacks and slow/failing I/O. [DependencyRulesTest](src/test/java/eu/startales/spacepixels/DependencyRulesTest.java) recursively enforces the established boundaries: jobs have no GUI, AWT or task references; tasks and the entire `util` tree have no GUI/Swing references; session state and extracted FITS services also forbid AWT. Rendering utilities may use headless AWT. `GuiDetectionReportPromptTest` checks the extracted dialog contents, independent category choices, EDT dispatch and noninteractive paths without showing windows; `ReportPromptMechanicsTest` drives the real report and import conversion dialogs.
- **Synthetic inputs:** [SyntheticDatasetFactory](src/test/java/eu/startales/spacepixels/testsupport/SyntheticDatasetFactory.java) builds disposable FITS datasets. Keep settings and input fixtures isolated from the observer's files.
- **Preparation parity:** `FitsPreparationParityTest` compares GUI/API pixels, timing and WCS/scaling headers across signed/scaled, integer/float, mono/colour, compressed and XISF inputs. It checks offset/clipping decisions, original-file preservation, strict refusal and declined conversion; `FitsServiceTest` also protects legacy output names and colour derivatives.
- **Iterative detection:** `IterativeDetectionPlanTest` checks pass bounds and sampling. `IterativeDetectionRunTest` compares actual findings, rejection indices, settings and CSVs against independent normal runs on the same subsets, and checks frozen settings, one-time tuning, failure/decline/cancellation and manifests. The opt-in `IterativeDetectionMechanicsTest` checks the dialog and preservation of an open session and unsaved settings.
- **Preference responsiveness:** `SettingsStorageTest` gates ordered writes to check snapshots, failures/retries and draining without blocking the EDT. `AstapInstallationCheckTest` checks background scanning, latest-only requests, stale failures and closure. `SettingsNavigatorSnapshotTest` checks save-time row baselines. The opt-in `SettingsIoMechanicsTest` exercises real panels with delayed writes, newer edits, application autosaves, partial-save failure/retry and window close; synthetic imports use the application entry point, not native chooser automation.
- **GUI mechanics:** `guiMechanicsTest` requires an interactive desktop and uses the mouse for native-input cases. Its fixtures use a scratch `user.home` and restore Java preferences; changing `user.home` alone does not isolate Java preferences. Direct-EDT lifecycle checks and native-input wiring checks cover different behavior.
- **Real data:** `realDataTest` runs the preparation/API/CLI integration harness on dataset subdirectories of `realDataRoot`. Use disposable copies: preparation and CLI export can create working directories and reports beside the data. The checked-in default path belongs to another environment; verify local paths through `AGENTS.md`. Other diagnostics under `src/test/java/.../tools` have their own opt-in settings.

`-PtestMaxHeap=6g` increases the heap for Gradle `Test` tasks; it does not configure the `realDataTest` JavaExec task. Application and tool launchers normally allow up to 80% of host RAM; `batchDetect` also supports `-PbatchMaxHeap=8g`.

For console diagnostics, use `.\gradlew.bat run -PspacepixelsDebug` or set `-Dspacepixels.debug=true` on the application JVM. `DebugOutput` controls per-frame details, timings and engine debug flags; normal application logging shows warnings and errors. `FailureDialog` offers Copy details with versions and the error.

## Principles

1. **JTransient owns detection.** SpacePixels does not change detection behaviour; engine changes belong in that project.
2. **The EDT is for the window.** Swing is touched only on the EDT; processing and file/network work use their owned workers. Preserve the dependency boundaries enforced by `DependencyRulesTest`.
3. **Every job ends once,** with its result or a reason. No failure leaves the window locked.
4. **Capture run inputs.** Settings and metadata containers are copied when the run starts; large image buffers have explicit owners and are not deep-copied for history or convenience.
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
| Explicit ownership and guarded handover | `ProcessingSession` and `SessionOwner`: session state, replacement and stale-result admission |
| Serialized persistence with guarded acknowledgements | `SessionStorage` and `SessionPersistence`: ordered reads/writes and versioned correction recovery |
| Observer, with explicit listeners | `SessionListener`, `JobCoordinator.Listener`, `SkyCatalogueModel` |
| Facade | `ImageProcessing`, in front of the import, pipeline and plate-solve services |
| Interaction adapter | `FitsImportInteraction` and `GuiFitsImportInteraction`: decisions on the EDT, FITS work on a worker |
| Shared application service | `FitsPreparationService`: common sequence inspection, readiness facts and normalized writes behind GUI/API policies |
| Strategy | `AutoTunerRunner.Algorithm`: calibrated and legacy tuning behind a common call |
| Request objects and defensive snapshots | `SpacePixelsPipelineRequest`, `IterativeDetectionRequest`, `checkedSettings(...)`, `ExportVisualizationSettings` |
| Template method | `SequenceViewerFrame.prepare(...)`, implemented by each viewer |
| Newest request wins | `SequenceViewerFrame`, `StretchPreviewRenderer` |
| Staged publication | `StagedFile`, `AtomicReportFile`: publish complete output and preserve the previous file on failure |
| One writer per section | the report's `…SectionWriter` classes |
| Planner and sequential orchestration | `IterativeDetectionPlan`, `IterativeDetectionPipelineService` and `IterativeDetectionIndex`: ordinary runs over independent input subsets |

## What comes next

The open steps from the architecture review, in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md#open-steps-from-the-architecture-review), build on completed S13's headless utility boundary:

- **S14:** pass site, rendering options and the Auto-Tune frame limit in an explicit run context.
- **S15:** consolidate finding labels and category rules.
- **S16:** prepare diagnostics with explicit measured/unavailable/failed states and consistent optional-section failure handling.

S7 session ownership and application preferences, S8 background session metadata I/O, S10 FITS service extraction, and S12 shared GUI/API preparation are implemented. The [completed-work archive](docs/archive/design-improvements-completed-2026-10.md) records their validation and historical limitations; earlier review proposals are not the current implementation. Broader features such as run history, saved-result viewing and MCP services remain in the design backlog.
