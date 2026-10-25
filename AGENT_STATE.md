# Agent State — v2.1 TEMPLATE (not active)

> Overall scope, reviewed results, and maintainer decisions. **Do not use these example rows as real project status.** At session startup, read `AGENTS.md` first and `AGENT_COLLABORATION.md` second; consult this state when selecting work or changing task status, not at every simple handoff.

## Collaboration

- Goal: <maintainer's authorized objective>
- Spec: <existing requirements/design document>
- Branch: <dedicated feature branch>
- Feature baseline: <full commit SHA>
- Agents (ordered): A=<CLI/model>; B=<CLI/model>; C=<optional>; D=<optional>
- Overall: ACTIVE  <!-- ACTIVE | WAIT_MAINTAINER | DONE | PAUSED_QUOTA -->
- Required final checks: <commands and required evidence>

## Task ledger

| ID | Task / spec link | Depends on | Status | Evidence / deviations / next action |
| --- | --- | --- | --- | --- |
| SP-02 | <spec link> | - | DONE | reviewed `<sha>`; tests PASS; <one short agreed variation> |
| SP-06 | <spec link> | SP-02 | DEFERRED | Decision D-01; preserved ref `deferred/SP-06`; active code reverted at `<sha>` |
| SP-08 | <spec link> | SP-02 | TODO | Eligible independently of SP-06 |

Statuses: `TODO`, `IN_PROGRESS`, `REVIEW`, `DONE`, `DEFERRED`. Task is DONE only after independent review. If a task is blocked by a deferred prerequisite, leave it TODO and note the dependency; do not select it prematurely.

## Maintainer decisions (open first)

### D-01 — <short title>
- **Task:** SP-06
- **Question for maintainer:** <concrete A-or-B choice and impact>
- **Agent A position:** <one sentence; reason>
- **Agent B position:** <one sentence; reason>
- **Work preserved:** `deferred/SP-06` at `<SHA>`; active branch excludes disputed implementation
- **Needed to resume:** <specific decision and any follow-up test>
- **Status:** OPEN

<!-- On decision: change status to RESOLVED, record the maintainer's choice and date, and make the affected task eligible again. For long disputes, link agent-decisions/D-01.md instead of expanding the state file. -->
