# Agent Handoff

- Owner: A
- Phase: IMPLEMENT
- Task: SP-19C
- Implementer: A
- Reviewer: B
- Base: 5cab700ac7664a0755249afdfd77500000c6d87c
- Implementation: -
- Fix rounds: 0/2
- Tests: Planned: trial-isolation/selective-Apply regressions, invalid text and real keyboard/focus checks, viewer lifecycle/job-locking checks, check, GUI mechanics and API consumer checks. Baseline account-save failure remains separately tracked.
- Quota visibility: UNKNOWN
- Next action: A shares the existing previews' trial-setting controls and adopts trial values plus Apply to Settings (D-02), merging only edited fields against current settings. Preserve computation, debounce, focus and close cleanup. F13 is outside scope. B and C remain read-only.
