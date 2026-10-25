# Agent Handoff

- Owner: C
- Phase: FIX
- Task: SP-11A
- Implementer: C
- Reviewer: A
- Base: 6df647069c726ce6b3c4ff004b6f6b08603d3db3
- Implementation: f02dca375fc47b379d54993dbeeec44cca20c97f
- Fix rounds: 1/2
- Tests: Independent PASS: check 511 (4 skipped), full GUI mechanics 33, isolated consumer 8. All 18 reference datasets/14,232 normalized report files and assets match the SP-08A outputs, also verifying SP-08B. Targeted external viewer probes reproduce R1/R2; their preferences were backed up/restored.
- Quota visibility: UNKNOWN
- R1: `SequenceViewerFrame.replaceFrameMetadata` does not invalidate in-flight preparation. A latch probe captures old WCS RA 150, replaces metadata with RA 210, then releases preparation: the displayed result reverts to RA 150 (one preparation). Advance/invalidate the preparation generation and retain the updated metadata on delivery; add a real-viewer regression for this ordering.
- R2: `MainApplicationPanel` retains/notifies only the last Manual Transient Inspection window; earlier open inspections miss WCS replacements. `TransientInspectionFrame.onFrameMetadataReplaced` also updates `fileInfoByName` by basename without checking ownership/path: a probe for `/dataset-a/shared.fit` accepted a replacement for `/dataset-b/shared.fit`. Notify every relevant current-session inspection and reject foreign dataset/session updates, including repeated imports/same basenames; preserve lifetime policy until SP-19B. Add regressions for multiple inspections and foreign metadata.
- R3: D-03 explicitly requires migration/API examples and consumer checks for removed metadata mutators. `API.md` and `src/consumerTest` are unchanged. Document setter/`snapshot()` removal and immutable construction/header replacement (including the deliberate source/API break), and exercise that migration against the isolated staged API artifact.
- Next action: C fixes R1-R3, reruns affected checks and returns the full task for A review. Existing real-data evidence can be reused if the correction changes only GUI delivery and documentation/consumer tests. A and B are read-only after this transfer.
- Evidence: External scratch prefix `spacepixels-sp11-review-` contains `MetadataRaceProbe.java` and `metadata-race-and-foreign.log`; reference manifests are under the external `spacepixels-sp08-` scratch prefix.
- Local edits: Maintainer decision annotations in AGENT_STATE.md remain preserved unstaged; their structured decisions are committed.
