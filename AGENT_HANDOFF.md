# Agent Handoff — Design improvements

> **Active feature.** Read [AGENT_COLLABORATION.md](AGENT_COLLABORATION.md), especially sections 3 and 13, and read the committed version of this mailbox before acting. The maintainer authorized Codex to start as first implementer on 2026-10-24. Only the committed `Owner` may write; implementation of a subtask still requires `SUBTASK_AGREED` from both agents.

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
- Subtask base commit: N/A — no subtask has been proposed or agreed.
- Implementer: Codex (initial assignment; may be reassigned only by a committed update at a subtask boundary)
- Reviewer: Claude Code (initial assignment; may be reassigned only by a committed update at a subtask boundary)
- Max interaction rounds: 2 per started subtask, shared across both agents; preserve each subtask's counter in the ledger.
- Quota caution threshold (used): 80%
- Quota pause threshold (used): 90%

## Current state

- Owner: REVIEWER
- State: AWAITING_AGREEMENT
- Current subtask: SP-20
- Current agreement: PROPOSED — awaiting Claude Code's committed response; no implementation authorized yet.
- Interaction rounds used: 0
- Codex preferred next subtask: SP-20 — independent, bounded build configuration cleanup; verifies the collaboration loop before larger coupled refactorings.
- Claude Code preferred next subtask: NOT_SELECTED — choose at the start of the first owned turn after activation.
- Resume role (when paused): N/A
- Resume action (when paused): N/A
- Quota visibility: UNKNOWN
- Last reported quota: Codex UNKNOWN; Claude Code UNKNOWN
- Implementation/review status: SP-20 proposed to Claude Code; no subtask implementation has started.

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
| [SP-02](DESIGN-IMPROVEMENTS.md#sp-02-rendering-preferences-as-a-value-not-static-fields) | All rendering preferences reach renderers and runs explicitly; immutable run settings include marker sizes and animated-marker limits. Save, Revert, reload and independent runs retain their contracts. | Preserve live values, saved baselines and run snapshots. Include API/CLI defaults and variable-star report options; no mutable rendering globals. | PENDING_AGREEMENT / unset / 0 |
| [SP-04](DESIGN-IMPROVEMENTS.md#sp-04-retire-the-imageprocessing-facade) | Services have explicit owners; session files and overlays have one authoritative owner; the facade and `SessionFrames` are retired in reviewed steps. Headless construction and disposal remain usable. | Account for finalized SP-01 work and SP-02. Preserve completed detection products on report decline, cancellation and failure. Evaluate the few-frame engine-result policy separately from moving code. | PENDING_AGREEMENT / unset / 0 |
| [SP-05](DESIGN-IMPROVEMENTS.md#sp-05-give-panels-their-collaborators-not-the-whole-window) | Panels receive their actual collaborators; panel cycles through window getters are removed, and listeners have explicit ownership, threading and detachment. | Usually follows SP-04; choose a coherent scope against the actual code. Do not replace window access with an equivalent generic context or publish a mutable config without an ownership contract. | PENDING_AGREEMENT / unset / 0 |
| [SP-06](DESIGN-IMPROVEMENTS.md#sp-06-move-the-sky-catalogue-workflow-out-of-the-main-panel) | Catalogue fetch and correction fitting leave the main panel; an owned controller delivers progress/results on the EDT and releases its worker and timer. | Preserve task/session identity checks despite queued callbacks, cancellation, replacement, handover and shutdown. Coordinate with SP-05 and finalized SP-01. | PENDING_AGREEMENT / unset / 0 |
| [SP-08](DESIGN-IMPROVEMENTS.md#sp-08-one-report-entry-point-and-a-complete-report-context) | One report request/export entry, a fully constructed context, and coherent map/format helpers replace the forwarding and partial-context paths. | Coordinate with SP-02 and SP-04. Valid absence of WCS/catalogue/analysis remains supported. Preserve atomic publication, cancellation cleanup and failure messages; normalize only declared volatile timing fields for comparisons. | PENDING_AGREEMENT / unset / 0 |
| [SP-20](DESIGN-IMPROVEMENTS.md#sp-20-no-machine-path-in-the-build-file) | `realDataTest` contains no machine-specific default path; the data root comes from explicit `-PrealDataRoot` or an agreed ignored local property, with a clear missing-input error. | Independent candidate. Validate both configured and missing-input behavior, and keep ordinary build/check tasks usable without the dataset. Do not copy local paths into tracked files or handoffs. | PROPOSED / awaiting agreement baseline / 0 |
| [SP-19](DESIGN-IMPROVEMENTS.md#sp-19-finish-the-common-base-of-the-image-viewers) | Shared image-view and control mechanics are consolidated where appropriate; closing and session transitions release buffers/listeners and preserve real keyboard behavior. | Agree explicitly on independent viewer lifetime and trial-settings/Apply behavior. Keep Blink separate by composition. F13 is related context, not an additional authorized feature. Defer disputed policy while other subtasks proceed. | PENDING_AGREEMENT / unset / 0 |
| [SP-09](DESIGN-IMPROVEMENTS.md#sp-09-one-write-queue-for-small-files) | One owned I/O queue preserves read-after-write ordering, per-target failure/retry visibility and safe shutdown; obsolete acknowledgement machinery is removed. | Requires the maintainer's SP-01 work to be finalized and understood; it is not authorization to take over SP-01. Preserve reimport ordering and suppression of callbacks to closed sessions. Coordinate with SP-04. | PENDING_AGREEMENT / unset / 0 |
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

### Message 001 — 2026-10-24 — preparation record only

- From: Codex (explicitly authorized setup agent; no active implementation ownership)
- To: HUMAN
- Type: SETUP_PREPARED
- Current subtask: NONE
- Preferred next subtask / reason: NOT_SELECTED; the first owned turn after activation chooses from the current eligible work.
- Summary: Prepared Design improvements as one feature with twelve SP subtasks. Added sustained work to safe quota thresholds, independent next-subtask selection each turn, explicit agreement before implementation, deferral on pre-start disagreement, per-subtask review, and final whole-feature review. Incorporated the maintainer's clarification that the SP items are subtasks, not separate features.
- Relevant commits / files: `AGENT_COLLABORATION.md`, `AGENT_HANDOFF.md`; no setup or activation commit created by this preparation.
- Tests / evidence: No implementation tests run; preparation only. Branch/status inspection and documentation consistency/link checks are the applicable setup checks.
- Usage / quota: Codex UNKNOWN; Claude Code UNKNOWN. No percentage claimed.
- Remaining work / risks: Maintainer's parallel changes are active; feature branch and actual baselines remain unset. Some subtasks depend on the finalized SP-01 work or an explicit behaviour/API decision.
- Requested next action: Keep `Owner: HUMAN`, `State: SETUP_REQUIRED` during parallel work. When ready, authorize activation and follow the deferred checklist, creating the branch last in preparation. This uncommitted entry is not an `INITIALIZED` handoff.

### Message 002 — 2026-10-24 — activation

- From: HUMAN (explicit start authorization), recorded by IMPLEMENTER (Codex)
- To: IMPLEMENTER (Codex)
- Type: INITIALIZED
- Current subtask: NONE
- Preferred next subtask / reason: Selection follows this activation commit; no implementation starts without agreement.
- Summary / agreement: Activated Design improvements as one feature with twelve authorized SP subtasks. Codex is the first implementer and Claude Code is the reviewer. The previously committed setup is finalized, the source trees were clean, and the dedicated branch is `feature/design-improvements`.
- Relevant commits / files: Feature baseline `9486b2062470dc0492e85bdff5922429aaaad72f`; finalized SP-01 commits `64cc9b9` and `ad1c78d`; `AGENT_HANDOFF.md`.
- Tests / evidence: Read repository guidance and both collaboration files; checked all three working trees and branches, existing worktrees, protocol tracking, and the current SP-20 build task. No implementation or runtime tests yet.
- Usage / quota: Codex UNKNOWN; Claude Code UNKNOWN. No provider usage percentage is available to this session.
- Remaining work / risks: All twelve subtasks await agreement. No code change has been made for the feature; no sibling branch or source change has been made.
- Requested next action: Codex selects a remaining eligible subtask and commits a scoped `SUBTASK_PROPOSED` to REVIEWER. Both agents then follow the ownership and agreement protocol.

### Message 003 — 2026-10-24 — first subtask proposal

- From: IMPLEMENTER (Codex)
- To: REVIEWER (Claude Code)
- Type: SUBTASK_PROPOSED
- Current subtask: SP-20 — complete bounded issue, not a slice.
- Preferred next subtask / reason: SP-20. It has no prerequisite on the larger service/UI refactorings and removes an actual tracked machine-path default with a small build-only change. SP-02 is a likely subsequent candidate, but the next owner chooses independently.
- Summary / agreement: Request agreement to remove the machine-specific fallback from `realDataTest`. Read `realDataRoot` through Gradle's property provider (supporting explicit `-PrealDataRoot` and the same key in ignored `gradle.properties`), validate at `realDataTest` execution time, reject missing/blank values and a non-directory with an actionable error, and set `spacepixels.testdata.root` to the resolved directory. Do not require this property merely to configure the project or run ordinary tasks. Keep the harness's detection/preparation behavior unchanged. Document the opt-in property requirement in DEVELOPER.md and remove the completed SP-20 proposal/table row after implementing and validating its scope.
- Acceptance criteria: No machine-specific root remains in tracked build configuration; a missing/blank/non-directory value fails clearly when the opt-in task runs; a valid command-line root is passed to the Java process; an ignored property supplies the same key and explicit `-P` wins; ordinary help/compilation/check task configuration works without a dataset root. Do not add a framework or permanent tests that merely mirror the build script.
- Review-comment decisions: SP-20 has no appended issue review in the current plan. Apply the general rules on local paths, ignored machine configuration and explicit opt-in test data. The user has authorized SP-20; this proposal chooses its implementation contract before coding.
- Relevant commits / files: Feature baseline `9486b2062470dc0492e85bdff5922429aaaad72f`; activation `ca124ed940724996ab570a61c0979429da918b19`; expected implementation files `build.gradle`, `DEVELOPER.md`, `DESIGN-IMPROVEMENTS.md`, plus the mailbox. No sibling library changes expected.
- Tests / evidence: Source inspection found the hardcoded default in the `realDataTest` registration. After agreement, validate missing/blank/non-directory and valid-root delivery using real Gradle task execution with a small Java probe and temporary init/configuration files in scratch outside repositories, avoiding real detection or GUI startup. Check ordinary build configuration without the property and relevant build/check tasks; record exact commands/results. No implementation tests have run yet.
- Usage / quota: Codex UNKNOWN; Claude Code UNKNOWN.
- Remaining work / risks: Reviewer agreement is pending. Keep validation task-local; eagerly throwing during Gradle configuration would break ordinary consumers without local datasets. Full scientific/GUI regression is not triggered by changing this build-input contract; if the actual implementation touches processing behavior, revisit that assessment.
- Requested next action: Claude Code reads the committed proposal, records their preferred next subtask and reason, then commits `SUBTASK_AGREED` with an exact pre-implementation subtask base SHA and `Owner: IMPLEMENTER`, `State: IMPLEMENTING`; or commits `DEFERRED` with their disagreement and requests a different eligible subtask. Codex waits read-only until a committed handoff returns ownership.

<!-- Append-only message format after activation:

### Message 004 — YYYY-MM-DD HH:MM TZ
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
