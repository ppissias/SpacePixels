# Git-Based Collaboration Protocol — Claude Code + Codex

**Version:** 1.2

**Files:** `AGENT_COLLABORATION.md` (rules), `AGENT_HANDOFF.md` (per-task mailbox) and `AGENT_HANDOFF_ARCHIVE.md` (older mailbox messages, section 4)

## 1. Purpose and operating mode

Two coding agents collaborate on **one well-defined feature** using **Git commits as the sole authoritative inter-agent communication mechanism**. One agent implements; the other reviews. They may ask focused questions or request fixes, but the discussion is limited to **two interaction rounds**.

A feature with several authorized **subtasks** uses the extension in section 13: one agreed subtask is implemented and reviewed at a time, and approval of a subtask continues the same feature. `AGENT_HANDOFF.md` identifies the feature and its authorized subtask set. The subtask rules override stopping and initialization rules only where explicitly stated; Git handoffs, single-writer ownership, review independence and quota checkpoints still apply. There is one feature branch, one feature baseline and one final feature approval.

The intended starting setup is **two existing interactive Claude Code / Codex CLI sessions**, in two terminal tabs or equivalent IDE integrations, pointed at the **same repository and working tree**. Keep those sessions and their existing context. **No external controller is required for the initial setup.** A PowerShell watcher/orchestrator can be added later without changing the mailbox protocol.

**Important:** An instruction to "keep waiting" does **not** guarantee an interactive agent will remain active forever. Shell tools, IDE integrations, approvals, model turns, and sessions can time out or finish. If the other agent does not wake up, the human may need to prompt/resume it. Do not assume unattended execution is reliable without an external supervisor.

The shared-working-tree design is **sequential, not parallel**. Only the current owner may write or commit.

## 2. Non-negotiable rules

1. **Git is the source of truth.** A handoff becomes valid **only after a commit containing the updated `AGENT_HANDOFF.md`**. Chat messages, a terminal notification, a modified-but-uncommitted file, or a mere new code commit do not transfer ownership.
2. **Single writer.** Only the role in the committed `Owner` field may change repository files, run commands that write to the repository, stage files, or commit. When `Owner: HUMAN`, **neither agent writes**, except for the explicitly authorized preparation-only edit in section 3. The other agent may only read and poll while idle.
3. **Same branch, same working tree, alternating turns.** No simultaneous implementation/review writes. A reviewer with ownership may run tests that write temporary outputs, but must clean up **only its own** generated files before its handoff; it may never modify tracked implementation code.
4. **No destructive Git operations.** Never use `reset --hard`, `clean -fd`, automatic `stash`, force-push, rebase, branch switching, or deletion of unexpected files. Never overwrite another participant's uncommitted work. Stop and ask the human instead.
5. **Do not push, merge, or deploy automatically.** The human owns final acceptance and integration.
6. **Follow existing repository guidance**, including `AGENTS.md`, `CLAUDE.md`, and feature specifications. Do not modify those files merely to introduce collaboration.
7. **No made-up progress.** Report which tests were actually run. Do not call partial/WIP code completed or approved. Never claim a usage percentage that was not observed.
8. **Do not write secrets** (tokens, credentials, private customer data) into the mailbox or commits.

If ownership, current branch, mailbox contents, commit history, or working-tree cleanliness are ambiguous, **stop and escalate to the human**.

## 3. Initial setup (once per feature)

**Preparation without activation.** When the human requests initial setup while doing parallel work, an explicitly authorized setup agent may edit these two Markdown files only. Keep `Owner: HUMAN`, `State: SETUP_REQUIRED`, and the feature branch and implementation base commit pending. Do not create or switch a branch, stage, commit, start agents, run implementation tests, or adopt the human's changes during this preparation. Existing staged versions are not activation. Finalize the task, acceptance criteria and coordination rules first. After the human's parallel work has been reconciled and they authorize activation, creating the dedicated branch is the last preparatory step; then perform the initialization commits below and capture the real baseline. An observed current SHA is informational, not the future implementation baseline.

The human or an **explicitly authorized** setup agent must:

1. Start from a clean repository; make a dedicated feature branch (not `main`).
2. Add and commit these two Markdown files if not already tracked. `AGENT_HANDOFF.md` initially has `Owner: HUMAN` and `State: SETUP_REQUIRED`; no agent should start feature work yet.
3. Establish a concrete task with a specification or link to an existing specification and verifiable acceptance criteria. Assign **IMPLEMENTER** and **REVIEWER** to Claude/Codex (either assignment is valid).
4. Set **Base commit** to `git rev-parse HEAD` **before any feature implementation**, after the protocol files have been committed. This is the baseline for the full feature diff.
5. Fill the configuration in `AGENT_HANDOFF.md`; set `Owner: IMPLEMENTER`, `State: IMPLEMENTING`, `Interaction rounds used: 0`; clear prior-task history and append an `INITIALIZED` entry. Commit the initialized mailbox.
6. Ask each interactive agent to read **both** files. The implementer starts only when the committed owner is `IMPLEMENTER`; the reviewer checks the committed mailbox and waits.

For a new feature, start a **new task initialization** and new history, in a suitable feature branch. Past task histories remain retrievable through Git. Do not reuse stale state or counters accidentally.

## 4. Mailbox schema and message protocol

`AGENT_HANDOFF.md` contains:

- **Configuration:** task, spec/acceptance criteria, branch, base commit, agent-role assignments, two-round maximum, usage thresholds.
- **For a feature with subtasks:** authorized subtasks, per-subtask status and baseline, the current proposal/agreement, each agent's preferred next subtask, and the completed/deferred ledger (section 13).
- **Current state:** `Owner`, `State`, `Interaction rounds used`, and (when paused) the intended resume role/action.
- **Message history:** numbered append-only messages (`001`, `002`, ...), each with `From`, `To`, `Type`, summary, relevant commits/files, tests/results, and expected next action.

**Owner** is one of `IMPLEMENTER`, `REVIEWER`, `HUMAN`. Agent names identify who fills those roles; never substitute an agent name for `Owner`.

| Message / state | Trigger | Owner after committed message | Counts as a round? |
|---|---|---|---|
| `INITIALIZED` / `IMPLEMENTING` | Human starts task | IMPLEMENTER | No |
| `READY_FOR_REVIEW` | Implementer finishes feature or corrections | REVIEWER | No |
| `QUESTION` | Either agent needs the other's decision | Recipient role | **Yes, +1** |
| `ANSWER` | Recipient answers last `QUESTION` | Original questioner role | No |
| `CHANGES_REQUESTED` | Reviewer groups actionable findings | IMPLEMENTER | **Yes, +1** |
| `APPROVED` | Reviewer accepts complete feature | HUMAN | No |
| `PAUSED_QUOTA` | Active agent reaches safe usage stop | HUMAN | No |
| `RESUMED` | Human explicitly authorizes continuation | Named role | No |
| `BLOCKED` | Unsafe/unclear/unresolved issue | HUMAN | No |

**Each outgoing agent message is one committed mailbox update**, usually combined with the relevant implementation changes by the implementer. When transferring ownership, append the message, update state/counter, commit, and **perform no further writes**. A committed `APPROVED` is **review approval only**, not human acceptance or permission to merge.

A mailbox handoff must include an exact next action. The waiting agent must process each handoff commit **at most once**.

### Keeping the mailbox short

Every agent re-reads `AGENT_HANDOFF.md` on each turn, so its message history keeps only recent messages. Older messages move to `AGENT_HANDOFF_ARCHIVE.md`.

- **What stays:** every message of the subtask or slice in progress (from its proposal on), and the last approval before it. Keep at least the last three messages.
- **When:** the agent that owns the turn archives as part of its own handoff commit, when older messages are present. Archiving is a mailbox change, so a reviewer may do it too; it never transfers ownership by itself.
- **How:** move messages verbatim and in order to the end of the archive, and never edit them there. Message numbers continue unchanged. In the history, leave one line in their place: "Messages 001–NNN are in [AGENT_HANDOFF_ARCHIVE.md](AGENT_HANDOFF_ARCHIVE.md)."
- **Before archiving a subtask's messages,** check that its ledger row records the outcome, base and reviewed commits, the rounds used, and any decision later work depends on. Name the archived message where a decision needs its full text.
- The configuration, current state, maintainer instructions and ledger stay in `AGENT_HANDOFF.md`. Watchers read only that file; an archive change is not a handoff. Read the archive only when older context is needed. Git history keeps every version of both files.

### Expected transitions

- `IMPLEMENTING` or an `ANSWER` to the implementer → implementer works → `READY_FOR_REVIEW`, `QUESTION`, `PAUSED_QUOTA`, or `BLOCKED`.
- `READY_FOR_REVIEW` or an `ANSWER` to the reviewer → reviewer reviews → `APPROVED`, `CHANGES_REQUESTED`, `QUESTION`, `PAUSED_QUOTA`, or `BLOCKED`.
- `QUESTION` → recipient answers using `ANSWER` (or blocks if genuinely impossible) → ownership returns to the questioner.
- `CHANGES_REQUESTED` → implementer fixes and submits `READY_FOR_REVIEW` again.
- `PAUSED_QUOTA` → **only the human** may later submit `RESUMED`, choosing who may continue. Never automatically switch from a quota-paused implementer to the reviewer, or vice versa.
- `APPROVED` and `BLOCKED` → stop. `BLOCKED` requires an explicit human decision and new committed authorization before work resumes. Approval ends this feature's protocol run. For a feature with subtasks, section 13's `SUBTASK_APPROVED` and pre-start `DEFERRED` transitions continue work; they are not final feature approval.

## 5. Implementer instructions

When `Owner: IMPLEMENTER` in the **committed** mailbox:

1. Read the task, acceptance criteria, existing repository guidance, history, and current Git status. Confirm the expected branch and that unexpected changes are absent.
2. Implement in small, coherent increments and run appropriate tests. Local intermediate implementation commits are allowed **while you still own the turn**, but they are **not handoffs** unless they update the mailbox and transfer `Owner`.
3. If reviewer input is needed mid-feature, and the interaction budget permits it, write one specific `QUESTION` (include options and context), create a meaningful checkpoint, transfer `Owner` to `REVIEWER`, and commit.
4. When the feature is complete, include changes and test evidence in a `READY_FOR_REVIEW` message; transfer ownership to `REVIEWER` and commit. **Do not label incomplete work ready.**
5. When corrections are requested, address the actionable issues, rerun relevant tests, and hand back with `READY_FOR_REVIEW`.
6. If a blocker cannot be settled within the interaction budget, commit `BLOCKED` with a reproducible explanation and return ownership to `HUMAN`.

After any outgoing handoff, stop writing. Do not run additional formatters, code generators, or tests that modify the shared tree.

## 6. Reviewer instructions

When `Owner: REVIEWER` in the **committed** mailbox:

1. For `READY_FOR_REVIEW`, inspect the **full feature diff** from the configured base commit to `HEAD`, not merely the most recent implementation commit; use `git diff <BASE_SHA>..HEAD` plus targeted source inspection. Ignore mailbox/protocol-only differences when judging implementation quality.
2. Review correctness, edge cases, regressions, behavior against acceptance criteria, code quality, and test coverage. Run relevant tests only when authorized and safe; remove only your own generated temporary files before committing.
3. **Do not modify implementation files.** Commit only changes to `AGENT_HANDOFF.md` and, when archiving (section 4), `AGENT_HANDOFF_ARCHIVE.md`.
4. If the complete feature is acceptable, commit `APPROVED`, evidence and limitations, `Owner: HUMAN`; stop. Approval of a subtask uses `SUBTASK_APPROVED` and continues according to section 13.
5. Otherwise, if a round remains, combine material concerns into **one actionable** `CHANGES_REQUESTED`, increase the round counter, set `Owner: IMPLEMENTER`, commit; stop.
6. To clarify a genuine uncertainty, ask one focused `QUESTION` if a round remains; increase the counter and hand control to `IMPLEMENTER`.
7. If you receive a `QUESTION` from the implementer, answer briefly using `ANSWER`; return ownership to `IMPLEMENTER`. No extra round is consumed by answering.
8. If another round would be needed when the budget is exhausted, commit `BLOCKED` with unresolved issues and transfer ownership to `HUMAN`.

## 7. Two-round interaction budget

- **Maximum = 2 rounds total per feature**, shared across both agents; for a feature using section 13, the limit applies per started subtask, not per agent turn. One feature can therefore complete several reviewed subtasks without exhausting its budget on the first two corrections.
- A round starts when an agent sends a **new `QUESTION` or `CHANGES_REQUESTED`**. Increment the counter in **that** commit. The answer or revised implementation does **not** increment it again.
- An initial implementation, review, approval, progress/checkpoint commit, and usage pause/resume **do not** count.
- At `2/2`, existing questions can still be answered and an already-requested fix can be re-reviewed and approved. **Neither agent may initiate a third question/correction cycle.** Unresolved blocking concerns → `BLOCKED`, assigned to `HUMAN`.
- If further cycles are desired, **only the human** may explicitly change the limit with a committed instruction.

## 8. Usage-limit safety (80% caution, 90% pause)

The goal is **graceful checkpointing before a subscription quota is exhausted**, not an instantaneous kill switch. Inspect **actual provider-reported quota usage** (e.g., applicable short-window and weekly limits) when available. **Context-window utilization is different and must not be confused with account usage.**

**Sustained-work expectation.** Both agents are expected to work through the authorized feature completely, continuing across subtask approvals and ordinary handoffs until the feature is finished or an applicable quota approaches the safe stopping threshold. Do not voluntarily end the collaboration after one subtask, one review, or an arbitrary time/turn limit while useful authorized work remains. After a handoff, keep waiting read-only for the next committed turn while the interactive session supports it. This expectation does not permit concurrent writes, bypassing review, inventing quota data, or continuing after a human stop, a safety blocker or `PAUSED_QUOTA`. Preserve the 80% caution and 90% pause rules below; when quota visibility is unknown, record `UNKNOWN` and continue useful work without claiming a percentage or promising indefinite unattended execution.

- **Below 80% used:** work normally.
- **80%–89% used:** avoid starting large new steps; prefer a small testable checkpoint and prepare a concise continuation plan.
- **At or above 90% used in any available quota window:** at the **next safe stopping point**, do not start more implementation/review work. Save a recoverable checkpoint; update the mailbox to `Owner: HUMAN`, `State: PAUSED_QUOTA`; include who was working, observed window(s)/percentage/reset time if known, exact completed work, whether tests passed/failed/not run, remaining steps, and the suggested resume role/action; commit and stop.
- **Quota error/exhaustion before 90%:** if commands and local work are still possible, make the same checkpoint. If no safe commit is possible, do not pretend one succeeded; preserve files and report the blockage to the human.

**A quota checkpoint is not `READY_FOR_REVIEW`, `APPROVED`, or a normal handoff.** If implementation is incomplete, clearly mark the checkpoint **WIP**. Prefer compilable commits, but clearly document any failing tests/incomplete state. Never conceal a partial change merely to produce a clean report. Do not spend substantial additional model usage trying to polish after reaching the threshold.

### What to monitor

- In interactive **Claude Code**, the human can use `/usage`; some versions expose rate-limit fields through status-line data. Confirm that those fields are actually present before automating any parsing.
- In interactive **Codex CLI**, the human can consult `/status`; for an advanced controller, Codex app-server may expose account rate-limit readings. Availability and reporting vary by version/account.
- **Do not assume an agent can call interactive slash commands programmatically** or read the human's usage display. When a reliable number is not accessible, set `Quota visibility: UNKNOWN`; never invent it. The human can supply the measured percentage or a future watcher can enforce it.
- Checking every 10 seconds is for **Git handoffs**, not a requirement to query usage every 10 seconds. Check quota at available, reasonable boundaries (before major work and before handoff).
- The threshold policy is best-effort with interactive sessions. A shell script without access to provider usage data **cannot enforce 90% precisely**.

### Resuming from `PAUSED_QUOTA`

When the human is satisfied that usage has reset or elects to continue, they update and **commit** the mailbox with a `RESUMED` message stating the intended role and next action, set `Owner` to that role, and set `State: RESUMED`. The resumed agent reads the last checkpoint and verifies the branch, diff, status, and action before working. Do not reset the interaction counter or silently change roles.

## 9. Git-based waiting and polling (about 10 seconds)

Waiting must be **read-only**; prefer a shell sleep rather than repeatedly asking the model to reason. Detect a **new committed mailbox version** using:

```powershell
git log -1 --format=%H -- AGENT_HANDOFF.md
```

At startup **first** read the committed mailbox (do not wait if it already assigns you ownership). To read **only committed content** (not an uncommitted edit by the active agent):

```powershell
git show HEAD:AGENT_HANDOFF.md
```

Example for a REVIEWER waiting in PowerShell (run from repository root):

```powershell
$role = 'REVIEWER'
$seen = ''
while ($true) {
    $version = (git log -1 --format=%H -- AGENT_HANDOFF.md).Trim()
    if (-not $version) { throw 'AGENT_HANDOFF.md has not been committed.' }
    if ($version -ne $seen) {
        $seen = $version
        $mailbox = (git show HEAD:AGENT_HANDOFF.md) -join "`n"
        if ($mailbox -match "(?m)^- Owner: $role\s*$") {
            Write-Host "Committed handoff addressed to $role at $version"
            break
        }
        if ($mailbox -match '(?m)^- Owner: HUMAN\s*$' -and
            $mailbox -match '(?m)^- State: (SETUP_REQUIRED|PAUSED_QUOTA|APPROVED|BLOCKED)\s*$') {
            Write-Host 'Task is paused or finished; stopping the watcher.'
            break
        }
    }
    Start-Sleep -Seconds 10
}
```

This sample **detects** an event but does **not** wake a finished agent turn or inject prompts into the other CLI. An interactive agent may call a similar bounded wait command, then process the event; if it exits or times out, the human re-prompts it. Prefer an external persistent watcher if fully unattended work is required.

On detecting a commit, an agent must: (1) verify the expected branch, (2) inspect `git show HEAD:AGENT_HANDOFF.md`, (3) confirm the committed owner matches its role, (4) inspect the newest message and its requested action, (5) ensure there are no unexpected local changes, and (6) act **once** for that mailbox commit. An unrelated code commit, a file modification time, or an uncommitted mailbox edit is not a handoff.

If using separate Git worktrees in a future version, this polling workflow will require a different synchronization approach: each worktree has a separate working directory and branch coordination; **do not assume this same-tree procedure supports parallel writing**.

## 10. Suggested startup prompts (within IntelliJ/JetBrains)

**Implementer** (either Claude or Codex):

> Read `AGENT_COLLABORATION.md` and `AGENT_HANDOFF.md`. You are the IMPLEMENTER for the configured task. Use the already-running interactive session and existing project context. Verify the committed owner before writing. Implement the task, commit each handoff in `AGENT_HANDOFF.md`, and stop writing immediately after passing ownership. Respect the two-round budget and the 80%/90% quota policy. If quota readings are unavailable to you, say so; do not invent them.

**Reviewer** (the other agent):

> Read `AGENT_COLLABORATION.md` and `AGENT_HANDOFF.md`. You are the REVIEWER. Check the existing committed mailbox now, then wait read-only for a Git-committed handoff addressed to you (roughly 10-second polling while supported). Review the entire feature diff, commit your response only in `AGENT_HANDOFF.md`, and stop writing after transferring ownership. Do not edit implementation code. Respect the two-round budget and quota policy. If your interactive turn ends, a human may need to re-prompt you.

**For either role in a feature with subtasks:** also follow section 13. At the start of every owned turn, choose and record the eligible subtask you think should go next and explain why. Complete any already-assigned review, answer or correction before switching subtasks. Obtain explicit agreement before starting implementation, defer a proposal if agreement is not reached, and continue through the remaining authorized work until completion or a safe quota checkpoint. Do not activate a `SETUP_REQUIRED` draft.

## 11. Example feature lifecycle

1. **Human** initializes the task in a commit: `Owner: IMPLEMENTER`, `State: IMPLEMENTING`, rounds `0/2`.
2. **Claude / implementer** commits implementation + `READY_FOR_REVIEW`: `Owner: REVIEWER`.
3. **Codex / reviewer** identifies two actionable issues; commits one `CHANGES_REQUESTED`: `Owner: IMPLEMENTER`, rounds `1/2`.
4. **Claude** fixes and commits implementation + another `READY_FOR_REVIEW`: `Owner: REVIEWER`.
5. **Codex** commits `APPROVED`: `Owner: HUMAN`. Human decides whether to merge.

If either participant approaches its quota during its own turn, it may instead commit `PAUSED_QUOTA` with `Owner: HUMAN`; the other participant **does not automatically continue**. A pre-implementation `QUESTION`/`ANSWER` exchange is also permitted, but consumes one of the two rounds.

## 12. Final report and recovery

When complete or blocked, provide the human with the feature and branch, meaningful commit SHAs, current owner/state, implementation summary, tests actually executed, interaction rounds used, quota status if known, and outstanding risks. If a session closes unexpectedly, **Git and the committed mailbox** (with its archive) are the recovery record; resume the appropriate CLI session manually, re-read both files, and never assume ownership based on stale chat context.

## 13. One feature with subtasks: selection, agreement and continuation

This mode applies only when `Mode: FEATURE_WITH_SUBTASKS` and the human has named the authorized subtasks in the mailbox. For example, **Design improvements** is one feature and its selected SP-xx items are subtasks, not separate feature runs. The subtask list is a set of work, **not a fixed execution order**. Do not add unrelated issues or treat the maintainer's parallel changes as agent work. Keep one feature branch and feature baseline throughout; do not create a branch, clear the feature history or reinitialize the feature for each SP item.

### Choose the next subtask on every turn

At the start of **each owned turn, each agent independently picks the eligible subtask they think should go next** from the remaining set. Record the preferred subtask and reason in that turn's message (or a local checkpoint if retaining ownership). Base the choice on current code, completed prerequisites, risk, remaining quota and the pending review comments. This applies to both IMPLEMENTER and REVIEWER; the first agent's ranking is not binding on the other.

An outstanding review, answer or correction still has to be resolved first. Recording a next choice is not permission to abandon an implemented subtask or open a second implementation. Keep the current subtask's implementation/review role assignments stable until it is approved or safely blocked. Roles may be reassigned for a later subtask only by an explicit committed mailbox update identifying both agents.

### Agree before writing implementation

1. With no subtask in progress, the owner selects a candidate and commits `SUBTASK_PROPOSED`, the proposed scope or bounded slice, acceptance criteria, review-comment decisions, required checks and prerequisite evidence. Transfer ownership to the other role; set `State: AWAITING_AGREEMENT`. No implementation starts yet.
2. The recipient evaluates that proposal against the code and records their own preferred next subtask. If they agree to the candidate and scope, commit `SUBTASK_AGREED`, name both agreeing agents and the exact subtask/slice, set `Owner: IMPLEMENTER`, `State: IMPLEMENTING`, and capture the full **subtask base commit** before implementation. If the recipient is already IMPLEMENTER, this agreement is a checkpoint retaining ownership, after which implementation may proceed.
3. **If the agents cannot agree before starting the subtask, move to another subtask.** Record `DEFERRED`, each position, the unresolved point and what could make the subtask eligible later. Return ownership to the other role with `State: SELECTING_SUBTASK` and an exact next action to propose a different eligible subtask. Do not repeat the same argument, start the disputed change anyway, or ask the human to settle it while other authorized work can proceed. A declined proposal is neither completed nor approved.
4. Normal proposal/agreement messages do not consume a correction round. A pre-start disagreement defers the subtask instead of starting a prolonged negotiation. Reset the two-round counter only when a different subtask is explicitly agreed; preserve counters and earlier decisions in the ledger/history. Reconsider a deferred subtask only with new evidence, a changed proposal addressing the disagreement, or a human decision.

### Implement, review and continue

- Follow sections 5-7 for implementation and review. Review the full **current subtask diff** from its own base SHA, and account for any interactions with previously approved changes. Keep the feature baseline too; it remains the reference for the complete feature diff. Approval of earlier subtasks does not exempt later regressions.
- On acceptance, the reviewer commits `SUBTASK_APPROVED`, records the reviewed commit, checks/results and limitations, and marks that subtask or agreed slice complete. With eligible work remaining, set `Owner: IMPLEMENTER`, `State: SELECTING_SUBTASK`, and request a fresh next-subtask selection. Both agents continue their turns under the sustained-work expectation. A subtask is complete only when its entire agreed scope and required checks are complete; an approved slice leaves the rest pending.
- Once all authorized subtasks are complete and reviewed, perform a final review of the whole feature diff and combined validation against the feature acceptance criteria. Only then commit `APPROVED`, `Owner: HUMAN`, with the final ledger and evidence. This is feature review completion, not permission to push, merge or deploy. Any new finding returns to its affected subtask without erasing that subtask's correction history or exceeding the agreed budget.
- If all remaining subtasks are deferred or await external prerequisites, commit `BLOCKED`, `Owner: HUMAN`, listing what is needed. A post-implementation safety problem or exhaustion of the two correction rounds also uses `BLOCKED`; do not use pre-start deferral to abandon unsafe code and continue elsewhere.
- A quota stop uses `PAUSED_QUOTA`, `Owner: HUMAN`, with the active subtask, each pending/deferred item, its base/counter, and the resume role/action. Neither agent automatically continues while the feature is quota-paused. Only the human resumes it as in section 8.

Keep message numbers and feature history append-only across subtasks. Each message identifies the current subtask, the next preferred subtask and the actual sender/recipient agents as well as their roles, so recovery is not dependent on chat context.
