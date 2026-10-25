# Lightweight Round-Robin Agent Collaboration — v2.1

## 1. Objective

Agents independently select **authorized, bounded** work, implement it, review each other, and rotate without waiting for routine maintainer prompts. Disagreements **defer the affected task, not the entire collaboration**. Keep agent-to-agent text minimal: source code, specs, tests, and Git are the detailed evidence.

This is a **single shared working tree**, with **one writer at a time**. The protocol expresses persistent intent but cannot keep interactive CLIs alive after their turns expire. Truly unattended execution requires a separate watcher/supervisor that can safely resume the actual CLI.

## 2. Files and reading discipline

**Required startup reading order for every agent (including Agent A):**
1. Read `AGENTS.md` **first** to understand the project, repository development rules, where features/specifications are defined, and required checks. Follow its guidance throughout the task.
2. Read `AGENT_COLLABORATION.md` (this protocol) to learn ownership, rotation, reviews, and safety rules.
3. Read `AGENT_STATE.md` for authorized scope, task ledger and pending maintainer decisions, then `AGENT_HANDOFF.md` for the current committed turn. During subsequent turns, read only the files needed for the action; do not repeatedly reread the full guidance without reason.

| File | Purpose | Read when |
| --- | --- | --- |
| `AGENTS.md` | Project overview, development standards, feature/spec locations and tests | **First**, on every agent session startup; revisit relevant sections as needed |
| `AGENT_COLLABORATION.md` | Stable rules (this document) | **Second**, on every agent session startup; revisit on ambiguity |
| `AGENT_STATE.md` | Approved scope, compact task ledger, maintainer decisions | Bootstrap, task selection, approval, deferral, maintainer review |
| `AGENT_HANDOFF.md` | Only current owner/phase/task/action | Each wake-up or handoff |

The relevant task specification and `git diff` are consulted as needed. Do not re-read prior tasks or copy source diffs into Markdown. **Git commits are the handoff history**; no mandatory append-only message archive or file per task. Add `agent-decisions/<ID>.md` only for a genuinely complex decision; link to it from `AGENT_STATE.md`. Do not create one file per ordinary task.

## 3. One-time bootstrap by Agent A

1. Maintainer starts Agent A with project goal/scope and tells each other agent its identity (B, C, ...). Ordered agent roster is A → B → C → ... → A.
2. Agent A first reads `AGENTS.md`, then `AGENT_COLLABORATION.md` and follows the startup order in section 2; checks for existing live collaboration, Git branch/status, and existing project specs. **If active state exists, resume; do not reinitialize or overwrite it.**
3. For a new collaboration, verify a safe, clean starting point, create an authorized feature branch if necessary, capture the baseline, and fill `AGENT_STATE.md` with the scope, linked specs, task breakdown, dependencies, and acceptance targets. Do not expand the maintainer-authorized scope.
4. Initialize `AGENT_HANDOFF.md` with `Owner: A`, `Phase: SELECT`, then commit both coordination files. Agent A chooses the first eligible task and implements. Other agents join read-only and await a committed handoff addressed to them.
5. If setup would disturb uncommitted maintainer work or another active owner, **do not modify it**; request resolution.

## 4. Non-negotiable safety

- **Single writer:** Only the agent named `Owner` in the **committed** handoff can write, stage, or commit. Waiting agents are read-only. A valid transfer requires a Git commit updating `AGENT_HANDOFF.md`; after transfer, the sender stops writing. Process each committed handoff at most once.
- **No destructive shortcuts:** No `reset --hard`, `clean -fd`, automatic stash, force-push, rebase, or deletion of unexpected work. Never overwrite unrelated or uncommitted maintainer changes. Do not push, merge, or deploy without explicit approval.
- **Review independence:** Reviewer may edit **coordination files** (`AGENT_HANDOFF.md`, `AGENT_STATE.md`, and optional decision notes) but not implementation code. Run only safe, relevant tests and report observed outcomes.
- **Keep evidence:** Implementation code and the mailbox belong in **separate commits** so an unresolved implementation can be shelved without removing its handoff metadata. Track task base and implementation SHA. Do not claim a test, quota number, or approval that was not observed.
- **Do not store secrets** in committed coordination files. Follow `AGENTS.md` and the repository-specific contracts, feature specifications, and test requirements it identifies.

## 5. Round robin: A implements / B reviews / B implements / C reviews

The reviewer of task N becomes the implementer of task N+1 after **APPROVED** or safely **DEFERRED**. With A,B,C,D: A/B → B/C → C/D → D/A. With two agents: A/B → B/A. Corrections and discussions stay with the original pair; they **do not** trigger rotation.

**SELECT / IMPLEMENT** (owner = implementer): Select the next eligible authorized task using `AGENT_STATE.md`; do not ask for pre-implementation agreement by default. Define bounded scope and clear acceptance checks, capture its baseline commit before code changes, implement, run tests, and commit code. Then commit a short handoff: `Phase: REVIEW`, `Owner: <reviewer>`, item, base/implementation SHA, checks, and the exact review request. Do not duplicate the code diff in prose.

**REVIEW** (owner = reviewer): Inspect the full task diff from base through reviewed implementation, including dependencies and relevant checks. Commit one of:

- **APPROVED:** The agreed requirements are met and no blocking issue remains. Update the compact `DONE` entry in `AGENT_STATE.md` with reviewed SHA, evidence, and any agreed deviation. Clear the handoff task; set `Phase: SELECT`, `Owner: <reviewer>` and `Reviewer: <next agent>`. The reviewer is now the implementer and continues immediately.
- **FIX:** Record one grouped, actionable set of material findings and send ownership back to the original implementer. Up to **two FIX rounds** per task; the implementer corrects, tests, commits, and sends back to REVIEW. Do not spend rounds on cosmetic preferences.
- **DEFER_REQUESTED:** An unresolved policy/design dispute or repeated blocking findings mean the task needs a maintainer decision. Record concise opposing positions and a proposed decision question; return ownership to the original implementer **to isolate any unapproved implementation** (section 6). No endless debate.

## 6. Deferral does not halt the project

**Before any code:** If task requirements need a maintainer choice, create a `DEFERRED` ledger entry and a short decision record. Commit `Phase: SELECT`, `Owner: <next agent>` to rotate to the next implementer, who selects an **independent eligible** task. No implementation needs shelving and no review is needed for unwritten code.

**After implementation:** A reviewer must **not** approve unresolved blocking code just to keep moving, nor leave rejected changes live on the feature branch. Use this safe defer procedure:

1. Reviewer commits `Phase: DEFER_REQUESTED`, `Owner: <original implementer>`, with task, affected implementation commit(s), reason, and maintainer question.
2. Original implementer preserves the disputed implementation under a dedicated Git reference (for example `deferred/SP-06` pointing to its implementation tip). If implementation commits are isolated and reverting them is demonstrably safe, use **new, non-destructive Git revert commits** to remove only the unapproved implementation from the active branch. Never rewrite history, revert unrelated commits, or erase coordination files. Verify clean state and relevant checks.
3. Implementer records `DEFERRED` in `AGENT_STATE.md`, with preserved Git reference, relevant SHAs, short description, each agent's position, the exact maintainer decision needed, and affected dependencies. Commit coordination/handoff with `Phase: SELECT`, `Owner: <original reviewer>` and the next reviewer in the roster. The original reviewer becomes the next implementer.
4. If safe separation/revert is **not possible**, stop with `Phase: WAIT_MAINTAINER`, `Owner: MAINTAINER` and explain the isolation problem. Continuing with contaminated code is not an acceptable shortcut.

**Non-blocking design preference:** If the implementation satisfies agreed behavior and safety, APPROVE with a short note; do not defer merely for a subjective preference.

**Dependencies:** Exclude deferred tasks and their blocked dependents from selection. Keep working on independent eligible tasks. If nothing eligible remains, set `Phase: WAIT_MAINTAINER`, `Owner: MAINTAINER` and provide a concise list of decisions. Never claim `DONE` while deferred tasks remain.

**Maintainer resolution:** Once the maintainer decides, update the decision and task state, then resume that item as an eligible task. Re-review the eventual implementation before calling it DONE. Preserve original records.

## 7. State and handoff length limits

`AGENT_HANDOFF.md` holds **one current state**, not a transcript. Target about 10–20 lines; comments are file/line or test references. `AGENT_STATE.md` holds **one compact ledger row per task**, plus decision records typically 3–8 lines each. Technical evidence lives in commits, specs, and tests. Create an optional per-decision note only if brief text cannot adequately explain the maintainer choice.

## 8. Quotas, stopping and execution

At measured account usage of 80%, prefer a safe checkpoint; at 90%, checkpoint and set `Phase: PAUSED_QUOTA`, `Owner: MAINTAINER`. When provider usage is inaccessible, write `UNKNOWN`, not a guess. Partial work is marked WIP and preserved. If a task cannot be safely isolated, preserve evidence and stop rather than inventing completion.

Agents should keep processing eligible work until all work is independently reviewed, no independent item remains, a true safety blocker arises, or a quota checkpoint requires pause. Maintainer approval is still required for final integration. **Prompt instructions do not wake a finished CLI**: a separate external supervisor is necessary for reliable unattended operation.
