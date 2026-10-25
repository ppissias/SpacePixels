# Agent Handoff — v2.1 TEMPLATE (not active)

> Single committed coordination mailbox; this file is **not** a conversation history. Do not overwrite a live handoff with the example. At session startup read `AGENTS.md` first, then `AGENT_COLLABORATION.md`, before using this handoff.

- Owner: A
- Phase: SELECT
- Task: -
- Implementer: A
- Reviewer: B
- Base: -
- Implementation: -
- Fix rounds: 0/2
- Tests: -
- Quota visibility: UNKNOWN
- Next action: Select an eligible task in AGENT_STATE.md and start implementation.

<!-- Valid phases: SELECT, IMPLEMENT, REVIEW, FIX, DEFER_REQUESTED, WAIT_MAINTAINER, PAUSED_QUOTA, DONE. Only the committed Owner may write; `MAINTAINER` is the reserved Owner value when agent work must pause for a maintainer decision or quota reset. The agent roster, scope, completed tasks, and maintainer decisions live in AGENT_STATE.md. -->

## Example: compact review handoff
<!--
- Owner: B
- Phase: REVIEW
- Task: SP-06
- Implementer: A
- Reviewer: B
- Base: abc1234
- Implementation: def5678
- Fix rounds: 0/2
- Tests: ./gradlew test PASS; ./gradlew guiMechanicsTest PASS
- Quota visibility: UNKNOWN
- Next action: Review abc1234..def5678, focusing on cancellation and stale callbacks.
-->

## Example: defer handoff to original implementer
<!--
- Owner: A
- Phase: DEFER_REQUESTED
- Task: SP-06
- Implementer: A
- Reviewer: B
- Base: abc1234
- Implementation: def5678
- Fix rounds: 2/2
- Tests: ./gradlew test PASS; design disagreement unresolved
- Quota visibility: UNKNOWN
- Next action: Preserve disputed implementation under deferred/SP-06, safely revert isolated implementation changes, record maintainer decision D-01 in AGENT_STATE.md, then transfer SELECT ownership to B. If isolation is unsafe, WAIT_MAINTAINER.
-->
