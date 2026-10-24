# Design improvements

This file lists design, refactoring and code-quality work for SpacePixels: changes to how the code is organised, not to what the application does. New or changed functionality is in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current design is described in [DEVELOPER.md](DEVELOPER.md) and [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md).

Every item here is a proposal until the maintainer decides on it. When an item is done, describe the result in DEVELOPER.md or HIGH_LEVEL_DESIGN.md and remove it from this file; git history keeps the record.

**How to use the issue reviews (2026-10-24).** Before implementing an item, consider and evaluate the review comments at its end against the current code. Record which comments are accepted, adjusted or rejected, why, and the relevant verification with the change. They are review input, not authorization to implement the proposal or automatic proof of a current bug. The review was static; no runtime or real-data checks were run. Recheck references and caller counts as other work lands.

## Code review of 2026-10-23

A fresh review of the whole application (about 50,000 lines of main code and 17,000 of tests), against the principles in [DEVELOPER.md](DEVELOPER.md#principles): readable code, a clear design with clear boundaries, and a few chosen patterns applied consistently, with no abstraction that lacks a real need.

**Overall.** The architecture is sound. Jobs, commands, the session handover, the viewers and the public API are small, well-bounded and consistent, and should stay as they are. The over-engineering the maintainer suspected is real, but it is concentrated in three kinds of place:

1. **State with more than one owner.** The rendering preferences and the session's frame list each live in two or three places. Each copy then needs machinery to stay in step: write barriers, version checks, rollback, static caches and "capture" steps. Giving each piece of state one owner removes that machinery rather than adding to it.
2. **Layers kept for compatibility after compatibility stopped mattering.** The libraries are now developed together with the application, and old signatures need not survive. Still left over are the `ImageProcessing` facade, which mostly forwards calls, and chains of overloads that exist for older callers.
3. **Mechanisms heavier than their use.** Examples are two near-identical write queues for small JSON files, and a binding framework whose application side writes to global static fields.

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
| [SP-04](#sp-04-retire-the-imageprocessing-facade) | Retire the `ImageProcessing` facade; detection and frame ownership are done, remaining service forwarding and the few-frame policy evaluation remain | Medium | Large |
| [SP-05](#sp-05-give-panels-their-collaborators-not-the-whole-window) | Give panels their collaborators, not the whole window | Medium | Medium |
| [SP-06](#sp-06-move-the-sky-catalogue-workflow-out-of-the-main-panel) | Move the sky-catalogue workflow out of the main panel | Medium | Small |
| [SP-07](#sp-07-one-binding-type-for-settings-rows) | One binding type for settings rows | Low | Small |
| [SP-08](#sp-08-one-report-entry-point-and-a-complete-report-context) | One report entry point and a complete report context | Medium | Medium |
| [SP-10](#sp-10-one-place-for-sky-math-and-statistics-helpers) | One place for sky-math and statistics helpers: done; evaluate the differing variants | Low | Small |
| [SP-11](#sp-11-fewer-fits-helper-classes-immutable-frame-metadata) | Fewer FITS helper classes; immutable frame metadata | Low | Medium |
| [SP-13](#sp-13-one-way-to-report-problems-and-diagnostics) | One way to report problems and diagnostics | Low | Small |
| [SP-14](#sp-14-package-names-that-say-what-they-hold) | Package names that say what they hold | Low | Medium, mechanical |
| [SP-15](#sp-15-pass-the-live-view-sink-with-the-run) | Pass the live-view sink with the run | Low | Small |
| [SP-16](#sp-16-check-the-ui-at-larger-display-scaling) | Check the UI at larger display scaling | Low | Small |
| [SP-18](#sp-18-upgrade-the-dependencies) | Upgrade the dependencies: done; follow-ups for nom-tam-fits 1.23.1 and Gson 2.15.0 | Low | Small |
| [SP-19](#sp-19-finish-the-common-base-of-the-image-viewers) | Finish the common base of the image viewers | Medium | Medium |

**Suggested order.**
1. SP-10 and SP-18 are done apart from their follow-ups: evaluate SP-10's differing variants as a numerical decision, and take SP-18's follow-ups when nom-tam-fits 1.23.1 is out. Keep both separate from refactorings.
2. Then SP-04, then SP-05 and SP-06. The shared application I/O queue (SP-09) is in place.
3. Then SP-08, then SP-07 and SP-15.
4. Do SP-14 last: it is mechanical, and renaming first would only make the other changes harder to review.

Refactorings are intended to preserve behaviour. Evaluate the exceptions raised by the issue reviews, including SP-19's viewer lifetime and settings policy, as explicit behaviour decisions. Check implementation changes with the relevant unit and GUI mechanics tests. Items that touch detection or reports also need byte-identical deterministic `report-data.json`, `detection_config.json` and CSV content on the reference datasets, using the normalization and comparison method in DEVELOPER.md.

### SP-04. Retire the ImageProcessing facade

**Step 1 done (2026-10-24): detection.** Detect Moving Targets, Detect Iteratively and the API call `StandardDetectionPipelineService` themselves. It keeps no state, so each caller creates one where it runs it and has nothing to dispose. A finished detection hands its `DetectionOverlays` (tracks and variable-star candidates, no image buffers) to its caller when the engine returns, before the report decision. `DetectionTask` keeps them apart from its `JobOutcome`. The window gives them to the job's session when the job ends, if that session is still the current one: after a written, declined or unwritable report, and after a cancel while the report was being written. A detection that stops before it finishes leaves the last run's overlays. The session owns them (`ProcessingSession.setLastRun`, refused once closed). `DetectionSummary`, `PipelineExecutionData` and `DetectionSafetyPrompt` are top-level types. Iterative passes still leave the session's overlays alone. The reports stayed byte-identical, through both the batch command and the window's detection job.

**Step 2 done (2026-10-24): import and frame ownership.** `ProcessingSession` owns its frame array, protects the array at handover and on reads, and clears it even if processor close fails. Metadata objects remain shared for in-place WCS updates; SP-11's immutability work stays separate. Import returns fresh metadata without storing it, and `ImageProcessing.readFiles()` replaces the three old accessors. Every no-interaction read uses the API/CLI's readiness-first validation order; interactive conversion decisions are unchanged. Detection and inspection tasks copy their admitted arrays, with explicit immutable run options for detection. GUI readers use the session, headless callers keep their one returned array, `RedirectImportException` is a top-level import type, and `SessionFrames` is removed. Processing utilities no longer depend on session state.

**Problem (what remains).** `ImageProcessing` remains an internal pass-through over `FitsImportService`, `FitsBatchService`, `PlateSolveService` and `FitsVisualizationRenderer`, together with static FITS helpers. Tasks for the remaining services still call the facade `preProcessing`.

**Proposal (remaining steps).**
- Tasks and the API call the remaining services directly. The session (or one `SessionServices` holder it owns) creates them with the session's files and settings.
- Then delete `ImageProcessing`.

**Benefit.** One owner for session data and one obvious place for each operation, with no forwarding layer.

**Verification.** All tests, the GUI mechanics suite, and byte-identical reports on the reference datasets. This is the largest item. Do it in steps, one service at a time.

**Issue review (2026-10-24; evaluate before implementation).**
- Give headless service construction and disposal an explicit owner too; the API currently closes its processor without creating a GUI session. Avoid replacing the facade with a holder that simply recreates its forwarding responsibilities. Migrate one service at a time.
- **Evaluate an existing engine-boundary exception.** `DetectionPipelineSupport.suppressLatePhaseOutputsWhenTooFewFramesRemain` rewrites `PipelineResult` and telemetry after engine execution. Assess this with JTransient against the rule that detection belongs to the engine. Do not silently delete or relocate this policy during the facade refactor; establish the intended few-frame result contract and compare real results separately.

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

**Issue review (2026-10-24; evaluate before implementation).**
- Prefer collaborators and listeners with specific responsibilities; replacing the window parameter with a generic context exposing the same getters would preserve the coupling.
- A published `DetectionConfig` is mutable. Define whether listeners receive a snapshot or a borrowed read-only view, keep UI notifications on the EDT, and specify listener detachment. A session supplier is appropriate for UI commands, but admitted workers must retain their captured session and settings.

### SP-06. Move the sky-catalogue workflow out of the main panel

**Problem.** `MainApplicationPanel` holds about 300 lines of the sky-catalogue and correction workflow. They manage a raw `Thread` through a one-element array, check identity by thread and session, and run their own progress timer. `ProcessingSession` has three methods only for that thread (`ownSkyCatalogueWorker`, `ownsSkyCatalogueWorker`, `releaseSkyCatalogueWorker`). This is a second background-work mechanism inside a view.

**Proposal.** A `SkyCatalogueController` in `gui` with one `ExecutorService` and a `Future` per session: start, cancel, and progress and results delivered on the EDT. Closing the session cancels the future. The panel only shows status.

**Issue review (2026-10-24; evaluate before implementation).**
- Cancelling a `Future` does not retract EDT callbacks already queued. Preserve the task/session checks currently in `showSkyFetchStage`, `skyFetchFinished` and `correctionFinished`, including replacement work within the same session.
- Specify who owns and shuts down the executor, stops its progress timer and detaches listeners. Check cancellation, task replacement, session handover and shutdown with a delayed result. A GUI controller must not introduce a dependency from the headless `session` package back to `gui`.

### SP-07. One binding type for settings rows

**Problem.**
- `DetectionSettingBinding` (engine fields) and `ApplicationSettingBinding` (application values) are two records of the same shape. With them come `ApplicationSetting`, client-property identity tags and three binding groups for twelve controls.
- `applySettingsToMemory` catches every `Exception`, prints it with `System.err` and continues.

**Proposal.** The application values are now plain values (`VisualizationSettings` and the Auto-Tune limit). Use one generic `SettingBinding<V>`, with a read and write of the model and a read and write of the control, for both kinds. Let a binding failure surface as an error instead of being swallowed.

**Issue review (2026-10-24; evaluate before implementation).**
- Consolidate only the genuinely shared binding behaviour, on the value model that SP-02 established. Preserve the typed `DetectionSetting` tables, hidden values, range checks and pair-alignment policy.
- Define what happens when applying a binding fails: surface the failure and prevent a run or save from proceeding with a partly applied configuration. Check that invalid text, changed markers and saved baselines retain their current meaning. A more general framework is not justified merely to eliminate two small records.

### SP-08. One report entry point and a complete report context

**Problem.**
- `DetectionReportGenerator` (1,523 lines) is an all-static class that holds data types, image rendering (mask overlays, global maps, drift map, mosaics, cluster maps), text formatting and the export orchestration. 27 classes call its static helpers.
- The export has one entry, `exportTrackVisualizations`, with eight positional parameters. The API and the GUI reach it through `StandardDetectionPipelineService` (`run`, or `runDetectionPipeline` and `exportDetectionReport`) and its private export overload.
- `DetectionReportContext` takes 20 constructor parameters, copied one by one from the `PipelineResult`. Six more fields are set after construction.
- The context also has fallback branches "for isolated section callers": `data == null ? category.id(..) : data.finding(..)` and `runContext == null ? defaults`. That is production code kept only so tests can build sections without a full context.

**Proposal.**
- One `ReportExporter` with a single `export(ReportRequest)`: the request holds the result, frames, files, run context and inclusion.
- A context factory that builds `DetectionReportContext` completely from the `PipelineResult` and `ReportData`. Tests use the same factory, so the fallback branches go.
- Move map rendering to a `ReportMaps` class and formatting to `ReportFormat`.

**Benefit.** One way into the report, a context that is always complete, and section writers that depend on two small helpers instead of the whole generator.

**Verification.** Byte-identical report folders, minus the generation time, on the reference datasets.

**Issue review (2026-10-24; evaluate before implementation).**
- A complete context should distinguish valid absence (no WCS, catalogue or optional analysis) from an uninitialized context. Keep no-WCS, empty-result and partial-inclusion cases covered when deleting test-only fallback construction paths.
- **Clarify byte comparison.** `PipelineDiagnosticsSectionWriter.writeStageTimings` writes measured engine and stage durations into HTML. Excluding generation time alone cannot make independent report folders byte-identical. List and normalize volatile timing fields, compare deterministic outputs and rendered assets exactly, and assess performance separately, as in DEVELOPER.md.
- Preserve cancellation cleanup, atomic HTML publication and report-failure messages when replacing the overloads. Keep handing a run's overlays over before the report decision and export (SP-04, step 1), so they still survive a declined, failed or cancelled report.

### SP-10. One place for sky-math and statistics helpers

**Done (2026-10-24).** `SkyMath` and `Statistics` in `util` hold every copy. The identical copies are merged: `normalizeDegrees` (four), the field-of-view clamp (two), the law-of-cosines separation (two), and the wrap to [-180, 180] (`normalizeLongitudeDegrees` and `normalizeSkyAngleDeltaDegrees` gave the same result for every input). `DetectionReportAstrometry` no longer forwards the JPL observatory-code check and the longitude wrap. The variants that differ moved unchanged, under names that say how they differ, and `SkyMathTest` and `StatisticsTest` pin their contracts. The reports stayed byte-identical.

**To evaluate: why do the remaining variants differ, and do they need to?**
- **Angular separation, six variants** in `SkyMath`:
  - `separationDegreesCosineRule`: the track astrometry of the report and the SatChecker queries. NaN for a non-finite input, and it resolves only about 0.003″.
  - `separationDegreesHaversine` (photometry pixel scale) and `separationDegreesHaversineUnclamped` (diagnostics pixel scale): the same arithmetic, except that the unclamped one can give NaN for points almost opposite on the sky.
  - `separationArcsecHaversineRaDeltaInDegrees` (session catalogue matching), `separationArcsecHaversineDeltasInDegrees` (variable-star overlay) and `separationArcsecHaversine` (solar-system queries): they differ in where degrees become radians and in how the haversine is limited.
  - `separationDegreesAtan2`: the size and overlap of the catalogue's sky field.

  They agree to far below a milliarcsecond on the tested pairs. One haversine form would probably do, unless a threshold depends on the last bits.
- **Wrapping to ±180:** `normalizeSignedDegrees` keeps both ends; `wrapSignedDegrees` (hour angle and RA motion in the track astrometry) maps -180 to 180, and never returns for an infinite input.
- **Medians** in `Statistics`: the mean of the two middle values (`median` for Night & Equipment, `medianIgnoringNaN` for photometry, `medianOfSorted` for star shapes) against the upper middle value (`upperMedian` for the plate-solution correction, `Double.MAX_VALUE` when empty; `upperMedianIgnoringNaN` for the field depth). Several algorithms also take the upper middle value inline: `PlateCorrectionFitter` (median distances and stars per frame), `PolynomialFit` (residual clipping), `FieldDepth` (patch offsets), `NightEquipment.clippedMedian`, and the photometry running median and black point.
- Changing any of them is a change of numerical policy, not a refactoring. Show its effect on the reference datasets (correction selection, report figures, catalogue matches) and decide it on its own.

### SP-11. Fewer FITS helper classes; immutable frame metadata

**Problem.**
- FITS input is spread over small static classes: `FitsFiles` (32 lines), `FitsImages`, `FitsFormatChecker`, `FitsSequenceValidator`, `FitsPreparationService` (all static, despite the name), `FitsPixelConverter` and `DetectionInputPreparation`. `ImageProcessing.getImageHDU` forwards to `FitsImages`.
- "Service" means an instance with collaborators in some classes and a static utility in others.
- `FitsFileInformation` (445 lines) is mutable. Callers protect themselves with `snapshot()` and array copies, for example in `ReportRunContext` and `ProcessingSession.getFiles()`.

**Proposal.**
- Merge discovery, HDU access, format check and sequence validation into one `FitsInput` class. Keep the converters separate.
- Name static helpers consistently, and keep "Service" for instances.
- Make `FitsFileInformation` immutable, with timestamps normalised when it is built. The defensive copies can then go.

**Issue review (2026-10-24; evaluate before implementation).**
- **Keep container protection.** Immutable frame objects eliminate deep metadata copying, but callers can still replace or reorder elements of a returned array. Keep array copies or expose immutable collections; freeze the header map too.
- `MainApplicationPanel.showWcsWriteOutcome` currently updates a frame's header in place. Define a session-owned metadata replacement operation that updates the table, selection and dependent viewers after a successful WCS write. Test that existing runs keep their snapshots while new work sees the new solution.
- Separate metadata immutability from helper consolidation. Discovery, HDU decoding and sequence validation have different responsibilities; merge them only if the resulting dependency structure is clearer, not because there are several small files.

### SP-13. One way to report problems and diagnostics

**Problem.** Problems are reported in four ways:
- `System.err.println`, in about 15 places, for example in `DetectionConfigurationPanel`, `OutputFolders` and several report writers;
- `printStackTrace`, in `FitsFormatChecker`;
- `java.util.logging`, in `PlateSolveService`, `ApplicationSettings` and `SessionOwner`;
- `DebugOutput`.

**Proposal.** Report problems with `java.util.logging` (WARNING or SEVERE), and send diagnostics only through `DebugOutput`. If JTransient adopts JT-04, diagnostics can become `java.util.logging` at FINE too, and `DebugOutput` shrinks to setting that level.

**Issue review (2026-10-24; evaluate before implementation).**
- Consolidating problem logging is reasonable, but logging must not replace propagation of a failure that the caller needs to handle or cause the same error to be reported at every layer.
- Evaluate JT-04 before depending on it. Per-operation diagnostics and explicit disabled policies are documented API behaviour and are tested with independent concurrent engines. A process-wide logger level is not an equivalent replacement simply because the desktop admits one job. Preserve caller isolation or explicitly decide and document the contract change.

### SP-14. Package names that say what they hold

**Problem.** `util` (49 classes) is the application's core, not a set of utilities. It holds the services and the facade, domain types (`PlateSolutionCorrection`, `TrackOverlay`, `VariableStarOverlay`, `ExposureGroup`, `BlinkSequence`), FITS input/output and WCS. `util.reporting` (56 classes) is a subsystem, not a utility.

**Proposal.** Domain-named packages: `fits` (input, preparation, conversion, WCS), `detection` (pipeline services, run options, overlays), `platesolve`, `report` (with a `report.lookup` subpackage for the SatChecker/JPL/SkyBoT proxy and builders) and `skycatalog`. Do it last, as a mechanical move, and keep `DependencyRulesTest` in step.

**Issue review (2026-10-24; evaluate before implementation).**
- Update `build.gradle` source-set and resource include patterns as well as Java imports and `DependencyRulesTest`; the headless artifact currently explicitly includes `util/**`.
- `FitsFileInformation` and visualization/run-option types are exposed through public request/result signatures. Moving them changes consumer APIs even if the implementation move is mechanical. Identify those breaks, update API examples and release documentation, and run `check` plus the isolated `apiConsumerTest` against the staged artifact.

### SP-15. Pass the live-view sink with the run

**Problem.** `RunPreviewScope` binds the live view's sink to the run's thread with a `ThreadLocal`, so the loading loop and report writers can offer pictures without a parameter. It was a reasonable shortcut when nothing else was passed explicitly. After S14, the run already carries explicit options.

**Proposal.** After SP-08, carry the sink in the run request, an empty sink for API and CLI runs, and remove the `ThreadLocal` scope. Low priority: the scope is small and contained.

**Issue review (2026-10-24; evaluate before implementation).**
- Preserve lazy rendering: `RunPreviewSink.wantsPreview` is checked before constructing a picture, and `LiveFindings` currently creates no observer/worker for an absent sink. An empty sink must retain that no-work behaviour for API/CLI runs.
- Specify the sink's lifetime, worker-thread calls and failure isolation, and ensure completed requests do not retain windows or frame buffers. Keep this change narrow and low priority.

### SP-16. Check the UI at larger display scaling

What remains of the earlier visual-rules item. Inspect the empty and loaded main window, the settings pages, the Astrometry Config tab, the viewers and the progress dialog at 100, 150 and 200 % display scaling and with a larger base font. Fix the local widths and font sizes found in the Overview status, the table HTML and the viewer controls. Layout tests cover forms and base fonts; display scaling of the whole window has not been inspected.

**Issue review (2026-10-24; evaluate before implementation).**
- No additional architectural blocker was identified. Retain the real keyboard/focus checks while inspecting clipping, wrapping, scroll access and controls at each scale; visual consolidation must not alter the existing text-field and viewer-key rules.
- Keep local layout fixes separate from changing viewer settings policy in SP-19, so their effects can be assessed independently.

### SP-18. Upgrade the dependencies

**Done (2026-10-24)** in all three repositories: Gradle 9.8.2, nom-tam-fits 1.23.0, Gson 2.14.0 and FlatLaf 3.7.2. The reference outputs stayed byte-identical, saved settings files load unchanged, and compressed frames were checked against cfitsio (through astropy). Spinners keep the caret when they gain the focus, as before 3.6 (`UiStyle.spinner`). Each commit message holds its checks. What remains:

- **nom-tam-fits 1.23.1** (expected around 2026-12-15) fixes the dither sequence at blank and zero pixels (#908). With 1.23.0, dithered floating-point `.fz` frames still differ from cfitsio by about one quantization step in tiles that contain blank or zero pixels: 1 ADU after SpacePixels' 16-bit conversion, on a few hundred pixels of a frame. Integer frames are exact. Upgrade when it is released, and repeat the comparison with cfitsio.
- **Gson 2.15.0** was published on 2026-10-24, hours before the upgrade, and changes the default `Map` type to `LinkedHashMap`. Take it at the next upgrade, with the round trip of saved settings files.
- **`FitsFactory.hduFactory(Object)`** is deprecated since nom-tam-fits 1.22 and used in `FitsPixelConverter`, `XisfImageConverter` and `ArtificialStarInjector`. Replace it before a release removes it.
- **Java.** JTransient and JPlateSolve stay at release 21: a library that runs on the older LTS reaches more users. The application could move to Java 25 (LTS) as its runtime, and as the runtime bundled by the installer, since the newest LTS gets security updates longest. Decide it with [F06](FEATURE-REQUESTS.md#f06-windows-installer).
- **JUnit** stays at 4.13.2, the last JUnit 4. Moving the tests to JUnit 6 would change nothing in the application, and the GUI mechanics tests would have to move to AssertJ Swing's Jupiter variant. If it is done later, run the JUnit 4 tests on the Vintage engine and migrate test classes as they are touched.

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

**Issue review (2026-10-24; evaluate before implementation).**
- **Viewer lifetime is a product decision.** DEVELOPER.md deliberately permits independent inspection windows to retain the old session's data. Closing every viewer on import changes that workflow; decide the desired policy explicitly and update the manual and lifecycle tests. Do not describe the existing choice as an accidental leak solely because buffers remain while a window is open.
- The Preview Frame live-edit versus Apply policy is also a behaviour decision. Keep it separate from sharing image components and controls, and preserve real key/focus tests for each viewer.
- A central viewer owner must unregister disposed windows and preserve cancellation, buffer release and listener detachment. Merely hiding all windows does not establish resource release. Keeping Blink separate by composition remains appropriate.

## Removed from the previous plan (2026-10-23)

- **S16, prepared diagnostic states and re-export.** Dropped. No code path renders a saved result again, so nothing would read the prepared states twice.
- **The rest of D09 and D10.** Done. The selected-release check is part of the release procedure in DEVELOPER.md, and the engine side is JTransient JA-11.
- **D12, shared visual rules.** Reduced to SP-16.
- **The target architecture map, the pattern table, the ownership table and the "constraints" list.** What still applies is in DEVELOPER.md, as principles, patterns and things to avoid.
- **The completed-work archive and the request register.** Removed. Git history keeps them.
