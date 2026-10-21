# Design improvements: completed work, October 2026

> Historical record, archived 2026-10-21. These sections were moved out of [DESIGN-IMPROVEMENTS.md](../../DESIGN-IMPROVEMENTS.md) once their work was done, so that the plan holds only pending work and open decisions. They keep the decisions, the evidence and the validation as written at the time, including statements that later changes superseded. Current behaviour is described in [HIGH_LEVEL_DESIGN.md](../../HIGH_LEVEL_DESIGN.md), [DEVELOPER.md](../../DEVELOPER.md) and the [manual](../../MANUAL.md). Where a section names work that was still open when it was archived, that work continues in the plan.

Contents:
- [The 2026-10 review: assessment and scope](#overall-assessment)
- Findings: [D01](#d01-treat-file-replacement-as-a-shared-application-responsibility), [D02](#d02-give-every-background-job-an-owner-and-an-identity) (the parts done), [D03](#d03-extend-the-viewers-latest-request-wins-rule-to-stretch-previews), [D04](#d04-make-cancellation-failure-and-resource-cleanup-explicit) (the parts done), [D05](#d05-build-a-validated-settings-snapshot-instead-of-reading-a-mutable-panel), [D07](#d07-derive-availability-from-state-and-use-real-command-actions)
- [The architecture adoption review](#architecture-adoption-review), with the steps finished by 2026-10-20 (S1–S6, S9, S11)
- [The EventBus decision](#guava-eventbus-keep-or-replace) and [the first job-coordinator slice](#s2-and-s3-first-slice-a-job-coordinator-for-the-detection-jobs)
- [Delivery stages A and B](#delivery-plan-as-of-2026-10-20) and [the earlier completed-work list](#completed-work-removed-from-the-queue)

## Overall assessment

The application has a good foundation: Swing with FlatLaf, a separate detection engine, a usable Java API, shared image viewers, and report section writers. The next improvement should make **ownership, state and failure handling explicit**. Replacing Swing or reorganising every package would deliver less value than making a job, a settings edit and an imported session behave consistently.

The most valuable work is, in order:

1. Protect files during replacement and settings saves.
2. Prevent obsolete previews and overlapping jobs from changing the current UI.
3. Give long operations a clear lifecycle, honest cancellation and reliable cleanup.
4. Validate settings into snapshots, and derive command availability from application state.
5. Improve keyboard access, layout scaling and the distinction between application preferences, the current session and a completed run.

These are engineering priorities, not findings that every possible failure has already occurred. The evidence below distinguishes code that is present from consequences inferred from it.

## Review scope and limits

Reviewed startup and import, workflow buttons and table selection, background tasks and events, preview rendering, frame viewers, settings and persistence, the API and CLI boundary, processing services, report generation and live lookup support. Existing manual screenshots were inspected for visual context; some predate the latest control placements. Source references name methods so the findings remain useful when line numbers change.

**Coverage clarification (2026-10-20).** This is a broad review of subsystems and important execution paths. It is not a completed class-by-class audit against the proposed architecture, and there is no exhaustive inventory of the changes needed to reach it. D01–D13 identify evidenced problems and useful next steps; they are not proof that every remaining dependency and ownership issue has been found. The [architecture adoption review](#architecture-adoption-review) below completed that inventory on 2026-10-20.

This was a source and architecture review, with primary documentation checked for Swing, FlatLaf and Guava. It was **not** a fresh interactive usability study, fault-injection run, accessibility audit or performance benchmark. Statements about races and I/O failures describe reachable design risks inferred from the code; their frequency has not been measured. No application code, user settings, FITS files or JTransient algorithms were changed by this review. M81 false-positive detection remains a separate investigation.

## D01. Treat file replacement as a shared application responsibility

**Implemented (2026-10-19).**
- **One helper.** `util.StagedFile` writes into a unique temporary file beside the target and replaces the target in one atomic move. It falls back to keeping a backup when an atomic move is unavailable, and removes the temporary file on failure.
- **Where it is used:**
  - the plate-solve WCS write, which also reads the copy back first to check the image size and that its data is complete;
  - the settings files (`SpacePixelsSettingsFiles` stages both files before replacing either, and names the file that failed);
  - the application config and profile migration;
  - the session files (Auto-Tune results, plate correction, sky catalogue), report text files, the lookup cache, and converted, decompressed and prepared FITS copies.
- **Settings Save** marks the settings saved only after both files are replaced.
- **Tests** inject failures while writing, during replacement (a folder in the target's place, and on Windows a frame held open), on either settings file, in the non-atomic fallback, and for a truncated FITS copy.
- **Real data:** a WCS write on a copy of a klangwolke-2 frame (121 MB) left the pixels identical and kept the other header cards. A klangwolke-2 batch run wrote the same `report-data.json` findings as before.

The original review follows.

**Evidence.** [PlateSolveService.updateFitsHeaderWithWCS](../../src/main/java/eu/startales/spacepixels/util/PlateSolveService.java) writes `<original>.tmp`, deletes the original, then calls `renameTo`. If deletion succeeds but renaming fails, the original path is gone. A temporary replacement may remain, so this is not proof that all image data has been destroyed, but recovery is now manual. The temporary filename is also fixed rather than unique.

[DetectionConfigurationPanel.savePersistedSettings](../../src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java) opens both settings files with `FileWriter` before writing either. Opening the second file can fail after the first has already been truncated. A later write or close can fail too. The saved-state marker is updated inside the try block before both resources have finished closing. [SpacePixelsAppConfigIO.write](../../src/main/java/eu/startales/spacepixels/config/SpacePixelsAppConfigIO.java) also writes directly to its destination. These failure paths were not exercised on user files.

**Proposal.** Extract a small file-publication helper from the pattern already used by `AtomicReportFile`: unique temporary file beside the target, complete and close the write, validate as appropriate, then replace. Prefer an atomic move where supported. Specify a recovery/backup strategy when it is unavailable; a regular replacement fallback is not automatically crash-safe. Never deliberately remove the only original before publishing its replacement.

Stage both settings serialisations before replacing either, and mark the editor saved only after the selected publication policy succeeds. **Two individually atomic file moves are not a two-file transaction.** Either provide rollback/recovery for a partially completed pair or expose which file failed and retain an unsaved state. Keep the current profile formats and migration backups; combining all preferences into a new format is not necessary for this fix.

**Acceptance.** With disposable fixtures, inject failure before replacement, during replacement and on the second settings file. Verify the previous FITS path remains readable, recovery files have an intentional policy, and the UI does not claim a failed save succeeded. This is SpacePixels work; it needs no detection-engine change.

## D02. Give every background job an owner and an identity

**Status (2026-10-20).**
- **Done:** every long job goes through `JobCoordinator` (S2/S3).
  - The coordinator admits a job on the EDT before its worker starts.
  - Each job has a handle with its id, kind and session.
  - Each job ends with one typed outcome, delivered once after the window is unlocked. Cancelled is one of the outcomes (D04).
  - A late progress report from an ended job is dropped.
- **Deliberate exception: the sky-catalogue fetch** (with the plate-solution check that follows it) stays outside the coordinator. It is bounded network work and does not lock the window, so it may run beside a job, as the proposal below asks for network work. It has its own indicator and Cancel. It runs one at a time on its own thread (`MainApplicationPanel.startSkyJob`), and that thread is its identity: a result whose thread is no longer the current one is dropped, so a new import discards the previous session's fetch.
- **Open:** the session in a job handle is the `ImageProcessing` of the import, a placeholder until the session object (S7). The acceptance check "cannot apply A's result to a new session" is complete once S7 exists.

**Evidence.** [MainApplicationPanel](../../src/main/java/eu/startales/spacepixels/gui/MainApplicationPanel.java) starts work from listeners, then reacts to a started event posted by the worker. The lock is therefore acquired asynchronously after submission. `lockUI` stores enabled flags recursively; another lock clears that snapshot, and a finish event calls `unlockUI` without checking which job owns the lock. `EngineProgressUpdateEvent` carries only a percentage and message. `DetectionFinishedEvent` mixes report, quick-preview and count fields; inspection and star-mask work use it to borrow detection's progress handling.

The current modal dialog prevents many user interactions once it is visible. That reduces exposure, but does not establish a synchronous admission rule or make overlapping lifecycle events safe. Sky fetching has its own indicator and does not post this generic progress event, so the earlier example of sky-fetch percentages directly moving the modal bar was too broad.

**Proposal.** Add a small application-owned job coordinator. On the EDT, validate the command and reserve its resource slot **before** launching a worker. Record a job ID, session ID, job kind, stage, progress and terminal outcome. Ignore updates from obsolete IDs. Model heavy image processing separately from bounded network work; decide explicitly whether catalogue fitting, preview loading and full detection may overlap given memory use. Do not put all work into one unrestricted executor, or queue many full-resolution previews.

Use typed outcomes such as completed report, imported session, tuned settings, cancelled and failed. An `Object result`, several nullable fields or `JobFinished<T>` subscriptions on Guava would weaken this: Guava does not support parameterised subscription types. A typed job handle can return its result directly, with an adapter for existing UI lifecycle messages.

**Acceptance.** Starting the same command twice admits only the permitted work. A late progress/finish event from job A cannot unlock job B, change B's status or apply A's result to a new session. Tests should assert this without constructing the entire main window.

## D03. Extend the viewer's “latest request wins” rule to stretch previews

**Implemented (2026-10-19).**
- **One render at a time.** `gui.StretchPreviewRenderer` renders the Image Stretch previews on one `SwingWorker`, as `SequenceViewerFrame.prepareCurrent` prepares frames.
  - A request carries the session (`ImageProcessing`), the frame's file and the stretch.
  - A request made during a render waits, replacing any request already waiting.
  - A result is shown only if its request is still the newest.
  - Before, every change started its own thread that read a whole frame, and every result was shown in the order the threads finished.
- **What makes a result stale:**
  - any change of frame or stretch, already before the 250 ms debounce fires;
  - hiding the tab;
  - another import, which also removes the previews of the previous session.

  While a render is due, the frame line ends in "rendering…". Once a result is shown, the label and the images describe the same request. With no frame selected, the previews are removed.
- **Event removed.** `GeneratePreviewsTask` returns the two images and now closes the frame on failure too. `PreviewGenerationFinishedEvent` is gone.
- **Tests:** `StretchPreviewRendererTest` holds an earlier render until a later request is made. It checks that:
  - only the later request's images and both histograms are shown;
  - waiting requests are replaced;
  - a cancelled render is not shown;
  - one render runs at a time.
- **Window harness**, on four klangwolke-2 frames copied to a scratch folder. It made two frame changes and two stretch changes while previews rendered, hid the tab during a render, and imported again. Each time:
  - the shown images and both histograms were identical to a direct render of the selected frame with the current stretch;
  - the frame label named that frame.
- **Not done:**
  - an abandoned render is not interrupted; it finishes its read and is dropped;
  - preview sampling (F05) is unchanged.

The original review follows.

**Evidence.** [StretchPanel.executePreviewTask](../../src/main/java/eu/startales/spacepixels/gui/StretchPanel.java) starts `GeneratePreviewsTask` after a debounce timer. Debouncing already exists and should stay. However, `PreviewGenerationFinishedEvent` (since removed) has images and success only: no session, frame, stretch parameters or request number. `onPreviewGenerationFinished` accepts every result.

**Consequence inferred from the code.** If request A is still loading when B starts, and A finishes last, A can replace B's images while the frame label or controls describe B. Debouncing limits starts close together; it cannot order jobs already running. There is also no per-preview worker handle to limit in-flight full-frame reads.

**Proposal.** Capture a preview request containing session ID, file identity, stretch snapshot and increasing generation. Allow one in-flight render with at most the newest pending request. Apply a result only if its generation is still current; invalidate it on session changes and disposal. Use cancellation when the underlying read supports it, but keep the identity check even after requesting cancellation. `SequenceViewerFrame.prepareCurrent` is an existing local model for worker/target checks, rather than another framework to introduce.

**Acceptance.** Force A to finish after B and verify the visible image, histogram and frame label all describe B. Include rapid parameter edits, import of another session and closing the view. This requires no JTransient algorithm change.

## D04. Make cancellation, failure and resource cleanup explicit

**Status (2026-10-20).**
- **Done:**
  - **One ending per job (S1).** Every job ends with its outcome or the out-of-memory exit.
  - **Real reasons (S11).** Failures are reported with their real reason.
  - **Typed outcomes (S3):** completed, declined, cancelled and failed. A declined report is not a failure, and a declined or cancelled import keeps the open session.
  - **One out-of-memory policy (`JobFailures`).**
  - **Cancel in the progress dialog.** It shows a stopping state, and the job stops at its next progress report; the job's own clean-up runs as it leaves. Closing the dialog cancels instead of exiting. Plate solving, the WCS write and Blink cannot be cancelled, and their dialog shows no Cancel.
  - **The report reports its progress (2026-10-20).** Measured on klangwolke-2 (33 frames of 61 MPix), the report reported once, at 90 %, and then nothing for 107–118 s, so a cancel there waited up to two minutes. Profiling put two thirds of that time in Unclassified Transients (35 %) and Likely Artifacts (30 %). `ReportProgress` now reports each step and each item of the slow steps. A cancel during the report then stopped in 0.10–0.16 s. The report output is unchanged (klangwolke-1: the same `report-data.json` and files).
  - **Clean-up after a cancel.** A cancelled detection removes the report folder it was writing; a cancelled iterative run keeps its finished passes with an index that says so. Every run gets a folder of its own (`OutputFolders`).
  - **Auto-Tune cancel fixed.** The calibrated tuner reports its progress inside a `try` that catches every exception, so a cancel during tuning came back as a failed result: the status bar said "Auto-Tuning completed successfully." and the Overview "Auto-Tune found no usable settings", naming the cancel exception. A cancel made while the tuner runs now ends as cancelled.
  - **Copy details** in the failure messages (`FailureDialog`): the message, the versions and the stack trace.
  - **Open last report** in the Detect group: the newest report beside the frames, found on import and after each run.
- **Open:**
  - **Engine waits.** Measured on klangwolke-2, a cancel during frame extraction took 26 s: the engine's worker threads finish their frames before the cancel takes effect. Other engine stages waited up to 6–29 s between reports (photometry's ensemble solution the longest), Auto-Tune up to 12.6 s. Shorter waits need a cancellation contract in JTransient; a separate engine discussion.
  - **The viewer check:** closing a viewer while it prepares a frame.

**Evidence.** [ProcessingProgressDialog](../../src/main/java/eu/startales/spacepixels/gui/ProcessingProgressDialog.java) is modal, fixed-size, and its close action exits the application after confirmation. Blink and sky fetching have separate cancellation mechanisms. Declining report generation after detection is represented as failure in [DetectionTask](../../src/main/java/eu/startales/spacepixels/tasks/DetectionTask.java), even though detection completed.

There are inconsistent error paths too. [ManualTransientInspectionTask](../../src/main/java/eu/startales/spacepixels/tasks/ManualTransientInspectionTask.java) calls `engine.shutdown()` only after a successful `detectTransients`; its catch logs an OOM without rethrowing it despite the comment about a global handler. [StarMaskStackTask](../../src/main/java/eu/startales/spacepixels/tasks/StarMaskStackTask.java) does shut down in `finally`, but its OOM branch logs without posting a terminal event or forwarding the error. Consequently, those paths can leave progress handling unfinished; an uncaught handler cannot handle an error swallowed by a catch. This was established from the branches, not by inducing OOM in the running application.

**Proposal.** Use a common job runner for ordinary start/progress/outcome handling and resource cleanup. Put engine shutdown and file closure in `finally`/try-with-resources. Have one fatal-error policy instead of copying JVM exit timers into individual tasks; after a genuine OOM, do not promise the session can safely continue. Distinguish detection succeeded/report skipped, user cancellation, partial export and actual failure.

A Cancel control should request cancellation, show a stopping state and retain resource ownership until work actually ends. For a stage that cannot stop safely, say it will stop after that stage. `Future.cancel` alone does not prove that engine computation or blocking I/O stopped. [SwingWorker's contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/SwingWorker.html) supplies worker/EDT completion mechanics; application and engine code still need a cooperative policy. If JTransient needs a new cancellation contract, discuss that engine change separately before implementing it.

**Acceptance.** Fail each ordinary stage, decline export, request cancellation, and close a viewer while it prepares data. Verify one terminal outcome, resource release and a correct command state. Test fatal-policy routing through an injected policy rather than exhausting real memory. Keep a “Details / Copy error” path for diagnostics and a persistent result link after success.

## D05. Build a validated settings snapshot instead of reading a mutable panel

**Implemented (2026-10-19), in the settings tab.**
- **One step for saves and runs.** `DetectionConfigurationPanel.checkedSettings(...)` is behind Save, Detect Moving Targets, Detect Iteratively, Manual Transient Inspection, Preview Frame, Auto-Tune and Test Star Mask. It returns a copy of the session `DetectionConfig`, hidden settings included, so later edits cannot change a run. `getJTransientConfig` is gone.
- **Invalid text is visible and stops saves and runs.**
  - `CheckedNumberEditor` takes only text that is one complete number within the setting's range. Before, "4,5" was read as 4, and "4.5" in a whole-number field as 4.
  - Other text stays when the field loses focus; before, it silently went back to the previous value. The field is outlined in red, and the footer says what to enter.
  - A refused save or run names itself, brings the tab forward and puts the cursor into the field. A setting hidden by a filter or by the expert switch is found with a search for its title.
  - A setting whose feature is switched off does not count.
- **Pairs in one place.** `config.DetectionSettingsRules` defines the six ordered pairs and the check for finite numbers. The tab moves the same setting as before when an edit crosses a pair, now only in `applySettingsToMemory` (the spinner listeners are gone), and says so in the footer, for example "Grow Sigma lowered to 4.0 (cannot be above Detection Sigma)".
- **Tests:** `DetectionSettingsRulesTest` and `CheckedNumberEditorTest`.
- **Window harness**, on a copy of the maintainer's profile. Its 43 checks cover refused saves and runs, focus, filters and expert settings, Revert, a NaN in a hidden setting, and a run's copy against later edits. Compared with the previous code:
  - the settings a run receives were byte-identical;
  - all 75 number fields were identical in value, type and text.
- **Not done:** profile loading and the API do not apply `DetectionSettingsRules` yet; they stay unchanged for now. Save keeps its name.
- **Fixed afterwards:** a value with more decimals than its field showed was kept rounded; 4.25 typed where the field showed 5.5 became 4.2. The field first showed the new value with its old number of decimals, and the auto-apply committed that text back. This predated D05. A field now shows every decimal of its value.
- **Preview Frame (decided by the maintainer and implemented, 2026-10-20).** Preview Frame shows Detection Sigma, Grow Sigma and Min Detection Pixels, so they can be tried on the frame:
  - **Shared models.** Their spinners share the models of the tab (`perFrameDetectionSettings()`), so an edit there is an ordinary unsaved edit, pairs included.
  - **Re-detect.** The frame on show is detected again 300 ms after the last change.
  - **A fresh copy per frame.** Every frame is prepared with a copy of the settings in use (`settingsInUse()`), taken on the EDT. Unlike `checkedSettings`, it changes nothing on the tab. Opening the window still goes through `checkedSettings`.
  - **Invalid text** keeps the last valid value, and a note says what to enter.
  - **Locked during jobs,** like the tab.
  - **Keys.** The window's keys do not fire while a text field has the focus (`ViewerSupport.bind`): "1" typed there does not zoom.
  - **Checked** with a window harness on three Apophis frames, with real key and mouse events.
- Before D05, a frame stepped to in an open Preview Frame also used the settings of that moment; from D05 until this change it kept the settings it was opened with.

The original review follows.

**Evidence.** [DetectionConfigurationPanel.commitSpinners](../../src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java) catches and ignores `commitEdit` failures. `applySettingsToMemory` changes the live config field by field and logs a broad exception. `getJTransientConfig` returns that same mutable object. Detection and Auto-Tune tasks retain that reference at construction, whereas manual inspection clones it. Standard detection does clone the effective config later; this is useful, but happens after loading, rather than defining one uniform click-time boundary.

Invalid text can therefore be rejected by the editor while a Save or Run path continues using the previous model value. Constraints are also implemented in spinner listeners and normalisation code, while callers of the API do not use those widgets. This is a validation/feedback weakness, not evidence that the engine receives every invalid edit.

**Proposal.** Read the editor into a draft; commit and validate it; then create the run snapshot or update the session settings as one operation. Return structured validation errors, focus the relevant input, and explain any automatic adjustment. Reuse applicable validation in the API and profile-loading boundary, with mode-specific policy where needed. Reject non-finite values and inconsistent pairs explicitly rather than relying only on spinner bounds.

Preserve auto-apply for valid edits and the current hidden-field behaviour. Build on a clone of the loaded configuration; do not rebuild it from visible rows and lose hidden values. All long tasks should receive the same kind of click-time config snapshot, frame identities and export choices. Prefer “Save as startup defaults” wording where that is what Save does; saving is not the step that first activates the current values.

**Acceptance.** Invalid typed input prevents Save/Run with an inline explanation. Editing the UI after a run starts cannot change that run. Revert, defaults, migration, auto-tuned values and hidden fields retain their current documented semantics. Validation that changes detection decisions needs the appropriate real-data comparison and maintainer discussion.

## D07. Derive availability from state and use real command actions

**Implemented (2026-10-20), as S4 in two slices.**
- **Commands:** `gui.CommandAvailability` holds the rules, and `gui.CommandAction` is a shared Swing `Action`. Every workflow button of the Main tab is now a command:
  - Import, Convert to Mono, Batch Stretch;
  - Plate Solve, Fetch Sky Catalogue;
  - Blink, Preview Frame, Manual Transient Inspection;
  - Auto-Tune, Detect Moving Targets, Detect Iteratively.
- **Shared entry points:** button, shortcut, table menu, the Overview's buttons, the settings tab's Ctrl+D and a dropped folder use the same command. No entry point calls `doClick()`, copies another control's enabled state, or follows the Overview's Run button any more.
- **The lock** no longer saves and restores enabled states. While a job runs it disables the panel's other controls and closes the session tabs. Afterwards it enables them again and opens the tabs only with a session. Two locks released once now leave nothing disabled; the saved-state map restored the second lock's disabled states.
- **Tabs** are addressed by their panel, not their position.
- **The policy is unchanged,** except for two places that did not follow it:
  - Plate Solve stayed enabled after the selection was cleared;
  - the Overview's Preview Frame opened on a colour session.

  Also, Ctrl+B now stops a blink, as Stop Blinking does.
- **Checked by:**
  - `CommandAvailabilityTest` (8 cases);
  - a window harness with real key events and a real right-click: 46 checks before an import, on mono and colour sessions, through selection changes, the batch-stretch switch, a blink, a job and a double lock.
- **Not done:** the row-sorter constraint below still applies when sorting is added.

**Evidence.** [MainApplicationPanel](../../src/main/java/eu/startales/spacepixels/gui/MainApplicationPanel.java) keeps enable rules in `lockUI`/`unlockUI`, selection listeners, workflow refresh, import handlers and helper methods. A saved component-state map records what happened to be enabled rather than why. The main Auto-Tune button follows another button's enabled property. Shortcuts call `button.doClick()`, and the table menu copies button checks. These controls are partly synchronised already, but the button hierarchy is serving as the application model.

**Proposal.** Calculate a small immutable availability model from session facts, valid settings, selection and job ownership. Each entry contains an enabled flag and reason. Apply it to shared `Action` objects for commands that have several entry points. The action dispatches a typed request; it does not discover its policy by walking Swing components. Oracle's [Action contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/Action.html) explicitly supports sharing command properties and enabled state across controls; it does not imply every local listener needs a command class.

Keep the useful disabled-button explanations, generated from the same availability result. Remove numeric tab-index assumptions as affected code is touched; address the tab/component by identity. Avoid a state machine containing every possible UI combination: a session lifecycle plus independently computed capabilities is sufficient initially.

**Table enhancement constraint.** The current table has no installed row sorter, so its direct selected-row-to-model indexing is not an existing sorting bug. If sorting/filtering is added, convert view rows to model rows in all selected-file and solve-result paths, and use file/session identity in events instead of a saved visible row number. Return numeric values and column classes before offering numeric sorting.

**Acceptance.** Button, shortcut and menu have identical availability and behaviour after imports, colour conversion, selection changes, errors and job completion. A late event cannot re-enable a prohibited operation. Preserve the current workflow policy first; relaxing locks is a separate product decision.

## Architecture adoption review

**Next step (2026-10-20).** The maintainer wants to move SpacePixels toward this architecture. Before any code changes for it, review the whole codebase and work out the steps that lead there. D01–D13 came from a broad review, not a class-by-class one, so they are a starting point, not the full list.

**Scope.**
- **Packages:** every production package, including the build wiring and the relevant tests:
  - `api`, `config`, `events`, `gui`, `tasks`, `tools`;
  - `util`, including `util.reporting` and `util.skycatalog`.
- **Out of scope:** JTransient and JPlateSolve are external contracts; their internals are not part of this review.
- **Paths to follow through the GUI, the API and the CLI:**
  - import and conversion;
  - plate solve, and catalogue fetch and correction;
  - preview and inspection;
  - Auto-Tune;
  - standard and iterative detection;
  - report and lookup.
- **Outcomes to follow:** cancellation, failure, session replacement and closing, as well as success. Searching the imports helps with the inventory, but it does not show that a path behaves correctly.

**What to record.**
- **Per subsystem:**
  - what it does now, and where its boundary should be;
  - which way its dependencies actually point;
  - who owns its state and resources, and which thread it runs on.
- **Per class:** **retain**, **move responsibility**, **adapt boundary** or **investigate**, with the reason. Propose a change only where the code conflicts with the target.

**Result.** The class inventory, the traced paths and the evidence are kept in [docs/architecture-review.md](../architecture-review.md). The steps they lead to are listed below. Each step has:
- a delivery stage;
- what must stay compatible;
- how the step will be verified;
- a link to D01–D13 where those already cover the work.

Record what the review did not cover and any deliberate exceptions. The review is complete when every production class and every entry path is accounted for.

**Progress.**

| Part | Packages | Status |
|---|---|---|
| 1. Workflow core | `gui`, `tasks`, `events` | Done 2026-10-20 |
| 2. Services and boundaries | `util`, `api`, `tools`, `config` | Done 2026-10-20 |
| 3. Presentation | `util.reporting`, `util.skycatalog` | Done 2026-10-20 |

**The review is complete (2026-10-20).** All 166 production classes are accounted for:
- 114 are retained;
- the 18 event classes retire with the bus;
- 34 change.

The coverage limits and the deliberate exceptions are listed in the [review summary](../architecture-review.md#review-summary).

**Steps from the review.** The review has the details. A step is accepted when the maintainer takes it up; its status is in the table, and the steps without one are still proposals.

| Step | Stage | Work | Covers |
|---|---|---|---|
| S1 | A | **Done 2026-10-20 (`01d983e`).** Every job ends with a finished event or the out-of-memory exit; before, some failures left the UI locked. | D04 (new evidence) |
| S2 | B | **Done for every job, 2026-10-20** (`54e32b6` to `23acc9b`). A job coordinator: admission before the worker starts, and job and session identity. Session identity is a placeholder until S7 | D02 |
| S3 | B | **Done 2026-10-20**, with S2: every job returns a `JobOutcome` (completed, declined or failed; cancelled since D04's Cancel, 2026-10-20). Typed outcomes per job kind | D04 |
| S4 | B | Shared actions and availability: one Detect action; tabs and solve results addressed by identity. Solve results are matched by file since the plate-solve slice (2026-10-20). **Done (2026-10-20):** `CommandAvailability` and one `CommandAction` per workflow command, shared by button, shortcut, table menu, Overview and folder drop; the lock without saved states; tabs by panel | D07 |
| S5 | B | **Done 2026-10-20 (`01d983e`, with S1).** Use `BatchStretchTask` instead of its inline copy | — |
| S6 | B–C | **Done 2026-10-20.** No GUI in `tasks`: their own logger and out-of-memory policy, and results returned instead of windows and dialogs opened. `DependencyRulesTest` keeps `tasks` and `jobs` free of `gui` and Swing | new |
| S7 | C | A session object, and the application config loaded once | D06, D11 |
| S8 | C | The WCS write and session-folder I/O off the EDT. **The WCS write is done (2026-10-20)**: it runs as a job. The session-folder reads and writes (sky catalogue and correction on import, Auto-Tune results, the correction switch) are still on the EDT | D08 |
| S9 | B–C | **Done 2026-10-20 (`23acc9b`)**: all 18 events, the bus and the Guava dependency are removed. Jobs report through the coordinator; a new session reaches the panels through the window's session listeners | EventBus decision |
| S10 | C | Split `ImageProcessing`: its session part into the session object, with batch operations and import as their own services. Keep it as a facade, and remove its dead methods | D06 |
| S11 | A–B | **Done 2026-10-20 (`d255b68`), the message fix.** Report failures as failures. Before, a report-export failure said "aborted by user due to high track count", a failed iterative pass was still listed, and an Astrometry.net failure showed two dialogs. Typed outcomes follow with S3 | D04 (new evidence) |
| S12 | C | One preparation flow for the GUI and the API, with the GUI's questions asked on the EDT. Typed import outcomes are done: since `74bbded` a declined import keeps the open session. The questions are still asked from the import worker | D08, D06, F07 |
| S13 | B–C | No `gui` and no Swing in `util` | new |
| S14 | D | An explicit run context: the site, rendering options and Auto-Tune frame limit go with the run, instead of coming from the home folder and static fields | D09, D10 |
| S15 | D | One source of finding labels and categories. Today T1, ST1, A1… are computed in seven places that agree only by walking the tracks in the same order | D09 |
| S16 | D | Prepared diagnostics with explicit states (*measured*, *unavailable*, *failed*), and one failure policy for optional report sections. Today a failed field-depth measurement reads as "not measured", and other section failures abort the report | D09 |

S1 and S11 were the small correctness fixes that needed nothing else first; both are done.

**Starting points at the time of the review.** Since then the bus and its events are gone (S9), and the tasks no longer build Swing views (S6).
- `ApplicationWindow` owns the shared event bus.
- Task classes post the lifecycle and progress events, and some also build Swing views.
- `ImageProcessing` still asks import questions in Swing dialogs and logs through `ApplicationWindow.logger`.
- The existing findings that cover parts of the work:
  - D02/D04: the job lifecycle;
  - D06/D08: the session and interactive preparation;
  - D09/D10: the report and settings contracts.

**Constraints.**
- Preserve the public API, the settings formats, the detection measurements and the current viewer and report behaviour, unless a separately agreed feature changes them.
- Once a boundary is extracted, protect it with a dependency rule that tests can check, such as "application services do not depend on Swing or `gui`".

## Guava EventBus: keep or replace?

**Implemented (2026-10-20).** The maintainer chose option 2 after the architecture review. The bus and Guava were removed the same day, in `23acc9b`, when the last job moved to the coordinator.

**Accepted (2026-10-20): option 2, retire it gradually.** In practice this meant:
- no separate migration project;
- no new events on the bus;
- each event is removed when its workflow gets a typed job handle or a session notification (S9);
- Guava is removed with the last event.

The question and the evidence follow.

The maintainer asked whether SpacePixels should keep Guava's `EventBus` or move to something else.

**Current use (2026-10-20).**
- **The bus:** `ApplicationWindow` owns one synchronous bus.
- **The events:** 18 event classes in `events`. They carry the start, progress and end of import, conversion, stretch, blink, plate solve, Auto-Tune and detection, plus the blink window's close.
- **Publishers:** 13 classes post these events: the task classes, `BlinkFrame`, `MainApplicationPanel` and `DetectionConfigurationPanel`.
- **Subscribers:** 24 `@Subscribe` methods in four GUI classes receive them: `ApplicationWindow`, `MainApplicationPanel`, `DetectionConfigurationPanel` and `StretchPanel`. All four are registered for the window's lifetime, and each method moves its work to the EDT itself.
- **The dependency:** Guava is used for nothing else, so retiring the bus would also remove the dependency.

**Evidence from part 1 of the review (2026-10-20).**
- 14 of the 18 events have a single subscriber, `MainApplicationPanel`. They carry one job's start, progress and end.
- Only two kinds of message have several listeners:
  - "the session changed" (import, 4 subscribers);
  - "tuning started or ended" (2 subscribers).
- Each use has a natural replacement in the target design:
  - the job messages: a job handle (D02/D04);
  - the session change: a notification from the session model (D06);
  - the tuning state: job state.

This supports option 2. See [the review](../architecture-review.md#the-event-bus-in-part-1).

**For keeping it.**
- It works, and it is small and contained.
- The problems in D02 and D04 do not come from the bus itself:
  - the events carry no job or session identity;
  - the UI lock is taken after the worker has started.

  Replacing the bus alone fixes neither problem.

**For replacing it.**
- Guava's own [guidance](https://github.com/google/guava/wiki/EventBusExplained#avoid-eventbus) advises against using it. Its reasons that matter here:
  - who posts an event and who receives it is not visible in the code;
  - a subscription cannot be generic (no `JobFinished<T>`);
  - the delivery thread cannot be controlled;
  - an exception in a subscriber is only logged and never reaches the poster.
- Typed job handles (D02/D04) would return results and failures directly to whoever started the job.

**Options.**
1. **Keep it as it is.**
2. **Retire it gradually.** When D02/D04 give a workflow a typed job handle, its progress and outcome go through that handle, and its events are removed. Guava goes when the last event does.
3. **Replace it with another event-bus library.** This keeps the same hidden coupling, so it gains little.

**Suggestion made before the decision.** Option 2:
- no separate migration project;
- no new events on the bus in the meantime;
- plain Java (listener interfaces, `SwingWorker`, `PropertyChangeSupport`) covers what remains, so no new library is needed.

To ground the decision, the review should list every publisher, subscriber and registration, including inline workers.

## S2 and S3, first slice: a job coordinator for the detection jobs

**Implemented (2026-10-20), in `54e32b6`, `f945ad8`, `281246f` and `953f3cf`.** The maintainer accepted the design and checks the four buttons in the GUI. Later slices moved the other jobs, all on 2026-10-20:
- Convert to Mono and Batch Stretch;
- Plate Solve;
- Auto-Tune;
- Blink;
- Import.

Every job now runs under the coordinator.

**Scope.** The four jobs that share the detection lock, progress dialog and finished event today:
- Detect Moving Targets;
- Detect Iteratively;
- Manual Transient Inspection;
- the star-mask stack behind Test Star Mask.

The other jobs (import, plate solve, Blink, Auto-Tune, Convert to Mono, Batch Stretch) stay on the bus for now and move one at a time later.

**New types**, in a new package `eu.startales.spacepixels.jobs` with no Swing or `gui` dependency:

| Type | Role |
|---|---|
| `Job<R>` | The work. `run(JobContext)` runs on the worker and returns a `JobOutcome<R>` |
| `JobContext` | What a running job may use: `progress(percent, message)` and its `JobHandle` |
| `JobOutcome<R>` | A sealed type: `Completed(R result)`, `Declined(String reason)` (the user said no, such as the report-size question) or `Failed(String reason, Throwable cause)` |
| `JobHandle` | The job's identity: a number, its `JobKind` and the session it was started for. Until S7 brings a session object, the session is the `ImageProcessing` instance |
| `JobKind` | `DETECTION`, `ITERATIVE_DETECTION`, `TRANSIENT_INSPECTION`, `STAR_MASK_STACK`; more kinds later |
| `JobCoordinator` | Admits, starts and finishes jobs. It is called on the UI thread and given two executors: one for the worker, one for the UI thread (`EventQueue::invokeLater` in the application, a direct executor in tests) |

**How a job runs:**
1. **Admission,** on the EDT, before any thread starts. The coordinator refuses the job, with a reason, when another coordinated job is running or when the window is locked by a job still on the bus. This is today's policy; only the timing changes.
2. **Locking:** an admitted job tells its busy listener at once. `MainApplicationPanel` implements that listener: it locks the window and shows the progress dialog. Today this waits for the worker's start event.
3. **Progress:** reaches the dialog on the EDT, and only while that job is the current one.
4. **Ending:** the coordinator turns the job's outcome into one call of the job's outcome handler, on the EDT. It then releases the lock. A thrown exception or `Error` becomes `Failed` with the reason. Running out of memory is thrown on the worker thread, as S1 established.

| Job | Its outcome handler, at the place that started it |
|---|---|
| Detection | `Completed(report file)` asks whether to open the report. `Declined` and `Failed` show today's messages |
| Iterative | Same, with the results folder. The frame-limit question moves from the worker to the button, before admission |
| Inspection | The job returns its frames and transients; the handler opens `TransientInspectionFrame`. The task no longer opens windows or takes the `StretchPanel` (part of S6) |
| Star-mask stack | The handler fills the master-stack cache and opens the explorer, as the callback does today |

**What is removed:**
- `DetectionStartedEvent` and `DetectionFinishedEvent`, with their bus subscribers. Only these four jobs use them, and this is the first retirement under S9.
- The four unused fields of the finished event go with it.

`EngineProgressUpdateEvent` stays for the jobs still on the bus.

**Ownership:**
- `ApplicationWindow` creates the coordinator.
- `MainApplicationPanel` and `DetectionConfigurationPanel` reach it through the window, as they reach each other today. S4 and S7 change that later.

**Compatibility.** Nothing visible changes, apart from the admission timing:
- the same progress dialog, questions, messages, report prompt and windows;
- the same detection output;
- the API and CLI are unaffected.

**Verification:**
- **Coordinator tests,** with a controllable worker executor and a direct UI executor:
  - a second job is refused while the first runs;
  - the lock is taken before the worker starts;
  - each outcome is delivered once, on the UI executor;
  - progress arriving after the outcome is ignored;
  - an exception and an `Error` both become `Failed`;
  - out of memory leaves the worker thread.
- **Job tests:** the four jobs adapted from `TaskFailureEventsTest`, asserting outcomes instead of events.
- **Full test suite,** and klangwolke-1 with identical `report-data.json`.
- **A GUI check** of the four buttons on a small dataset.

**Delivery, in four commits:**
1. The `jobs` package and its tests, with no users yet.
2. The star-mask stack and inspection on the coordinator.
3. Detection and iterative detection on the coordinator.
4. Remove the two events, and update the architecture and plan documents.

## Delivery plan, as of 2026-10-20

| Stage | Work | Why this order |
|---|---|---|
| Architecture review | Done 2026-10-20: steps S1–S16 above, mapped to the stages below. The EventBus question was decided the same day: retire it gradually | Makes the remaining scope explicit before the architecture work grows. Focused correctness fixes need not wait. |
| A: focused correctness | **Done (2026-10-19).** D01 file publication; D03 preview identity; D05 invalid-editor feedback | Small, concrete failure modes; do not wait for a broad architecture migration. |
| B: workflow foundation | **Mostly done (2026-10-20).** D02 ownership/admission and D04 outcomes/cleanup, for every job rather than only the detection, inspection and star-mask jobs. D07 actions done with S4. Open: the rest of D04 (see its status) | Makes lifecycle explicit before replacing UI locking. |
| C: session boundary | D06 application preferences/session handover; D08 background WCS write and metadata restoration | Reduces UI/service coupling and protects session transitions. |
| D: presentation and polish | D09 prepared report data; D10 one page of typed settings bindings; D11 results/readiness; D12/D13 layout and access | Builds on explicit state; accessibility fixes that are independent can start earlier. |

Reusing `BatchStretchTask` instead of the duplicate inline worker is a useful small change within Stage B, not the main architectural solution. Add one adapter/command at a time and keep the public API and file formats compatible. Do not combine the file-safety fix, an EventBus change and a settings redesign in one commit.

## Completed work removed from the queue

The workflow strip, primary detection action, disabled-reason tooltips, frame context menu and common shortcuts are implemented. So are the Overview with calibrated-profile choices, analysis switches, settings navigation/search, saved-value markers, expert disclosure, auto-apply and explicit Save/Revert/Defaults.

The completed task list also covers report legends and layer guidance, variable-star overlays/maps, compact sky orientation, common-motion explanations, the persistent finding-browser placement, numerical frame-rejection reasons and the updated Signal Weave section. These are maintained as current behavior in the source/user documentation, not scheduled again here. Historical screenshots and mock-up numbers are not acceptance targets for new changes.
