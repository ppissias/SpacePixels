# Agent Handoff

- Owner: C
- Phase: FIX
- Task: SP-19B
- Implementer: C
- Reviewer: A
- Base: afaf95a4598173af63d97acb8e59bdc5efd6a0f5
- Implementation: 45cf46f6fe6d246016dead241d9abfc92cb2c65c
- Fix rounds: 1/2
- Tests: Independent ViewerRegistryTest + DependencyRulesTest 14 PASS; lifecycle/WCS/stretch/keyboard/viewer mechanics 10 PASS. External pending-worker disposal probe FAILS as described below.
- Quota visibility: UNKNOWN
- R1: ViewerRegistry.windowClosed only unregisters, while SequenceViewerFrame.dispose (and BlinkFrame.dispose) bypasses lifecycle cleanup. Reproduced with a registered sequence viewer preparing a frame: dispose, flush EDT, registry count 0 but release count 0, 3 retained stretch listeners and 2 catalogue listeners; releasing the worker delivers the result after disposal. Later closeAll cannot reach the forgotten viewer.
- Next action: Make disposal perform full idempotent cleanup before ownership is forgotten; preserve cancellation and stale-result guards. Add a real registered-viewer regression using direct dispose during pending preparation (the current disposingAViewer test sends WINDOW_CLOSING), and retain import/exit and normal-close coverage. Rerun relevant checks, commit source separately and return REVIEW to A. B and A remain read-only after this transfer.
