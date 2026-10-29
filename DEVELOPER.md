# Developer guide

How SpacePixels is built, how its code is organised, the principles it follows, and how to change it safely. This guide is for developers and coding agents. The implementation sections describe the current code; the principles guide changes. Planned design work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) and planned functionality in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md).

| Read | For |
|---|---|
| [MANUAL.md](MANUAL.md) | What the application does, for users |
| [API.md](API.md) | The supported Java API and its requests and results |
| [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md) | Components, processing flows and data contracts in detail |
| [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) | Pending design and refactoring work, and the latest code review |
| [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) | Pending functional requests and their decisions |
| [PUBLISHING.md](PUBLISHING.md) | Desktop packages, Maven publication and the API consumer test |
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
.\gradlew.bat releaseCheck         # full release checks; needs Node and an unlocked desktop
.\gradlew.bat run                  # the desktop application
.\gradlew.bat installDist          # application and tool launchers under build/install/SpacePixels
```

Headless batch run of one dataset (it prints `Report file: ...` and any problems):

```bash
./gradlew -q batchDetect -PbatchMaxHeap=11g -PbatchArgs="\"<folder>\" \"<profile.json>\""
```

Add `-PspacepixelsDebug` (or `-Dspacepixels.debug=true` on a plain `java` command) for progress, per-frame statistics and the engine's debug output.

**Source sets.** `main` is the headless API and CLI artifact (`eu.startales.spacepixels:spacepixels`): `api`, `config`, `session`, `fits`, `preparation`, `detection`, `platesolve`, `skycatalog`, `report`, `util` and `tools`, with no Swing and no FlatLaf. `desktop` holds `gui`, `jobs`, `tasks` and the icons, and is not published. `run`, `installDist` and the desktop launchers use both; the CLI launchers use only `main`. `check` verifies the published jar, its POM and Gradle metadata, and that the two source sets own disjoint classes.

**Sibling libraries.** The required library versions are ordinary dependency declarations in `build.gradle`; update them there when selecting dependencies for a SpacePixels release. During development, both libraries can instead be built from their sibling checkouts. Set `useLocalJTransientBuild=true` and `useLocalJPlateSolveBuild=true` in the git-ignored `gradle.properties` (copy [gradle.properties.example](gradle.properties.example)). The environment variables `USE_LOCAL_JTRANSIENT_BUILD` and `USE_LOCAL_JPLATE_SOLVE_BUILD` also work; an explicit `-P` property wins. Without the flags, Gradle resolves the declared dependencies from Maven Central.

**Releases.** Run `releaseCheck` before distributing desktop packages; `mavenCentralBundle` includes it automatically. It adds GUI mechanics, required report JavaScript checks, API consumers using both Gradle and Maven POM metadata, and the available small real-data smoke tests to the ordinary checks. Node and an unlocked desktop are required; missing optional datasets are reported as skips. Desktop distributions bundle the library JARs and can use sibling checkouts. For Maven Central publication, publish the library dependencies first and disable both composite flags. See [PUBLISHING.md](PUBLISHING.md).

### Dependencies

The build uses Gradle 9.8.2, nom-tam-fits 1.23.0, Gson 2.14.0 and FlatLaf 3.7.2. `UiStyle.spinner` keeps the caret where the user puts it when a number field gains focus.

FITS HDU creation uses the public `Fits.makeHDU(Object)` API in pixel conversion, XISF import, artificial-star injection and test fixtures.

The application and both libraries compile for Java 21. The Windows installer bundles a Java 21 runtime.

### Windows installer

`gradle/windows-installer.gradle` supplies two opt-in Windows x64 tasks: `windowsAppImage` produces `build/windows/image/SpacePixels/SpacePixels.exe` and a private Java runtime; `windowsInstaller` wraps that image in `build/installers/SpacePixels-<installer-version>.exe`. Use a 64-bit JDK 21. The installer task also needs WiX 3 build tools (`candle.exe`, `light.exe`), found through `-PwixHome=<folder>`, `WIX_HOME`, `WIX`, or PATH. Store local paths in git-ignored `gradle.properties`, not in the build script. See [PUBLISHING.md](PUBLISHING.md#windows-installer) for commands and release validation.

The tasks reuse `installDist`'s complete JAR set and all shared distribution files, including the default detection profile, appearance example and license. Archive launcher scripts are replaced by `SpacePixels.exe`, `spacepixels-batch.exe` and `injectStars.exe`; both command-line tools have console launchers. The multi-size ICO embeds the existing PNG icons without changing their pixels. `jpackage` links the packaging JDK's Java 21 runtime; native `java`/`javaw` commands are retained for Appearance's helper-based restart, and runtime legal notices stay included. All three launchers keep the existing memory/G1 options.

The installer uses per-user installation and directory selection, optional desktop and Start menu shortcuts, unsigned output, local Gradle builds, and upgrades through a new installer. The fixed upgrade UUID must not change between releases. The Windows installer uses a numeric version derived from the application version; `-PwindowsInstallerVersion=major.minor.build` can override it (Windows limits: 255.255.65535). Home settings and datasets are kept outside the installation folder and are not installer payload or cleanup targets. There is no automatic updater.

## The packages

All packages are under `eu.startales.spacepixels`.

| Package | What it holds | Main classes |
|---|---|---|
| `gui` | Swing views, shared commands and their availability, settings pages, frame viewers | `ApplicationWindow`, `MainApplicationPanel`, `DetectionConfigurationPanel`, `CommandAction`, `CommandAvailability`, `SequenceViewerFrame`, `BlinkFrame` |
| `jobs` | Runs long operations one at a time; no Swing | `JobCoordinator`, `Job`, `JobOutcome`, `JobKind` |
| `tasks` | The long operations, one class per job | `FitsImportTask`, `DetectionTask`, `PlateSolveTask`, `AutoTuneTask`, … |
| `session` | The imported dataset: identity, owned state and handover; the application I/O queue; no Swing | `ProcessingSession`, `SessionOwner`, `SkyCatalogueModel`, `AutoTuneState`, `ApplicationStorage` |
| `config` | Application settings, detection profiles, visualization settings and their files; the typed setting definitions | `ApplicationSettings`, `AppConfig`, `SpacePixelsDetectionProfileIO`, `DetectionSetting`, `DetectionRunOptions`, `VisualizationSettings` |
| `fits` | FITS/XISF input, validation, preparation, pixel converters, display stretch, WCS | `FitsFileInformation`, `FitsImportService`, `FitsBatchService`, `FitsInput`, `FitsPixelConverter`, `FitsPreparation`, `WcsCoordinateTransformer` |
| `preparation` | External observing-session preparation, conservative input inventories, metadata preservation and common output conversion; no Swing or session state | `FramePreparationService`, `PreparationRequest`, `PreparationProcess`, `SirilHeaderCorrections`, `PreparedSequenceWriter` |
| `detection` | Detection pipeline services, iterative detection, live preview, auto-tune runners, overlays | `StandardDetectionPipelineService`, `IterativeDetectionPipelineService`, `AutoTunerRunner`, `TrackOverlay`, `VariableStarOverlay` |
| `platesolve` | Plate solving integration and session plate solution correction | `PlateSolveService`, `PlateSolutionCorrection`, `AstrometryNetAccount` |
| `skycatalog` | Fetching and storing the sky catalogue; the plate-solution correction fit; sky snapshots | `SkyCatalogueClient`, `SkyCatalogueStore`, `PlateCorrectionFitter`, `SkySnapshot` |
| `report` | The HTML report, its model, section writers, and lookup proxy/builders (`report.lookup`) | `ReportExporter`, `ReportRequest`, `ReportData`, `FindingCategory`, `ReportLookupProxyServer` |
| `util` | General cross-cutting utilities | `StagedFile`, `Statistics`, `SkyMath`, `BuildInfo`, `Cancellation`, `DebugOutput` |
| `api`, `tools` | The public Java API and the command-line tools | `DefaultSpacePixelsPipelineApi`, `SpacePixelsPipelineRequest`, `BatchDetectionCli` |

Other locations: `src/main/resources` (icons, build metadata), `src/dist/config` (the default detection profile), `src/launchers`, `src/consumerTest` (the isolated API consumer build).

The main paths through the code:

- **GUI:** a `gui` command → `JobCoordinator` runs a `tasks` job → `fits`/`detection`/`platesolve` services → JTransient or JPlateSolve.
- **Headless:** `api` and `tools` → `fits`/`detection` services → JTransient.

**Input contract.** Detection consumes aligned, equally sized monochrome frames with unsigned-16-bit pixels (`BZERO=32768`, `BSCALE=1`) in `short[][]` buffers. `FitsFileInformation` carries each frame's immutable metadata, unmodifiable header map and construction-time normalised timing. Compressed FITS can have an empty primary HDU, so use the HDU helper (`FitsInput.getImageHDU`). Preparation keeps timing and WCS headers, the registration padding, and the sequence-wide offset recorded in `SPOFFSET`.

`FitsInput` owns discovery, image-HDU selection, format/readiness inspection and sequence validation. Its `Input` facts
come from one inspection per file, on the importer's optional owned executor. Strict headless loading checks readiness
before cohort consistency; interactive import preserves its sequence checks before conversion offers.
`FitsPreparation` writes prepared copies through `FitsPixelConverter`; `XisfImageConverter` remains separate.
These are static helpers; `FitsImportService` and `FitsBatchService` retain their instance-owned work.

`ImageScaling` owns one sequence-wide linear scaling policy for ordinary FITS conversion, Siril preparation and XISF import. Format readers supply physical samples and nominal scale; `FitsPixelConverter` owns decoding, registration-fill preservation, RGB reduction and 16-bit quantization. Out-of-headroom samples require an explicit clipping or full-range decision; headless callers reject them by default. `PreparedSequenceWriter` owns source association, metadata and publication. Registration exclusions are recorded without shifting remaining source identities. `RunDirectories` gives reports and preparation runs the same dated, collision-safe naming.

`PlateSolveService` edits the selected physical image header in a staged copy, including a tile-compressed image extension. It preserves every other stored byte, verifies all extensions and the saved logical image metadata, then publishes. It refuses whole-file compressed streams until decompressed.

`FrameCalibrationInspector` reads the first/middle/last frame without changing pixels. Its sharp-spike criterion (above sky + 10 sigma, four-neighbour excess sum below 0.25 of the peak) warns at at least 5 spikes/MP and 30 spikes in a sample.

Alignment uses spaced optical-peak centroids, requiring at least three matches and half of the smaller star set to support one translation above 2.5 pixels. Missing or dimmer stars do not become artificial offsets. Floating samples keep their physical noise scale; only known unsigned-integer samples use integer histogram bins. Pixel-row and matching checks observe `CancellationScope`; unreadable samples fail rather than returning a clean verdict.

Interactive import can decline before session replacement. Both desktop detection workflows supply `PreFlightSafetyPrompt` before tuning/passes; cancellation is the default, with an explicit override. Headless imports log warnings; the public API exposes warning-only or fail-fast policy. Benchmark tests require the explicit `spacepixels.testdata.root` property and never fall back to a machine-specific path.

## How a user action runs

Twelve workflows use the job pattern: observing-session preparation, import, Convert to Mono, Batch Stretch, plate solve, writing the WCS header, Blink, Auto-Tune, Detect, Detect Iteratively, Manual Transient Inspection and the Test Star Mask stack.

1. **The command, on the EDT.** `CommandAvailability` decides from session facts whether the command can run, and why not. `CommandAction` is the one Swing `Action` behind the command's button, shortcut, menu item and Overview button. The handler captures what the job needs: a validated copy of the settings (`DetectionConfigurationPanel.checkedSettings`), the files, the run options and the session.

The window creates the four panels and passes each one the collaborators it uses (`SessionAccess`, the job coordinator, the frame list, the settings readout, storage and the folder import). Panels do not take the window or call each other by type. The settings page publishes the current `DetectionConfig`; the main readout copies the eight Overview values during that EDT call and does not keep the configuration. The wiring lasts as long as the window, so there is no listener to detach. A job still copies the session and settings at admission.
2. **Admission.** `JobCoordinator.start(kind, session, job, handler)` reserves the single job slot or says why it cannot. On admission, `MainApplicationPanel` locks the window and shows `ProcessingProgressDialog`.
3. **The work, on a worker.** The task reports progress through `JobContext.progress` and returns a `JobOutcome`: `Completed`, `Declined`, `Cancelled` or `Failed`. Cancel makes the next progress report throw `JobCancelledException`; engine tasks also bind JTransient's `CancellationScope`, which stops computation between reports. An exception becomes `Failed`, except out-of-memory, which closes the application with a message.
4. **The outcome, on the EDT,** arrives exactly once after the window is unlocked, and is applied only if its session is still the current one (`ApplicationWindow.forCurrentSession`).

Shutdown cancels the current job and waits off the EDT for its worker to return after cleanup, independently of queued outcome delivery. This covers local plate-solver processes, preparation and an already admitted noncancellable WCS write. Accepted storage writes drain afterwards, before exit or committing a restart.

Blink is the exception: loading the frames is the job, and the blink window then keeps the main window locked until it closes. Work outside the job slot has its own owner: the sky-catalogue fetch and correction fit (`gui.SkyCatalogueController`, with one executor per session and a future per request), session and preference writes (single-thread writers owned by the window), ASTAP checks, stretch previews and viewer preparation (newest request wins).

**Observing-session preparation** opens in one application-owned `PreparationWindow`, launched by **Prepare Frames…** above ordinary import. Its panel captures the Siril recipe; `FramePreparationTask` uses the shared job slot and `FramePreparationService` works only on private input copies. Installation checks and input inspection run outside the EDT and discard stale completions; opening the window checks an already configured Siril path automatically. Live script output reaches a bounded progress-dialog log through guarded job detail callbacks, alongside elapsed time. The process owner waits for owned descendants on completion/cancellation; application shutdown also joins preparation cleanup. Successful products are independent of later import outcomes. The optional guarded import closes the preparation window and confirms the new dataset only after successful session installation; failures keep the window available. Pending follow-ups are suppressed after closure or a session change. See [Preparation implementation](docs/features/preparation-integrations.md) for the current contracts and metadata handling.

The catalogue controller owns its progress timer and delivers model changes and status on the EDT. Delivery checks the request identity and the current session even after cancellation, replacement or shutdown. The session owns a non-blocking shutdown callback, with no dependency on the GUI; closing it cancels work, shuts down the executor, stops the timer and detaches the view. A catalogue completed before its correction remains available and saves independently of the fit outcome.

## The session

`SessionOwner` holds the current `ProcessingSession`. A session has a UUID and owns its frame metadata (`ProcessingSession.replaceFrameHeader` replaces a frame's `FitsFileInformation` after a WCS write while existing runs keep their snapshots), selection, sky catalogue and correction (`SkyCatalogueModel`), Auto-Tune state, master-stack cache, last report and last run's overlays, and the services that work on its frames: its plate solver (`PlateSolveService`) and the batch operations on its folder (`FitsBatchService`). Large image buffers are shared by ownership, never deep-copied.

- **Handover.** `FitsImportTask` reads the frames and the saved session data (catalogue, correction, Auto-Tune results) on a worker; each attempt closes its `FitsImportService` before it returns, so the result holds no resources. The window builds the session from that result. Only a completed import is installed, through `SessionOwner.replace(expected, candidate)`, which rejects a stale import and closes it. A failed, declined or cancelled import keeps the current session.
- **Closing a session** stops its catalogue worker, closes its plate solver and releases its frames, caches and selection, also when the solver cannot confirm its shutdown. Every viewer is closed on import (a fresh start) and at exit, through the central `ViewerRegistry`; disposed windows are unregistered and their workers and listeners released.
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

`WrappingControlsPanel` uses `WrapLayout` and re-measures enclosing layouts after width changes, so wrapped viewer rows receive their full height. `WidthTrackingPanel` shares viewport-width tracking between settings, Astrometry, the workflow strip and the star-mask sidebar. It reveals focused descendants without changing focus ownership and detaches its focus listener when removed. The workflow scroll area reserves table space; the sidebar retains natural content height. The Auto-Tune table scrolls locally rather than imposing its full column width on the page.
- `DetectionConfigurationPanel` builds the pages. `SettingsNavigator` provides the page list, search, the unsaved-changes filter and the expert switch. Each `SettingRow` has a changed marker and a reset button, and `CheckedNumberEditor` accepts only a complete number in range. Create every spinner with `UiStyle.spinner`, which keeps the caret where the user puts it when the spinner gains the focus (FlatLaf would select the whole value). Overview notes, Astrometry status, the stretch-tab explanation and the star-mask sidebar use `WrappingHelpLabel`, so a larger body font increases their height instead of clipping them. Viewer sliders take their width from `UiStyle.space`, which follows the display scale.
- Every visible engine setting is described by a `DetectionSetting` (stable field ID, title, unit, guidance, expert flag, getter and setter) in one table per page, such as `CoreDetectionSettings` and `MovingObjectsSettings`. The binding to its control is registered where the row is built. Application-owned controls on those pages (`ApplicationSettingBinding`) carry their metadata, owner getter/setter and UI accessors directly, and `SettingRow` receives its setting ID and expert visibility directly at construction rather than through component client properties.
- **Strictness** (Lenient / Normal / Strict) sets the five frame-outlier limits together (`frameRejectionPresetValues`) and shows "Custom" when they match no preset.
- `showOnlyWhen(toggle, rows…)` shows dependent rows only while their switch is on: the geometric linker, activity clusters and the bright-star eccentricity limit.

**Edits and runs.** Edits apply to the session's `DetectionConfig` at once (`applySettingsToMemory` fails fast rather than swallowing binding or alignment errors). Save writes the profile and the visualization preferences on the settings writer, and only a successful write advances the saved baseline. Every run takes its own validated copy, so later edits never affect a running job. Preview Frame detects each frame with a fresh copy of other settings plus its local trial thresholds. Preview Frame and Star Mask Explorer share checked trial inputs (TrialSettingsControls), a 300 ms refresh delay and Apply to Settings. Apply merges only explicitly edited fields into current controls, using the normal pair rules; it never replaces a captured whole configuration or advances the saved baseline. Automatic trial pair alignment does not mark an untouched field edited. Closing discards unapplied trials; reopening takes current settings.

**Rules.** JTransient's `DetectionConfigRules` owns the numeric checks: non-finite values are errors, and seven pairs must stay ordered (grow sigma ≤ sigma, and so on). The panel asks it to align a pair when one side is edited. The rhythm/jump pair is an old GUI policy, not an engine invariant. **Review rules…** shows advisory findings only.

**Profiles.** Profiles are read with Gson, which ignores unknown keys. `SpacePixelsDetectionProfileIO.needsMigration` compares key sets. When a `DetectionConfig` field is added or removed, the application offers a one-time "Migrate profile" step that keeps a backup. Also update `src/dist/config/default_detection_profile.json`. The old profile name `spacepixels_jtransient.json` is still read.

## Frame viewers

Desktop appearance is owned by `gui.AppearanceSettings`, `AppearanceTheme` and `AppearancePanel`, independently of engine, application and report settings. `main` reads the checked `spacepixels_appearance.json` and optional UTF-8 FlatLaf properties file before entering the EDT. The EDT installs an instance-owned FlatLaf definition and the saved zoom factor before constructing any windows. The Appearance tab is available without a session; Save captures its controls and uses `ApplicationStorage` under the Appearance target, with `StagedFile` replacement. Changes apply after restart, so existing windows never keep a mixture of old and new fonts or colours. Restore Defaults changes only the form until Save. Unreadable overrides preserve the selected built-in theme and size; malformed preferences use defaults with a warning and are not automatically rewritten. Essential invalid custom UI defaults fall back to the built-in theme.

`UiStyle` supplies body/heading typography, readable help/link colours and a separate foreground for the permanently dark image canvas. Enabled explanatory text uses the help colour instead of the disabled-control colour. Image-viewer annotations, histogram text and chart axes follow the base font; their note backdrops use font metrics. The distributed `config/appearance-example.properties` shows optional overrides. Appearance changes remain in the desktop source set.

After a successful appearance save, the user can choose Restart now or Later. `ApplicationWindow` owns the restart request and timer: existing work finishes, new job admissions pause, and a worker prepares `ApplicationRestart` before session shutdown. Preparation failure keeps the current window usable. Shutdown drains `ApplicationStorage` before committing the helper. The small helper JVM waits for the old process to exit before launching the same runtime, JVM options, classpath, home, arguments and working directory, avoiding overlapping application heaps. An uncommitted helper never relaunches the application.

`WindowSizing` installs per-window minimum sizes from logical baselines and measured content, including wrapped controls and current font metrics. It bounds those limits and initial placement to the usable monitor. Viewer toolbars and long dialog forms retain scroll access if enlarged controls exceed the available display; `WidthTrackingPanel` reveals focused controls on Tab. Standard file choosers use the same protected dialog sizing. The Appearance editors use their native preferred widths.

Preview Frame (`DetectionSequenceFrame`), Manual Transient Inspection (`TransientInspectionFrame`) and Show full size (`StretchedSequenceFrame`) extend `SequenceViewerFrame`. Each prepares one frame at a time in `prepare(...)` on a `SwingWorker`, and the newest request wins. Blink (`BlinkFrame`) is built from the same parts: `FrameView` draws, `StretchControls` shares the stretch with the Image Stretch tab, `ViewerSupport` provides the keys and status bar, and `SkyAnnotations` draws catalogue labels. Blink, Preview Frame and Manual Transient Inspection can show the last run's tracks (T) from the session's `TrackOverlay`. Manual Transient Inspection keeps the master reference from its first pass and re-detects only the frame on show when its trial per-frame settings change.

`TransientInspectionDetector` owns that window's cache, keyed by the kept frame and a serialized snapshot of the complete `DetectionConfig`. Each cache miss creates and closes its own engine on the preparation worker. Closing the window clears the cache without waiting for computation or closing a running engine; the cancelled worker finishes its engine cleanup. Cache locks cover only lookups and updates, and a late result cannot repopulate a closed cache. Re-detection borrows the original star mask, including its original jitter footprint.

**Closing a viewer** cancels pending preparation, drops its result, detaches the catalogue and shared stretch listeners (`StretchControls` must detach, or the shared models keep the window and its buffers), and releases its frames. All viewers register with `ViewerRegistry`, which unregisters disposed windows and closes every viewer on session change and at exit.

`BlinkGifExport` owns region selection and one streamed GIF worker. Admission captures the included frame order, playback speed, stretch, viewport and overlay state. An independent `FrameView` paints each selected device-pixel crop on the EDT; the worker computes stretch tables and encodes one frame at a time. Closing or reloading cancels its staged output, and session identity guards later UI updates. The selection layer does not change ordinary pan gestures or appear in the export.

**Focus rules** (tested with real key and mouse events): only the frame view takes the focus, except the preview settings fields. Their spinners use `setRequestFocusEnabled(false)`, so an arrow click leaves the keys on the frame. Window keys bound through `ViewerSupport.bind` do not fire while a text field has the focus. Enter commits a valid trial value and Esc discards unfinished text; either returns focus to the image. Apply changes session settings. Trial inputs, Reset and Apply follow the application job lock through ViewerRegistry. A blink of selected frames resolves its WCS over the whole session (`BlinkFrame.open` takes `sessionFiles`).

## The report

`ReportExporter.export(ReportRequest)` is the single normal-report entry point. The API and the standard/iterative
detection services pass the engine result, shared image buffers, copied metadata/configuration, captured run context,
inclusion and progress in one request. Export resolves its folder, builds the complete context, writes the report and
removes unfinished output on cancellation. Detection overlays are delivered before the report decision and export.

The report has three stages:

1. **Model:** `DetectionReportContext.create` normalizes optional engine analyses and initializes every shared field, including `ReportData`, before section rendering. Tests use the same factory. Missing WCS, catalogue or optional analysis is a valid captured absence; it never triggers live settings or session-file reads. `ReportData` holds the finding identities and frame selection, and is also written as `report-data.json`. `FindingCategory` owns each category's prefix, label, anchor rule, colour and inclusion option, so cards, maps, CSVs, overlays and the report question agree.
2. **Sections:** one writer per section, such as `TargetVisualizationSectionWriter`, `AnomalySectionWriter` and `PhotometryReportSectionWriter`.
3. **Publish:** the HTML is published only when complete (`AtomicReportFile`).

`ReportMaps` draws the map and mask pictures, and `ReportFormat` formats report text. `IterativeDetectionIndex` writes the iterative index; `DetectionReportGenerator` retains representative-frame sampling helpers.
Internal Java callers use `ReportExporter.export(new ReportRequest(...))`; the supported `api` surface and report schemas are unchanged.

Moving-object clean Wide downloads crop twice the narrow source field, with a minimum of 400 source pixels per side when available and dimensions bounded by the input frame. The complete field is then scaled proportionally to at most 1000 output pixels per side. This separates sky coverage from output resolution, so the size limit cannot crop the ends of a long track. Narrow downloads and the annotated animations keep their existing geometry.

Variable-star brightest/faintest cutouts use each crop's median as its local sky, with one shared span from the brightest crop's maximum minus its sky. A frame's `PhotometryFrameStat.skyMedian` summarizes the field; local gradients can leave unequal cutout backgrounds when that field-wide statistic is used directly. The faintest crop's sky-subtracted signal is multiplied by `10^(0.4 * (Z_faint - Z_bright))`, using the ensemble magnitude zero points, before the existing square-root display curve. Nonfinite zero points leave the flux scale unchanged and the caption says the correction is unavailable. This is display-only: source pixels, photometry, light curves, CSVs and findings are not modified.

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
| `user.home/spacepixels_appearance.json` | Desktop theme, interface size and optional custom-properties file |
| Dataset folder | `sky_catalogue.json`, `plate_correction.json`, `auto_tune.json` |
| Run output folder | `detection_report.html`, `detection_config.json`, `report-data.json`, CSVs, images and GIFs; iterative runs add per-pass folders, `index.html`, `run-data.json` and input manifests |
| Selected preparation output parent | A unique `spacepixels_prepared_…` folder with private working copies, `aligned/`, optional reference stack, generated instructions, log and versioned preparation manifest |

Saving the profile and the visualization preferences stages both files, but replaces them independently; an error names the file that was not saved. A cancelled run removes its unfinished output folder; a cancelled iterative run keeps the passes it finished.

Siril executable selections belong to `AppConfig` and its `copy()`, persisted through the existing application settings queue. Recipe, input-role, sensor/CFA and working-file retention selections are request values recorded in the local preparation manifest, not detection settings or Gradle properties. Working copies/intermediates are removed by default after owned processes stop, including on failure/cancellation; retaining them is an explicit request option. Final products and run records remain, and incomplete output is never automatically imported.

## Testing

Tests use JUnit 4.13.2. Start with the affected tests, then run the full suite.

- **Boundaries and ownership:** `DependencyRulesTest` (no GUI in jobs, tasks or headless domain packages; no AWT in session state), `JobCoordinatorTest`, `ProcessingSessionTest`, `ApplicationStorageSessionTest`, `ApplicationStorageSettingsTest`. The storage tests cover one queue's settings/session order, reimport reads behind writes, target failures and retries, shutdown drain, and session completions already queued when their owner closes.
- **Synthetic data:** `SyntheticDatasetFactory` builds disposable FITS datasets.
- **Preparation parity:** `FitsPreparationParityTest` compares GUI and API preparation across formats.
- **Iterative detection:** `IterativeDetectionPlanTest` and `IterativeDetectionRunTest`, which compares passes with ordinary runs on the same subsets.
- **GUI mechanics** (`guiMechanicsTest`): drive the real window by the visible text of its controls, with a scratch `user.home` and restored preferences. Viewer navigation tests must send real `KeyEvent`s to the focus owner; invoking actions directly misses controls that consume Space or the arrow keys.
- **Release real-data smoke tests** (`realDataSmokeTest`): check a bounded selection of small reference datasets, sequentially on scratch copies, including scientific outcomes and report exports. Configure `realDataRoot` in git-ignored `gradle.properties` (see [gradle.properties.example](gradle.properties.example)), or override it with `-PrealDataRoot=<folder>`. Missing datasets are skipped; processing failures fail the build. These checks supplement the full scientific regression below.
- **Real-data harness:** `.\gradlew.bat realDataTest -PrealDataRoot=<folder>` runs preparation, API and CLI checks on every dataset directory under the supplied root; supply scratch copies for this broader opt-in task. An existing data directory is required only when this task runs; there is no machine-specific default. Alternatively, set `realDataRoot` in git-ignored `gradle.properties`; explicit `-P` wins. Relative paths are resolved against the project directory and passed to the test JVM as absolute paths.

`-PtestMaxHeap=6g` raises the heap of the test tasks.

`AutoTunerRealDataDiagnosticsIT` prints the calibrated tuner’s report for one dataset without running the full pipeline. It is skipped unless `SPACEPIXELS_TUNER_DATASET` points to an aligned 16-bit FITS folder. Optional `SPACEPIXELS_TUNER_PROFILE` selects a base profile and `SPACEPIXELS_TUNER_PREPARE=1` enables format preparation. Use scratch copies and the isolated settings setup described above.

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
