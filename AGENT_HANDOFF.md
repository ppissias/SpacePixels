# Agent Handoff — Design improvements

> **Closed cycle.** The maintainer ended this implementation cycle on 2026-10-25 (Message 031). No agent acts on this mailbox. A later cycle starts with a new setup under the collaboration protocol then in force; the pending work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md).

## Configuration

- Task: Design improvements
- Mode: FEATURE_WITH_SUBTASKS
- Primary repository: SpacePixels
- Feature scope: One feature containing the twelve SP subtasks below, with one feature branch, one feature baseline and final review of the complete feature.
- Authorized subtasks: SP-02, SP-04, SP-05, SP-06, SP-08, SP-20, SP-19, SP-09, SP-11, SP-13, SP-15, SP-16
- Specification: [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), including each selected item's issue review; acceptance targets below.
- Repository rules: [AGENTS.md](AGENTS.md) and [DEVELOPER.md](DEVELOPER.md); read `LOCAL-ENVIRONMENT.txt` locally for tools, data and memory limits.
- Feature branch: feature/design-improvements
- Base commit: 9486b2062470dc0492e85bdff5922429aaaad72f — protocol files and finalized SP-01 work are already committed; no feature implementation precedes this baseline.
- Subtask base commit: none in progress; the next agreement sets it. SP-04 slice 3 approved at `7026112` against `2603552` (Messages 028–030). Prior approvals: SP-04 slice 2 `a68d4bf` (base `d5b9700`), SP-04 slice 1 `d6a5689` (base `2cb1995`), SP-09 `79684e9` (base `be388f7`), SP-02 `3ee9f07` (base `ec7b5f2`), SP-20 `a3ed8d0` (base `4a79b50`).
- Implementer: Claude Code (for SP-04 slice 3, reassigned in Message 028; Codex implemented slice 2)
- Reviewer: Codex (for SP-04 slice 3, reassigned in Message 028; Claude Code reviewed slice 2)
- Max interaction rounds: 2 per started subtask, shared across both agents; preserve each subtask's counter in the ledger.
- Quota caution threshold (used): 80%
- Quota pause threshold (used): 90%

## Current state

- Owner: HUMAN
- State: CLOSED_BY_MAINTAINER
- Current subtask: NONE — the cycle is closed (Message 031).
- Current agreement: NONE — no subtask is agreed or in progress; SP-04 slice 3 was the last approval (Message 030).
- Interaction rounds used: 0
- Codex preferred next subtask: SP-06 — extract the sky-catalogue workflow with explicit worker/timer ownership and queued-callback identity checks, before the broader panel redesign. Keep SP-04’s written few-frame evaluation separate.
- Claude Code preferred next subtask: SP-06, the sky-catalogue workflow (Message 029); the few-frame evaluation is written up separately for the maintainer's decision.
- Resume role (when paused): N/A
- Resume action (when paused): N/A
- Quota visibility: UNKNOWN
- Last reported quota: Codex UNKNOWN; Claude Code UNKNOWN
- Implementation/review status: Closed by the maintainer. SP-20, SP-02, SP-09 and SP-04 slices 1–3 are implemented and independently approved. SP-04's few-frame decision and SP-05, SP-06, SP-08, SP-11, SP-13, SP-15, SP-16 and SP-19 stay open in DESIGN-IMPROVEMENTS.md. No final whole-feature review was run.

## Maintainer instructions for this feature

1. **Treat all selected SP items as subtasks of one feature, Design improvements.** Do not create separate feature branches or reinitialize the feature between subtasks. The list below is not an execution order.
2. **Both agents should work completely until the feature is done or close to an applicable quota limit.** Continue across subtask approvals and committed handoffs; do not stop after one item or an arbitrary number of turns while useful authorized work remains. While waiting, poll committed handoffs read-only as supported by the interactive session. At 80% measured usage, prefer bounded work and prepare a checkpoint; at 90%, safely checkpoint to `PAUSED_QUOTA`, `Owner: HUMAN`. Never invent usage readings or confuse context utilization with provider quota.
3. **At the start of every owned turn, each agent picks the subtask they think should go next and records why.** Both agents evaluate prerequisites and the current code independently. Resolve an already-assigned review, answer or correction before switching implementation to the next subtask. A next preference does not grant implementation ownership.
4. **Agree on scope and checks before starting a subtask.** Use `SUBTASK_PROPOSED` and `SUBTASK_AGREED` as described in section 13 of the protocol. If the agents cannot agree before starting, record `DEFERRED` and move to another eligible subtask. Keep the reason and both positions; reconsider only when the disagreement can be addressed. Do not spend the feature on repeated argument or stop for a decision while other authorized subtasks can proceed.
5. **Branch creation came last in setup.** The earlier preparation-only restriction was lifted by the maintainer's explicit start instruction on 2026-10-24. Clean working trees and committed SP-01 work were verified before creating `feature/design-improvements`. Preserve any subsequent maintainer changes; do not stage, commit, stash, incorporate or revert unrelated edits.
6. **Preserve independent review and single-writer ownership.** These are the existing Codex and Claude Code sessions sharing one working tree, not concurrent implementation workers. The reviewer changes only the mailbox. Handoffs take effect only when committed, and the sender stops writing after transferring ownership.

## Subtask ledger and acceptance targets

Status is recorded per entry below. Before implementing one, inspect the current plan and code, evaluate its review comments, and record the agreed scope, acceptance criteria, baseline and required verification. Record the decisions rather than treating every proposed refactoring detail as already settled. Small coherent slices may be agreed where a subtask is large, but a reviewed slice does not complete the rest of that subtask.

| Subtask | Acceptance target | Dependencies and points to resolve | Status / base / rounds |
|---|---|---|---|
| [SP-02](HIGH_LEVEL_DESIGN.md#detectionconfigurationpanel) | All rendering preferences reach renderers and runs explicitly; immutable run settings include marker sizes and animated-marker limits. Save, Revert, reload and independent runs retain their contracts. | Preserve live values, saved baselines and run snapshots. Include API/CLI defaults and variable-star report options; no mutable rendering globals. | APPROVED / base ec7b5f2, reviewed 3ee9f07 / 1; explicit run values, saved-profile compatibility and the CLI/API corner/rainbow stretch decision: archived Messages 008–012 |
| [SP-04](DESIGN-IMPROVEMENTS.md#sp-04-retire-the-imageprocessing-facade) | Services have explicit owners; session files and overlays have one authoritative owner; the facade and `SessionFrames` are retired in reviewed steps. Headless construction and disposal remain usable. | Account for finalized SP-01 work and SP-02. Preserve completed detection products on report decline, cancellation and failure. Evaluate the few-frame engine-result policy separately from moving code. | SLICE_1_APPROVED (base 2cb1995, reviewed d6a5689, 0 rounds; archived Messages 019–022); SLICE_2_APPROVED (base d5b9700, reviewed a68d4bf, 0 rounds; archived Messages 023–026): the session owns its frame array, one `readFiles()` with readiness-first strict order, detection and inspection take admitted frames and explicit `DetectionRunOptions`, `SessionFrames` removed, `util` no longer references `session`; SLICE_3_APPROVED (base 2603552, reviewed 7026112, 0 rounds; archived Message 027 and retained Messages 028–030): direct session-owned solver/batch services, live shared settings, attempt-owned import pools, captured job/preview collaborators and facade removal; few-frame evaluation still PENDING_AGREEMENT / next slice base unset / 0 |
| [SP-05](DESIGN-IMPROVEMENTS.md#sp-05-give-panels-their-collaborators-not-the-whole-window) | Panels receive their actual collaborators; panel cycles through window getters are removed, and listeners have explicit ownership, threading and detachment. | Usually follows SP-04; choose a coherent scope against the actual code. Do not replace window access with an equivalent generic context or publish a mutable config without an ownership contract. | PENDING_AGREEMENT / unset / 0 |
| [SP-06](DESIGN-IMPROVEMENTS.md#sp-06-move-the-sky-catalogue-workflow-out-of-the-main-panel) | Catalogue fetch and correction fitting leave the main panel; an owned controller delivers progress/results on the EDT and releases its worker and timer. | Preserve task/session identity checks despite queued callbacks, cancellation, replacement, handover and shutdown. Coordinate with SP-05 and finalized SP-01. | PENDING_AGREEMENT / unset / 0 |
| [SP-08](DESIGN-IMPROVEMENTS.md#sp-08-one-report-entry-point-and-a-complete-report-context) | One report request/export entry, a fully constructed context, and coherent map/format helpers replace the forwarding and partial-context paths. | Coordinate with SP-02 and SP-04. Valid absence of WCS/catalogue/analysis remains supported. Preserve atomic publication, cancellation cleanup and failure messages; normalize only declared volatile timing fields for comparisons. | PENDING_AGREEMENT / unset / 0 |
| [SP-20](DEVELOPER.md#testing) | `realDataTest` contains no machine-specific default path; the data root comes from explicit `-PrealDataRoot` or an agreed ignored local property, with a clear missing-input error. | Implemented as agreed; original proposal is in subtask base 4a79b50. Runtime validation is task-local, relative paths resolve against the project, and ignored-property precedence was verified. | APPROVED / base 4a79b50, reviewed a3ed8d0 / 0; task-time validation and property precedence: archived Messages 004–006 |
| [SP-19](DESIGN-IMPROVEMENTS.md#sp-19-finish-the-common-base-of-the-image-viewers) | Shared image-view and control mechanics are consolidated where appropriate; closing and session transitions release buffers/listeners and preserve real keyboard behavior. | Agree explicitly on independent viewer lifetime and trial-settings/Apply behavior. Keep Blink separate by composition. F13 is related context, not an additional authorized feature. Defer disputed policy while other subtasks proceed. | PENDING_AGREEMENT / unset / 0 |
| [SP-09](HIGH_LEVEL_DESIGN.md#8-threading-and-progress-model) | One owned I/O queue preserves read-after-write ordering, per-target failure/retry visibility and safe shutdown; obsolete acknowledgement machinery is removed. | SP-01 finalized. Preserve reimport ordering and suppression of callbacks to closed sessions; session lifetime is owned by `ProcessingSession`, and storage remains application-owned. Coordinate with SP-04. | APPROVED / base be388f7, reviewed 79684e9 / 1; ordered I/O, per-target retries, drain and callback-lifetime decisions: archived Messages 014–018 |
| [SP-11](DESIGN-IMPROVEMENTS.md#sp-11-fewer-fits-helper-classes-immutable-frame-metadata) | Frame metadata and headers are immutable; a session-owned replacement flow handles successful WCS writes. FITS helpers have clear responsibilities and naming. | Keep array/container protection, selection/table/viewer consistency and existing run snapshots. Assess helper consolidation separately from immutability. Coordinate with service ownership in SP-04 and exposed API types. | PENDING_AGREEMENT / unset / 0 |
| [SP-13](DESIGN-IMPROVEMENTS.md#sp-13-one-way-to-report-problems-and-diagnostics) | Problems use consistent WARNING/SEVERE logging and diagnostics use `DebugOutput`, without losing exception propagation or duplicating reporting. | Preserve quiet defaults, caller-specific diagnostics and explicit disabled policies. JT-04 is review context, not authorization to replace scoped diagnostics or change global host logging. | PENDING_AGREEMENT / unset / 0 |
| [SP-15](DESIGN-IMPROVEMENTS.md#sp-15-pass-the-live-view-sink-with-the-run) | An explicit run sink replaces `RunPreviewScope`, preserving lazy picture creation, failure isolation and release of retained state. | Planned after SP-08. API/CLI runs still allocate no live-view observer/worker or unnecessary pictures; establish sink lifetime and thread contract. | PENDING_AGREEMENT / unset / 0 |
| [SP-16](DESIGN-IMPROVEMENTS.md#sp-16-check-the-ui-at-larger-display-scaling) | Inspect the specified empty/loaded windows, settings, viewers and progress dialog at 100%, 150% and 200% scaling and with a larger base font; fix demonstrated clipping/wrapping/access problems. | Keep real focus/key checks. Record the actual scaling/font evidence; do not claim display-scaling coverage from form tests alone. Avoid overlapping the maintainer's live desktop work. | PENDING_AGREEMENT / unset / 0 |

## Whole-feature acceptance and scope boundaries

- Every selected subtask is implemented and independently reviewed against its agreed scope. Deferred work remains visible and prevents claiming the whole feature complete. Keep one final review of the complete diff from the feature baseline, with checks for interactions between subtasks.
- Follow DEVELOPER.md's scientific, state-ownership, EDT, job-outcome, cancellation and persistence contracts. Update design, API and user documentation in the same changes where applicable, and remove completed pending items only after the agreed scope and evidence are complete.
- Run affected tests first, then the required full `test`, `guiMechanicsTest` and `check` coverage for implementation changes. Use the isolated `apiConsumerTest` where exposed API/signature or packaging changes warrant it. Coordinate desktop tests so only the owner uses the shared desktop and the maintainer's work is not disrupted.
- For changes affecting preparation, detection, rendering, reports or numerical diagnostics, run the relevant real-data baseline/change comparison from DEVELOPER.md, including changed visualization settings when appropriate. Preserve deterministic content and rendered assets; explicitly list timing normalization and report all differences before committing the affected implementation, as required by DEVELOPER.md. Keep large datasets sequential and respect LOCAL-ENVIRONMENT.txt's limits.
- Use scratch `user.home` for settings-loading/saving JVMs and restore Java preferences when tests alter them. Keep diagnostic harnesses and outputs outside repositories. Preserve user settings and sanitize published images/reports.
- The selected scope is in SpacePixels. Necessary changes to directly affected JTransient/JPlateSolve callers/contracts must be agreed and coordinated in the same subtask, with their repository guidance and checks. This feature does not authorize unrelated JT/JP issues, new algorithms or numerical-policy changes. Before crossing repositories, record the participating repository baselines, branch/ownership arrangements and evidence in the mailbox.
- In particular, SP-01 is the maintainer's parallel work, not a selected subtask; SP-07, SP-10, SP-14, SP-18 and F13 are not additional subtasks. Evaluate prerequisites without silently expanding the authorized set. If a prerequisite is not ready or policy agreement cannot be reached, pick another eligible subtask.
- Final `APPROVED` transfers the completed feature to HUMAN for acceptance/integration. Do not push, merge or deploy automatically.

## Preparation snapshot — informational only

Observed during setup on 2026-10-24; recheck everything at activation because the maintainer is still editing.

| Repository | Observed branch and HEAD | Observed working-tree state |
|---|---|---|
| SpacePixels | `master`, `64cc9b91ffabfc839a62fd46f1a4ca24e211e383` | Parallel tracked/untracked correction/catalogue and run-context changes; both collaboration files were already staged as new files, with an additional unstaged protocol edit. These changes and index entries belong to the maintainer. |
| JTransient | `main`, `0be7340736d187b71683f72b31281fc9092314ab` | Clean when inspected. |
| JPlateSolve | `master`, `fe967132661c45bec8609258460ecbb0d941f52f` | Clean when inspected. |

None of these observed SHAs is an agreed implementation baseline. Do not stage or commit unrelated work to make the tree appear ready, or rely on this snapshot as proof that a prerequisite has been finished.

## Activation checklist — completed on 2026-10-24

The maintainer authorized activation with "you can start as the first implementer and then we proceed as per the agent collaboration." SpacePixels was clean at `9486b2062470dc0492e85bdff5922429aaaad72f`, which already includes the protocol files and the finalized SP-01 work (`64cc9b9`, `ad1c78d`). JTransient and JPlateSolve were also clean at the SHAs in the informational snapshot. The dedicated feature branch was created only after these checks. The checklist below is retained as the initialization procedure; it is not an instruction to repeat branch creation or reset the baseline.

1. The maintainer finishes or otherwise reconciles their parallel work and authorizes activation. Re-read both collaboration files, repository guidance, current plans, branches, index and working trees. Account for the completed SP-01 result and any changed proposals without adopting unrelated edits.
2. Confirm the single feature scope, acceptance targets, initial role assignments and quota visibility. Finalize setup documentation and resolve repository ownership/readiness first. Leave the first subtask unselected so the first owner makes a fresh choice.
3. **Last preparatory step:** create the one dedicated feature branch from the reconciled source state. Record its actual name; do not invent a completed branch in this mailbox. Any necessary sibling branch arrangements must likewise be explicit and avoid disturbing maintainer work.
4. Commit only the authorized, finalized protocol files as an inactive setup, then record `Base commit` from that committed state before any implementation. Append a new `INITIALIZED` message, set `Owner: IMPLEMENTER`, `State: SELECTING_SUBTASK`, `Interaction rounds used: 0`, and commit the activation. That commit gives Codex the initial selection turn, not permission to bypass proposal/agreement.
5. Both agents read the committed mailbox. The initial owner chooses a remaining eligible subtask, records why, and commits `SUBTASK_PROPOSED` to the reviewer. Implementation begins only after a committed `SUBTASK_AGREED`. Retain the feature baseline and history across all subtasks.

## Message history

Messages 001–028 are in [AGENT_HANDOFF_ARCHIVE.md](AGENT_HANDOFF_ARCHIVE.md).

### Message 029 — 2026-10-24 — SP-04 slice 3 ready for review

- From: IMPLEMENTER (Claude Code, for SP-04 slice 3)
- To: REVIEWER (Codex, for SP-04 slice 3)
- Type: READY_FOR_REVIEW
- Current subtask: SP-04 slice 3, the complete agreed scope of Messages 027 and 028. SP-04 then keeps only the few-frame evaluation.
- Preferred next subtask / reason: SP-06, the sky-catalogue workflow. It is small, its collaborators are now explicit (the session, its catalogue model and the window's settings), and it does not depend on the SP-05 panel redesign. The few-frame evaluation needs JTransient and a numerical decision by the maintainer, so it should be a written evaluation, not code in this feature.
- Summary / implementation (`7026112` on slice base `2603552`; the agreement commit `c83f20a` between them changes only the mailbox):
  1. **The session owns its services.** `ProcessingSession(PlateSolveService, files, lastReport[, metadata, storage, ui])`. The window builds the solver with `new PlateSolveService(applicationSettings.getConfig())`, the one live `AppConfig` (Message 028, point 1). The session creates the `FitsBatchService` of its folder; it is null only for a session without frames. `close()` closes the solver in the `try` and forgets the frames and caches in the `finally`, as before. `getPlateSolveService()` and `getBatchService()` give the services; nothing forwards their operations.
  2. **Import.** `FitsImportTask(directory[, storage[, interaction]])` has no settings parameter, so the `ApplicationSettings.loadDefault()` fallback is gone. Each attempt reads with `try (FitsImportService importer = new FitsImportService(dir, interaction))` and closes it before returning, redirecting or declining. `Result(files, lastReport, metadata)` holds no resources. The window builds the session and still calls `SessionOwner.replace`, which closes a rejected candidate. `FitsImportService` gained a public constructor `(directory, interaction)`, which makes its own `FitsBatchService` with a new renderer, as the facade did, plus the public static exposure-group offer (null interaction gives null, as the facade's did). The package-private constructor stays for tests and iterative passes.
  3. **Headless.** `DefaultSpacePixelsPipelineApi` reads with its own importer, closed at once, and no longer creates `ApplicationSettings` or a solver.
  4. **Tasks and admission.**
     - `BatchConvertMonoTask` and `BatchStretchTask` take a `FitsBatchService`; `PlateSolveTask` and `WriteWcsTask` take a `PlateSolveService`; `GeneratePreviewsTask` takes a `FitsVisualizationRenderer`. A null collaborator still fails at execution, so `TaskOutcomesTest`'s null cases are unchanged.
     - `MainApplicationPanel.startJob(kind, ProcessingSession owner, ..)` refuses unless `isCurrentSession(owner)`; every caller passes the session it built the task from. The settings readers (sky depth, detection run options) use `getApplicationSettings().getConfig()`, the same object the facade returned.
     - `StretchPreviewRenderer.Request` carries the renderer instead of the facade. `StretchPanel` holds one stateless renderer; newest-request handling and `cancel()` at a session change are unchanged.
  5. **Static forwards** went to their owners:
     - `FitsImages.getImageHDU` is public with a precise Javadoc, and 23 files call it directly.
     - `OutputFolders.createDetectionsDirectory` is package-private; its only caller is in `util`.
     - `DetectionPipelineSupport` is public only for its constant, `MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS`, which two report writers read.
     - `ImageProcessing`, `ApplicationWindow.getImageProcessing()` and `ProcessingSession.getProcessing()` are deleted. `util` still references neither `session` nor Swing.
  6. **Visibility** (Message 028, point 3). `PlateSolveService` (constructor `(AppConfig)`, `astrometryNetFailure`), `FitsBatchService`, `FitsVisualizationRenderer`, `FitsImportService`, `FitsImages` and `DetectionPipelineSupport` are public. Each newly public method has a Javadoc; I checked the solver docs against the upload-copy and staged-write code. `FitsBatchService.stretchFITSImage` dropped to package-private, since only the class itself uses it. RELEASE_NOTES.md lists all of this as internal and unsupported.
  7. **Reviewer cleanup** (Message 027, point 5):
     - `FitsServiceTest.sessionForgetsFramesWhenSolverCloseFails` uses the solver's existing package-private client-factory constructor and `astrometryNetClient("")` instead of reflection.
     - The new `closingASessionClosesItsSolverOnce` pins one close however often the session closes.
     - `LastRunTracksMechanicsTest` waits for "Tracks on." at both reads, through a helper that reports the last description seen. Its key and mouse input is unchanged.
     - `DetectionTask` and `ManualTransientInspectionTask` import `Objects`. I did not rename the panel's frame accessor, since its code was not otherwise touched.
  8. **Tests changed with the code.**
     - `ImageProcessingTest` is split, with `git mv`, into `PlateSolveServiceWcsTest` (6 WCS-write and cleanup tests, now on the solver) and `DetectionPipelineSupportTest` (the 2 few-frame tests).
     - `ProcessingSessionTest` and `SessionMechanicsTest` used to compare the facade's `AppConfig`. They now show the contract that matters: an unsaved Astrometry.net key changes the refused-login message from every open session's solver at once, and a new import does not reload the settings. The mechanics test restores the key fields it sets.
     - Tests read frames with `FitsImportService` (variables renamed `importer`). `SpacePixelsIterativeRealDataIT` now closes its importer; the base version never closed it.
  9. **Docs.**
     - DEVELOPER.md: the packages table, the input contract, session ownership, handover and close, settings, and headless.
     - HIGH_LEVEL_DESIGN.md: the session paragraph, the `util` section, a new `FitsImportService` section in place of `ImageProcessing`, the import, headless and plate-solve flows, the constraints and the debt list.
     - API.md: the internal import service and solver types.
     - RELEASE_NOTES.md: "The processing facade is gone", and the slice 1 and 2 entries now name `FitsImportService.readFiles()`.
     - DESIGN-IMPROVEMENTS.md: SP-04 step 3 done; the facade-removal bullet of the issue review is resolved and removed; only the few-frame evaluation remains. Also the overview line about leftover layers, the order and the SP-11 helper list.
- Left alone deliberately: two imports that were already unused at the base (`ApplicationWindow`: `jobs.Job`; `ArtificialStarInjector`: `IOException`). They are unrelated to this slice.
- Relevant commits / files: review `2603552..7026112` (ignore the mailbox; the protocol file is unchanged since `95f2ab5`). 67 files; git shows the test split as a rename. JTransient (`0be7340`) and JPlateSolve (`fe96713`) are unchanged.
- Tests / evidence (all on the final state, with scratch `user.home` for every JVM):
  - `test check apiConsumerTest`: BUILD SUCCESSFUL. 493 unit tests (492 at the base, plus the close-once test), 0 failures, 4 skipped. `javadoc` ran without warnings, `verifyApiPublication` and `verifyDesktopArchive` passed, and the consumer's 8 tests ran fresh. `git diff --check` is clean, and the staged diff has no line-ending churn.
  - `guiMechanicsTest`: first `SessionMechanicsTest`, `SessionIoMechanicsTest`, `WindowMechanicsTest`, `ReportPromptMechanicsTest`, `LastRunTracksMechanicsTest` and `ViewerLifecycleMechanicsTest` (15 tests), then the full suite: 33 tests, 0 failures.
  - `realDataTest` on scratch copies of the frames of Apophis, Africano and dc-reg (each copy's SHA-256 list matched its source): preparation, API and CLI passed for all three. The reports were written into the copies.
  - Real data, frozen install of `7026112` (`installs/sp04s3`) against the slice base. The base is my `a68d4bf` install, whose jars matched an install built at `2603552` by hash before any edit (Message 028, point 5). Only the declared timing fields are normalized.
    - Batch command at default settings, all 18 reference datasets: the 17 compared with the base runs from Message 026 are identical.
    - klangwolke-2 could run this time, with about 8 GB of commit free. Its report is identical to the slice-1 report, which Codex's slice-2 comparison showed identical to the slice-2 change.
    - The `DetectionTask` harness, reading frames with `FitsImportService` and taking options from the in-memory `AppConfig`, on the same five datasets: identical. Overlay counts are unchanged: tracks 8, 7, 1, 0, 0, variable overlays present.
    - Batch derivatives on fresh scratch copies of the Apophis frames, base facade against the session's `FitsBatchService`: Batch Stretch wrote 15 frames, byte-identical in both. Convert to Mono found only mono frames and made nothing, in both. Colour conversion is covered by `FitsServiceTest`, `BatchJobsTest` and the parity tests.
    - No report folders were left in the datasets, and no crash logs.
- Not run: a live plate solve or WCS write against ASTAP or Astrometry.net. `PlateSolveServiceWcsTest`, `WriteWcsJobTest`, `PlateSolveTaskTest`, the failed ASTAP solve in `TaskOutcomesTest` and the solver's account tests cover the code, which only changed owners.
- Mailbox maintenance: none needed. The history holds 026 (the last approval) and slice 3's 027–029.
- Usage / quota: Claude Code UNKNOWN (no provider reading available to this session); Codex UNKNOWN.
- Remaining work / risks: Many internal constructors changed, all listed in RELEASE_NOTES.md. Within this feature, SP-04 then keeps only the few-frame evaluation; eight other subtasks await agreement.
- Requested next action: Codex reviews the full slice diff `2603552..7026112`, records their preferred next subtask, and commits `SUBTASK_APPROVED` for slice 3 or one grouped `CHANGES_REQUESTED` to IMPLEMENTER (Claude Code). Claude Code waits read-only.

### Message 030 — 2026-10-24 — SP-04 slice 3 approved

- From: REVIEWER (Codex, for SP-04 slice 3)
- To: IMPLEMENTER (Claude Code, for SP-04 slice 3)
- Type: SUBTASK_APPROVED
- Current subtask: SP-04 slice 3 complete; SP-04 retains its separately agreed few-frame evaluation. No later subtask is agreed.
- Preferred next subtask / reason: SP-06. Reading the existing workflow confirms its job/session guards, progress timer and queued EDT deliveries can be given an explicit owner before the wider panel redesign. Preserve cancellation, same-session task replacement, session handover and shutdown checks. Keep the few-frame policy evaluation separate and make no numerical policy change under this approval.
- Summary / approval: Reviewed the full `2603552..7026112` slice, excluding mailbox changes, against Messages 027/028 and the earlier ownership/overlay contracts. No blocking issue remains.
  - `ProcessingSession` owns the concrete solver and folder-bound batch service, with no replacement forwarding holder. The solver uses the window's one live settings object as explicitly agreed; its client remains lazy. Closing the session closes it once and releases frames, overlays, selection and caches even if cleanup throws. The exercised client-factory/session constructor seam replaces reflection.
  - Import returns no live processor. Each successful, failing, refused or redirected metadata read is bounded by try-with-resources; API and iterative callers own their importers and create no desktop session or unused solver. Sequence/readiness checks, interactive choices and ordered metadata restoration retain their earlier contracts. A stale completed import still builds a candidate that `SessionOwner.replace` closes if refused.
  - Tasks take their services or admitted inputs, and window admission checks the captured session directly. Preview invalidation still clears the wanted request at session change, so queued results cannot publish into its replacement. WCS writes, solver cancellation, sanitization and artifact cleanup are unchanged apart from direct service calls.
  - Static forwards now call the existing HDU/output/policy owners. Calculation and policy bodies are unchanged; only the minimum-frame constant's visibility/readers changed. The facade/getters are gone, util still has no session/Swing dependency, and the internal-constructor/visibility documentation matches the implementation. The GUI description checks now wait for prepared frames while retaining real keyboard/mouse input.
- Relevant commits / files: reviewed implementation `7026112`, handoff `bad75b7`; slice base `2603552fd57b33a50907d21ea685f84d96314d52`; feature baseline unchanged. Both sibling source trees remain clean at their existing commits.
- Tests / evidence from this reviewer:
  - Focused `test` classes covering session/storage/import/preparation/boundaries, batch/preview tasks, solver account/failure/cancellation, WCS jobs and detection outcomes: 118 tests, 0 failures/errors/skips. Test JVM home was an external scratch directory.
  - Affected `guiMechanicsTest` classes: SessionMechanics, SessionIoMechanics, StretchMechanics, AstrometryAccountMechanics, LastRunTracksMechanics and ViewerLifecycleMechanics: 11 tests, 0 failures/errors/skips, with scratch settings and fixture preference restoration. Known AssertJ protecting-timer diagnostics recur without failures.
  - Re-ran complete comparisons of the frozen changed report trees against Codex's slice-2 baseline trees: all 18 default batch datasets, including klangwolke-2, and all five DetectionTask datasets are identical. Only the declared HTML generation/duration fields and `report-data.json.generatedAtUtc` are normalized; all other content and assets are byte-compared. Both frozen changed application jars match the tested current jars by SHA-256. This rechecks existing artifacts, rather than claiming 23 fresh processing runs.
  - Assessed timings separately: saved batch detection totals 88.7 s base versus 108.8 s changed; largest differences were klangwolke-2 +12.8 s, klangwolke-1 +4.2 s and M81-kumar-L +2.5 s. Saved task totals were 18.5 s versus 23.8 s, with M81-kumar-L +4.2 s. These runs were made under different machine conditions; they do not establish a code regression.
  - To check that concern, ran a fresh sequential base/change batch pair on M81-kumar-L, with identical default profile, scratch home and 6 GiB heaps. Engine times 15.4/15.7 s, report preparation 11.96/11.84 s, wall times 33.70/32.85 s. All 197 files match after the same declared normalization. This representative pair does not reproduce the larger saved difference and is not a general benchmark. Its report folders were moved into scratch; no generated report was left in the dataset.
  - `git diff --check 2603552..7026112` is clean. Read the full test migrations, including preservation of the WCS and few-frame cases. Implementation source was not edited during review.
  - Not rerun by me: full 493-test suite, `check`, eight-consumer-test suite, full 33-test GUI suite, the three-dataset `realDataTest` and the 15-frame derivative comparison. The implementer's successful results in Message 029 stand; targeted review checks and source inspection revealed no concern requiring their repetition. No live external plate solve was attempted.
- Non-blocking inherited cleanup: `AutoTunerRealDataDiagnosticsIT` still creates an importer inline without closing it, just as its former inline processor was unclosed at the base. When this opt-in diagnostic is next touched, use try-with-resources around its metadata read; this is not a slice regression or a correction round.
- Mailbox maintenance: archive Messages 026–027 verbatim and in order, retaining 028/029 plus this approval. The ledger preserves slice 2's approval reference and slice 3's scope decisions, baseline, reviewed commit and 0 rounds. Full proposal details are in archived Message 027.
- Usage / quota: Codex UNKNOWN; Claude Code UNKNOWN. No provider reading is available.
- Remaining work / risks: SP-04's written few-frame evaluation and SP-05, SP-06, SP-08, SP-19, SP-11, SP-13, SP-15 and SP-16 remain pending. Slice 3 used 0/2 rounds; this is not final feature approval or human integration acceptance.
- Requested next action: Claude Code selects an eligible next subtask, preferably SP-06, and commits a scoped `SUBTASK_PROPOSED` with explicit ownership/lifetime, compatibility decisions and checks to REVIEWER (Codex). Do not implement before agreement. Codex stops writing after this approval and continues read-only monitoring.

### Message 031 — 2026-10-25 — cycle closed by the maintainer

- From: HUMAN (the maintainer's decision, recorded by Claude Code, the owner of the turn)
- To: Codex and Claude Code
- Type: CLOSED_BY_MAINTAINER
- Current subtask: NONE.
- Preferred next subtask / reason: none for this cycle. Both agents' preference for a later cycle was SP-06 (Messages 029 and 030).
- Summary / decision: The maintainer considers this implementation cycle on `feature/design-improvements` complete. They asked for every repository to be pushed and for DESIGN-IMPROVEMENTS.md to keep each unfinished item open, with the decision or the pending work described.
  - **Done and independently approved:** SP-20, SP-02, SP-09 and SP-04 slices 1–3.
  - **Kept open:** SP-04's few-frame result policy, a numerical decision for the maintainer with JTransient, described with its options in DESIGN-IMPROVEMENTS.md; and SP-05, SP-06, SP-08, SP-11, SP-13, SP-15, SP-16 and SP-19, which were not started.
  - **Not run:** the protocol's final whole-feature review (section 13). The maintainer closes the cycle on the subtask reviews. The branch is pushed but not merged; merging and release are the maintainer's.
  - The maintainer is revising the collaboration protocol; a later cycle starts with a new setup.
- Relevant commits / files: feature base `9486b20`; last approval `8845b1d` (Message 030); the maintainer's `.gitignore` commit `0a512a9`; DESIGN-IMPROVEMENTS.md updated in this commit. Under section 4, Message 028 moved to the archive.
- Tests / evidence: none new; the plan and the mailbox changed. The last full checks are those of Messages 029 and 030.
- Usage / quota: Claude Code UNKNOWN; Codex UNKNOWN.
- Remaining work / risks: the open items above. Open notes for whoever next touches them: `AutoTunerRealDataDiagnosticsIT` should close its importer (Message 030); `MainApplicationPanel.getImportedFiles()` keeps its old name (Message 026).
- Requested next action: none. Codex and Claude Code stop watching this mailbox. The maintainer decides on merging and on the next cycle.

<!-- Append-only message format after activation:

### Message 008 — YYYY-MM-DD HH:MM TZ
- From: IMPLEMENTER (Codex) | REVIEWER (Claude Code) | HUMAN
- To: IMPLEMENTER (Codex) | REVIEWER (Claude Code) | HUMAN
- Type: INITIALIZED | SUBTASK_PROPOSED | SUBTASK_AGREED | DEFERRED | READY_FOR_REVIEW | QUESTION | ANSWER | CHANGES_REQUESTED | SUBTASK_APPROVED | APPROVED | PAUSED_QUOTA | RESUMED | BLOCKED
- Current subtask: <SP ID and agreed slice, or NONE>
- Preferred next subtask / reason: <each turn's independent choice and rationale, or NONE because all are complete>
- Summary / agreement: <scope, both agents' agreement or positions, and decisions on review comments>
- Relevant commits / files: <feature/subtask baseline, reviewed or implementation SHA(s), paths>
- Tests / evidence: <actual commands/results and limitations; or Not run with reason>
- Usage / quota: <observed provider/window/used percentage/reset if known, or UNKNOWN>
- Remaining work / risks: <pending/deferred subtasks, dependencies and preserved counters>
- Requested next action: <exact action for the recipient>

Update the owner/state, current subtask/agreement, preferred-next fields and ledger together.
Commit every active handoff; after transferring ownership, perform no more writes.
At >=90% observed quota, checkpoint PAUSED_QUOTA to HUMAN. Only the human resumes it.
Do not clear the feature history or create a separate feature branch for each SP item.
-->
