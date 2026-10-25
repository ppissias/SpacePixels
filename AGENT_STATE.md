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
- Final integration: Maintainer approval is required before push/merge of this feature.
- Protocol: AGENT_COLLABORATION.md v2.2 (2026-10-25). Quotas are gone. Every agent keeps running and checks for new handoff commits every 2 minutes, an owner commits a status update at least every 30 minutes during long work, and an owner silent for an hour may be bypassed (section 8). Agents started under v2.1 re-read sections 4 and 8.

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
| SP-19B | Viewer ownership and lifetime policy | SP-19A | DONE | A reviewed 73a87799d3373ab4000a786464b648f259fbfd63 (including 45cf46f6). R1 resolved: direct disposal cleans up once, detaches listeners and drops pending results before unregistration; original independent probe now PASS. Independent check 516 tests (4 skipped), consumer 9 PASS; full GUI 36/37 PASS, with the account-save failure also reproduced on pre-SP-19B desktop code (see validation follow-up). No implementation edits in review. |
| SP-19C | Shared trial-setting controls and edit/apply policy | SP-19A, SP-19B | DONE | Reviewed `717de6aeef85e2e6d9bc30b70056a8a024018933`. Reran TrialSettingsControlsTest 4 PASS. Apply sends only edited fields, including a typed second field, and does not move the saved baseline. Job lock and close stop the delayed refresh; reopen loads current values. F13 excluded. GUI mechanics not rerun. No code change in review. |
| SP-16 | Inspect/fix 100%, 150%, 200% scaling and larger font | SP-05, SP-07, SP-19A, SP-19C | DONE | Reviewed `fb69f693366a06273316a4ce8e3d69ab0bbe2a93`. Reran DisplayScaleLayoutTest 2, SettingsRowLayoutTest 5, gui unit suites (95 tests), check (520 tests, 4 skipped) and apiConsumerTest 9: all PASS. WrappingHelpLabel wraps with font scale; viewer sliders scale via UiStyle.space; keyboard/focus rules unchanged. No code change in review. |
| SP-14 | Move core code into domain packages | All other eligible in-scope work | DONE | A reviewed cdca9e211d4b8bf81cc96a00808140b8c05284c2 plus ab07d0b5ba26448fb08bedb8ab12f0da7d1513cf. R1 resolved: approved package/source/binary migration and release guidance explicit, malformed reference corrected. Independent check 522 (4 skipped), consumer 9, GUI 14, JavaScript 23 PASS. Production bodies mechanically equivalent; 110 moved packaged classes and 4 resource payloads verified; all 18 datasets/14,232 normalized outputs identical. No implementation edits in review. |
| SP-19C-F1 | Clear a restored trial edit before later automatic alignment | SP-14 | REVIEW | A implemented 93467d67abe6da76b6bd90a4c4737c76f35b101a; B reviews. Original probe now preserves newer untouched grow value; unit covers spinner/text restoration and real trial mechanics covers the sequence. Full checks and scale evidence recorded with SP-16V. |
| SP-16V | Complete whole-window scaling acceptance | SP-14 | REVIEW | A implemented cce83ad23b75121eb090900b1bcbffeba0a044f3; B reviews. Actual 100/150/200 percent plus 20-point font: 92 inspected captures, no fixed-control/text flags. Wrapping rows, scrolling/table access, focus reveal, padded paragraphs and long filenames corrected. Check 524 (4 skipped), consumer 9 PASS; final full GUI 41/42, known baseline account-save assertion still red in isolation; 7 related GUI at 200 percent PASS. |

Statuses: TODO, IN_PROGRESS, REVIEW, DONE, DEFERRED. Independent review is required for DONE. Split SP-08, SP-11 and SP-19 rows together cover their original items; do not remove the parent proposal before all its authorized parts are accounted for.

## Maintainer decisions

### D-01 — Independent viewer lifetime
- Task: SP-19B. RESOLVED (2026-10-25): the maintainer chose a fresh start on import; close every viewer, including Manual Transient Inspection and Star Mask Explorer.
- Next action: Implement owned viewer registration, session-handover/exit cleanup, queued-callback guards and unregister-on-disposal checks.
- Maintainer decision: proceed with closing every viewer on import. It is a fresh start.  

### D-02 — Trial-setting policy and F13 boundary
- Task: SP-19C. RESOLVED (2026-10-25): the maintainer chose the same trial-value/Apply workflow in the existing preview windows; Preview Frame adopts Apply to Settings.
- Next action: Share controls for existing viewers, preserve preview refresh and focus rules, and apply only the edited fields to session settings. F13 remains a separate feature.
- Maintainer decision:  we should follow the same approach everywhere. I propose to have a button "apply" in the preview windows, which would apply the settings.

### D-03 — Public package compatibility
- Task: SP-11A and SP-14. RESOLVED (2026-10-25): the maintainer confirmed there are no official external SpacePixels API callers and approved intentional public Java API changes.
- Next action: Document removed metadata mutators and public package/type moves, update migration/API examples and consumer checks, and preserve scientific results/report schemas. No legacy shims without a real caller.
- Maintainer decision: There are no official external callers of the SpacePixels API currently, it is still early. If I understand correctly, this is the concern, breaking the public API? If this is the case, then please proceed. 

## Validation follow-up

- GUI mechanics have shown intermittent failures: the account-save assertion failed on both SP-19B and a separately compiled pre-SP-19B desktop (afaf95a), then passed in SP-19C's final full run. That run passed 39/40; LastRunTracksMechanicsTest's inspection-description assertion passed on an isolated retry with all six track/trial tests. Preserve real input/focus coverage and capture any recurrence during final checks; no account or track behavior was changed for these failures.

- Final viewer validation: the first full GUI run passed 40/42 (the previously seen track-description case and an initial settings-file-chooser timeout); all six track/settings/resize cases passed in isolation, the final full run passed 41/42, with the account-save assertion also failing in isolation. That same account case passed in the first full run; its failure was previously reproduced on the pre-change baseline. That retry opens java.base/java.util to the test runtime for AssertJ timer reflection; production runtime flags are unchanged. Scientific code is unchanged by these GUI follow-ups; the SP-14 18-dataset/14,232-output comparison remains the reference evidence.
