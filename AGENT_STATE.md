# Agent State — design improvements collaboration

## Collaboration

- Goal: Implement all remaining DESIGN-IMPROVEMENTS.md items from SP-05 through SP-19, as authorized on 2026-10-25; exclude SP-21 and SP-22.
- Spec: [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), including each issue review; follow [DEVELOPER.md](DEVELOPER.md).
- Branch: `feature/design-improvements-collaboration`
- Feature baseline: `b4bac726b6242d029d9c7636cccffd25d8d85895`
- Agents (ordered): A=this session; B=maintainer-started Agent B; C=maintainer-started Agent C. No additional agents are needed.
- Overall: ACTIVE
- Execution: One shared working tree, one committed Owner; A implements / B reviews, B implements / C reviews, C implements / A reviews. Waiting agents are read-only.
- Scope boundary: Completed SP-10, SP-13 and SP-18 stay complete; dropped SP-15 stays dropped. SP-23 is already complete. No dependency upgrades or unrelated functional requests.
- Required final checks: `gradlew.bat check`, `guiMechanicsTest`, `apiConsumerTest`; reference-data before/after comparisons for preparation/report/numeric changes as in DEVELOPER.md. Large JVMs run sequentially, using LOCAL-ENVIRONMENT.txt and scratch homes. Run the relevant checks per task before review.
- Final integration: Maintainer approval is required before push/merge of this feature. Quota visibility: UNKNOWN.

## Task ledger

| ID | Task / spec | Depends on | Status | Evidence / deviations / next action |
| --- | --- | --- | --- | --- |
| SP-06 | Extract the sky-catalogue workflow | - | DONE | Reviewed `bd173bee9374516eddb947d327657cde4180c63a`. Reran SkyCatalogueControllerTest 9, ProcessingSessionTest 10, DependencyRulesTest 9: PASS. GUI mechanics not rerun (they move the mouse); implementer recorded SessionMechanicsTest and SessionIoMechanicsTest PASS. No code change in review. |
| SP-05 | Give panels specific collaborators and listeners | SP-06 | DONE | Reviewed `9ba8f6072feac587fe62dc98b6f51d0ed3d1d186`. Reran DependencyRulesTest and gui unit suites (93 tests across 22 suites): PASS. Implementer recorded test 502 PASS (4 skipped) and guiMechanicsTest 33 PASS. Borrowed DetectionConfig read on EDT without retention; jobs keep captured session/settings at admission. No code change in review. |
| SP-08A | One report request/entry point and a complete context (SP-08) | - | DONE | Reviewed `3830993fa14174972e9e04a9e7b306f3ef363f26`. Reran reporting tests, ReportFailureMessagesTest and DetectionRunIsolationTest: 100 tests, 0 failures. Reference comparison not rerun; implementer recorded 14,232 files matched after the listed timing normalization. No code change in review. |
| SP-08B | Separate report map rendering and formatting helpers (SP-08) | SP-08A | DONE | Reviewed `f5b444d53ff4bfec006307030026a073f2949f19`. Reran reporting suite, ReportFailureMessagesTest, DetectionRunIsolationTest and DependencyRulesTest: 109 tests, 0 failures. Map and format call sites use ReportMaps and ReportFormat; iterative index escapes notes/failures; method bodies unchanged. No code change in review. A subsequently compared combined SP-08B/SP-11A outputs on all 18 datasets: 14,232 normalized files/assets identical. |
| SP-07 | Fail-fast settings apply and direct row wiring | SP-05 | DONE | A reviewed `3f420611affdfc5dabfd6c9b32bd291862bab2e1` (including `b73aa00` and `678d445`). Normal check PASS (503 tests, 4 skipped), headless TypedDetectionSettingsTest 5 PASS, full GUI mechanics 33 PASS. R1/R2/R3 resolved; no implementation edits in review. |
| SP-11A | Immutable frame metadata and session-owned WCS replacement | - | DONE | A reviewed `f8db55890915c408ef938187ebc142f9bdde6c7e` (including `f02dca3`). R1-R3 resolved. Independent check 511 (4 skipped), full GUI mechanics 35 and consumer 9 PASS; original race/foreign-metadata probes now PASS. All 18 datasets/14,232 normalized outputs match; reused for GUI-only correction. No implementation edits in review. |
| SP-11B | Clarify FITS input helpers and static naming | SP-11A | DONE | Reviewed `4f4fed22d416706eee99953cab784d46595b7d67`. Reran FitsServiceTest 11, FitsPreparationParityTest 9, FitsSequenceValidationTest 21, FitsPixelConverterTest 10, DetectionInputPreparationTest 12, DependencyRulesTest 9: 72 tests, 0 failures. HDU selection, unsigned predicate and readiness error order match the previous bodies. Converters stay separate. Reference comparison not rerun; implementer recorded 14,232 files matched. No code change in review. |
| SP-19A | Shared image component and small viewer helpers | SP-05 | DONE | Reviewed `625a2be066176382ae7451b0bd07b385cfe7dfd3`. ZoomableImageView removed; FrameView supports ViewLink, showPicture, imageOverlayLevels/imageOverlayVisible; ViewerSupport.bindStretch and sessionName shared; Frame/star-mask/viewer tests pass; StretchMechanicsTest, ViewerKeyboardMechanicsTest, WcsReplacementMechanicsTest PASS; gradlew check and apiConsumerTest PASS. No code change in review. |
| SP-19B | Viewer ownership and lifetime policy | SP-19A | REVIEW | C implementation `73a87799d3373ab4000a786464b648f259fbfd63`, base `afaf95a4598173af63d97acb8e59bdc5efd6a0f5`. D-01 resolved; R1 resolved: disposal performs full idempotent cleanup before unregistering; pending worker cancelled and stale results dropped. ViewerRegistryTest 5 PASS, viewer/stretch/wcs mechanics 10 PASS, check and apiConsumerTest PASS. A reviews. |
| SP-19C | Shared trial-setting controls and edit/apply policy | SP-19A | TODO | D-02 RESOLVED: trial values plus Apply to Settings in existing preview windows; F13 remains outside this scope. |
| SP-16 | Inspect/fix 100%, 150%, 200% scaling and larger font | SP-05, SP-07, SP-19A, SP-19C | TODO | Inspect the final empty/loaded window, settings, Astrometry Config, viewers and progress; retain real focus checks. |
| SP-14 | Move core code into domain packages | All other eligible in-scope work | TODO | Last mechanical step; public Java API changes approved (D-03), with source/resource patterns, migration/API examples, release docs and consumer checks. |

Statuses: TODO, IN_PROGRESS, REVIEW, DONE, DEFERRED. Independent review is required for DONE. Split SP-08, SP-11 and SP-19 rows together cover their original items; do not remove the parent proposal before all its authorized parts are accounted for.

## Maintainer decisions

### D-01 — Independent viewer lifetime
- Task: SP-19B. RESOLVED (2026-10-25): the maintainer chose a fresh start on import; close every viewer, including Manual Transient Inspection and Star Mask Explorer.
- Next action: Implement owned viewer registration, session-handover/exit cleanup, queued-callback guards and unregister-on-disposal checks.

### D-02 — Trial-setting policy and F13 boundary
- Task: SP-19C. RESOLVED (2026-10-25): the maintainer chose the same trial-value/Apply workflow in the existing preview windows; Preview Frame adopts Apply to Settings.
- Next action: Share controls for existing viewers, preserve preview refresh and focus rules, and apply only the edited fields to session settings. F13 remains a separate feature.

### D-03 — Public package compatibility
- Task: SP-11A and SP-14. RESOLVED (2026-10-25): the maintainer confirmed there are no official external SpacePixels API callers and approved intentional public Java API changes.
- Next action: Document removed metadata mutators and public package/type moves, update migration/API examples and consumer checks, and preserve scientific results/report schemas. No legacy shims without a real caller.
