# Developer guide

How SpacePixels is built, how its code is organised, the principles it follows, and how to change it safely. This guide is for developers and coding agents. The implementation sections describe the current code; the principles guide changes. Planned design work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) and planned functionality in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md).

| Read | For |
|---|---|
| [MANUAL.md](MANUAL.md) | What the application does, for users |
| [API.md](API.md) | The supported Java API and its requests and results |
| [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md) | Components, processing flows and data contracts in detail |
| [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) | Pending design and refactoring work |
| [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) | Pending functional requests and their decisions |
| [PUBLISHING.md](PUBLISHING.md) | Desktop packages, Maven publication and the API consumer test |
| [docs/features/](docs/features/) | Technical notes on individual features |
| [Research evidence](docs/research/README.md) | Coordinated slow-mover investigation archive and code-delivery history |

Machine-specific paths (tools, test datasets, scratch folders, memory limits) are not in this repository. Each machine keeps them in a git-ignored `LOCAL-ENVIRONMENT.txt` at the repository root (see [AGENTS.md](AGENTS.md)).

<!-- root-toc:start -->
## Contents

- [Principles](#principles)
- [Design patterns in use](#design-patterns-in-use)
- [How to add](#how-to-add)
- [Working rules](#working-rules)
- [Build and run](#build-and-run)
  - [Dependencies](#dependencies)
  - [Windows installer](#windows-installer)
- [The packages](#the-packages)
- [How a user action runs](#how-a-user-action-runs)
- [The session](#the-session)
- [Settings](#settings)
- [Frame viewers](#frame-viewers)
- [The report](#the-report)
- [Headless: the API and the command line](#headless-the-api-and-the-command-line)
- [Persistence and outputs](#persistence-and-outputs)
- [Testing](#testing)
  - [Real-data regression](#real-data-regression)
- [Diagnostics](#diagnostics)
<!-- root-toc:end -->

## Principles

Scientific correctness and preservation of valid results constrain every design change. Within that constraint, the maintainer's goals for the code, in order:

1. **Readable code.** A reader should understand a class from its name, its Javadoc and its fields. Prefer plain, direct code over clever generality.
2. **A clear application design with clear boundaries.** Each piece of mutable state has one authoritative owner. Each package has one job, and its dependencies point one way.
3. **No abstraction or engineering without a real need.** An interface normally needs two implementations or a test seam that is actually used; an explicit listener or ownership boundary can also justify one. A layer needs a responsibility of its own. A compatibility path needs a caller that still uses it. Remove unused machinery after identifying the contracts it protects. Fewer classes or files alone do not make a design simpler.
4. **A few chosen design patterns, applied consistently.** They are listed in the [table below](#design-patterns-in-use), with when to use each. Reuse them when their responsibilities fit; consistency does not justify an unnecessary abstraction.
5. **One implementation of each behaviour.** Code that must give the same result is written once and called from every place that needs it: a preview, a check or a tool that shows what detection will do runs detection's own code. A second implementation is acceptable when it has a reason of its own, such as bounded memory, speed or a different measurement. It then gives that reason in its Javadoc, and a test pins the two together where they must agree. Prefer a parameter or a small shared step to a copy; sharing is a real need in the sense of principle 3, but share the step, not a framework.

The design rules that follow from them, each with what checks it: a test where one exists, otherwise review.

| Rule | Checked by |
|---|---|
| **JTransient owns detection.** SpacePixels prepares data, runs the work and presents results; a change to what is detected belongs in the engine. | Review. The tools that preview detection are pinned to it by the engine's equality tests (`FrameQualityResultTest`, `ExcludedAreaTest`). |
| **One owner for each piece of mutable state, and a run works on a snapshot taken when it is admitted.** It never reads live controls, globals or files that another component may be rewriting. | `ProcessingSessionTest`, `TaskOutcomesTest` (admitted frames and settings), `IterativeDetectionRunTest`; otherwise review |
| **Collaborators are passed in constructors and listeners are explicit.** No service locator, global event bus, DI framework or mutable static application state. | Review |
| **Swing is touched only on the event dispatch thread;** file, network and processing work runs on owned workers. | `DependencyRulesTest` for the package boundaries (no window code in jobs, tasks, session state or the headless packages) and `check` for the two source sets; thread confinement itself by review and `guiMechanicsTest` |
| **Every job ends exactly once,** with its result or a reason, and no failure leaves the window locked. | `JobCoordinatorTest`, `JobFailuresTest`, `TaskOutcomesTest` |
| **Files are replaced only when complete** (`StagedFile`, `AtomicReportFile`). | `StagedFileTest`; otherwise review |
| **The console shows only problems.** Diagnostics go through `DebugOutput` and appear with `-Dspacepixels.debug=true`. | Review |
| **Logic is testable without the window.** | The `test` task runs without the GUI mechanics tests, which drive the real window separately |
| **The three repositories are one coordinated source set.** They change together, with no fallback code for older library versions. | `check` (the API consumer build and the published metadata); otherwise review |
| **One implementation of each behaviour** (principle 5). | The tests that pin two paths together: `IterativeDetectionRunTest` (passes against ordinary runs), `FitsPreparationParityTest` (GUI against API preparation), and the engine's equality tests |

The tests check selected boundaries and outcomes. They do not prove thread confinement or every ownership rule; those rely on review.

**Avoid:** rewriting the Swing UI in another toolkit to fix state problems; splitting classes by line count; replacing a large panel with an equally large controller; generic jobs with untyped results; making every object observable; keeping large image buffers for later convenience; changing the viewers' focus rules without real keyboard tests.

## Design patterns in use

Reach for one of these before inventing a structure. JTransient's own patterns are in its [developer guide](../JTransient/DEVELOPER.md#design-patterns-in-use).

| Pattern | Use it when | Where |
|---|---|---|
| Command with a coordinator | an action is long enough to need progress or Cancel | `Job` and `JobCoordinator`: one admission rule, one outcome per job |
| Shared command (Swing `Action`) | one action has a button, a shortcut and a menu item | `CommandAction`, enabled by `CommandAvailability` |
| Typed outcome | a caller must tell completed, declined, cancelled and failed apart | `JobOutcome` |
| Explicit ownership and guarded handover | state lives as long as a session and must not reach the next one | `ProcessingSession`, `SessionOwner` |
| Observer with explicit listeners | a view follows a model that something else owns | `JobCoordinator.Listener`, `SessionListener`, `SkyCatalogueModel` listeners |
| Interaction adapter | work on a worker needs a decision from the user | `FitsImportInteraction` / `GuiFitsImportInteraction`; `GuiDetectionReportPrompt`: decisions on the EDT, work on a worker |
| Request and result objects | an operation takes more than a few inputs, or its inputs are captured at admission | `SpacePixelsPipelineRequest` / `Result`, `IterativeDetectionRequest`, `DetectionRunOptions`, `SkySnapshot` |
| Template method | several windows share a flow and differ in one step | `SequenceViewerFrame.prepare(...)` |
| Newest request wins | a preview is recomputed while the user keeps changing its input | `SequenceViewerFrame`, `StretchPreviewRenderer`, `AstapInstallationCheck` |
| Staged publication | a file that others read is rewritten | `StagedFile`, `AtomicReportFile` |
| One writer per report section | the report gets a section | the `…SectionWriter` classes |
| Settings table | a detection setting gets a row | the `DetectionSetting` definitions, one table per settings page |

## How to add

Short recipes for the common additions. Each follows a pattern above; the sections further down describe the parts.

**A detection setting.**
1. Add the field to the engine first (JTransient's [Changing DetectionConfig](../JTransient/DEVELOPER.md#changing-detectionconfig)).
2. Ask the maintainer for its class: core, basic, expert or hidden ([Settings](#settings)). A hidden setting stops here.
3. Add a `DetectionSetting` to the table of its page, such as `MovingObjectsSettings`, and build its row where the page builds the others.
4. Add the key to `src/dist/config/default_detection_profile.json`; the profile migration offer follows from the changed key set.
5. Update the counts in `TypedDetectionSettingsTest` and the settings mechanics tests, and describe the setting in MANUAL.md.

**A long action (a command with a job).**
1. Add a `JobKind` and a class in `tasks` that implements `Job<T>` and copies what it needs in its constructor.
2. Say when it may run in `CommandAvailability`, and give it one `CommandAction` for its button, shortcut and menu item.
3. Start it through the job coordinator (`startJob` in `MainApplicationPanel`), and apply its `JobOutcome` in the handler, which runs on the EDT for the session that started it.
4. Add it to `TaskOutcomesTest`, and drive it in a GUI mechanics test.

**A report section.**
1. Write a `…SectionWriter`; findings of a new kind also get a `FindingCategory`, which owns their prefix, colour, folder and inclusion option.
2. Name every file with `ReportFiles.link(..)` and write it to `context.file(link)`.
3. Put data other tools read into `ReportData` as new keys, and describe them in API.md.
4. Put styles and scripts in the `report/` resources and list them in `ReportAssets`.

**A viewer layer.**
1. Add it to `ViewerLayer`, and give it a colour and a legend swatch in `OverlayStyle`.
2. Draw it in `FrameView` only while the layer is on.
3. Give it a switch in a side-panel section (`SidePanelSection`), following the shared `ViewerLayerState`.
4. Deliver its data detached from the run (`DetectionOverlays`) or from the session through a listener; never keep image buffers for it.

**A file saved with the session.**
1. Give `ProcessingSession` the value and a setter that saves through the session's storage (`SessionMetadataStore`), and add it to `SessionMetadata` with a guarded restore at import.
2. Let a run take it at admission (`ProcessingSession.runSnapshot`, `ReportRunContext`); a run never reads the file while it works.
3. List the file under [Persistence and outputs](#persistence-and-outputs) and in MANUAL.md.

## Working rules

These apply to everyone, including coding agents.

- **Never overwrite the user's saved settings:** `spacepixels_app.json`, `spacepixels_detection_profile.json` and `spacepixels_visualization.json` in the user's home folder. To run anything that loads or saves settings, start the JVM with `-Duser.home=<scratch folder>` and copy a profile there.
- **Java preferences** under node `/eu/startales/spacepixels` hold the window placement, the stretch values, `blink.frameMillis`, `gui/showExpertSettings`, `gui/progressDialog.liveView`, the frame viewers' layer choices (`gui/viewerLayers`), their side panel's width and visibility (`gui/viewerSidePanel.*`), and the tips screen's startup choice and next tip (`gui/tips.*`). A tool or test that changes them must back them up and restore them; isolating `user.home` does not isolate preferences. The `guiMechanicsTest` task keeps them in memory (`MemoryPreferencesFactory`), so its tests start from the defaults and cannot touch the user's own, and `PreferenceSnapshot` resets them between tests.
- **Discuss a fix before implementing it**, and show its effect on real data (see [Real-data regression](#real-data-regression)). Engine fixes go one at a time.
- **Separate refactoring from behavior changes.** Changes to accepted inputs, save-failure behavior, viewer lifetime or public contracts need an explicit decision even when proposed in a design issue. Consider and evaluate each issue's review comments before implementation, and record the decision and relevant checks with the change.
- **Do not commit an image that shows observer coordinates.** Before committing an image in the manual, the documentation, or on GitHub, check that the observer's coordinates are not visible in it. Leave the image out of the commit when they are.
- **Commits:** code commits have descriptive messages, with the validation in the body. Documentation-only commits use a generic message such as "Update docs". Never put private details or locations in a commit message. Don't rewrite history. Stage only your own changes, and leave unrelated working-tree edits alone.
- **Codex attribution:** when Codex creates a commit, append `Co-authored-by: Codex <noreply@openai.com>` as a commit-message trailer. Keep the maintainer as the primary author.
- **Refer to the maintainer and other people as they/them.**
- **Document new Java types** (including nested types and tests) with their responsibility, and give methods a short Javadoc covering purpose and any ownership, units or threading contract. Update the Javadoc when behaviour changes.
- **Keep documentation current in the same change:** behaviour in MANUAL.md, design in this guide or HIGH_LEVEL_DESIGN.md, and pending work only in DESIGN-IMPROVEMENTS.md or FEATURE-REQUESTS.md. When an item is done, describe the result and remove the item; git history keeps the record.
- **Experiment documentation:** default to a short decision, limitation or next step. Publish detailed protocols, result tables and experiment histories only when the maintainer explicitly asks. Keep algorithm references focused on current behavior; include research only when needed to explain a current design or limitation, without dataset-specific diaries. Existing evidence archives may remain linked separately.
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

GUI mechanics send real mouse and keyboard events. Keep the desktop unlocked and idle while they run so other windows or input do not intercept those events.

Headless batch run of one dataset (it prints `Report file: ...` and any problems):

```bash
./gradlew -q batchDetect -PbatchMaxHeap=11g -PbatchArgs="\"<folder>\" \"<profile.json>\""
```

Add `-PspacepixelsDebug` (or `-Dspacepixels.debug=true` on a plain `java` command) for progress, per-frame statistics and the engine's debug output.

**Source sets.** `main` is the headless API and CLI artifact (`eu.startales.spacepixels:spacepixels`): `api`, `config`, `session`, `fits`, `preparation`, `detection`, `platesolve`, `skycatalog`, `solarsystem`, `report`, `util` and `tools`, with no Swing and no FlatLaf. `desktop` holds `gui`, `jobs`, `tasks` and the icons, and is not published. `run`, `installDist` and the desktop launchers use both; the CLI launchers use only `main`. `check` verifies the published jar, its POM and Gradle metadata, and that the two source sets own disjoint classes.

**Sibling libraries.** The required library versions are ordinary dependency declarations in `build.gradle`; update them there when selecting dependencies for a SpacePixels release. During development, both libraries can instead be built from their sibling checkouts. Set `useLocalJTransientBuild=true` and `useLocalJPlateSolveBuild=true` in the git-ignored `gradle.properties` (copy [gradle.properties.example](gradle.properties.example)). The environment variables `USE_LOCAL_JTRANSIENT_BUILD` and `USE_LOCAL_JPLATE_SOLVE_BUILD` also work; an explicit `-P` property wins. Without the flags, Gradle resolves the declared dependencies from Maven Central.

**Releases.** Run `releaseCheck` before distributing desktop packages; `mavenCentralBundle` includes it automatically. It adds GUI mechanics, required report JavaScript checks, API consumers using both Gradle and Maven POM metadata, and the available small real-data smoke tests to the ordinary checks. Node and an unlocked desktop are required; missing optional datasets are reported as skips. Desktop distributions bundle the library JARs and can use sibling checkouts. For Maven Central publication, publish the library dependencies first and disable both composite flags. See [PUBLISHING.md](PUBLISHING.md).

### Dependencies

The build uses Gradle 9.8.2, nom-tam-fits 1.23.0, Gson 2.14.0 and FlatLaf 3.7.2.

Application distributions bundle those libraries and JTransient and JPlateSolve in `lib/`. nom-tam-fits and Gson also declare annotation-only jars (SpotBugs annotations under LGPL 2.1, jsr305, Jakarta annotations, and Error Prone annotations). Those groups are excluded on every SpacePixels dependency edge that can reach them, and on the same dependencies in JTransient and JPlateSolve, so the application image and the published POMs omit them. `NOTICE.txt`, beside `LICENSE`, carries the Apache 2.0 text for Gson and FlatLaf, The Unlicense text from nom-tam-fits, and the BSD 2-Clause notices for JTransient and JPlateSolve. `LICENSE` stays the SpacePixels BSD 2-Clause text and is the Windows installer click-through license. The OpenJDK notices stay under `runtime/legal/` in the Windows image.

FITS HDU creation uses the public `Fits.makeHDU(Object)` API in pixel conversion, XISF import, artificial-star injection and test fixtures.

The application and both libraries compile for Java 21. The Windows installer bundles a Java 21 runtime.

### Windows installer

`gradle/windows-installer.gradle` supplies two opt-in Windows x64 tasks: `windowsAppImage` produces `build/windows/image/SpacePixels/SpacePixels.exe` and a private Java runtime; `windowsInstaller` wraps that image in `build/installers/SpacePixels-<installer-version>.exe`. Use a 64-bit JDK 21. The installer task also needs WiX 3 build tools (`candle.exe`, `light.exe`), found through `-PwixHome=<folder>`, `WIX_HOME`, `WIX`, or PATH. Store local paths in git-ignored `gradle.properties`, not in the build script. See [PUBLISHING.md](PUBLISHING.md#windows-installer) for commands and release validation.

The tasks reuse `installDist`'s complete JAR set and all shared distribution files, including the default detection profile, appearance example, license and third-party notices. Archive launcher scripts are replaced by `SpacePixels.exe`, `spacepixels-batch.exe` and `injectStars.exe`; both command-line tools have console launchers. The multi-size ICO embeds the existing PNG icons without changing their pixels. `jpackage` links the packaging JDK's Java 21 runtime; native `java`/`javaw` commands are retained for Appearance's helper-based restart, and runtime legal notices stay included. All three launchers keep the existing memory/G1 options.

The installer uses per-user installation and directory selection. Repair, removal, and a different release use that selected folder: `src/windows-installer/main.wxs` records it because the generated file components are registered by registry key paths, which otherwise make maintenance fall back to the default Local AppData folder. The folder page of another release shows the saved path unless `INSTALLDIR` was set on the command line. It also offers optional desktop and Start menu shortcuts, is unsigned, is built locally, and upgrades through a new installer. The fixed upgrade UUID must not change between releases. The Windows installer uses a numeric version derived from the application version; `-PwindowsInstallerVersion=major.minor.build` can override it (Windows limits: 255.255.65535). Home settings and datasets are kept outside the installation folder and are not installer payload or cleanup targets. There is no automatic updater.

## The packages

All packages are under `eu.startales.spacepixels`.

| Package | What it holds | Main classes |
|---|---|---|
| `gui` | Swing views, shared commands and their availability, settings pages, frame viewers | `ApplicationWindow`, `MainApplicationPanel`, `DetectionConfigurationPanel`, `CommandAction`, `CommandAvailability`, `SequenceViewerFrame`, `BlinkFrame`, `TipsDialog` |
| `jobs` | Runs long operations one at a time; no Swing | `JobCoordinator`, `Job`, `JobOutcome`, `JobKind` |
| `tasks` | The long operations, one class per job | `FitsImportTask`, `DetectionTask`, `PlateSolveTask`, `AutoTuneTask`, … |
| `session` | The imported dataset: identity, owned state and handover; the application I/O queue; no Swing | `ProcessingSession`, `SessionOwner`, `SkyCatalogueModel`, `AutoTuneState`, `ApplicationStorage` |
| `config` | Application settings, detection profiles, visualization settings and their files; the typed setting definitions | `ApplicationSettings`, `AppConfig`, `SpacePixelsDetectionProfileIO`, `DetectionSetting`, `DetectionRunOptions`, `VisualizationSettings` |
| `fits` | FITS/XISF input, validation, preparation, pixel converters, display stretch, WCS | `FitsFileInformation`, `FitsImportService`, `FitsBatchService`, `FitsInput`, `FitsPixelConverter`, `FitsPreparation`, `WcsCoordinateTransformer` |
| `preparation` | External observing-session preparation, conservative input inventories, metadata preservation and common output conversion; no Swing or session state | `FramePreparationService`, `PreparationRequest`, `PreparationProcess`, `SirilHeaderCorrections`, `PreparedSequenceWriter` |
| `detection` | Detection pipeline services, iterative detection, live preview, auto-tune runners, overlays | `StandardDetectionPipelineService`, `IterativeDetectionPipelineService`, `AutoTunerRunner`, `TrackOverlay`, `VariableStarOverlay` |
| `platesolve` | Plate solving integration and session plate solution correction | `PlateSolveService`, `PlateSolutionCorrection`, `AstrometryNetAccount` |
| `skycatalog` | Fetching and storing the sky catalogue; the plate-solution correction fit; sky snapshots | `SkyCatalogueClient`, `SkyCatalogueStore`, `FetchStep`, `PlateCorrectionFitter`, `SkySnapshot` |
| `solarsystem` | Predicting the known asteroids and comets of a session's field with JPL, and storing the predictions; the JPL Small-Body Identification URLs | `SolarSystemClient`, `SolarSystemQuery`, `SolarSystemObjects`, `SolarSystemStore`, `JplSbIdentUrlBuilder` |
| `report` | The HTML report, its model, section writers, and lookup proxy/builders (`report.lookup`) | `ReportExporter`, `ReportRequest`, `ReportData`, `FindingCategory`, `ReportLookupProxyServer` |
| `util` | General cross-cutting utilities | `StagedFile`, `Statistics`, `SkyMath`, `BuildInfo`, `Cancellation`, `DebugOutput` |
| `api`, `tools` | The public Java API and the command-line tools | `DefaultSpacePixelsPipelineApi`, `SpacePixelsPipelineRequest`, `BatchDetectionCli` |

Other locations: `src/main/resources` (icons, build metadata), `src/dist/config` (the default detection profile), `src/launchers`, `src/consumerTest` (the isolated API consumer build).

The main paths through the code:

- **GUI:** a `gui` command → `JobCoordinator` runs a `tasks` job → `fits`/`detection`/`platesolve` services → JTransient or JPlateSolve.
- **Headless:** `api` and `tools` → `fits`/`detection` services → JTransient.

**Input contract.** Detection consumes aligned, equally sized monochrome frames with unsigned-16-bit pixels (`BZERO=32768`, `BSCALE=1`) in `short[][]` buffers. `FitsFileInformation` carries each frame's immutable metadata, unmodifiable header map and construction-time normalised timing. Compressed FITS can have an empty primary HDU, so use the HDU helper (`FitsInput.getImageHDU`). Preparation keeps timing and WCS headers, the registration padding, and the sequence-wide offset recorded in `SPOFFSET`.

More on input inspection, intensity scaling, WCS, plate-solve hints and the import quality checks: [implementation notes](HIGH_LEVEL_DESIGN.md#input-preparation-and-plate-solving).

## How a user action runs

The job pattern covers observing-session preparation, import, Convert to Mono, Batch Stretch, plate solve, writing the WCS header, Blink, Auto-Tune, Detect, Detect Iteratively, Manual Transient Inspection, standalone quality control and the Test Star Mask stack.

1. **The command, on the EDT.** `CommandAvailability` decides from session facts whether the command can run, and why not. `CommandAction` is the one Swing `Action` behind the command's button, shortcut, menu item and Overview button. The handler captures what the job needs: a validated copy of the settings (`DetectionConfigurationPanel.checkedSettings`), the files, the run options and the session.

The window creates the four panels and passes each one the collaborators it uses (`SessionAccess`, the job coordinator, the frame list, the settings readout, storage and the folder import). Panels do not take the window or call each other by type. The settings page publishes the current `DetectionConfig`; the main readout copies the eight Overview values during that EDT call and does not keep the configuration. The wiring lasts as long as the window, so there is no listener to detach. A job still copies the session and settings at admission.
2. **Admission.** `JobCoordinator.start(kind, session, job, handler)` reserves the single job slot or says why it cannot. On admission, `MainApplicationPanel` locks the window and shows `ProcessingProgressDialog`.
3. **The work, on a worker.** The task reports progress through `JobContext.progress` and returns a `JobOutcome`: `Completed`, `Declined`, `Cancelled` or `Failed`. Cancel makes the next progress report throw `JobCancelledException`; engine tasks also bind JTransient's `CancellationScope`, which stops computation between reports. An exception becomes `Failed`, except out-of-memory, which closes the application with a message.
4. **The outcome, on the EDT,** arrives exactly once after the window is unlocked, and is applied only if its session is still the current one (`ApplicationWindow.forCurrentSession`). Cancellation does not retract queued UI callbacks: delivery still checks the job and session identity. Completed detection products are kept separately from the outcome of the report export.

Shutdown cancels the current job and waits off the EDT for its worker to return after cleanup, independently of queued outcome delivery. This covers local plate-solver processes, preparation and an already admitted noncancellable WCS write. Accepted storage writes drain afterwards, before exit or committing a restart.

Blink is the exception: loading the frames is the job, and the blink window then keeps the main window locked until it closes, because a run would have to fit in memory beside its frames. An open blink is not a job, so the viewers' trial inputs stay usable. Blink and Preview Frame stay open side by side: each one's ↗ button brings the other forward on the frame shown, and returning to an open blink reads nothing. Work outside the job slot has its own owner: the sky-catalogue fetch and correction fit (`gui.SkyCatalogueController`, with one executor per session and a future per request), the asteroid and comet fetch (`gui.SolarSystemController`, built the same way), session and preference writes (single-thread writers owned by the window), ASTAP checks, stretch previews and viewer preparation (newest request wins).

More on the preparation window, stretched-copy export and the catalogue controller: [implementation notes](HIGH_LEVEL_DESIGN.md#jobs-controllers-and-the-preparation-window).

## The session

Standalone quality control keeps its small result (`QualityControlSnapshot`) in the session; see the [implementation notes](HIGH_LEVEL_DESIGN.md#standalone-quality-control).

`SessionOwner` holds the current `ProcessingSession`. A session has a UUID and owns its frame metadata (`ProcessingSession.replaceFrameHeader` replaces a frame's `FitsFileInformation` after a WCS write while existing runs keep their snapshots), selection, sky catalogue, asteroid and comet predictions and correction (`SkyCatalogueModel`), Auto-Tune state, master-stack cache, last report and last run's overlays, and the services that work on its frames: its plate solver (`PlateSolveService`) and the batch operations on its folder (`FitsBatchService`). Large image buffers are shared by ownership, never deep-copied.

- **Handover.** `FitsImportTask` reads the frames and the saved session data (catalogue, correction, Auto-Tune results) on a worker; each attempt closes its `FitsImportService` before it returns, so the result holds no resources. The window builds the session from that result. Only a completed import is installed, through `SessionOwner.replace(expected, candidate)`, which rejects a stale import and closes it. A failed, declined or cancelled import keeps the current session.
- **Closing a session** stops its catalogue worker, closes its plate solver and releases its frames, caches and selection, also when the solver cannot confirm its shutdown. Every viewer is closed on import (a fresh start) and at exit, through the central `ViewerRegistry`; disposed windows are unregistered and their workers and listeners released.
- **Session files** (`sky_catalogue.json`, `plate_correction.json`, `auto_tune.json`, `solar_system.json`, `excluded_areas.json`) are written on the window's session writer. They are restored at import only if they match the frames (field, plate solution, frame count and size; for the asteroid and comet predictions, the field and a time span that covers the frames). The catalogue and the asteroid file also keep the fetch steps beside the problem sentences. A saved JPL address is the request that was sent, including the observing site.
- **A run never reads the session files.** Detect and Detect Iteratively take the session's catalogue, correction and excluded areas when they are admitted (`ProcessingSession.runSnapshot`, a `SkySnapshot`); an iterative pass on a prepared copy in another folder, and every headless run, read that folder's files once, at the start (`ReportRunContext.capture`). Jobs therefore never wait for session writes. When a save fails, the value stays in use until SpacePixels closes, in the viewers and in the reports, and the window says it was not saved.
- **Application settings** (`ApplicationSettings`, the Astrometry Config tab) are loaded once by the window, before any import. Each session's plate solver reads that one live object, so unsaved edits reach the next solve; an import never reads or reloads them.
- **Snapshots.** Live values, immutable run snapshots and saved baselines have distinct responsibilities; several representations are not by themselves duplicate ownership. Immutability of an element does not make its containing array immutable.

## Settings

How the pages are laid out, the site and fetch settings, the Faint Movers page and the stretch preview: [implementation notes](HIGH_LEVEL_DESIGN.md#settings-pages).

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

- `DetectionConfigurationPanel` builds the pages. `SettingsNavigator` provides the page list, search, the unsaved-changes filter and the expert switch. Each `SettingRow` has a changed marker and a reset button, and `CheckedNumberEditor` accepts only a complete number in range. Create every spinner with `UiStyle.spinner`, which keeps the caret where the user puts it when the spinner gains the focus (FlatLaf would select the whole value), and give every decimal spinner a `DecimalSpinnerModel`: its arrows step in decimal, where `SpinnerNumberModel` gives 19.299999999999997 for 19.2 + 0.1, which `CheckedNumberEditor` would show in full. Overview notes, Astrometry status, the stretch-editor guidance and the star-mask sidebar use `WrappingHelpLabel`, so a larger body font increases their height instead of clipping them. Viewer sliders take their width from `UiStyle.space`, which follows the display scale.
- Every visible engine setting is described by a `DetectionSetting` (stable field ID, title, unit, guidance, expert flag, getter and setter) in one table per page, such as `CoreDetectionSettings` and `MovingObjectsSettings`. The binding to its control is registered where the row is built. Application-owned controls on those pages (`ApplicationSettingBinding`) carry their metadata, owner getter/setter and UI accessors directly, and `SettingRow` receives its setting ID and expert visibility directly at construction rather than through component client properties.
- **Strictness** (Lenient / Normal / Strict) sets the five frame-outlier limits together (`frameRejectionPresetValues`) and shows "Custom" when they match no preset.
- `showOnlyWhen(toggle, rows…)` shows dependent rows only while their switch is on: the geometric linker, activity clusters and the bright-star eccentricity limit.

**Edits and runs.** Edits apply to the session's `DetectionConfig` at once (`applySettingsToMemory` fails fast rather than swallowing binding or alignment errors). Save writes the profile and the visualization preferences on the settings writer, and only a successful write advances the saved baseline. Every run takes its own validated copy, so later edits never affect a running job. Preview Frame detects each frame with a fresh copy of other settings plus its local trial thresholds. Preview Frame, Manual Transient Inspection and Star Mask Explorer share checked trial inputs (`TrialSettingsControls`), a 300 ms refresh delay, **Revert** and **Save to settings**. Save to settings merges only explicitly edited fields into current controls, using the normal pair rules; it never replaces a captured whole configuration or advances the saved baseline. Automatic trial pair alignment does not mark an untouched field edited. Closing discards unapplied trials; reopening takes current settings.

**Rules.** JTransient's `DetectionConfigRules` owns the numeric checks: non-finite values are errors, and seven pairs must stay ordered (grow sigma ≤ sigma, and so on). The panel asks it to align a pair when one side is edited. The rhythm/jump pair is an old GUI policy, not an engine invariant. **Review rules…** shows advisory findings only.

**Profiles.** Profiles are read with Gson, which ignores unknown keys. `SpacePixelsDetectionProfileIO.needsMigration` compares key sets. When a `DetectionConfig` field is added or removed, the application offers a one-time "Migrate profile" step that keeps a backup. Also update `src/dist/config/default_detection_profile.json`. The old profile name `spacepixels_jtransient.json` is still read.

## Frame viewers

The faint-mover overlay, the Auto-Tune live view, appearance and restart, window sizing, the stretch editor, saved-report overlays and GIF export: [implementation notes](HIGH_LEVEL_DESIGN.md#viewers-live-view-and-appearance).

Preview Frame (`DetectionSequenceFrame`) and Manual Transient Inspection (`TransientInspectionFrame`) extend `SequenceViewerFrame`. The stretch editor's Show in Preview uses Preview Frame, which shows a frame the detection cannot read, such as a colour frame, without raw detections. Each prepares one frame at a time in `prepare(...)` on a `SwingWorker`, and the newest request wins. Blink (`BlinkFrame`) is built from the same parts: `FrameView` draws, `StretchControls` shares the stretch models owned by `StretchPanel`, `ViewerSupport` provides the keys and status bar, and `SkyAnnotations` draws catalogue labels and the predicted asteroids and comets (placed per frame, at the middle of its exposure). Blink, Preview Frame and Manual Transient Inspection can show all the last run's findings from the session's overlays, among them moving object tracks (T) and streak tracks (Shift+T) from its `TrackOverlay`. Manual Transient Inspection keeps the master reference from its first pass and re-detects only the frame on show when its trial per-frame settings change.

**Closing a viewer** cancels pending preparation, drops its result, detaches the catalogue and shared stretch listeners (`StretchControls` must detach, or the shared models keep the window and its buffers), and releases its frames. All viewers register with `ViewerRegistry`, which unregisters disposed windows and closes every viewer on session change and at exit.

**Focus rules** (tested with real key and mouse events): only the frame view takes the focus, except the preview settings fields. Their spinners use `setRequestFocusEnabled(false)`, so an arrow click leaves the keys on the frame. Window keys bound through `ViewerSupport.bind` do not fire while a text field has the focus. Enter commits a valid trial value and Esc discards unfinished text; either returns focus to the image. Save to settings changes session settings. Trial inputs, Revert and Save to settings follow the application job lock through ViewerRegistry, not the lock of an open blink. A blink of selected frames resolves its WCS over the whole session (`BlinkFrame.open` takes `sessionFiles`).

## The report

Individual report features (faint-mover review, satellite and known-object comparisons, lookups, path checks, cutouts): [implementation notes](HIGH_LEVEL_DESIGN.md#report-details).

`ReportExporter.export(ReportRequest)` is the single normal-report entry point. The API and the standard/iterative
detection services pass the engine result, shared image buffers, copied metadata/configuration, captured run context,
inclusion and progress in one request. Export resolves its folder, builds the complete context, writes the report and
removes unfinished output on cancellation. Detection overlays are delivered before the report decision and export.

The report has three stages:

1. **Model:** `DetectionReportContext.create` normalizes optional engine analyses and initializes every shared field, including `ReportData`, before section rendering. Tests use the same factory. Missing WCS, catalogue or optional analysis is a valid captured absence; it never triggers live settings or session-file reads. `ReportData` holds the finding identities and frame selection, and is also written as `report-data.json`. `FindingCategory` owns each category's prefix, label, anchor rule, colour and inclusion option, so cards, maps, CSVs, overlays and the report question agree.
2. **Sections:** one writer per section, such as `TargetVisualizationSectionWriter`, `AnomalySectionWriter` and `PhotometryReportSectionWriter`. A writer names each file by its link from `ReportFiles.link(..)`: `ReportFiles.FIELD` for pictures of the whole field, `ReportFiles.DIAGNOSTICS` for the diagnostics pictures, `FindingCategory.folder()` for a category's pictures and data files. It writes to `context.file(link)`, which creates the folder, and puts the same link in the HTML. File names stay unique across folders: the report's animation player finds a GIF's metadata by its file name and its poster and frame sheets beside it. Pictures of the whole field (including the cropped Transient Cluster Map) come from `context.fieldDisplayImage(..)`: the enhanced stack (`EnhancedStack`, saved by `EnhancedStackFile`) when the report built one, drawn once per report with the automatic asinh stretch of `DisplayImageRenderer.createAsinhDisplayImage` and shared, so callers must not draw on it. Pictures that compare a detection with its reference use the median stack. Track animations share one fixed stretch across their frames (`DisplayImageRenderer.sharedDisplayLevels`), discovered from the median of the frames' sky medians and robust sky noises.
3. **Publish:** `ReportAssets` writes the folder's `report.css` and `report.js` before the sections, and the HTML is published only when complete (`AtomicReportFile`).

Styles and scripts live in resources under `report/` and reach the browser only through those two files, each report folder holding its own copy, so a report keeps its look after an update. `ReportAssets` lists the parts and their order; each script runs in its own `try`, so one failure does not stop the others. A section writer puts per-report values on its elements (`data-*` attributes, an element's width), not in a style or script. The `application/json` blocks stay in the page: a page opened from disk cannot fetch local data files, and the lookup proxy saves its results into the marked block of the HTML. So does the small head script that applies the remembered theme before the page is drawn.

`ReportMaps` draws the map and mask pictures, and `ReportFormat` formats report text. `IterativeDetectionIndex` writes the iterative index; `DetectionReportGenerator` retains representative-frame sampling helpers.
Internal Java callers use `ReportExporter.export(new ReportRequest(...))`; the supported `api` surface and report schemas are unchanged.

The report never changes the engine's results. When the engine stops after quality control because it kept fewer than three frames (`PipelineResult.tooFewFrames`), the report says so and shows the quality-control results. Its live lookups (SatChecker, JPL, SkyBoT, VSX) go through `ReportLookupProxyServer`, a small localhost server owned by the window; its **Stellarium Local** buttons post directly from `report-lookups.js` to a local Stellarium Remote Control server on `127.0.0.1:8090`. `report-data.json` is versioned. Its frame numbers are one-based, and its finding IDs are local to the report.

## Headless: the API and the command line

`DefaultSpacePixelsPipelineApi.run(request)` prepares the input according to the request's policy (no questions), validates the frames strictly, optionally runs Auto-Tune, detects, and optionally writes the report. It is synchronous, reads the frames with its own `FitsImportService`, which it closes, and never touches a GUI session or creates a plate solver. `BatchDetectionCli` is a thin client of it. The supported surface is the `api` package and the types it exposes ([API.md](API.md)); everything else is internal.

A change to an exposed API type needs an explicit compatibility decision, updated consumer checks and release documentation; updating the sibling callers does not update external consumers.

## Persistence and outputs

| Location | Contents |
|---|---|
| `user.home/spacepixels_app.json` | Application preferences: solvers, Astrometry.net account, observing site |
| `user.home/spacepixels_detection_profile.json` | Detection configuration, Auto-Tune frame limit, variable-star report options |
| `user.home/spacepixels_visualization.json` | Rendering and export preferences |
| Java preferences `/eu/startales/spacepixels` | Window placement, stretch and viewer preferences |
| `user.home/spacepixels_appearance.json` | Desktop theme, interface size and optional custom-properties file |
| Dataset folder | `sky_catalogue.json`, `plate_correction.json`, `auto_tune.json`, `solar_system.json`, `excluded_areas.json` |
| Run output folder | `detection_report.html`, `report.css`, `report.js`, `report-custom.css`, `detection_config.json`, `report-data.json`, CSVs, images and GIFs; iterative runs add per-pass folders, `index.html`, `run-data.json` and input manifests |
| Selected preparation output parent | A unique `spacepixels_prepared_…` folder with private working copies, `aligned/`, optional reference stack, generated instructions, log and versioned preparation manifest |

Saving the profile and the visualization preferences stages both files, but replaces them independently; an error names the file that was not saved. A cancelled run removes its unfinished output folder; a cancelled iterative run keeps the passes it finished.

## Testing

Tests use JUnit 4.13.2. Start with the affected tests, then run the full suite.

- **Boundaries and ownership:** `DependencyRulesTest` (no GUI in jobs, tasks or headless domain packages; no AWT in session state), `JobCoordinatorTest`, `ProcessingSessionTest`, `ApplicationStorageSessionTest`, `ApplicationStorageSettingsTest`. The storage tests cover one queue's settings/session order, reimport reads behind writes, target failures and retries, shutdown drain, and session completions already queued when their owner closes.
- **Synthetic data:** `SyntheticDatasetFactory` builds disposable FITS datasets.
- **Preparation parity:** `FitsPreparationParityTest` compares GUI and API preparation across formats.
- **Iterative detection:** `IterativeDetectionPlanTest` and `IterativeDetectionRunTest`, which compares passes with ordinary runs on the same subsets.
- **GUI mechanics** (`guiMechanicsTest`): drive the real window by the visible text of its controls, with a scratch `user.home` and restored preferences. Viewer navigation tests must send real `KeyEvent`s to the focus owner; invoking actions directly misses controls that consume Space or the arrow keys.
- **Release real-data smoke tests** (`realDataSmokeTest`): run Tier 1 from the local reference inventory, sequentially on scratch copies of the default profile, including scientific outcomes and report exports. The Tier 1 sessions are Apophis, Integral-spacecraft-challenge, iTelescope-slow-mover-hot-pixels, m31-b-reg and m5-reg. Configure `realDataRoot` in git-ignored `gradle.properties` (see [gradle.properties.example](gradle.properties.example)), or override it with `-PrealDataRoot=<folder>`. Explicit `-P` wins. A relative path is resolved against the project directory. Missing datasets are skipped; processing failures fail the build. These checks supplement the full scientific regression below. Klangwolke-2 is not in this task.

`-PtestMaxHeap=6g` raises the heap of the test tasks.

`AutoTunerRealDataDiagnosticsIT` prints the calibrated tuner’s report for one dataset without running the full pipeline. It is skipped unless `SPACEPIXELS_TUNER_DATASET` points to an aligned 16-bit FITS folder. Optional `SPACEPIXELS_TUNER_PROFILE` selects a base profile and `SPACEPIXELS_TUNER_PREPARE=1` enables format preparation. Use scratch copies and the isolated settings setup described above.

### Real-data regression

Any change that can affect detection, preparation, reports or numeric diagnostics is checked on the reference datasets. Names, tier assignments, baseline findings and diagnostic notes are kept locally on each machine. `LOCAL-ENVIRONMENT.txt` locates the reference folder and its `REFERENCE-DATASETS.md` inventory. The reference for a session is the report folder named there: `detection_config.json` is the pipeline configuration, and `report-data.json` in the same folder is the result. Ignore older date-named folders unless asked.

The inventory organises the sessions into three tiers:

- **Tier 1, release smoke:** Apophis, Integral-spacecraft-challenge, iTelescope-slow-mover-hot-pixels, m31-b-reg and m5-reg. Routine check and `realDataSmokeTest`. A few minutes, on the default profile.
- **Tier 2, with Tier 1, is the full regression:** those five, plus the deep-sky, comet, asteroid and multi-night sessions named in the inventory, including Klangwolke-2. Run the full list before a release. Klangwolke-2 is the golden reference. It is in Tier 2 because a 61-megapixel session does not fit the smoke budget, and every engine change runs it anyway.
- **Tier 3:** variable-star, galaxy-core and gradient, faint-mover, and long-cadence sessions. Run the group the change can affect, together with Klangwolke-2. The inventory names the group for each investigation.

To check a change:

1. Run Tier 1. For an engine change, also run Klangwolke-2 and the Tier 3 group the change can affect. Run the rest of Tier 2 before a release, and sooner when those first results are not enough to judge the change. Use `batchDetect` on the base commit and on the change, with the same profile and a scratch `user.home`. A comparison against a saved baseline reuses the `detection_config.json` in the report folder named in the inventory. The smoke test's pins are default-profile outcomes and are separate from the auto-tuned baselines.
2. Compare the rejected frames, the tracks and anomalies, the variable-star verdicts and candidates star by star, and the timings. On Klangwolke-2, compare the moving targets, the streak tracks and the faint movers, and keep the catalogue-matched faint movers. For a pure refactoring, `report-data.json` (apart from its generation time), `detection_config.json` and the CSVs must be identical. When comparing full report folders, normalize only explicitly listed volatile fields, including the HTML's measured engine and stage durations; compare deterministic content and rendered assets exactly. Measure performance separately rather than requiring identical elapsed times.
3. Report the differences to the maintainer before committing.

Run large datasets one at a time; the memory available per JVM is in `LOCAL-ENVIRONMENT.txt`.

## Diagnostics

`-PspacepixelsDebug` on `run` or `batchDetect`, or `-Dspacepixels.debug=true` on the JVM, turns on `DebugOutput`: progress, per-frame statistics and JTransient's diagnostics. Without it, the console shows only warnings and errors. `FailureDialog` offers Copy details with the versions and the stack trace.
